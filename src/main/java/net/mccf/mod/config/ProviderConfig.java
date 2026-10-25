package net.mccf.mod.config;

/**
 * 只管保存单个 Provider 的配置值；协议参数与能力适配由对应 TranslationProvider 实现负责。
 *
 * endpoint 字段对所有 Provider（除 Mock 外）都有意义，不再是 Ollama 专属：
 * 每个 Provider 都有一个默认官方地址（见 {@link ProviderDefaults}），
 * 用户可以在配置界面里改成自建反代 / 兼容网关的地址，也可以一键恢复默认。
 *
 * 不是所有字段对所有 Provider 都同样关键——例如 DeepL 不需要 model，
 * Ollama 不需要 apiKey——各 Provider 的实现类只读取自己关心的字段。
 */
public class ProviderConfig {

	/** API Key / Auth Token。Ollama 本地部署通常不需要，可留空。 */
	public String apiKey = "";

	/** 模型名称，例如 "gpt-4o-mini"、"claude-sonnet-4-6"、"gemini-3.5-flash"、"deepseek-v4-flash"。 */
	public String model = "";

	/**
	 * API 基础地址。默认值见 {@link ProviderDefaults}，用户可修改为自建
	 * 反代/兼容网关地址；留空或点击"恢复默认"时使用官方默认地址。
	 */
	public String endpoint = "";

	/**
	 * 是否强制关闭该 Provider 的"思考"/"推理"模式（如果它支持的话）。
	 *
	 * 默认关闭（false）——不强制干预 Provider 的默认行为。每个 Provider
	 * 各自独立一份这个开关（不是全局一个），应用户明确要求"只想关 DeepSeek
	 * 的思考，不想关 Kimi 的"这类精细控制。
	 *
	 * 只对确认支持这个能力的 Provider 生效：DeepSeek、Kimi、Claude、Gemini、
	 * 智谱（Zhipu）——具体每家用什么参数关闭、参数是否对所有模型代次都有效，
	 * 见各自 TranslationProvider 实现类的注释。DeepL/Mock 不提供此开关；
	 * Ollama 本身支持 think，但本项目尚未为它接入此开关，所以界面不展示。
	 */
	public boolean disableThinking = false;

	public ProviderConfig() {}

	public ProviderConfig(String apiKey, String model) {
		this.apiKey = apiKey;
		this.model = model;
	}

	public ProviderConfig(String apiKey, String model, String endpoint) {
		this.apiKey = apiKey;
		this.model = model;
		this.endpoint = endpoint;
	}

	/** 若 endpoint 为空，返回该 Provider 的官方默认地址；否则返回用户自定义值。 */
	public String effectiveEndpoint(String providerId) {
		return (endpoint == null || endpoint.isBlank())
				? ProviderDefaults.get(providerId).endpoint()
				: endpoint.trim();
	}
}
