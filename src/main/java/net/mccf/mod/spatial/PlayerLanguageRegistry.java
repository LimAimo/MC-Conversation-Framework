package net.mccf.mod.spatial;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个在线玩家的目标语言（自动从客户端 Minecraft 语言设置检测得来）。
 *
 * 数据来源：客户端在加入服务器时通过 {@link net.mccf.mod.network.LanguageReportPayload}
 * 主动上报自己的 {@code Options.language}（见客户端 MCCFClient 的 JOIN 事件监听）。
 * 服务端在收到上报时调用 {@link #setLanguage} 写入本表；玩家离线时清理。
 *
 * 这是一个纯内存的运行时注册表，不做持久化——语言设置本质上属于客户端状态，
 * 每次玩家上线都会重新上报一次，因此无需在服务端持久保存。
 */
public class PlayerLanguageRegistry {

	private static final Map<UUID, String> LANGUAGES = new ConcurrentHashMap<>();
	private static final String DEFAULT_LANGUAGE = "en_us";

	private PlayerLanguageRegistry() {}

	public static void setLanguage(UUID playerId, String languageCode) {
		LANGUAGES.put(playerId, languageCode);
	}

	public static String getLanguage(UUID playerId) {
		return LANGUAGES.getOrDefault(playerId, DEFAULT_LANGUAGE);
	}

	public static void remove(UUID playerId) {
		LANGUAGES.remove(playerId);
	}

	public static void clear() { LANGUAGES.clear(); }
}
