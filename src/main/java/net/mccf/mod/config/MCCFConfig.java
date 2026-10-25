package net.mccf.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只管服务端配置的默认值、校验和读写，不管权限与网络同步（由 ConfigSyncHandler 处理）。
 * 管理员可修改 config/mccf/config.json 来调整行为，
 * 也可以通过游戏内配置界面（ModMenu 集成 / 按键呼出）修改，二者共用同一份文件。
 *
 * 所有距离数值都以"格"（block）为单位，与 Minecraft 原版距离单位一致。
 */
public class MCCFConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(MCCF.MOD_ID).resolve("config.json");
	}

	/**
	 * "看得见说话者"的距离阈值，单位：格。在此距离内且视线未被方块阻挡的听众归为
	 * VISIBLE 档（消息走原版聊天栏）；超出此距离但在 hearingRange 内、或视线被阻挡
	 * 的听众归为 AUDIBLE 档（消息走物品栏上方字幕）。
	 *
	 * 字段名沿用历史命名（早期版本 VISIBLE 档的字幕确实悬浮在说话者头顶，由
	 * WorldSubtitleRenderer 渲染）。0.16.0 起 WorldSubtitleRenderer 已删除、VISIBLE
	 * 改走聊天栏，但这个阈值本身承担的是 HearingResolver 的距离判定职责，与展示
	 * 载体无关，故保留不改名——改名会破坏旧 config.json 的向后兼容。
	 */
	public double subtitleVisibleRange = 32.0;

	/** 玩家仍可"听到"但看不到说话者的最大距离，单位：格。超出此距离完全收不到消息。 */
	public double hearingRange = 48.0;

	/**
	 * 加入 / 保留在 Conversation 中所需的最大距离。
	 * 不超过 hearingRange；更远但仍可听的玩家仍收到消息，只不因这次发言加入该组。
	 */
	public double conversationRange = 48.0;

	/** 一个 Conversation 在无人发言后，闲置多少秒后自动释放（避免过期上下文残留）。 */
	public int conversationIdleTimeoutSeconds = 120;

	/** 射线遮挡检测：是否启用（关闭后仅使用距离判定，性能更好但不考虑墙体）。 */
	public boolean enableOcclusionCheck = true;

	/** 当前启用的翻译 Provider ID，对应某个已注册 TranslationProvider 的 ID。 */
	public String activeProvider = "mock";

	/**
	 * 每个 Provider 的独立配置（API Key / 模型名 / endpoint），key 为 Provider ID。
	 * 即使某个 Provider 当前未激活，它的配置也会保留在这里，方便随时切换。
	 */
	public Map<String, ProviderConfig> providers = defaultProviderConfigs();

	private static Map<String, ProviderConfig> defaultProviderConfigs() {
		Map<String, ProviderConfig> map = new LinkedHashMap<>();
		for (String id : ProviderDefaults.all().keySet()) {
			ProviderDefaults.Defaults d = ProviderDefaults.get(id);
			// 空值保留“自动选择”的含义，尤其 DeepL 要按 Key 类型决定免费/付费端点。
			map.put(id, new ProviderConfig("", d.model(), ""));
		}
		return map;
	}

	/** 取某个 Provider 的配置，若不存在（例如旧配置文件缺字段）则返回一份默认值并写回。 */
	public ProviderConfig getProviderConfig(String providerId) {
		return providers.computeIfAbsent(providerId, id -> defaultProviderConfigs().getOrDefault(id, new ProviderConfig()));
	}

	public static MCCFConfig loadOrCreate() {
		Path configPath = configFile();
		try {
			if (Files.exists(configPath)) {
				try (Reader reader = Files.newBufferedReader(configPath)) {
					MCCFConfig loaded = GSON.fromJson(reader, MCCFConfig.class);
					if (loaded != null) {
						if (loaded.providers == null) {
							loaded.providers = defaultProviderConfigs();
						} else {
							// 补齐新增 Provider 的默认配置（比如从旧版本升级上来，缺少 kimi/deepseek 字段）。
							defaultProviderConfigs().forEach(loaded.providers::putIfAbsent);
						}
						loaded.validateAndClamp();
						MCCF.LOGGER.info("[MCCF] Loaded config from {}", configPath);
						return loaded;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			MCCF.LOGGER.error("[MCCF] Config could not be read ({}); retaining the original file and using defaults in memory.",
					e.getClass().getSimpleName());
		}

		MCCFConfig defaults = new MCCFConfig();
		defaults.validateAndClamp();
		// 损坏文件可能仍含可恢复的 Key，不用默认值覆盖它；仅首次缺文件时自动创建。
		if (!Files.exists(configPath)) defaults.save();
		return defaults;
	}

	/**
	 * 校验并修正 conversationRange 与 hearingRange 的关系。
	 *
	 * 为什么 clamp 而不是抛异常：配置文件可能被管理员手动改错，抛异常会导致
	 * 服务器启动失败（loadOrCreate 的调用方没有 try-catch 的预期，异常会
	 * 一路抛到 ModInitializer.onInitialize 导致整个模组加载失败），clamp
	 * 到合法值更友好——服务器能正常启动，日志里会有警告提醒管理员修正。
	 */
	void validateAndClamp() {
		if (!Double.isFinite(hearingRange) || hearingRange < 0) hearingRange = 48.0;
		if (!Double.isFinite(subtitleVisibleRange) || subtitleVisibleRange < 0) subtitleVisibleRange = 32.0;
		if (!Double.isFinite(conversationRange) || conversationRange < 0) conversationRange = 48.0;
		subtitleVisibleRange = Math.min(subtitleVisibleRange, hearingRange);
		if (conversationIdleTimeoutSeconds <= 0) conversationIdleTimeoutSeconds = 120;
		if (activeProvider == null || !ProviderDefaults.all().containsKey(activeProvider)) activeProvider = "mock";
		if (providers == null) providers = defaultProviderConfigs();
		providers.entrySet().removeIf(entry -> entry.getValue() == null);
		defaultProviderConfigs().forEach(providers::putIfAbsent);
		providers.forEach((id, provider) -> {
			if (provider.apiKey == null) provider.apiKey = "";
			if (provider.model == null) provider.model = ProviderDefaults.get(id).model();
			if (provider.endpoint == null) provider.endpoint = "";
			if ("kimi".equals(id) && "kimi-k2.5".equals(provider.model)
					&& (provider.endpoint.isBlank() || provider.endpoint.equals("https://api.moonshot.ai")
					|| provider.endpoint.equals("https://api.moonshot.cn"))) {
				// 只迁移官方已停用的默认模型，反代提供的同名私有模型不擅自替换。
				provider.model = ProviderDefaults.get("kimi").model();
			}
		});
		if (conversationRange > hearingRange) {
			MCCF.LOGGER.warn("[MCCF] conversationRange ({}) > hearingRange ({}), clamping conversationRange to hearingRange. " +
					"The conversation grouping range cannot exceed the hearing range.",
					conversationRange, hearingRange);
			conversationRange = hearingRange;
		}
	}

	public void save() {
		Path configPath = configFile();
		try {
			Files.createDirectories(configPath.getParent());
			try (Writer writer = Files.newBufferedWriter(configPath)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to save config.", e);
		}
	}
}
