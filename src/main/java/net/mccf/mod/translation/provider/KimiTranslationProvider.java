package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Kimi (Moonshot AI) 翻译 Provider。
 *
 * 使用 OpenAI Chat Completions 格式，并按 Kimi 的模型约束省略固定采样参数。
 *
 * 默认模型 kimi-k2.6 支持关闭思考。K2.5 已于 2026-08-31 下线。
 * Kimi 当前模型的采样温度由模型/思考模式固定，因此请求中省略 temperature。
 * K2.7/K3 系列无法关闭思考，不向这些模型发送 disabled thinking 参数。
 * 约束来源：https://platform.kimi.ai/docs/api/models-overview
 *
 * Endpoint: POST https://api.moonshot.ai/v1/chat/completions
 * Auth: Authorization: Bearer <key>
 */
public class KimiTranslationProvider implements TranslationProvider {

	public static final String ID = "kimi";

	private final ProviderConfig config;

	public KimiTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Kimi / Moonshot AI (" + effectiveModel() + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Kimi API key not configured"));
		}

		String model = effectiveModel();
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/chat/completions";
		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		boolean disableThinking = config.disableThinking && model.equals("kimi-k2.6");
		String body = ChatCompletionsSupport.buildRequestBodyWithDefaultSampling(
				model, systemPrompt, request.sourceText(), disableThinking);

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"Authorization", "Bearer " + config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(ChatCompletionsSupport::parseChatCompletionResponse)
				.thenApply(TranslationResult::new);
	}

	private String effectiveModel() {
		return config.model == null || config.model.isBlank() ? "kimi-k2.6" : config.model;
	}

	@Override
	public CompletableFuture<List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Kimi API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(ChatCompletionsSupport::parseModelListResponse);
	}
}
