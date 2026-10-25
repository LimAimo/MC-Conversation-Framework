package net.mccf.mod.spatial;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端记录哪些在线玩家选择了"纯客户端模式"——即客户端通过
 * {@link net.mccf.mod.network.ModePreferencePayload} 明确告诉服务端
 * "我只要本地翻译，不要使用服务器 Provider"；服务器仍按空间规则发送原文包。
 *
 * 职责边界：这里只存翻译来源偏好，是否分发消息由 SpatialChatHandler 的服务端
 * 空间规则决定。client-only 不能改变其他玩家的可见范围，也不能让原版全服广播恢复。
 *
 * 为什么用 Set 而不是 Map：和 {@link PlayerLanguageRegistry} 不同，这里只需要
 * 回答"这个玩家是不是 client-only"这一个布尔事实，不需要额外附带任何值
 * （没有偏好强度、没有模式子类型——client-only 是个二元状态）。Set 的语义
 * 恰好就是"成员资格判定"，比 Map<UUID, Boolean> 更直白也不容易误用成
 * "存别的什么东西"。用 {@link ConcurrentHashMap#newKeySet()} 拿到的是
 * 线程安全的 Set 视图，读写在多线程（网络线程 + 主线程）下都安全。
 *
 * 和 PlayerLanguageRegistry 一样是纯内存注册表，不做持久化：玩家每次上线
 * 都会通过 ModePreferencePayload 重新上报一次当前偏好，所以离线后丢弃即可。
 */
public final class ClientOnlyModeRegistry {

	private static final Set<UUID> CLIENT_ONLY_PLAYERS = ConcurrentHashMap.newKeySet();

	private ClientOnlyModeRegistry() {}

	/**
	 * 设置某个玩家的 client-only 偏好。clientOnly=true 时加入集合，
	 * false 时移除——同一玩家多次上报以最后一次为准。
	 */
	public static void setClientOnly(UUID playerId, boolean clientOnly) {
		if (clientOnly) {
			CLIENT_ONLY_PLAYERS.add(playerId);
		} else {
			CLIENT_ONLY_PLAYERS.remove(playerId);
		}
	}

	public static boolean isClientOnly(UUID playerId) {
		return CLIENT_ONLY_PLAYERS.contains(playerId);
	}

	/** 玩家离线时调用，避免过期偏好残留到下次该玩家上线前的真空期。 */
	public static void remove(UUID playerId) {
		CLIENT_ONLY_PLAYERS.remove(playerId);
	}

	public static void clear() { CLIENT_ONLY_PLAYERS.clear(); }
}
