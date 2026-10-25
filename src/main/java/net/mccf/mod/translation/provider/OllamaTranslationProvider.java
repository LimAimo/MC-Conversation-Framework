package net.mccf.mod.translation.provider;

import com.google.gson.JsonObject;
import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Ollama 本地/自建模型翻译 Provider。不需要 API Key（本地部署默认无鉴权），
 * 但需要可配置的 host（默认本机默认端口）和模型名。
 *
 * Endpoint: POST {host}/api/chat
 * 响应体格式：{ "message": { "role": "assistant", "content": "..." }, "done": true, ... }
 */
public class OllamaTranslationProvider implements TranslationProvider {

	public static final String ID = "ollama";

	private final ProviderConfig config;

	public OllamaTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		String model = config.model.isBlank() ? "llama3.2" : config.model;
		return "Ollama (" + model + " @ " + config.effectiveEndpoint(ID) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		String model = config.model.isBlank() ? "llama3.2" : config.model;
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/api/chat";

		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String body = buildRequestBody(model, systemPrompt, request.sourceText());

		// Ollama 本地默认无需鉴权；若管理员在自建反代前加了鉴权，可以把 token
		// 拼进 endpoint 配置里由反代自行处理，这里不强制要求 apiKey。
		Map<String, String> headers = (config.apiKey == null || config.apiKey.isBlank())
				? Map.of("Content-Type", "application/json")
				: Map.of("Content-Type", "application/json", "Authorization", "Bearer " + config.apiKey);

		return HttpProviderSupport.postJson(endpoint, body, headers)
				.thenApply(OllamaTranslationProvider::parseChatResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<java.util.List<String>> listModels() {
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/api/tags";
		Map<String, String> headers = (config.apiKey == null || config.apiKey.isBlank())
				? Map.of()
				: Map.of("Authorization", "Bearer " + config.apiKey);
		return HttpProviderSupport.getJson(endpoint, headers)
				.thenApply(OllamaTranslationProvider::parseTagsResponse);
	}

	/** Ollama 模型列表响应：{"models":[{"name":"llama3.2",...}]}。 */
	private static java.util.List<String> parseTagsResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		com.google.gson.JsonArray models = root.getAsJsonArray("models");
		if (models == null) {
			throw new RuntimeException("No models array in Ollama response");
		}
		java.util.List<String> names = new java.util.ArrayList<>();
		for (var element : models) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.has("name")) {
				names.add(obj.get("name").getAsString());
			}
		}
		names.sort(String::compareTo);
		return names;
	}

	private static String buildRequestBody(String model, String systemPrompt, String userText) {
		return "{"
				+ "\"model\":\"" + HttpProviderSupport.escapeJson(model) + "\","
				+ "\"messages\":["
				+ "{\"role\":\"system\",\"content\":\"" + HttpProviderSupport.escapeJson(systemPrompt) + "\"},"
				+ "{\"role\":\"user\",\"content\":\"" + HttpProviderSupport.escapeJson(userText) + "\"}"
				+ "],"
				+ "\"stream\":false"
				+ "}";
	}

	private static String parseChatResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonObject message = root.getAsJsonObject("message");
		if (message == null || !message.has("content")) {
			throw new RuntimeException("No message.content in Ollama response");
		}
		return message.get("content").getAsString().trim();
	}
}
