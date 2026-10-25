package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 只管 Gemini generateContent 的请求、响应及模型列表；听众和上下文边界由上层决定。
 *
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * Auth: x-goog-api-key: <key>
 *
 * 本实现沿用将翻译指令与原文放在同一个 user part 的格式。
 * 思考控制按模型区分：2.5 Flash 可用 budget=0，2.5 Pro 只能降至最小预算128；
 * 已知3系列文本模型和4 Argon使用 thinkingLevel=low，不能保证完全关闭思考。
 * 未识别模型不猜测参数，保留服务端默认值。
 * 依据：https://ai.google.dev/gemini-api/docs/generate-content/thinking
 */
public class GeminiTranslationProvider implements TranslationProvider {

	public static final String ID = "gemini";
	private static final List<String> LOW_THINKING_MODELS = List.of(
			"gemini-3-flash", "gemini-3.1-flash-lite", "gemini-3.5-flash",
			"gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-4-argon");

	private final ProviderConfig config;

	public GeminiTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Gemini (" + (config.model.isBlank() ? "gemini-3.5-flash" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key not configured"));
		}

		String model = config.model.isBlank() ? "gemini-3.5-flash" : config.model;
		String base = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID));
		String endpoint = base + "/v1beta/models/" + model + ":generateContent";

		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String combinedPrompt = systemPrompt + "\n\nText to translate:\n" + request.sourceText();
		String body = buildRequestBody(model, combinedPrompt, config.disableThinking);

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"x-goog-api-key", config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(GeminiTranslationProvider::parseGenerateContentResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<java.util.List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key not configured"));
		}
		String base = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID));
		String endpoint = base + "/v1beta/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("x-goog-api-key", config.apiKey))
				.thenApply(GeminiTranslationProvider::parseModelListResponse);
	}

	/** Gemini 模型列表响应：{"models":[{"name":"models/gemini-2.5-pro",...}]}，name 需去掉 "models/" 前缀。 */
	private static java.util.List<String> parseModelListResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonArray models = root.getAsJsonArray("models");
		if (models == null) {
			throw new RuntimeException("No models array in Gemini response");
		}
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (var element : models) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.has("name")) {
				String name = obj.get("name").getAsString();
				ids.add(name.startsWith("models/") ? name.substring("models/".length()) : name);
			}
		}
		ids.sort(String::compareTo);
		return ids;
	}

	static String buildRequestBody(String model, String promptText, boolean disableThinking) {
		String thinkingConfig = "";
		if (disableThinking) {
			if (model.startsWith("gemini-2.5-flash")) {
				thinkingConfig = "\"thinkingBudget\":0";
			} else if (model.equals("gemini-2.5-pro") || model.startsWith("gemini-2.5-pro-")) {
				thinkingConfig = "\"thinkingBudget\":128";
			} else if (model.startsWith("gemini-3.1-flash-lite-image")) {
				// This variant accepts minimal/high but rejects low; never send a blanket level.
				thinkingConfig = "\"thinkingLevel\":\"minimal\"";
			} else if (!model.contains("-image") && LOW_THINKING_MODELS.stream()
					.anyMatch(base -> model.equals(base) || model.startsWith(base + "-"))) {
				// Low works across the documented text variants, whereas minimal rejects 3.8 Flash.
				thinkingConfig = "\"thinkingLevel\":\"low\"";
			}
		}
		String thinkingField = thinkingConfig.isEmpty() ? ""
				: ",\"generationConfig\":{\"thinkingConfig\":{" + thinkingConfig + "}}";
		return "{"
				+ "\"contents\":[{\"parts\":[{\"text\":\"" + HttpProviderSupport.escapeJson(promptText) + "\"}]}]"
				+ thinkingField
				+ "}";
	}

	/** 解析响应：candidates[0].content.parts[0].text */
	private static String parseGenerateContentResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonArray candidates = root.getAsJsonArray("candidates");
		if (candidates == null || candidates.isEmpty()) {
			throw new RuntimeException("No candidates in Gemini response");
		}
		JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
		JsonArray parts = content.getAsJsonArray("parts");
		if (parts == null || parts.isEmpty()) {
			throw new RuntimeException("No parts in Gemini response");
		}
		return parts.get(0).getAsJsonObject().get("text").getAsString().trim();
	}
}
