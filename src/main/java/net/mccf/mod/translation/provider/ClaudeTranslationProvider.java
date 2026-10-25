package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 只管 Claude Messages API 的请求、响应及模型列表；上下文可见性由上层决定。
 *
 * Endpoint: POST https://api.anthropic.com/v1/messages
 * Auth: x-api-key: <key>（注意不是 Authorization: Bearer）
 * 必须带 anthropic-version 请求头，且 max_tokens 是必填字段。
 *
 * 默认 Sonnet4.6 以及 Opus4.7/4.8 接受 thinking.type=disabled；不能把
 * 较新模型对 enabled+budget_tokens 的限制误认为对 disabled 的限制。
 * 其他型号的能力应以 Models API 的 capabilities.thinking.types.disabled.supported
 * 为准，本实现尚未自动探测；不能保证所有未来型号都接受同一参数。
 * 依据：https://platform.claude.com/docs/en/build-with-claude/thinking
 */
public class ClaudeTranslationProvider implements TranslationProvider {

	public static final String ID = "claude";
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final ProviderConfig config;

	public ClaudeTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Claude (" + (config.model.isBlank() ? "claude-sonnet-4-6" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Claude API key not configured"));
		}

		String model = config.model.isBlank() ? "claude-sonnet-4-6" : config.model;
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/messages";
		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String body = buildRequestBody(model, systemPrompt, request.sourceText(), config.disableThinking);

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"x-api-key", config.apiKey,
				"anthropic-version", ANTHROPIC_VERSION,
				"content-type", "application/json"
		)).thenApply(ClaudeTranslationProvider::parseMessagesResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<java.util.List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Claude API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/models";
		return HttpProviderSupport.getJson(endpoint, Map.of(
				"x-api-key", config.apiKey,
				"anthropic-version", ANTHROPIC_VERSION
		)).thenApply(ChatCompletionsSupport::parseModelListResponse);
	}

	static String buildRequestBody(String model, String systemPrompt, String userText, boolean disableThinking) {
		String thinkingField = disableThinking ? ",\"thinking\":{\"type\":\"disabled\"}" : "";
		return "{"
				+ "\"model\":\"" + HttpProviderSupport.escapeJson(model) + "\","
				+ "\"max_tokens\":1024,"
				+ "\"system\":\"" + HttpProviderSupport.escapeJson(systemPrompt) + "\","
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"" + HttpProviderSupport.escapeJson(userText) + "\"}]"
				+ thinkingField
				+ "}";
	}

	/** 解析 Messages API 响应：content 是一个数组，取其中 type=="text" 的第一个块。 */
	private static String parseMessagesResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonArray content = root.getAsJsonArray("content");
		if (content == null || content.isEmpty()) {
			throw new RuntimeException("No content in Claude response");
		}
		for (var element : content) {
			JsonObject block = element.getAsJsonObject();
			if (block.has("type") && "text".equals(block.get("type").getAsString())) {
				return block.get("text").getAsString().trim();
			}
		}
		throw new RuntimeException("No text block in Claude response");
	}
}
