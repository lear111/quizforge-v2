package cloud.treenewbee.quizforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cloud.treenewbee.quizforge.ai.application.AiGatewaySettings;
import cloud.treenewbee.quizforge.ai.application.AiModelClient;
import cloud.treenewbee.quizforge.ai.application.UpstreamKeyService.UpstreamKey;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * 手动冒烟：用作者本机环境变量中的真实 Key 调用一次上游（同步一次、流式一次），只访问模型服务商的 API。
 * 不进入 CI（CI 没有 QF_AI_SMOKE_KEY，测试被跳过），不连数据库、不扣费。
 *
 * <pre>
 * QF_AI_SMOKE_KEY=sk-… [QF_AI_BASE_URL=…] [QF_AI_MODEL=…] mvn -f backend/pom.xml test -Dtest=AiUpstreamSmokeTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "QF_AI_SMOKE_KEY", matches = ".+")
class AiUpstreamSmokeTest {

    @Test
    void callsTheRealUpstreamOnce() throws Exception {
        String baseUrl = System.getenv().getOrDefault("QF_AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = System.getenv().getOrDefault("QF_AI_MODEL", "qwen-vl-plus");
        AiGatewaySettings settings = new AiGatewaySettings(baseUrl, model, Duration.ofSeconds(40), Duration.ofSeconds(120),
                64, 2, 2, Duration.ofSeconds(150));
        AiModelClient client = new AiModelClient(settings);
        UpstreamKey key = new UpstreamKey("smoke", 0, System.getenv("QF_AI_SMOKE_KEY"), 30_000, 90_000);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("请只回复 OK")).modelName(model)
                .maxOutputTokens(16).build();

        ChatResponse sync = client.chat(key, request);
        System.out.println("同步：" + sync.aiMessage().text() + "，用量 " + sync.tokenUsage());
        assertThat(sync.tokenUsage()).isNotNull();

        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        StringBuilder text = new StringBuilder();
        client.stream(key, request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                text.append(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        ChatResponse streamed = done.get(60, TimeUnit.SECONDS);
        System.out.println("流式：" + text + "，用量 " + streamed.tokenUsage());
        assertThat(streamed.tokenUsage()).as("上游应在流式结束时返回用量（stream_options.include_usage）").isNotNull();
    }
}
