package cloud.treenewbee.quizforge.practice.application;

import static cloud.treenewbee.quizforge.content.application.ContentPages.notFound;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import cloud.treenewbee.quizforge.content.application.BankAvailability;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.content.infrastructure.ExtensionPackageRow;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionMapper;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionRow;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadRows;
import cloud.treenewbee.quizforge.practice.projection.ProjectionCache;
import cloud.treenewbee.quizforge.practice.projection.ProjectionKey;
import cloud.treenewbee.quizforge.practice.application.PracticeViews.*;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RulePackage;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads hold no transaction or database lock while the isolated runner projects a question. */
@Service
public class PracticeReadService {
    private static final Set<String> RULE_CODES = Set.of("RULE_TIMEOUT", "SCORE_UNAVAILABLE",
            "INVALID_OUTLINE_ITEMS", "INVALID_OUTLINE_STATES", "UNSUPPORTED_API_VERSION", "RULE_REJECTED");
    private final PracticeReadMapper reads;
    private final QuestionMapper questions;
    private final BankAvailability availability;
    private final ContentPages pages;
    private final RuleRunnerClient runner;
    private final ProjectionCache projections;
    private final QuestionStamps stamps;
    private final Clock clock;
    private final JsonMapper json;

    public PracticeReadService(PracticeReadMapper reads, QuestionMapper questions, BankAvailability availability,
            ContentPages pages, RuleRunnerClient runner, ProjectionCache projections, QuestionStamps stamps, Clock clock, JsonMapper json) {
        this.reads = reads; this.questions = questions; this.availability = availability; this.pages = pages;
        this.runner = runner; this.projections = projections; this.stamps = stamps; this.clock = clock; this.json = json;
    }

    public Catalog catalog(String userId) {
        List<Bank> banks = reads.collections(userId).stream().map(collection -> new Bank(collection.id(), "bank",
                collection.title(), collection.groupName(), collection.questionCount(), available(collection.bankId()))).toList();
        List<String> groups = banks.stream().map(Bank::group).filter(group -> group != null && !group.isBlank()).distinct().toList();
        return new Catalog(banks, List.of(), Map.of("banks", groups, "extensions", List.of()), true, false);
    }

    public Overview overview(String userId, String id) {
        var collection = collection(userId, id);
        String context = context(userId, id);
        List<QuestionSummary> questions = new ArrayList<>();
        Map<String, State> states = new LinkedHashMap<>();
        for (var row : reads.questions(collection.bankId(), context, userId)) {
            JsonNode outline = decode(row.outline());
            questions.add(new QuestionSummary(row.id(), row.title(),
                    new Extension(row.extensionId(), row.extensionVersion(), row.extensionName(), hex(row.extensionDigest())),
                    outline != null && outline.path("level").asString().equals("question") ? outline.path("label").asString() : null,
                    outline != null && outline.path("level").asString().equals("parts") ? outline.path("items") : null, decode(row.listening()), row.outlineGroupIndex(), row.outlineGroupName()));
            states.put(row.id(), new State(row.status() == null ? "unanswered" : row.status(), null,
                    "submitted".equals(row.status()) ? SubmissionResults.result(json, row.result(), row.score(), row.maxScore(), row.gradingStatus()) : null,
                    row.revision() == null ? 0 : row.revision(), decode(row.answerState()), decode(row.outlineStates())));
        }
        // The revision is an opaque validator, not a raw bank hash containing answer-dependent information.
        String revision = stamps.stamp(context, "bank", 0, collection.contentSha256(), collection.contentSha256()).contentVersion();
        return new Overview(id, "bank", collection.title(), revision, collection.description(), features(collection),
                listeningMetadata(collection), List.copyOf(questions), java.util.Collections.unmodifiableMap(states),
                new Availability(true, null, null), states.values().stream().allMatch(state -> "submitted".equals(state.status())));
    }

    public Object question(String userId, String id, String qid, String knownStamp) {
        var source = source(userId, id, qid);
        var collection = source.collection();
        var question = source.question();
        String context = context(userId, id);
        var row = reads.state(context, qid, userId).orElse(null);
        var stamp = stamps.stamp(context, qid, row == null ? 0 : row.revision(), question.contentSha256(), question.extensionDigest());
        if (matches(knownStamp, stamp)) return new Unchanged(true, stamp);
        return renderQuestion(userId, qid, collection, question, context, row, stamp);
    }

    public record PreparedDraft(String contextId, byte[] contentSha256, byte[] extensionDigest, QuestionResponse template, String userId, String collectionSnapshot) {}

    /** Resolve ownership and immutable projection before entering the draft/receipt transaction. */
    public PreparedDraft prepareDraft(String userId, String id, String qid) {
        var source = source(userId, id, qid);
        var collection = source.collection();
        var question = source.question();
        String context = context(userId, id);
        var template = renderQuestion(userId, qid, collection, question, context, null,
                stamps.stamp(context, qid, 0, question.contentSha256(), question.extensionDigest()));
        return new PreparedDraft(context, question.contentSha256(), question.extensionDigest(), template, userId,
                collectionSnapshot(collection, context, userId));
    }

    /** Validate the public version without projecting or grading; receipt replay must not execute rules. */
    public void validateContentVersion(String userId, String collectionId, String qid, String contentVersion) {
        var source = source(userId, collectionId, qid);
        String context = context(userId, collectionId);
        if (contentVersion != null && !contentVersion.equals(stamps.stamp(context, qid, 0,
                source.question().contentSha256(), source.question().extensionDigest()).contentVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTENT_CONFLICT", "题目内容版本已变化，请刷新后重试");
        }
    }

    private String collectionSnapshot(PracticeReadRows.Collection collection, String context, String userId) {
        var snapshot = json.createObjectNode();
        snapshot.put("id", collection.id()); snapshot.put("kind", "bank"); snapshot.put("title", collection.title());
        snapshot.put("description", collection.description());
        snapshot.set("features", features(collection)); snapshot.set("listening", listeningMetadata(collection));
        var items = snapshot.putArray("questions");
        for (var row : reads.questions(collection.bankId(), context, userId)) {
            var outline = decode(row.outline());
            var item = items.addObject(); item.put("id", row.id()); item.put("title", row.title());
            item.put("contentSha256", hex(row.contentSha256()));
            item.set("type", json.valueToTree(new Extension(row.extensionId(), row.extensionVersion(), row.extensionName(), hex(row.extensionDigest()))));
            item.set("outline", outline); item.set("listening", decode(row.listening()));
            item.set("outlineLabel", outline != null && "question".equals(outline.path("level").asString()) ? outline.get("label") : null);
            item.set("outlineItems", outline != null && "parts".equals(outline.path("level").asString()) ? outline.get("items") : null);
            item.put("outlineGroupIndex", row.outlineGroupIndex()); item.put("outlineGroupName", row.outlineGroupName());
        }
        return json.writeValueAsString(snapshot);
    }

    /** Internal rule input; ownership, availability and exact question lookup match the read path. */
    public record RuleQuestion(JsonNode data, RulePackage rulePackage) {}

    public RuleQuestion ruleQuestion(String userId, String collectionId, String questionId) {
        var source = source(userId, collectionId, questionId);
        return new RuleQuestion(json.readTree(source.question().data()),
                pages.rulePackage(pages.extension(source.question().extensionDigest())));
    }

    private record QuestionSource(PracticeReadRows.Collection collection, QuestionRow question) {}
    private QuestionSource source(String userId, String collectionId, String questionId) {
        var collection = collection(userId, collectionId);
        return new QuestionSource(collection, questions.findById(collection.bankId(), questionId).orElseThrow(() -> notFound()));
    }

    private QuestionResponse renderQuestion(String userId, String qid, PracticeReadRows.Collection collection,
            QuestionRow question, String context, PracticeReadRows.State row, QuestionStamps.Stamp stamp) {
        boolean submitted = row != null && row.status().equals("submitted");
        JsonNode projected;
        ExtensionPackageRow extension = pages.extension(question.extensionDigest());
        if (submitted) {
            if (row.projection() == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "已提交题目的投影缺失，请联系管理员");
            projected = decode(row.projection());
        } else {
            var key = new ProjectionKey(hex(question.contentSha256()), hex(question.extensionDigest()));
            projected = decode(projections.get(key, () -> project(extension, question.data())));
            if (projected == null) throw unavailable();
        }
        State state = row == null ? new State("unanswered", null, null, 0, null, null)
                : new State(row.status(), decode(row.answer()), submitted ? SubmissionResults.result(json, row.result(), row.score(), row.maxScore(), row.gradingStatus()) : null, row.revision(),
                        decode(row.answerState()), decode(row.outlineStates()));
        return new QuestionResponse(new Question(qid, question.title(), projected, question.outlineGroupIndex(), question.outlineGroupName()), state,
                reads.whiteboard(context, qid, userId).map(json::readTree).orElse(null), stamp,
                new Extension(extension.extensionId(), extension.version(), extension.name(), hex(extension.digest())),
                features(collection), new Capabilities(!submitted, !submitted, false, false, false, false), null);
    }

    private String project(ExtensionPackageRow extension, String data) {
        try {
            var result = runner.execute("project", pages.rulePackage(extension),
                    Map.of("data", json.readTree(data), "state", Map.of("submitted", false, "result", json.nullNode())));
            if (!result.ok()) {
                String code = RULE_CODES.contains(result.code() == null ? "" : result.code()) ? result.code() : "RULE_REJECTED";
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "题型投影失败，请稍后重试（" + code + "）");
            }
            if (result.data() == null || !result.data().hasNonNull("projected")) throw unavailable();
            return json.writeValueAsString(result.data().get("projected"));
        } catch (RuleRunnerException error) { throw unavailable(); }
    }

    public ContentPages.FileResponse listening(String userId, String id, String materialId) throws java.io.IOException {
        var collection = collection(userId, id);
        JsonNode config = decode(collection.listening());
        if (config != null) for (JsonNode material : config.path("materials")) {
            if (material.path("id").asString().equals(materialId)) {
                return pages.listening(userId, collection.bankId(), material.path("file").asString());
            }
        }
        throw notFound();
    }

    private JsonNode listeningMetadata(PracticeReadRows.Collection collection) {
        JsonNode config = decode(collection.listening());
        if (config == null) return null;
        List<Map<String, Object>> materials = new ArrayList<>();
        for (JsonNode material : config.path("materials")) {
            String id = material.path("id").asString();
            String revision = stamps.stamp(collection.id(), id, 0, collection.contentSha256(), collection.contentSha256()).contentVersion();
            materials.add(Map.of("id", id, "title", material.path("title").asString(), "revision", revision));
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("materials", materials);
        if (config.has("examMaterialId")) result.put("examMaterialId", config.path("examMaterialId").asString());
        return json.valueToTree(result);
    }

    private PracticeReadRows.Collection collection(String userId, String id) {
        var collection = reads.collection(userId, id).orElseThrow(() -> notFound());
        if (!availability.isAvailable(collection.bankId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CLOUD_EXTENSION_REVIEW_REQUIRED", "题库审核中，或所用拓展的审核已撤销");
        }
        return collection;
    }

    private Availability available(String bankId) {
        boolean available = availability.isAvailable(bankId);
        return new Availability(available, available ? null : "CLOUD_EXTENSION_REVIEW_REQUIRED", available ? null : "审核中");
    }

    private String context(String userId, String collection) {
        return reads.context(userId, collection).orElseGet(() -> {
            try { reads.insertContext(Ids.uuidV7(), userId, collection, clock.instant().truncatedTo(ChronoUnit.MILLIS)); }
            catch (DuplicateKeyException concurrentOpen) { /* uk_context_scope identifies the one shared archive. */ }
            return reads.context(userId, collection).orElseThrow(() -> notFound());
        });
    }

    private boolean matches(String known, QuestionStamps.Stamp actual) {
        if (known == null) return false;
        if (known.length() > 1024) throw invalidStamp();
        JsonNode stamp;
        try { stamp = json.readTree(known); } catch (RuntimeException invalid) { throw invalidStamp(); }
        if (stamp == null || !stamp.isObject() || !stamp.path("revision").isIntegralNumber()
                || !stamp.path("revision").canConvertToLong() || stamp.path("revision").asLong() < 0
                || !stamp.path("contentVersion").isString() || !stamp.path("packageVersion").isString()) throw invalidStamp();
        return stamp.path("revision").asLong() == actual.revision()
                && stamp.path("contentVersion").asString().equals(actual.contentVersion())
                && stamp.path("packageVersion").asString().equals(actual.packageVersion());
    }

    private JsonNode decode(String value) { return value == null ? null : json.readTree(value); }
    private JsonNode features(PracticeReadRows.Collection collection) {
        var result = (tools.jackson.databind.node.ObjectNode) json.readTree(collection.features());
        result.put("history", true);
        return result;
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RULE_UNAVAILABLE", "规则服务暂时不可用，请稍后重试"); }
    private static ApiException invalidStamp() { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "题目版本戳格式无效"); }
}
