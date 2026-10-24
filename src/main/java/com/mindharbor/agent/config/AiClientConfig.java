package com.mindharbor.agent.config;

import com.mindharbor.agent.service.ai.AiClient;
import com.mindharbor.agent.service.ai.SpringAiChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 大模型客户端装配配置。
 *
 * <p>根据 application.yml 或环境变量选择本地项目模型或 OpenAI 客户端，
 * 让业务服务只依赖统一的 {@link AiClient} 接口。</p>
 */
@Configuration
public class AiClientConfig {

    public AiClient aiClient(MindHarborProperties properties) {
        return aiClient(properties, null, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @Bean
    public AiClient aiClient(MindHarborProperties properties,
            com.mindharbor.agent.service.ai.ModelCallTelemetry telemetry,
            io.micrometer.observation.ObservationRegistry observations) {
        String provider = properties.getAi().getProvider().toLowerCase();
        if ("ollama".equals(provider)) {
            OllamaChatModel model = ollamaChatModel(properties, observations);
            return new SpringAiChatClient(model, model, telemetry, provider, properties.getAi().getOllama().getModel());
        }
        if ("openai".equals(provider)) {
            if (properties.getAi().getOpenai().getApiKey().isBlank()) {
                throw new IllegalStateException("AI_PROVIDER=openai requires OPENAI_API_KEY.");
            }
            OpenAiChatModel model = openAiChatModel(properties, observations);
            return new SpringAiChatClient(model, model, telemetry, provider, properties.getAi().getOpenai().getModel());
        }
        throw new IllegalArgumentException(
                "Unsupported AI_PROVIDER=" + provider + ". Supported providers: ollama, openai.");
    }

    private OllamaChatModel ollamaChatModel(MindHarborProperties properties, io.micrometer.observation.ObservationRegistry observations) {
        MindHarborProperties.Ollama ollama = properties.getAi().getOllama();
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollama.getBaseUrl())
                .build();
        OllamaOptions options = OllamaOptions.builder()
                .model(ollama.getModel())
                .temperature(properties.getAi().getTemperature())
                .numPredict(properties.getAi().getMaxTokens())
                .topP(0.85)
                .repeatPenalty(1.12)
                .build();
        return OllamaChatModel.builder()
                .observationRegistry(observations)
                .ollamaApi(api)
                .defaultOptions(options)
                .build();
    }

    private OpenAiChatModel openAiChatModel(MindHarborProperties properties, io.micrometer.observation.ObservationRegistry observations) {
        MindHarborProperties.OpenAi openai = properties.getAi().getOpenai();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openai.getBaseUrl())
                .apiKey(openai.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .streamUsage(openai.isStreamUsage())
                .model(openai.getModel())
                .temperature(properties.getAi().getTemperature())
                .maxTokens(properties.getAi().getMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .observationRegistry(observations)
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
