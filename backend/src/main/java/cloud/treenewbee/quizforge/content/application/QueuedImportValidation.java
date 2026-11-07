package cloud.treenewbee.quizforge.content.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedBank;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedExtension;
import cloud.treenewbee.quizforge.content.domain.RuleInputHash;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.admin.infrastructure.ExtensionReviewMapper;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 整包跨题库归组；同一拓展的三个批量操作连续执行，避免包之间反复切换工作进程。 */
@Component
public class QueuedImportValidation {
    public record Validated(JsonNode capabilities, Map<String, JsonNode> questions) {}
    private record Input(Object data, String location, boolean needsOutline) {}
    private final ImportRules rules;
    private final RuleRunnerClient runner;
    private final ImportQueueMapper cache;
    private final ExtensionReviewMapper reviews;
    private final JsonMapper json;
    private final Clock clock;
    private final int parallelism;
    private final java.util.concurrent.ExecutorService executor;

    public QueuedImportValidation(ImportRules rules, RuleRunnerClient runner, ImportQueueMapper cache, ExtensionReviewMapper reviews,
            JsonMapper json, Clock clock, @Value("${qf.import.extension-concurrency:2}") int concurrency,
            @Value("${qf.import.runner-worker-limit:8}") int workerLimit) {
        if (concurrency < 1 || workerLimit < 1) throw new IllegalArgumentException("导入规则并发必须为正数");
        this.rules = rules;
        this.runner = runner;
        this.cache = cache;
        this.reviews = reviews;
        this.json = json;
        this.clock = clock;
        this.parallelism = Math.min(concurrency, Math.max(1, workerLimit / 2));
        this.executor = Executors.newFixedThreadPool(parallelism, Thread.ofVirtual().name("import-rules-", 0).factory());
    }

    @jakarta.annotation.PreDestroy
    void stop() { executor.shutdownNow(); }

    @SuppressWarnings("unchecked")
    public Map<String, Validated> validate(List<ParsedExtension> extensions, List<ParsedBank> banks, Runnable guard) {
        extensions.forEach(this::requireNotStopped);
        Map<String, LinkedHashMap<String, Input>> groups = new LinkedHashMap<>();
        for (ParsedExtension extension : extensions) groups.put(extension.path(), new LinkedHashMap<>());
        for (ParsedBank bank : banks) {
            var authored = new java.util.HashSet<String>();
            for (var group : (List<Map<String, Object>>) bank.bank().get("outline")) {
                for (var item : (List<Map<String, Object>>) group.get("items")) {
                    if (item.containsKey("label") || item.containsKey("parts")) authored.add((String) item.get("question"));
                }
            }
            for (var question : (List<Map<String, Object>>) bank.bank().get("questions")) {
                var binding = (Map<String, Object>) question.getOrDefault("extension", bank.bank().get("extension"));
                Object data = question.get("data");
                var input = new Input(data, "题库「" + bank.folderName() + "」题目「" + question.get("id") + "」",
                        !authored.contains((String) question.get("id")));
                groups.get((String) binding.get("path")).merge(RuleInputHash.compute(data), input,
                        (before, next) -> before.needsOutline() ? before : next);
            }
        }
        // 所有本机导入任务共用上限；即使一个拓展失败，也等待同包其余调用结束后再释放任务槽位。
        {
            Map<String, java.util.concurrent.Future<Validated>> futures = new LinkedHashMap<>();
            for (var extension : extensions) {
                futures.put(extension.path(), executor.submit(() -> runner.background(() -> rules.guarded(() -> {
                    guard.run();
                    requireNotStopped(extension);
                },
                        () -> validateExtension(extension, groups.get(extension.path()))))));
            }
            Map<String, Validated> result = new LinkedHashMap<>();
            RuntimeException failure = null;
            for (var entry : futures.entrySet()) {
                try {
                    result.put(entry.getKey(), entry.getValue().get());
                } catch (InterruptedException error) {
                    futures.values().forEach(future -> future.cancel(true));
                    Thread.currentThread().interrupt();
                    throw ImportRules.unavailable();
                } catch (java.util.concurrent.ExecutionException error) {
                    if (failure == null) failure = error.getCause() instanceof RuntimeException cause ? cause
                            : new CompletionException(error.getCause());
                }
            }
            if (failure != null) throw failure;
            return Map.copyOf(result);
        }
    }

    private Validated validateExtension(ParsedExtension extension, LinkedHashMap<String, Input> inputs) {
        byte[] digest = HexFormat.of().parseHex(extension.digest());
        var spec = rules.spec(extension);
        String capabilityKey = RuleInputHash.capabilities();
        JsonNode capabilities = cache.cached(digest, hex(capabilityKey)).map(json::readTree).orElseGet(() -> {
            JsonNode value = rules.call(spec, "capabilities", Map.of());
            if (!value.path("canAiGrade").isBoolean() || !value.path("canOutlineItems").isBoolean()) {
                throw ImportRules.unavailable();
            }
            cache.cache(digest, hex(capabilityKey), value.toString(), clock.instant());
            return value;
        });
        Map<String, JsonNode> result = new LinkedHashMap<>();
        List<Map.Entry<String, Input>> missing = new ArrayList<>();
        boolean canOutline = capabilities.path("canOutlineItems").asBoolean() && spec.outlineItemsDeclared();
        Map<String, String> hits = new LinkedHashMap<>();
        List<String> keys = List.copyOf(inputs.keySet());
        for (int start = 0; start < keys.size(); start += 500) {
            var hashes = keys.subList(start, Math.min(start + 500, keys.size())).stream().map(QueuedImportValidation::hex).toList();
            cache.cachedBatch(digest, hashes).forEach(row -> hits.put(HexFormat.of().formatHex(row.hash()), row.outcome()));
        }
        inputs.forEach((key, input) -> {
            JsonNode value = hits.containsKey(key) ? json.readTree(hits.get(key)) : null;
            if (value != null && (!canOutline || !input.needsOutline() || value.has("outlineItems"))) result.put(key, value);
            else missing.add(Map.entry(key, input));
        });
        List<Object> data = missing.stream().map(entry -> entry.getValue().data()).toList();
        if (!data.isEmpty()) {
            try {
                rules.batch(spec, "validateBank", data, null);
            } catch (ImportFailure failure) {
                if ("RULE_REJECTED".equals(failure.code())) {
                    // 拒绝时才逐题定位，正常路径不增加调用。
                    for (var entry : missing) {
                        try {
                            rules.batch(spec, "validateBank", List.of(entry.getValue().data()), null);
                        } catch (ImportFailure individual) {
                            throw new ImportFailure(individual.code(), entry.getValue().location() + "：" + individual.getMessage());
                        }
                    }
                }
                throw failure;
            }
            var outlineInput = canOutline ? missing.stream().filter(entry -> entry.getValue().needsOutline()).toList()
                    : List.<Map.Entry<String, Input>>of();
            List<JsonNode> outlines = rules.batch(spec, "outlineBatch",
                    outlineInput.stream().map(entry -> entry.getValue().data()).toList(), "outlineItems");
            Map<String, JsonNode> generated = new LinkedHashMap<>();
            for (int i = 0; i < outlineInput.size(); i++) generated.put(outlineInput.get(i).getKey(), outlines.get(i));
            List<?> scoreInput = data.stream().map(value -> Map.of("data", value,
                    "state", Map.of("status", "unanswered", "submitted", false))).toList();
            List<JsonNode> scores = rules.batch(spec, "scoreBatch", scoreInput, "scores");
            List<ImportQueueMapper.CacheWrite> writes = new ArrayList<>();
            for (int i = 0; i < missing.size(); i++) {
                var outcome = json.createObjectNode();
                outcome.set("score", scores.get(i));
                JsonNode outline = generated.get(missing.get(i).getKey());
                if (outline != null) {
                    if (!outline.isArray()) throw ImportRules.unavailable();
                    outcome.set("outlineItems", outline);
                }
                String key = missing.get(i).getKey();
                writes.add(new ImportQueueMapper.CacheWrite(key, outcome.toString()));
                result.put(key, outcome);
            }
            writes.sort(java.util.Comparator.comparing(ImportQueueMapper.CacheWrite::hash));
            for (int start = 0; start < writes.size(); start += 64) {
                cache.cacheBatch(digest, writes.subList(start, Math.min(start + 64, writes.size())), clock.instant());
            }
        }
        return new Validated(capabilities, Map.copyOf(result));
    }

    private void requireNotStopped(ParsedExtension extension) {
        reviews.findByDigest(HexFormat.of().parseHex(extension.digest())).ifPresent(review -> {
            if (List.of("revoked", "rejected").contains(review.status())) {
                throw new ImportFailure("INVALID_EXTENSION", "拓展「" + extension.path() + "」已被管理员停用或拒绝，无法校验");
            }
        });
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
