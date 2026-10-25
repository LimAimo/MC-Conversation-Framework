package net.mccf.mod.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个 Provider 的默认值（endpoint / 模型名）集中定义在这里，是"恢复默认"
 * 功能和初次创建配置时的唯一数据源，避免默认值散落在各个 Provider 实现类
 * 里、容易改一处忘一处。
 *
 * endpoint 字段的含义因 Provider 而异：
 * - OpenAI/Claude/Gemini/DeepL/Kimi/DeepSeek：API 基础地址，用户可改成
 *   自建反代/兼容网关的地址（例如企业内部的 OpenAI 兼容网关）。
 * - Ollama：本地/远程 Ollama 服务地址。
 * - Mock：不使用，留空。
 */
public final class ProviderDefaults {

	public record Defaults(String endpoint, String model) {}

	private static final Map<String, Defaults> DEFAULTS = new LinkedHashMap<>();

	static {
		DEFAULTS.put("mock", new Defaults("", ""));
		DEFAULTS.put("openai", new Defaults("https://api.openai.com", "gpt-4o-mini"));
		DEFAULTS.put("claude", new Defaults("https://api.anthropic.com", "claude-sonnet-4-6"));
		DEFAULTS.put("gemini", new Defaults("https://generativelanguage.googleapis.com", "gemini-3.5-flash"));
		DEFAULTS.put("deepl", new Defaults("https://api-free.deepl.com", ""));
		DEFAULTS.put("kimi", new Defaults("https://api.moonshot.ai", "kimi-k2.6"));
		DEFAULTS.put("deepseek", new Defaults("https://api.deepseek.com", "deepseek-v4-flash"));
		DEFAULTS.put("zhipu", new Defaults("https://open.bigmodel.cn/api/paas/v4", "glm-5.2"));
		DEFAULTS.put("ollama", new Defaults("http://localhost:11434", "llama3.2"));
	}

	private ProviderDefaults() {}

	public static Defaults get(String providerId) {
		return DEFAULTS.getOrDefault(providerId, new Defaults("", ""));
	}

	public static Map<String, Defaults> all() {
		return DEFAULTS;
	}
}
