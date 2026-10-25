package net.mccf.mod.client.mode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;
import net.mccf.mod.network.ModePreferencePayload;
import net.mccf.mod.network.RequestConfigPayload;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 判断"当前是否处于纯客户端模式"（服务器没有安装 MCCF，或玩家手动强制切换）。
 *
 * 纯客户端模式下，MCCF 不再依赖任何服务端组件：不做空间化听觉判定、不做
 * 点对点分发、不渲染悬浮/物品栏字幕——只是把收到的聊天消息（不管是谁发的、
 * 不管距离多远，因为纯客户端模式下完全看不到这些服务端才有的信息）在本地
 * 翻译成玩家自己的语言，追加显示在聊天栏里。翻译用的 Provider 配置是玩家
 * 本地自己维护的一份（见 {@link net.mccf.mod.client.config.ClientOnlyTranslationConfig}），
 * 不经过任何服务端权限校验。
 *
 * <p>检测方式：{@link ClientPlayNetworking#canSend(net.minecraft.network.packet.CustomPayload.Id)}
 * 反映的是"当前连接的服务器是否声明了可以接收某个自定义包"，这个声明只有在
 * 服务端 MCCF 通过 {@code PayloadTypeRegistry}/{@code ServerPlayNetworking.registerGlobalReceiver}
 * 注册了对应通道时才会存在。用它来判断"服务器有没有装 MCCF"是可靠的——不需要
 * 额外发一个"探测"包等服务端回应，Fabric 在登录阶段的通道协商（vanilla 的
 * "已知通道"机制）已经把这个信息带过来了。
 *
 * <p>玩家也可以在配置界面里手动强制切换模式（见 {@link Override}），
 * 优先级高于自动检测——例如玩家想在装了 MCCF 的服务器上也只用本地翻译，
 * 不依赖服务端的空间化分发。
 */
public final class ClientOnlyModeManager {

	public enum Override {
		/** 跟随自动检测结果（默认）。 */
		AUTO,
		/** 无论服务器是否装了 MCCF，都强制使用纯客户端模式。 */
		FORCE_CLIENT_ONLY,
		/** 强制视为"服务器已装 MCCF"，走完整的服务端空间化流程（前提是服务器确实装了，否则功能不会生效）。 */
		FORCE_SERVER_MODE
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(MCCF.MOD_ID);
	private static final Path STATE_FILE = CONFIG_DIR.resolve("client-mode.json");

	/** 纯粹用于 Gson 序列化的内部数据结构。 */
	private static final class PersistedState {
		String override = Override.AUTO.name();
		/**
		 * "首次加入提示"是否已经发过。1.1.2 起从内存中的 static boolean 改为
		 * 持久化字段，避免老玩家每次重启客户端都被同一条提示刷屏。
		 *
		 * 字段名 tippedFirstJoin 而不是 tipped 是为了语义清晰——未来若有别的
		 * 一次性提示也能用同样的命名模式（tippedXxx）扩展，不会和通用的 tipped
		 * 标志冲突。Gson 反序列化旧文件时该字段缺失会被默认为 false，等价于
		 * "还没提示过"，老玩家升级到 1.1.2 后仍会收到**一次**提示——这是有意为之，
		 * 让升级用户知道有这个改进存在；之后再也不会重复提示。
		 */
		boolean tippedFirstJoin = false;
	}

	private static Override override = Override.AUTO;
	private static boolean tippedFirstJoin = false;

	/** 本次连接里自动检测到的结果：服务器是否声明了 MCCF 的网络通道。未连接任何服务器时为 false。 */
	private static boolean serverHasMod = false;

	private ClientOnlyModeManager() {}

	/** 客户端启动时调用一次，读取玩家上次保存的手动覆盖设置。 */
	public static void load() {
		try {
			if (Files.exists(STATE_FILE)) {
				try (Reader reader = Files.newBufferedReader(STATE_FILE)) {
					PersistedState state = GSON.fromJson(reader, PersistedState.class);
					if (state != null && state.override != null) {
						try {
							override = Override.valueOf(state.override);
						} catch (IllegalArgumentException ignored) {
							override = Override.AUTO;
						}
					}
					// 1.1.2 起持久化 tippedFirstJoin，避免老玩家重启客户端被重复提示。
					// 旧文件没有这个字段时 Gson 默认为 false，老玩家升级后会再提示一次
					// （有意为之，让升级用户感知到改进），之后不会再重复。
					if (state != null) {
						tippedFirstJoin = state.tippedFirstJoin;
					}
				}
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to read client-only mode state, defaulting to AUTO.", e);
		}
	}

	private static void save() {
		try {
			Files.createDirectories(CONFIG_DIR);
			PersistedState state = new PersistedState();
			state.override = override.name();
			state.tippedFirstJoin = tippedFirstJoin;
			try (Writer writer = Files.newBufferedWriter(STATE_FILE)) {
				GSON.toJson(state, writer);
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to save client-only mode state.", e);
		}
	}

	/** 查询"首次加入提示"是否已经发过。 */
	public static boolean hasTippedFirstJoin() {
		return tippedFirstJoin;
	}

	/** 标记"首次加入提示"已发，并立即落盘。 */
	public static void markTippedFirstJoin() {
		if (tippedFirstJoin) return;
		tippedFirstJoin = true;
		save();
	}

	/**
	 * 在 {@code ClientPlayConnectionEvents.JOIN} 时调用，刷新自动检测结果。
	 *
	 * 顺序很关键：必须先设置 serverHasMod，再调用 sendModePreference()——
	 * sendModePreference 内部用 {@link #isClientOnlyModeActive()} 决定上报什么值，
	 * 而 AUTO 模式下 isClientOnlyModeActive 直接依赖 serverHasMod。若顺序反了，
	 * 首次进服会基于"上一次连接的残留 serverHasMod"上报，可能与服务端真实状态相反。
	 */
	public static void onJoinServer() {
		serverHasMod = ClientPlayNetworking.canSend(RequestConfigPayload.ID);
		MCCF.LOGGER.info("[MCCF] Server MCCF detection: {}", serverHasMod ? "detected" : "not detected");
		sendModePreference();
	}

	/** 在 {@code ClientPlayConnectionEvents.DISCONNECT} 时调用，避免残留上一个服务器的检测结果。 */
	public static void onDisconnect() {
		serverHasMod = false;
	}

	public static Override getOverride() {
		return override;
	}

	public static void setOverride(Override newOverride) {
		net.mccf.mod.client.chat.ClientOnlyChatTranslator.resetSession();
		override = newOverride == null ? Override.AUTO : newOverride;
		save();
		// 模式变更后立即通知服务端，让其更新对该玩家的空间化处理策略。
		// 若当前未连接服务器（或连的是旧服务端不认这个包），canSend 会返回 false，
		// sendModePreference 内部静默跳过，不会产生无意义的警告日志。
		sendModePreference();
	}

	/**
	 * 把当前模式偏好上报给服务端。服务端据此决定是否对该玩家跳过空间化拦截与字幕分发。
	 *
	 * 为什么必须做 canSend 检查而不是直接 send：旧版本 MCCF 服务端没有注册
	 * ModePreferencePayload 通道，直接 send 会在客户端日志里刷"unknown packet"
	 * 警告（虽然 Fabric 会优雅丢弃，但日志噪声对排查问题不友好）。canSend 检查
	 * 的是登录阶段通道协商结果，能精确区分"新服务端可以发"和"旧服务端/无 MCCF
	 * 不该发"，后者直接退回从 SubtitlePayload 提取文本的本地翻译方案。
	 *
	 * 何时调用：1) 玩家在配置界面切换模式后（{@link #setOverride}）；2) 加入服务器后
	 * （{@link #onJoinServer}，此时通道协商已完成，canSend 结果可靠）。
	 */
	public static void sendModePreference() {
		if (ClientPlayNetworking.canSend(ModePreferencePayload.ID)) {
			ClientPlayNetworking.send(new ModePreferencePayload(isClientOnlyModeActive()));
		}
	}

	/**
	 * 当前是否应该按纯客户端模式运行。
	 *
	 * @return true 表示应该只做本地聊天翻译，不依赖任何服务端功能
	 */
	public static boolean isClientOnlyModeActive() {
		return switch (override) {
			case FORCE_CLIENT_ONLY -> true;
			case FORCE_SERVER_MODE -> false;
			case AUTO -> !serverHasMod;
		};
	}

	/** 供配置界面展示"自动检测到的结果是什么"，与最终生效结果（可能被手动覆盖）区分开。 */
	public static boolean isServerDetected() {
		return serverHasMod;
	}
}
