package cloud.treenewbee.quizforge.ai.application;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.ai.application.AiConcurrencyLimiter.Permit;
import cloud.treenewbee.quizforge.ai.application.UpstreamKeyService.UpstreamKey;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.PreDestroy;

/**
 * 统一的模型调用入口（L5.2，设计 08 第 0、1 节）。AI 辅导（L5.3）与 AI 评分（L5.4）只通过这里调用模型。
 *
 * <p>每次调用：
 * <ol>
 *   <li>普通读检查余额 &gt; 0，否则 402「余额不足，请充值」；</li>
 *   <li>每用户并发 2、全站并发可配置，超出立即 429，不排队；</li>
 *   <li>按顺序最多尝试 3 个 Key（事务外）：401/403/欠费把 Key 标为不可用并换下一个；429、5xx、超时、网络错误换下一个；
 *       400/404 等请求本身的问题直接失败；</li>
 *   <li>成功后按所用 Key 的单价扣费：ceil((输入 token × 输入单价 + 输出 token × 输出单价) / 1,000,000)，
 *       至少 1 个单位（0.0001 元），调用 {@link AiBalanceService#charge}；同一 ref 已扣过（409）按已扣处理；</li>
 *   <li>失败（超时、网络错误、5xx、流式中途出错等未得到完整回复与用量的调用）不扣费。</li>
 * </ol>
 *
 * <p>工具调用：请求可以带 toolSpecifications，回复中保留模型的工具调用请求（{@link AiReply#toolCalls()}）；
 * 工具循环由调用方实现，每一次模型调用单独计费，ref 由调用方提供且每次不同。
 */
@Service
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);
    static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;
    static final long MAX_TOTAL_IMAGE_BYTES = 12L * 1024 * 1024;
    private static final Pattern DATA_URL = Pattern.compile("data:(image/(?:png|jpeg|webp));base64,(.*)", Pattern.DOTALL);
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    /** 上游返回这些错误码时是 Key 所属账号的问题（欠费、额度用完、账号停用），与 401/403 一样换 Key 并标为不可用。 */
    private static final Set<String> KEY_ACCOUNT_CODES = Set.of("Arrearage", "insufficient_quota", "quota_exceeded",
            "billing_hard_limit_reached", "account_deactivated", "AllocationQuota.FreeTierOnly", "BudgetLimitExceeded",
            "PrepaidBillOverdue", "PostpaidBillOverdue", "CommodityNotPurchased", "InvalidApiKey");

    /**
     * 一次成功调用的结果。
     *
     * @param response      LangChain4j 的完整回复（含 AiMessage、finishReason）
     * @param inputTokens   上游报告的输入 token（未报告为 0）
     * @param outputTokens  上游报告的输出 token（未报告为 0）
     * @param usageReported 上游是否返回了用量；未返回时按最低 1 个单位扣费
     * @param cost          本次费用（0.0001 元）
     * @param alreadyCharged 该 ref 此前已经扣过（重试），本次没有再扣
     * @param balanceAfter  扣费后的余额；alreadyCharged 时为 null
     * @param keyId         所用 Key 的 ID
     */
    public record AiReply(ChatResponse response, long inputTokens, long outputTokens, boolean usageReported, long cost,
                          boolean alreadyCharged, Long balanceAfter, String keyId) {

        public AiMessage message() {
            return response.aiMessage();
        }

        public String text() {
            return response.aiMessage().text();
        }

        /** 模型请求执行的工具（没有时为空）；由调用方执行后把结果作为 ToolExecutionResultMessage 再调用一次。 */
        public List<ToolExecutionRequest> toolCalls() {
            return response.aiMessage().hasToolExecutionRequests() ? response.aiMessage().toolExecutionRequests() : List.of();
        }
    }

    /**
     * 流式回调。onPartial 在上游的读取线程上调用；它抛出异常（例如客户端已断开）时网关不再转发后续片段，
     * 但仍继续读完上游的回复、取得用量并扣费，最后照常调用 onComplete。onComplete 与 onError 只调用其中一个，各一次。
     */
    public interface AiStreamHandler {
        void onPartial(String text);

        void onComplete(AiReply reply);

        void onError(ApiException error);
    }

    private enum Kind { KEY, RETRY, REQUEST }

    record Failure(Kind kind, int status, String reason) {
    }

    private final AiBalanceService balances;
    private final UpstreamKeyService keys;
    private final AiModelClient client;
    private final AiConcurrencyLimiter limiter;
    private final AiGatewaySettings settings;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-stream-timer");
        thread.setDaemon(true);
        return thread;
    });
    /** 流式结束后的扣费与回调不占用上游 HTTP 客户端的线程。 */
    private final ExecutorService settlements = Executors.newVirtualThreadPerTaskExecutor();

    public AiGateway(AiBalanceService balances, UpstreamKeyService keys, AiModelClient client, AiConcurrencyLimiter limiter,
                     AiGatewaySettings settings) {
        this.balances = balances;
        this.keys = keys;
        this.client = client;
        this.limiter = limiter;
        this.settings = settings;
    }

    @PreDestroy
    void stop() {
        timers.shutdownNow();
        settlements.shutdown();
    }

    // ---------------------------------------------------------------- 同步

    /** 同步调用：返回完整回复、用量与扣费。失败时抛出 ApiException（不扣费）。 */
    public AiReply chat(String userId, ChatRequest request, String ref) {
        ChatRequest prepared = prepare(request, ref);
        requireBalance(userId);
        try (Permit ignored = limiter.acquire(userId)) {
            return call(userId, prepared, ref, requireKeys(), "chat");
        }
    }

    /** 异步任务先持久化模型输出，再在采用结果的短事务中结算。普通 chat/stream 不受影响。 */
    public record Returned(String text, long inputTokens, long outputTokens, boolean usageReported,
            long cost, String keyId, String ref) {}

    public interface TaskCheckpoint {
        /** 每次真实上游尝试都计数，换 Key 与输出修复共用任务的三次预算。 */
        void beforeCall();
        void returned(Returned value);
    }

    public Returned chat(String userId, ChatRequest request, String ref, TaskCheckpoint checkpoint) {
        ChatRequest prepared = prepare(request, ref);
        requireBalance(userId);
        try (Permit ignored = limiter.acquire(userId)) {
            List<Failure> failures = new ArrayList<>();
            for (UpstreamKey key : requireKeys()) {
                checkpoint.beforeCall();
                ChatResponse response;
                try { response = client.chat(key, prepared); }
                catch (RuntimeException error) {
                    Failure failure = classify(error);
                    failures.add(failure); note(key, failure);
                    if (failure.kind() == Kind.REQUEST) break;
                    continue;
                }
                TokenUsage usage = response.tokenUsage();
                long input = count(usage == null ? null : usage.inputTokenCount());
                long output = count(usage == null ? null : usage.outputTokenCount());
                boolean reported = usage != null && (usage.inputTokenCount() != null || usage.outputTokenCount() != null);
                var value = new Returned(response.aiMessage().text(), input, output, reported,
                        cost(input, output, key.inputPrice(), key.outputPrice()), key.id(), ref);
                checkpoint.returned(value);
                return value;
            }
            throw failed(failures);
        }
    }

    /** 与任务结果一起提交；没有模型调用，也不会再次检查余额是否为正。 */
    public void settleReturned(String userId, Returned value) {
        if (balances.chargeOnce(userId, value.cost(), value.ref()) != null)
            keys.recordUsage(value.keyId(), value.inputTokens(), value.outputTokens(), value.cost());
    }

    /** 复用已有的虚拟线程执行器，评分任务不再创建线程池。 */
    public void runBackgroundTask(Runnable task) { settlements.execute(task); }

    private AiReply call(String userId, ChatRequest prepared, String ref, List<UpstreamKey> candidates, String mode) {
        long started = System.nanoTime();
        List<Failure> failures = new ArrayList<>();
        for (UpstreamKey key : candidates) {
            ChatResponse response;
            try {
                response = client.chat(key, prepared);
            } catch (RuntimeException error) {
                Failure failure = classify(error);
                failures.add(failure);
                note(key, failure);
                if (failure.kind() == Kind.REQUEST) break;
                continue;
            }
            return settle(userId, ref, key, response, started, mode);
        }
        log.info("AI 调用失败 mode={} user={} ref={} tried={} ms={} result={}", mode, userId, ref, failures.size(),
                elapsed(started), failures.stream().map(Failure::reason).toList());
        throw failed(failures);
    }

    // ---------------------------------------------------------------- 流式

    /**
     * 流式调用：余额、并发与请求检查不通过时立即抛出 ApiException；之后的结果通过 handler 回调，
     * 同时由返回的 future 给出（成功为 AiReply，失败以 ApiException 结束）。
     */
    public CompletableFuture<AiReply> stream(String userId, ChatRequest request, String ref, AiStreamHandler handler) {
        ChatRequest prepared = prepare(request, ref);
        requireBalance(userId);
        Permit permit = limiter.acquire(userId);
        try {
            StreamCall call = new StreamCall(userId, prepared, ref, requireKeys(), handler, permit);
            call.start();
            return call.result;
        } catch (RuntimeException error) {
            permit.close();
            throw error;
        }
    }

    private final class StreamCall {
        final String userId;
        final ChatRequest request;
        final String ref;
        final List<UpstreamKey> candidates;
        final AiStreamHandler handler;
        final Permit permit;
        final CompletableFuture<AiReply> result = new CompletableFuture<>();
        final long started = System.nanoTime();
        final List<Failure> failures = new ArrayList<>();
        int attempt = -1;
        boolean delivering;      // 已经向调用方转发过片段：之后出错不能再换 Key
        boolean detached;        // 调用方的 onPartial 抛出异常：不再转发，但继续读完上游
        boolean finished;
        StreamingHandle handle;
        ScheduledFuture<?> firstEventTimer;
        ScheduledFuture<?> deadline;

        StreamCall(String userId, ChatRequest request, String ref, List<UpstreamKey> candidates, AiStreamHandler handler,
                   Permit permit) {
            this.userId = userId;
            this.request = request;
            this.ref = ref;
            this.candidates = candidates;
            this.handler = handler;
            this.permit = permit;
        }

        synchronized void start() {
            deadline = timers.schedule(this::deadlineReached, settings.streamTimeout().toMillis(), TimeUnit.MILLISECONDS);
            next();
        }

        /** 尝试下一个 Key；调用时持有锁。 */
        private void next() {
            int current = ++attempt;
            UpstreamKey key = candidates.get(current);
            firstEventTimer = timers.schedule(() -> attemptFailed(current, key,
                    new Failure(Kind.RETRY, 0, "等待上游超时")), settings.callTimeout().toMillis(), TimeUnit.MILLISECONDS);
            try {
                client.stream(key, request, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                        partial(current, partial.text(), context.streamingHandle());
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        completed(current, key, response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        attemptFailed(current, key, classify(error));
                    }
                });
            } catch (RuntimeException error) {
                // 在锁内同步失败：交给定时线程处理，避免在 next() 内递归。
                timers.execute(() -> attemptFailed(current, key, classify(error)));
            }
        }

        synchronized void partial(int current, String text, StreamingHandle streaming) {
            if (finished || current != attempt) {
                // 已放弃的尝试（等待超时后换了 Key）晚到的片段：停止读取，不转发、不计费。
                if (streaming != null) streaming.cancel();
                return;
            }
            handle = streaming;
            if (firstEventTimer != null) firstEventTimer.cancel(false);
            delivering = true;
            if (detached || text == null || text.isEmpty()) return;
            try {
                handler.onPartial(text);
            } catch (RuntimeException error) {
                detached = true;
                log.info("AI 流式调用的接收方已断开，继续读取上游以取得用量 user={} ref={}", userId, ref);
            }
        }

        synchronized void completed(int current, UpstreamKey key, ChatResponse response) {
            if (finished || current != attempt) return;
            finished = true;
            cancelTimers();
            settlements.execute(() -> {
                AiReply reply;
                try {
                    reply = settle(userId, ref, key, response, started, "stream");
                } catch (RuntimeException error) {
                    permit.close();
                    ApiException failure = error instanceof ApiException api ? api
                            : new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_CHARGE_FAILED", "AI 扣费失败，请稍后重试");
                    log.error("AI 流式调用扣费失败 user={} ref={}", userId, ref, error);
                    notifyError(failure);
                    result.completeExceptionally(failure);
                    return;
                }
                permit.close();
                try {
                    handler.onComplete(reply);
                } catch (RuntimeException ignored) {
                    // 调用方已断开：费用已经按用量记入。
                }
                result.complete(reply);
            });
        }

        synchronized void attemptFailed(int current, UpstreamKey key, Failure failure) {
            if (finished || current != attempt) return;
            if (firstEventTimer != null) firstEventTimer.cancel(false);
            failures.add(failure);
            note(key, failure);
            if (!delivering && failure.kind() != Kind.REQUEST && attempt + 1 < candidates.size()) {
                next();
                return;
            }
            fail(delivering ? new ApiException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_FAILED", "AI 回复中断，本次不扣费")
                    : failed(failures));
        }

        synchronized void deadlineReached() {
            if (finished) return;
            if (handle != null) handle.cancel();
            failures.add(new Failure(Kind.RETRY, 0, "回复超时"));
            fail(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_UPSTREAM_TIMEOUT", "AI 回复超时，本次不扣费"));
        }

        private void fail(ApiException error) {
            finished = true;
            cancelTimers();
            permit.close();
            log.info("AI 调用失败 mode=stream user={} ref={} tried={} ms={} result={}", userId, ref, attempt + 1,
                    elapsed(started), failures.stream().map(Failure::reason).toList());
            settlements.execute(() -> {
                notifyError(error);
                result.completeExceptionally(error);
            });
        }

        private void notifyError(ApiException error) {
            try {
                handler.onError(error);
            } catch (RuntimeException ignored) {
                // 调用方已断开。
            }
        }

        private void cancelTimers() {
            if (firstEventTimer != null) firstEventTimer.cancel(false);
            if (deadline != null) deadline.cancel(false);
        }
    }

    // ---------------------------------------------------------------- 测试连接（管理员）

    /** 用一个很短的请求测试指定的 Key，费用计入管理员自己。成功时恢复 Key 的可用状态。 */
    public Map<String, Object> testKey(String actorId, String keyId) {
        UpstreamKey key = keys.key(keyId);
        ChatRequest probe = prepare(ChatRequest.builder().messages(UserMessage.from("请只回复 OK")).maxOutputTokens(8).build(),
                "x");
        requireBalance(actorId);
        Map<String, Object> result = new LinkedHashMap<>();
        long started = System.nanoTime();
        try (Permit ignored = limiter.acquire(actorId)) {
            AiReply reply = call(actorId, probe, "ai-key-test:" + Ids.uuidV7(), List.of(key), "key-test");
            keys.markOk(key);
            result.put("ok", true);
            result.put("cost", reply.cost());
        } catch (ApiException error) {
            if (!error.code().startsWith("AI_UPSTREAM") && !error.code().equals("AI_KEY_REJECTED")) throw error;
            result.put("ok", false);
            result.put("reason", error.getMessage());
        }
        result.put("ms", elapsed(started));
        keys.auditTest(actorId, keyId, result);
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private AiReply settle(String userId, String ref, UpstreamKey key, ChatResponse response, long started, String mode) {
        TokenUsage usage = response.tokenUsage();
        long input = count(usage == null ? null : usage.inputTokenCount());
        long output = count(usage == null ? null : usage.outputTokenCount());
        boolean reported = usage != null && (usage.inputTokenCount() != null || usage.outputTokenCount() != null);
        long cost = cost(input, output, key.inputPrice(), key.outputPrice());
        if (!reported) log.warn("AI 上游没有返回用量，按最低费用扣费 user={} ref={} key={}", userId, ref, key.id());
        boolean already = false;
        Long balance = null;
        try {
            balance = balances.charge(userId, cost, ref).balanceAfter();
        } catch (ApiException error) {
            if (!"LEDGER_DUPLICATE".equals(error.code())) throw error;
            already = true;
        }
        if (!already) keys.recordUsage(key, input, output, cost);
        log.info("AI 调用 mode={} user={} ref={} key={} in={} out={} cost={} ms={} result={}", mode, userId, ref, key.id(),
                input, output, cost, elapsed(started), already ? "ok-already-charged" : "ok");
        return new AiReply(response, input, output, reported, cost, already, balance, key.id());
    }

    /** 费用 = ceil((输入 × 输入单价 + 输出 × 输出单价) / 1,000,000)，至少 1 个单位（与旧平台一致）。 */
    static long cost(long inputTokens, long outputTokens, long inputPrice, long outputPrice) {
        BigInteger amount = BigInteger.valueOf(inputTokens).multiply(BigInteger.valueOf(inputPrice))
                .add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(outputPrice)));
        BigInteger units = amount.add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000));
        return Math.max(1, units.min(BigInteger.valueOf(Long.MAX_VALUE / 2)).longValue());
    }

    private static long count(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private void requireBalance(String userId) {
        if (!balances.hasPositiveBalance(userId)) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "AI_BALANCE_EXHAUSTED", "余额不足，请充值");
        }
    }

    private List<UpstreamKey> requireKeys() {
        List<UpstreamKey> candidates = keys.candidates();
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试");
        }
        return candidates;
    }

    /** 校验并整理请求：ref、图片大小、max_tokens 上限；模型 ID 统一使用配置值。 */
    ChatRequest prepare(ChatRequest request, String ref) {
        if (ref == null || ref.isBlank() || ref.length() > 100) {
            throw new IllegalArgumentException("ref is required (at most 100 characters) and must differ for every model call");
        }
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw invalid("请求中没有消息");
        }
        checkImages(request.messages());
        Integer requested = request.maxOutputTokens();
        if (requested != null && (requested < 1 || requested > settings.maxOutputTokens())) {
            throw invalid("max_tokens 必须在 1 到 " + settings.maxOutputTokens() + " 之间");
        }
        ChatRequestParameters parameters = request.parameters().overrideWith(ChatRequestParameters.builder()
                .modelName(settings.model())
                .maxOutputTokens(requested == null ? settings.maxOutputTokens() : requested)
                .build());
        return ChatRequest.builder().messages(request.messages()).parameters(parameters).build();
    }

    /** 图片只接受 PNG、JPEG、WebP 的 base64 / data URL；单张 ≤ 4 MiB，一次请求合计 ≤ 12 MiB（沿用旧版）。 */
    static void checkImages(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage message : messages) {
            if (!(message instanceof UserMessage user)) continue;
            for (Content content : user.contents()) {
                if (content instanceof TextContent) continue;
                if (!(content instanceof ImageContent image)) throw invalid("只支持文本与图片内容");
                long bytes = imageBytes(image.image());
                if (bytes > MAX_IMAGE_BYTES) throw tooLarge("单张图片不能超过 4 MiB");
                total += bytes;
                if (total > MAX_TOTAL_IMAGE_BYTES) throw tooLarge("一次请求的图片合计不能超过 12 MiB");
            }
        }
    }

    private static long imageBytes(Image image) {
        String type;
        String data;
        if (image.base64Data() != null) {
            type = image.mimeType() == null ? "" : image.mimeType().toLowerCase(Locale.ROOT);
            data = image.base64Data();
        } else {
            Matcher match = image.url() == null ? null : DATA_URL.matcher(image.url().toString());
            if (match == null || !match.matches()) throw invalid("图片只接受 PNG、JPEG、WebP 的 data URL");
            type = match.group(1);
            data = match.group(2);
        }
        if (!IMAGE_TYPES.contains(type) || data.isEmpty() || data.length() % 4 != 0) {
            throw invalid("图片只接受 PNG、JPEG、WebP 的 data URL");
        }
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            boolean valid = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/'
                    || c == '=' && i >= data.length() - 2;
            if (!valid) throw invalid("图片的 base64 数据无效");
        }
        return data.length() / 4L * 3 - (data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0);
    }

    private void note(UpstreamKey key, Failure failure) {
        if (failure.kind() == Kind.KEY) keys.markUnavailable(key, failure.reason());
        else keys.recordError(key, failure.reason());
    }

    /** 区分 Key 本身的问题、可以换 Key 重试的问题与请求本身的问题。 */
    static Failure classify(Throwable error) {
        HttpException http = null;
        for (Throwable cause = error; cause != null && http == null; cause = cause.getCause()) {
            if (cause instanceof HttpException found) http = found;
            if (cause.getCause() == cause) break;
        }
        String body = http != null ? String.valueOf(http.getMessage()) : String.valueOf(error.getMessage());
        for (String code : KEY_ACCOUNT_CODES) {
            if (body.contains("\"" + code + "\"")) return new Failure(Kind.KEY, http == null ? 0 : http.statusCode(), "欠费、额度不足或 Key 无效（" + code + "）");
        }
        if (http != null) {
            int status = http.statusCode();
            if (status == 401) return new Failure(Kind.KEY, status, "Key 无效（HTTP 401）");
            if (status == 403) return new Failure(Kind.KEY, status, "没有权限（HTTP 403）");
            if (status == 402) return new Failure(Kind.KEY, status, "欠费或额度不足（HTTP 402）");
            if (status == 429) return new Failure(Kind.RETRY, status, "被限流（HTTP 429）");
            if (status == 408 || status >= 500) return new Failure(Kind.RETRY, status, "上游错误（HTTP " + status + "）");
            return new Failure(Kind.REQUEST, status, "请求被拒绝（HTTP " + status + "）");
        }
        if (error instanceof AuthenticationException) return new Failure(Kind.KEY, 401, "Key 无效");
        if (error instanceof InvalidRequestException || error instanceof ModelNotFoundException
                || error instanceof ContentFilteredException) {
            return new Failure(Kind.REQUEST, 400, "请求被拒绝");
        }
        return new Failure(Kind.RETRY, 0, "无法连接或等待超时");
    }

    private static ApiException failed(List<Failure> failures) {
        Failure last = failures.isEmpty() ? null : failures.getLast();
        if (last != null && last.kind() == Kind.REQUEST) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_REJECTED", "模型拒绝了这个请求，本次不扣费");
        }
        if (!failures.isEmpty() && failures.stream().allMatch(failure -> failure.status() == 429)) {
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_UPSTREAM_BUSY", "AI 服务当前请求较多，请稍后重试，本次不扣费");
        }
        if (!failures.isEmpty() && failures.stream().allMatch(failure -> failure.kind() == Kind.KEY)) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_KEY_REJECTED", "AI 服务暂时不可用，请稍后重试，本次不扣费");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_FAILED", "AI 服务暂时没有响应，本次不扣费");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "AI_REQUEST_INVALID", message);
    }

    private static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "AI_REQUEST_TOO_LARGE", message);
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
