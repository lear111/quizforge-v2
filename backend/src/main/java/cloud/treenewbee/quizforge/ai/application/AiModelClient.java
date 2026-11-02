package cloud.treenewbee.quizforge.ai.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * LangChain4j 的底层 OpenAI 兼容客户端（OpenAiChatModel / OpenAiStreamingChatModel），每个 Key 一组，复用 HTTP 连接。
 * 不重试（换 Key 由 AiGateway 决定）、不记录请求与响应正文（日志里不能出现 Key、题目与图片）。
 * 流式请求由 LangChain4j 固定附带 stream_options.include_usage = true，最后一个事件带用量。
 */
@Component
public class AiModelClient {

    private record Models(String apiKey, OpenAiChatModel sync, OpenAiStreamingChatModel streaming) {
    }

    private final AiGatewaySettings settings;
    private final Map<String, Models> models = new ConcurrentHashMap<>();

    public AiModelClient(AiGatewaySettings settings) {
        this.settings = settings;
    }

    public ChatResponse chat(UpstreamKeyService.UpstreamKey key, ChatRequest request) {
        return models(key).sync().chat(request);
    }

    public void stream(UpstreamKeyService.UpstreamKey key, ChatRequest request, StreamingChatResponseHandler handler) {
        models(key).streaming().chat(request, handler);
    }

    private Models models(UpstreamKeyService.UpstreamKey key) {
        // Key 的内容不能修改（只能删除后重新添加），按 ID 缓存；Key 不同时重新创建。
        return models.compute(key.id(), (id, current) -> current != null && current.apiKey().equals(key.apiKey()) ? current
                : new Models(key.apiKey(),
                        OpenAiChatModel.builder().baseUrl(settings.baseUrl()).apiKey(key.apiKey()).modelName(settings.model())
                                .timeout(settings.callTimeout()).maxRetries(0).logRequests(false).logResponses(false).build(),
                        OpenAiStreamingChatModel.builder().baseUrl(settings.baseUrl()).apiKey(key.apiKey())
                                .modelName(settings.model()).timeout(settings.callTimeout())
                                .logRequests(false).logResponses(false).build()));
    }
}
