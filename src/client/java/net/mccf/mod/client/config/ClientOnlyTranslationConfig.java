package net.mccf.mod.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.chat.ClientOnlyChatTranslator;
import net.mccf.mod.config.ProviderConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纯客户端模式下，玩家自己维护的一份翻译 Provider 配置——目标语言就是玩家
 * 自己客户端设置的语言（沿用 {@code MCCFClient.detectClientLanguage}），
 * 不需要像服务端配置那样区分"谁能编辑"：这份配置只影响玩家自己本地看到的
 * 翻译结果，不会分发给任何人，所以任何玩家都可以自由编辑，不做权限校验。
 *
 * 存储在本地文件 {@code config/mccf/client-only-config.json}，与服务端权威
 * 配置 {@code config/mccf/config.json} 是两份完全独立的文件——即使玩家同时
 * 连接一个装了 MCCF 的服务器，这份本地配置也不会被服务端配置覆盖，二者
 * 只在玩家主动点击"从服务器同步"时才会有一次性的单向拷贝（见
 * {@link #copyPublicFieldsFrom(ClientConfigState)}）。
 *
 * Provider 配置项复用 {@link ClientProviderConfig}（与服务端 apiKey/model/
 * endpoint 字段一一对应），避免再定义一套重复的结构。
 */
public class ClientOnlyTranslationConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(MCCF.MOD_ID);
	private static final Path CONFIG_FILE = CONFIG_DIR.resolve("client-only-config.json");

	public String activeProvider = "mock";
	public Map<String, ClientProviderConfig> providers = new LinkedHashMap<>();

	/**
	 * 是否在物品栏上方字幕（AUDIBLE 模式）中同时显示原文和译文——客户端个人偏好，
	 * 每个玩家独立决定，不受服务器/op 限制。1.1.1 起从服务端配置迁移到客户端。
	 * 默认开启：AUDIBLE 字幕只显示译文时玩家无法对照原文，开启后能帮助理解翻译质量。
	 */
	public boolean showOriginalText = true;

	/**
	 * 是否在聊天栏（VISIBLE 模式）中同时显示原文和译文——客户端个人偏好。
	 * 与 {@link #showOriginalText} 分开：两个字段各自独立控制一种展示场景，
	 * 玩家可能只想让聊天栏更详细、不想让物品栏字幕变长，反之亦然。
	 * 默认关闭：VISIBLE 消息默认只显示译文，与原版聊天体验一致。
	 */
	public boolean showOriginalTextInChat = false;

	private static ClientOnlyTranslationConfig instance;

	public static ClientOnlyTranslationConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public ClientProviderConfig getOrCreate(String providerId) {
		return providers.computeIfAbsent(providerId, id -> new ClientProviderConfig());
	}

	/** 转换成 {@link ProviderFactory} 需要的服务端配置对象，方便直接复用现有 Provider 实现类。 */
	public ProviderConfig toProviderConfig(String providerId) {
		return getOrCreate(providerId).toProviderConfig();
	}

	/**
	 * 从当前已连接服务器下发的配置快照里，拷贝 Provider 选择 / 模型名 / Endpoint
	 * 到本地配置，方便玩家在装了 MCCF 的服务器上快速让本地翻译和服务器用的
	 * 是同一个 Provider/模型，而不用重新手动选一遍。
	 *
	 * 有意不拷贝 apiKey：即使当前玩家是 op、快照里带着真实 Key，也不应该被
	 * 静默复制到本地文件里——本地 Key 应该始终是玩家自己主动填写的。
	 */
	public void copyPublicFieldsFrom(ClientConfigState serverState) {
		this.activeProvider = serverState.activeProvider;
		for (Map.Entry<String, ClientProviderConfig> entry : serverState.providers.entrySet()) {
			ClientProviderConfig source = entry.getValue();
			ClientProviderConfig local = getOrCreate(entry.getKey());
			local.model = source.model;
			local.endpoint = source.endpoint;
			local.isCustomEndpoint = source.isCustomEndpoint;
			local.disableThinking = source.disableThinking;
			// apiKey 故意不拷贝，见方法说明。
		}
	}

	private static ClientOnlyTranslationConfig load() {
		try {
			if (Files.exists(CONFIG_FILE)) {
				try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
					ClientOnlyTranslationConfig loaded = GSON.fromJson(reader, ClientOnlyTranslationConfig.class);
					if (loaded != null) {
						if (loaded.providers == null) {
							loaded.providers = new LinkedHashMap<>();
						}
						return loaded;
					}
				}
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to read client-only translation config, falling back to defaults.", e);
		}
		return new ClientOnlyTranslationConfig();
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_DIR);
			try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
				GSON.toJson(this, writer);
			}
			// 1.1.2 修复"改 API Key 后翻译仍用旧 Key"：保存后失效 ClientOnlyChatTranslator
			// 的 Provider 缓存，下一次翻译请求会基于最新配置重建 Provider。
			// 必须在文件成功写入之后再失效——如果写入失败缓存失效反而会让玩家以为
			// "改了 Key 但没保存"（实际是没保存成功 + 缓存清了，状态更乱）。
			ClientOnlyChatTranslator.invalidateProviderCache();
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to save client-only translation config.", e);
		}
	}
}
