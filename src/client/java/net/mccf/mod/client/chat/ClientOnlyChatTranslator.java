package net.mccf.mod.client.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.config.ClientOnlyTranslationConfig;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.translation.provider.ProviderFactory;
import net.mccf.mod.util.RateLimiter;
import net.mccf.mod.translation.provider.TranslationProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只管本地翻译、请求排序和聊天栏追加，空间判定由安装了 MCCF 的服务器负责。
 * 无 MCCF 的服务器通过原版 CHAT 事件提供消息；MCCF 服务器通过原文字幕包提供
 * 已经过空间筛选的消息。本类只在本地翻译模式启用时工作，原文仍由调用方展示。
 */
public final class ClientOnlyChatTranslator {
	private static final OrderedClientResults RESULTS = new OrderedClientResults();

	private ClientOnlyChatTranslator() {}

    /**
     * 仅在消息来源没有 GameProfile 或字幕包姓名时，从原版 <Name> 前缀兜底。
     * 模组自定义聊天格式不保证可解析，正常 CHAT 路径优先使用 GameProfile 的权威姓名。
     */

	private static final Pattern SPEAKER_NAME_PATTERN = Pattern.compile("^<([^<>]{1,16})>\\s");

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (!ClientOnlyModeManager.isClientOnlyModeActive()) return;

			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null) return;

			String sourceText = message.getString();
			if (sourceText.isBlank()) return;

			// sender.getId() 返回的是 GameProfile 的 UUID（与 player.getUuid() 同源可比），
			// 不是实体 int id。toString 后传给 translateAndAppend 做"是不是自己发的"判定。
			String senderUuid = (sender != null && sender.getId() != null) ? sender.getId().toString() : null;
			translateAndAppend(sourceText, senderUuid, sender != null ? sender.getName() : null);
		});
	}

	/**
	 * 把一条原文走本地翻译后追加显示到聊天栏。供两个调用点复用：
	 * 1. {@link #register()} 注册的 CHAT 事件监听器（原版聊天广播触发）；
	 * 2. MCCFClient 的 SubtitlePayload 接收器（MCCF 服务端发送原文时触发）。
	 *
	 * 抽取成独立方法是为了避免两处复制粘贴翻译/节流/Provider 缓存逻辑——
	 * 这两处的"翻译 + 追加"行为完全一致，只有"文本从哪里来"不同。
	 *
	 * @param sourceText 原文（调用方负责保证非空、非空白）
	 * @param senderUuid 说话者 UUID 字符串，可为 null（用于"是不是自己发的"判定）
	 */
	public static void translateAndAppend(String sourceText, String senderUuid) {
		translateAndAppend(sourceText, senderUuid, null);
	}

	/**
	 * 带 speakerName 提示的重载——供 SubtitlePayload 退回路径使用。
	 *
	 * SubtitlePayload 自带 speakerName 字段（服务端从 GameProfile 取的权威名字），
	 * 比从聊天文本 "<玩家名>" 前缀解析更可靠。CHAT 事件路径没有这个提示，
	 * 仍走 {@link #parseSpeakerName} 从文本解析。
	 *
	 * @param speakerNameHint 说话者显示名提示，null 表示无提示（走文本解析）
	 */
	public static void translateAndAppend(String sourceText, String senderUuid, String speakerNameHint) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;

		// 自己发的消息不翻译——玩家显然已经知道自己说了什么。
		// 退回方案路径下，SubtitlePayload 的 speakerId 也可能是自己（旧服务端不知道
		// 我是 client-only，可能给我发我自己说话的字幕），同样需要跳过。
		// 跳过翻译前仍记入历史（Source.SELF）——纯客户端模式下原版聊天广播本来就正常
		// 显示自己发的消息（这条路径没有 SpatialChatHandler 拦截），但历史界面需要完整
		// 记录，所以这里补一条，跟 MCCFClient 里服务端空间化模式下自己回显的记录逻辑对齐。
		if (senderUuid != null && senderUuid.equals(client.player.getUuid().toString())) {
			net.mccf.mod.client.history.ChatHistoryManager.record(
					new net.mccf.mod.client.history.ChatHistoryEntry(
							client.player.getUuid(), client.player.getGameProfile().getName(),
							sourceText, sourceText,
							net.mccf.mod.client.history.ChatHistoryEntry.Source.SELF,
							System.currentTimeMillis(), null, null, null));
			return;
		}

		if (!rateLimiter.tryAcquire()) {
			MCCF.LOGGER.warn("[MCCF] Client-only translation rate limit exceeded, skipping: {}",
					sourceText.length() > 80 ? sourceText.substring(0, 80) + "..." : sourceText);
			return;
		}

		String targetLang = detectClientLanguage(client);
		TranslationProvider provider = getProvider();

		// 预先解析说话者名（即使翻译失败也能用于历史记录），失败时返回 null，
		// 历史记录会用空字符串作为 speakerName（界面显示 "?"）。
		// speakerNameHint 非 null 时（SubtitlePayload 退回路径）优先使用，避免
		// 从不带 "<>" 前缀的纯文本里解析不到名字而留空。
		String speakerName = speakerNameHint != null ? speakerNameHint : parseSpeakerName(sourceText);

		var connection = client.getNetworkHandler();
		long receivedAt = System.currentTimeMillis();
		var ticket = RESULTS.reserve();
		CompletableFuture<TranslationProvider.TranslationResult> translation;
		try {
			translation = provider.translate(new TranslationProvider.TranslationRequest(
					sourceText, "auto", targetLang, List.of()));
		} catch (Exception ex) {
			translation = CompletableFuture.failedFuture(ex);
		}
		// A failed/timed-out request advances the queue so it cannot hold all later messages forever.
		translation.orTimeout(35, TimeUnit.SECONDS).whenComplete((result, failure) -> client.execute(() ->
				RESULTS.complete(ticket, () -> {
					if (client.player == null || client.getNetworkHandler() != connection
							|| !ClientOnlyModeManager.isClientOnlyModeActive()) return;
					if (failure != null) {
						Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
						String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
						MCCF.LOGGER.warn("[MCCF] Client-only translation failed: {}", reason);
						notifyPlayerOfFailure(client, reason);
						return;
					}
					String translated = result.translatedText();
					if (translated == null || translated.isBlank()) translated = sourceText;
					if (!translated.equals(sourceText)) {
						client.inGameHud.getChatHud().addMessage(
								Text.literal("⇄ " + translated).formatted(Formatting.GRAY));
					}
					net.mccf.mod.client.history.ChatHistoryManager.record(
							new net.mccf.mod.client.history.ChatHistoryEntry(
									parseSenderUuid(senderUuid), speakerName != null ? speakerName : "",
									sourceText, translated,
									net.mccf.mod.client.history.ChatHistoryEntry.Source.CLIENT_ONLY,
									receivedAt, null, null, null));
				})));
	}

	/** Called on connect/disconnect and local-mode changes; old callbacks become no-ops. */
	public static void resetSession() {
		RESULTS.reset();
	}

	/**
	 * 给玩家发一次去重的翻译失败提示。
	 *
	 * 去重策略：以 reason 字符串为 key 记录"最近一次提示时间"，60 秒内同一 reason
	 * 不再提示。换 Provider / 换 API Key 后失败原因通常会变，新 reason 会立即提示，
	 * 让玩家感知"现在的失败是新问题"。
	 *
	 * 为什么用 static 字段而不是实例字段：本类是 utility 类（私有构造器），
	 * 所有方法都是 static，状态自然也用 static。这导致状态在客户端进程内全局共享，
	 * 不能区分"不同服务器"——但翻译失败的原因通常是 API Key 错误、网络超时等
	 * 跨服务器共性问题，全局去重足够。
	 */
	private static final java.util.Map<String, Long> lastFailureNotifyMillis = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long FAILURE_NOTIFY_DEDUP_MILLIS = 60_000L;

	private static void notifyPlayerOfFailure(MinecraftClient client, String reason) {
		long now = System.currentTimeMillis();
		Long last = lastFailureNotifyMillis.get(reason);
		if (last != null && now - last < FAILURE_NOTIFY_DEDUP_MILLIS) {
			return; // 同一 reason 60 秒内已提示过，跳过避免刷屏
		}
		lastFailureNotifyMillis.put(reason, now);

		client.execute(() -> {
			if (client.player == null) return;
			// 灰色文字提示玩家翻译失败，并附简要原因。reason 来自已脱敏的错误描述，仍可能较长，
			// 截断到 100 字符避免聊天栏被单行错误占满。
			String truncated = reason != null && reason.length() > 100
					? reason.substring(0, 100) + "..." : String.valueOf(reason);
			client.inGameHud.getChatHud().addMessage(
					Text.literal("[MCCF] 翻译失败：" + truncated + "（详见日志）")
							.formatted(Formatting.YELLOW));
		});
	}

	/**
	 * 从聊天文本里解析 "<玩家名> 消息" 前缀。
	 *
	 * 不破坏原 sourceText 的内容——纯客户端模式下整条 sourceText 都会被送去翻译
	 * （包括 "<玩家名>" 前缀），这是历史行为不在本次修复范围内改动。这里只是从
	 * 同一份文本里抠出玩家名用于历史记录展示，不修改翻译输入。
	 */
	private static String parseSpeakerName(String sourceText) {
		if (sourceText == null || sourceText.isEmpty()) return null;
		Matcher matcher = SPEAKER_NAME_PATTERN.matcher(sourceText);
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * 安全解析 UUID 字符串。
	 *
	 * 1.1.2 修复"senderUuid 非 null 但不是合法 UUID 格式时 UUID.fromString 抛
	 * IllegalArgumentException 被异步调度器吞掉"的问题。某些 Mod 可能修改
	 * 调用方传入非标准 ID，这里捕获异常退化成零 UUID，保证翻译流程继续。
	 */
	private static UUID parseSenderUuid(String senderUuid) {
		if (senderUuid == null) return new UUID(0, 0);
		try {
			return UUID.fromString(senderUuid);
		} catch (IllegalArgumentException e) {
			MCCF.LOGGER.warn("[MCCF] Invalid sender UUID format, using zero UUID: {}", senderUuid);
			return new UUID(0, 0);
		}
	}

	/**
	 * 获取当前配置激活的翻译 Provider，按 providerId 缓存实例。
	 *
	 * 为什么缓存 Provider 而不是每次新建：ProviderFactory.create 内部会 new 一个
	 * ProviderConfig 再 new 一个 Provider，聊天消息高频时每条都新建会产生
	 * 大量短命对象。大部分玩家在一次游戏会话里不会频繁切换 Provider，按 id 缓存
	 * 足以覆盖绝大多数场景。
	 *
	 * 1.1.2 修复"改 API Key 后翻译仍用旧 Key"：旧版缓存永不失效，玩家改 Key 后
	 * 必须切 Provider 再切回来才能刷新——这个限制太隐晦，玩家会以为是 Key 没保存
	 * 成功反复改。新版增加 invalidateCache() 方法，由 ClientOnlyTranslationConfig
	 * 在 save() 完成后回调触发，保证"保存即刷新"。Provider 切换时（cachedProviderId
	 * 变化）也自然重建，无需额外处理。
	 */
	private static TranslationProvider cachedProvider;
	private static String cachedProviderId;

	private static TranslationProvider getProvider() {
		ClientOnlyTranslationConfig config = ClientOnlyTranslationConfig.get();
		String currentId = config.activeProvider;
		if (cachedProvider == null || !currentId.equals(cachedProviderId)) {
			cachedProvider = ProviderFactory.create(currentId, config.toProviderConfig(currentId));
			cachedProviderId = currentId;
		}
		return cachedProvider;
	}

	/**
	 * 让外部（{@link ClientOnlyTranslationConfig#save()}）通知"配置已变更"，
	 * 失效缓存的 Provider 实例，下次 getProvider() 会用最新配置重建。
	 *
	 * 为什么不直接在 getProvider 里每次都比对字段：实时感知配置变更需要
	 * ProviderConfig 自己支持"字段变更通知"或在 getProvider 里逐字段 diff，
	 * 复杂度不值当；保存路径是已知且单一的（玩家在配置界面点"保存"），
	 * 在保存后回调失效缓存是最简单可靠的方案。
	 */
	public static void invalidateProviderCache() {
		cachedProvider = null;
		cachedProviderId = null;
	}

	/**
	 * 纯客户端翻译的速率限制器：每秒最多 5 条翻译请求。
	 *
	 * 限流逻辑已抽取到 {@link RateLimiter}，这里只持有实例并调用 tryAcquire()。
	 * 抽取的原因：限流逻辑是纯并发控制，不依赖任何 Minecraft 类，混在
	 * ChatTranslator 里无法单独测试。抽成独立类后可以写 JUnit 测试覆盖
	 * 并发竞争和窗口边界场景。
	 *
	 * 为什么限流 5 条/秒：大部分翻译 API 的免费 tier 限制在每秒个位数请求（OpenAI
	 * 免费 key 约 20 RPM、DeepL 免费档短时并发也有限制），5 条是保守值。超出会触发
	 * 429 速率限制甚至临时封 Key——QA 报告的 M8 级问题就是玩家在聊天刷屏时本地翻译
	 * 狂调 API 导致 Key 被封。限流后超出的消息直接丢弃，玩家最多少看几条翻译，
	 * 不会影响游戏功能。
	 *
	 * 1.1.2 升级：RateLimiter 从固定窗口改为滑动窗口，避免窗口边界附近的 2 倍突刺
	 * 触发上游 API 限流。详见 RateLimiter 类注释。
	 */
	private static final RateLimiter rateLimiter = new RateLimiter(1000L, 5);

	private static String detectClientLanguage(MinecraftClient client) {
		String language = client.options.language;
		return (language == null || language.isBlank()) ? "en_us" : language;
	}
}
