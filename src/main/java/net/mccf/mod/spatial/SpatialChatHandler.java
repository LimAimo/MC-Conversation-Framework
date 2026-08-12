package net.mccf.mod.spatial;

import net.mccf.mod.MCCF;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.context.Conversation;
import net.mccf.mod.context.ConversationManager;
import net.mccf.mod.network.ConversationRosterPayload;
import net.mccf.mod.network.SubtitlePayload;
import net.mccf.mod.translation.TranslationService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MCCF 的核心调度点：拦截原版聊天广播，替换为"空间化 + 上下文化 + 翻译化"的
 * 点对点分发。
 *
 * 处理流程（对应设计文档的三条核心原则）：
 * 1. 拦截原版全服聊天广播（返回 false 阻止其广播，避免"信息传播到不该到达的人"）。
 * 2. 用 {@link HearingResolver} 计算说话者周围谁能"看到"/"听到"这句话
 *    （原则 2：信息只会传播到真正能够接收到它的人）。
 * 3. 把这次发言喂给 {@link ConversationManager}，动态合并/拆分对话组
 *    （原则 3：AI 不应该拥有全知视角，上下文严格限定在当前对话组内）。
 * 4. 对每个听众，用其"客户端语言"作为目标语言发起翻译（异步），
 *    翻译上下文严格限定于该 Conversation 内的近期消息。
 * 5. 翻译完成后，为每个听众单独发送一个 {@link SubtitlePayload}，
 *    displayMode 由其与说话者的距离/视线关系决定。
 *
 * 客户端语言检测的网络协议已实现：客户端通过 {@link net.mccf.mod.network.LanguageReportPayload}
 * 在加入服务器时上报自己的 Minecraft 语言设置，服务端在 {@link PlayerLanguageRegistry}
 * 内存维护在线玩家的目标语言。另外客户端可通过 {@link net.mccf.mod.network.ModePreferencePayload}
 * 声明自己处于纯客户端模式，此时本类对该玩家不做空间化处理（见 onChatMessage/dispatchTo 开头检查）。
 */
public class SpatialChatHandler {

	private final ConversationManager conversationManager;
	private final TranslationService translationService;
	private final MCCFConfig config;
	private final HearingResolver hearingResolver;

	public SpatialChatHandler(ConversationManager conversationManager, TranslationService translationService,
			MCCFConfig config) {
		this.conversationManager = conversationManager;
		this.translationService = translationService;
		this.config = config;
		this.hearingResolver = new HearingResolver(config);
	}

	/**
	 * 对应 {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} 的回调签名。
	 * 默认返回 false：MCCF 完全接管消息分发，不让原版群发广播发生。
	 * 唯一例外：说话者本人选择了纯客户端模式——此时返回 true 放行原版广播。
	 */
	public boolean onChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
		// client-only 玩家的聊天不拦截：这个玩家明确表示只要本地翻译，服务端不应该为他做
		// 空间化处理。放行后原版全服广播照常发生，所有人（包括他自己）都收到原版 CHAT 事件——
		// 这正是 client-only 模式预期的"全服广播 + 各自本地翻译"行为。代价是这条消息不受空间化
		// 隔离约束，但这本来就是玩家主动选择放弃服务端空间化才换来的，属于知情取舍。
		if (ClientOnlyModeRegistry.isClientOnly(sender.getUuid())) {
			return true;
		}

		MinecraftServer server = sender.getServer();
		if (server == null) {
			return true; // 极端情况下退回原版行为，保证消息不丢失。
		}

		String rawText = message.getContent().getString();
		if (rawText.isBlank()) {
			return false;
		}

		// 1. 计算空间范围内的候选听众（同世界的所有其他在线玩家）。
		// 说话者本人不参与距离/遮挡判定（自己跟自己永远"听得到"，判定没有意义），
		// 但这不代表说话者应该收不到自己的回显——回显走独立分支（见下方 dispatchSelfEcho），
		// 不占用 HearingResolver 的候选名单，也不影响 Conversation 的听众集合语义。
		List<ServerPlayerEntity> candidates = server.getPlayerManager().getPlayerList().stream()
				.filter(p -> p.getWorld() == sender.getWorld())
				.filter(p -> !p.getUuid().equals(sender.getUuid()))
				.collect(Collectors.toList());

		HearingResolver.HearingResult hearing = hearingResolver.resolveAll(sender, candidates);
		List<ServerPlayerEntity> allListeners = hearing.allListeners();

		String speakerName = sender.getGameProfile().getName();

		// 2. 驱动 Conversation 合并/拆分（原则：距离 + 主动发言）。
		//
		// 这一步现在无论 allListeners 是否为空都要执行（提前到"是否有听众"判断
		// 之前），保证说话者本人始终先被归入一个 Conversation——哪怕周围完全
		// 没人、只有他自己一个参与者。这是为了让 dispatchSelfEcho 发出的自己
		// 回显消息始终携带一个有效的 conversationId，客户端聊天历史记录里
		// 才能把"自言自语"也正常归组显示，而不是用一个特殊的空/随机标识表示
		// "不属于任何对话组"（应用户明确要求：无论有没有听众，都先归组）。
		//
		// audienceIds 为空集合时，recordUtterance 只会把 speaker 自己加入
		// Conversation（见该方法实现），这与"周围没人，Conversation 里只有
		// 我自己"的语义完全吻合，不需要特殊分支处理。
		Set<UUID> audienceIds = allListeners.stream().map(ServerPlayerEntity::getUuid).collect(Collectors.toSet());
		long currentTick = server.getTicks();
		ConversationManager.UtteranceResult utterance = conversationManager.recordUtterance(sender.getUuid(), audienceIds, currentTick);
		Conversation conversation = utterance.conversation();
		conversation.recordMessage(sender.getUuid(), rawText, currentTick);

		// 无论其他人能否听到，说话者本人始终应该在自己的客户端上看到自己刚说的话——
		// 这跟原版聊天行为一致（原版广播里说话者也会收到自己发的消息），MCCF 接管分发后
		// 不该丢失这个基本体验。回显不翻译（自己的话不需要翻译给自己看），displayMode
		// 跟随"本次发言时，其他听众里哪种关系占多数"（见 dispatchSelfEcho 说明）。
		dispatchSelfEcho(sender, speakerName, rawText, hearing, conversation.getId());

		// 只在这次发言真的给 Conversation 带来了新成员时，才广播最新的完整
		// 参与者名单给该 Conversation 当前的所有人——应用户明确要求，不要
		// 无条件每次发言都广播（那样最简单但浪费带宽）。newlyJoined 为空
		// （同一批人持续对话、没人新加入）时跳过，这是最常见的情况，
		// 大部分发言根本不需要这次网络开销。
		if (!utterance.newlyJoined().isEmpty()) {
			broadcastConversationRoster(conversation, server);
		}

		if (allListeners.isEmpty()) {
			// 没有任何人能听到——原则 2 的直接体现：这句话不会被任何人看到/听到，
			// 也不会作为"可翻译上下文"分发给任何其他听众；但 Conversation 本身
			// （只包含说话者自己）仍然存在，用于让自己的历史记录正确归组，见上方
			// 调整顺序的说明。这跟"原则 2：信息只会传播到真正能够接收到它的人"
			// 并不矛盾——没有人以外的第三方会知道/记录这句话，只是说话者自己的
			// 客户端知道自己说了什么，这本来就是天经地义的。
			return false;
		}

		// 3. 为该 Conversation 构造严格受限的上下文（仅限当前仍在场成员的近期发言）。
		List<String> contextMessages = conversation.getRecentMessages().stream()
				.map(Conversation.ContextMessage::text)
				.collect(Collectors.toList());

		String sourceLang = PlayerLanguageRegistry.getLanguage(sender.getUuid());

		// 4. 对每个听众：确定显示模式 -> 翻译 -> 发送字幕包。
		dispatchTo(hearing.visible(), sender, speakerName, rawText, sourceLang, contextMessages, "VISIBLE", conversation.getId());
		dispatchTo(hearing.audibleOnly(), sender, speakerName, rawText, sourceLang, contextMessages, "AUDIBLE", conversation.getId());

		return false;
	}

	/**
	 * 把某个 Conversation 当前的完整参与者名单（UUID + 显示名）广播给该
	 * Conversation 的所有参与者——不只是新加入的人，老成员也需要更新本地
	 * "这个对话现在都有谁"的状态（用于聊天历史记录界面的大标题/"XX 加入了
	 * 对话"提示）。
	 *
	 * 跳过条件：
	 * - 玩家已不在线（Conversation 的 participants 集合可能包含刚下线、
	 *   还没被清理掉的玩家 UUID——见 ConversationManager 的过期/清理机制，
	 *   这里防御性地用 getPlayerManager().getPlayer 拿不到就跳过）。
	 * - 玩家是 client-only 模式（他们的聊天历史记录走完全独立的本地路径
	 *   {@code ClientOnlyChatTranslator}，没有"服务端 Conversation"这个概念，
	 *   发这个包给他们没有意义，白白浪费一次网络往返）。
	 */
	private void broadcastConversationRoster(Conversation conversation, MinecraftServer server) {
		List<UUID> ids = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (UUID participantId : conversation.getParticipants()) {
			ServerPlayerEntity participant = server.getPlayerManager().getPlayer(participantId);
			if (participant == null) continue; // 已下线，跳过（不影响其他在线成员收到名单）
			ids.add(participantId);
			names.add(participant.getGameProfile().getName());
		}
		if (ids.isEmpty()) return;

		ConversationRosterPayload roster = new ConversationRosterPayload(conversation.getId(), ids, names);
		for (UUID participantId : conversation.getParticipants()) {
			ServerPlayerEntity participant = server.getPlayerManager().getPlayer(participantId);
			if (participant == null) continue;
			if (ClientOnlyModeRegistry.isClientOnly(participantId)) continue;
			// 1.1.8：没装 MCCF 的参与者不支持 ConversationRosterPayload 通道，跳过避免发包失败。
			if (participant.networkHandler == null) continue;
			if (!ServerPlayNetworking.canSend(participant.networkHandler, ConversationRosterPayload.ID)) continue;
			server.execute(() -> {
				if (participant.networkHandler != null) {
					ServerPlayNetworking.send(participant, roster);
				}
			});
		}
	}

	/**
	 * 给说话者本人发一份"自己说的话"的回显包，不经过翻译（自己的话不需要翻译给自己看）。
	 *
	 * 为什么复用 SubtitlePayload 而不是新建一个包类型：字段完全够用——originalText 和
	 * translatedText 都填原文即可，客户端不需要区分"这是回显"还是"这是真的同语言翻译结果"，
	 * 因为无论哪种情况客户端的处理方式都一样（显示原文）。复用能减少一次
	 * PayloadTypeRegistry 注册和一处客户端分支，维护成本更低。
	 *
	 * displayMode 的选择——"跟随主导模式"：说话者本人对自己没有距离/遮挡概念（自己
	 * 永远在自己身边），没法直接复用 HearingResolver 的判定。改为统计本次发言时，
	 * 其他听众里 VISIBLE 和 AUDIBLE 两档各有多少人，人数多的一档即为"主导模式"，
	 * 自己的回显跟随这个主导模式展示——多数人能看到我时，我自己也理应用聊天框看到
	 * 自己说的话（沉浸感一致：大家都在近处对话）；多数人只能听到听不到看到我时
	 * （比如隔墙/远距离喊话），自己的回显也降级成物品栏字幕，呼应"这是一句喊出去的话
	 * 而不是面对面聊天"的情境。没有任何听众时（allListeners 为空）默认 VISIBLE——
	 * 聊天框比一闪而过的字幕更保险，避免独自一人说话时消息被错过。
	 */
	private void dispatchSelfEcho(ServerPlayerEntity sender, String speakerName, String rawText,
			HearingResolver.HearingResult hearing, UUID conversationId) {
		if (sender.networkHandler == null) return;

		// 1.1.8 修复：说话者没装 MCCF（不支持 SubtitlePayload 通道）时，服务端拦截了
		// 原版广播后必须用原版方式补发回显——否则说话者自己完全看不到自己发的消息。
		// sendMessage 发的是系统消息（非签名聊天），手动补 "<名字> 原文" 前缀保持
		// 与原版聊天栏格式一致。没装 MCCF 的玩家不需要翻译，能看到原文即可。
		if (!ServerPlayNetworking.canSend(sender.networkHandler, SubtitlePayload.ID)) {
			sender.sendMessage(Text.literal("<" + speakerName + "> " + rawText), false);
			return;
		}

		String displayMode = hearing.visible().size() >= hearing.audibleOnly().size() ? "VISIBLE" : "AUDIBLE";
		// 自己回显不翻译，sourceLang == targetLang（都是说话者自己的语言）——
		// 历史记录界面据此判断"这条消息不需要显示语言标签"（源语言=目标语言时，
		// 说明没有发生真正的翻译，不需要画"中文→英语"这种箭头）。
		String selfLang = PlayerLanguageRegistry.getLanguage(sender.getUuid());
		SubtitlePayload echo = new SubtitlePayload(
				sender.getUuid(), speakerName, rawText, rawText, displayMode, conversationId, selfLang, selfLang);
		sender.getServer().execute(() -> {
			if (sender.networkHandler != null) {
				ServerPlayNetworking.send(sender, echo);
			}
		});
	}

	private void dispatchTo(List<ServerPlayerEntity> listeners, ServerPlayerEntity sender, String speakerName,
			String rawText, String sourceLang, List<String> contextMessages, String displayMode, UUID conversationId) {
		for (ServerPlayerEntity listener : listeners) {
			// 1.1.8 修复：没装 MCCF 的听众（不支持 SubtitlePayload 通道）用原版 sendMessage
			// 发原文。服务端拦截了原版广播，不补这一条这些听众就完全收不到消息。
			// 没装 MCCF 的玩家不需要翻译，能看到原文即可。
			if (!ServerPlayNetworking.canSend(listener.networkHandler, SubtitlePayload.ID)) {
				listener.sendMessage(Text.literal("<" + speakerName + "> " + rawText), false);
				continue;
			}

			// 1.1.8 修复：client-only 听众在说话者非 client-only 时（走到这里说明说话者
			// 非 client-only，因为 client-only 说话者在 onChatMessage 开头就 return true 了），
			// 原版广播已被拦截，收不到原版 CHAT 事件。旧版直接 continue 跳过，导致
			// client-only 听众完全收不到消息——注释说"主动选择的可见性损失"，但用户体验
			// 极差，host 选强制客户端模式后什么都看不到。改为发未翻译的 SubtitlePayload
			//（originalText=translatedText=rawText），客户端走 SubtitlePayload 接收器的
			// client-only 分支手动显示原文 + 本地翻译。不调 translationService.translate
			// 避免浪费服务端翻译计算——client-only 玩家明确要用自己的本地 Provider 翻译，
			// 服务端翻译结果会被客户端忽略。
			//
			// 不会导致"原版 CHAT + SubtitlePayload 原文"重复显示：说话者非 client-only 时
			// 原版广播被拦截，client-only 听众收不到 CHAT；说话者也是 client-only 时
			// onChatMessage return true 放行原版广播，但根本不会走到 dispatchTo。
			if (ClientOnlyModeRegistry.isClientOnly(listener.getUuid())) {
				SubtitlePayload payload = new SubtitlePayload(
						sender.getUuid(), speakerName, rawText, rawText, displayMode,
						conversationId, sourceLang, sourceLang);
				sender.getServer().execute(() -> {
					if (listener.networkHandler != null) {
						ServerPlayNetworking.send(listener, payload);
					}
				});
				continue;
			}

			String targetLang = PlayerLanguageRegistry.getLanguage(listener.getUuid());

		// 1.1.1 起，服务端始终在 originalText 里携带原文——是否显示原文不再是
		// 服务端 op 配置，而是客户端个人偏好（ClientOnlyTranslationConfig 里
		// 的 showOriginalText / showOriginalTextInChat），由客户端渲染时自行
		// 判断。这样每个玩家可以独立决定要不要看原文，不受服务器管理员限制。
		translationService.translate(rawText, sourceLang, targetLang, contextMessages)
				.thenAccept(translated -> {
					SubtitlePayload payload = new SubtitlePayload(
								sender.getUuid(), speakerName, rawText, translated, displayMode,
								conversationId, sourceLang, targetLang);
						// 网络发送必须回到服务器主线程执行。
						sender.getServer().execute(() -> {
							if (listener.networkHandler != null) {
								ServerPlayNetworking.send(listener, payload);
							}
						});
					})
					.exceptionally(ex -> {
						MCCF.LOGGER.error("[MCCF] Failed to dispatch subtitle to {}", listener.getGameProfile().getName(), ex);
						return null;
					});
		}
	}
}
