package cloud.treenewbee.quizforge.client.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.ai.application.AiBalanceService;
import cloud.treenewbee.quizforge.ai.application.AiGateway;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.output.FinishReason;

/**
 * 旧客户端的 AI 网关（OpenAI 兼容，遗留契约 C-05 第 9 节）。校验规则与旧平台 AiGateway.validate 相同，
 * 模型调用复用 L5.2 的 {@link AiGateway}（余额检查、每用户并发 2、Key 池、按用量计费），错误码映射回旧契约。
 * 旧网关与旧客户端都没有流式：stream 只接受 false。不记录题目、答案、图片或模型输出。
 */
@Service
public class ClientAiService {

    public static final String MODEL = "quizforge-official";
    private static final Set<String> FIELDS = Set.of("model", "messages", "max_tokens", "response_format", "temperature", "stream");
    private static final Set<String> ROLES = Set.of("system", "user", "assistant");
    private static final Set<String> FORMATS = Set.of("json_object", "json_schema", "text");
    private static final Pattern IMAGE = Pattern.compile("data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]+={0,2})");
    private static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 12L * 1024 * 1024;

    private final AiGateway gateway;
    private final AiBalanceService balances;

    public ClientAiService(AiGateway gateway, AiBalanceService balances) {
        this.gateway = gateway;
        this.balances = balances;
    }

    public Map<String, Object> complete(String userId, Map<String, Object> body) {
        ChatRequest request = request(body);
        AiGateway.AiReply reply;
        try {
            reply = gateway.chat(userId, request, "client-ai:" + Ids.uuidV7());
        } catch (ApiException error) {
            throw legacy(error);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", reply.text() == null ? "" : reply.text());
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason(reply.response().finishReason()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "qf-" + UUID.randomUUID());
        result.put("object", "chat.completion");
        result.put("model", MODEL);
        result.put("choices", List.of(choice));
        if (reply.usageReported()) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", reply.inputTokens());
            usage.put("completion_tokens", reply.outputTokens());
            usage.put("total_tokens", reply.inputTokens() + reply.outputTokens());
            result.put("usage", usage);
        }
        long balance = reply.balanceAfter() != null ? reply.balanceAfter() : balances.balance(userId).balance();
        Map<String, Object> accounting = new LinkedHashMap<>();
        accounting.put("cost", reply.cost());
        accounting.put("balance", Math.max(0, balance));
        result.put("quizforge", accounting);
        return result;
    }

    /** 与旧平台相同的白名单校验；通过后转换为模型请求。 */
    public static ChatRequest request(Map<String, Object> body) {
        if (!FIELDS.containsAll(body.keySet()) || body.containsKey("stream") && !Boolean.FALSE.equals(body.get("stream"))) {
            throw invalid("不支持的请求字段或流式输出");
        }
        if (!MODEL.equals(body.get("model"))) throw invalid("model 必须为 " + MODEL);
        if (!(body.get("messages") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 50) {
            throw invalid("messages 必须是 1–50 条消息");
        }
        long[] images = {0};
        List<ChatMessage> messages = new ArrayList<>();
        try {
            for (Object item : raw) messages.add(message(item, images));
        } catch (IllegalArgumentException error) {
            // 空白文本等 LangChain4j 不接受的内容。
            throw invalid("消息内容格式不正确");
        }
        ChatRequest.Builder request = ChatRequest.builder().messages(messages);
        if (body.containsKey("max_tokens")) {
            if (!(body.get("max_tokens") instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 1 || n.longValue() > 8192) {
                throw invalid("max_tokens 必须是 1–8192 的整数");
            }
            request.maxOutputTokens(n.intValue());
        }
        if (body.containsKey("temperature")) {
            if (!(body.get("temperature") instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 2) {
                throw invalid("temperature 必须在 0–2 之间");
            }
            request.temperature(n.doubleValue());
        }
        if (body.containsKey("response_format")) {
            if (!(body.get("response_format") instanceof Map<?, ?> format) || !(format.get("type") instanceof String type) || !FORMATS.contains(type)) {
                throw invalid("response_format 不受支持");
            }
            // json_schema 按 JSON 输出处理：官方额度的客户端只用 json_object，结果由客户端校验。
            request.responseFormat("text".equals(type) ? ResponseFormat.TEXT : ResponseFormat.JSON);
        }
        return request.build();
    }

    private static ChatMessage message(Object raw, long[] images) {
        if (!(raw instanceof Map<?, ?> message) || !Set.of("role", "content").containsAll(message.keySet())
                || !(message.get("role") instanceof String role) || !ROLES.contains(role)) {
            throw invalid("消息格式不正确");
        }
        Object content = message.get("content");
        List<Content> parts = new ArrayList<>();
        if (content instanceof String text) {
            parts.add(TextContent.from(text));
        } else if (content instanceof List<?> items && !items.isEmpty()) {
            for (Object item : items) parts.add(part(item, images));
        } else {
            throw invalid("消息内容格式不正确");
        }
        if ("user".equals(role)) return UserMessage.from(parts);
        // 模型接口中 system 与 assistant 只有文本；已发布客户端只在 user 消息中发送图片。
        StringBuilder text = new StringBuilder();
        for (Content part : parts) {
            if (!(part instanceof TextContent value)) throw invalid("图片只能放在 user 消息中");
            text.append(value.text());
        }
        return "system".equals(role) ? SystemMessage.from(text.toString()) : AiMessage.from(text.toString());
    }

    private static Content part(Object raw, long[] images) {
        if (!(raw instanceof Map<?, ?> part)) throw invalid("消息内容格式不正确");
        if ("text".equals(part.get("type")) && part.get("text") instanceof String text && part.size() == 2) return TextContent.from(text);
        if (!"image_url".equals(part.get("type")) || part.size() != 2 || !(part.get("image_url") instanceof Map<?, ?> image)
                || !Set.of("url", "detail").containsAll(image.keySet()) || !(image.get("url") instanceof String url)) {
            throw invalid("图片只接受 PNG、JPEG、WebP 的 data URL");
        }
        Matcher match = IMAGE.matcher(url);
        if (!match.matches()) throw invalid("图片只接受 PNG、JPEG、WebP 的 data URL");
        String data = match.group(2);
        long bytes = data.length() * 3L / 4 - (data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0);
        if (bytes > MAX_IMAGE_BYTES) throw tooLarge("单张图片不能超过 4 MiB");
        images[0] += bytes;
        if (images[0] > MAX_TOTAL_IMAGE_BYTES) throw tooLarge("一次请求的图片总大小不能超过 12 MiB");
        return ImageContent.from(data, match.group(1), detail(image.get("detail")));
    }

    private static ImageContent.DetailLevel detail(Object raw) {
        return switch (raw instanceof String text ? text : "auto") {
            case "low" -> ImageContent.DetailLevel.LOW;
            case "high" -> ImageContent.DetailLevel.HIGH;
            default -> ImageContent.DetailLevel.AUTO;
        };
    }

    private static String finishReason(FinishReason reason) {
        if (reason == null) return null;
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case TOOL_EXECUTION -> "tool_calls";
            default -> "stop";
        };
    }

    /** L5.2 网关的错误码映射回旧契约（客户端按这些码决定提示与是否改用自定义模型）。 */
    private static ApiException legacy(ApiException error) {
        return switch (error.code()) {
            case "AI_BALANCE_EXHAUSTED" -> new ApiException(HttpStatus.PAYMENT_REQUIRED, error.code(),
                    "免费 AI 额度已用完，可以输入兑换码获取额度，或改用自己的模型。");
            case "AI_REQUEST_INVALID" -> invalid(error.getMessage());
            case "AI_SITE_BUSY", "AI_UPSTREAM_BUSY" -> new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", error.getMessage());
            case "AI_UNAVAILABLE", "AI_KEY_REJECTED" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED",
                    "免费 AI 暂时不可用，请稍后重试，或改用自己的模型");
            case "AI_UPSTREAM_REJECTED" -> new ApiException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_FAILED", error.getMessage());
            default -> error;
        };
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "AI_REQUEST_UNSUPPORTED", message);
    }

    private static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "AI_REQUEST_TOO_LARGE", message);
    }
}
