package net.mccf.mod.spatial;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.mccf.mod.MCCF;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.context.Conversation;
import net.mccf.mod.context.ConversationManager;
import net.mccf.mod.network.ConversationRosterPayload;
import net.mccf.mod.network.SubtitlePayload;
import net.mccf.mod.translation.TranslationService;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 这个类只管空间化聊天调度、听众上下文边界和有序分发；分组状态交给
 * ConversationManager，翻译与缓存交给 TranslationService，界面展示由客户端决定。
 *
 * client-only 偏好只决定在哪里翻译，不能让客户端绕过服务器的信息传播规则。
 */
public class SpatialChatHandler {
	// 通道 ID 没有版本协商，旧客户端的解码器最多接受 128 人，发送端必须遵守该边界。
	private static final int COMPATIBLE_ROSTER_LIMIT = 128;

	private final ConversationManager conversationManager;
	private final TranslationService translationService;
	private final MCCFConfig config;
	private final HearingResolver hearingResolver;
	private final OrderedDeliveryQueue<UUID> deliveries = new OrderedDeliveryQueue<>();
	private final Map<RosterView, List<UUID>> sentRosters = new HashMap<>();
	private record RosterView(UUID conversationId, UUID listenerId) {}

	public SpatialChatHandler(ConversationManager conversationManager, TranslationService translationService,
			MCCFConfig config) {
		this.conversationManager = conversationManager;
		this.translationService = translationService;
		this.config = config;
		this.hearingResolver = new HearingResolver(config);
	}

	public boolean onChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
		MinecraftServer server = sender.getServer();
		if (server == null) return false;
		String rawText = message.getContent().getString();
		if (rawText.isBlank()) return false;

		Map<UUID, ConversationManager.Location> positions = ConversationManager.snapshotLocations(server);
		conversationManager.reconcileParticipants(positions);
		List<ServerPlayerEntity> candidates = server.getPlayerManager().getPlayerList().stream()
				.filter(player -> player.getWorld() == sender.getWorld())
				.filter(player -> !player.getUuid().equals(sender.getUuid())).toList();
		HearingResolver.HearingResult hearing = hearingResolver.resolveAll(sender, candidates);
		Set<UUID> audience = hearing.allListeners().stream()
				.map(ServerPlayerEntity::getUuid).collect(Collectors.toCollection(LinkedHashSet::new));
		// 听力范围可以大于对话范围；远处听众仍能收到本句话，但不能借此合并整个旧组。
		Set<UUID> groupAudience = audience.stream()
				.filter(id -> positions.get(sender.getUuid()).within(positions.get(id), config.conversationRange))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Conversation conversation = conversationManager.recordUtterance(
				sender.getUuid(), groupAudience, server.getTicks()).conversation();
		Set<UUID> recipients = new LinkedHashSet<>(audience);
		recipients.add(sender.getUuid());
		conversation.recordMessage(sender.getUuid(), rawText, server.getTicks(), recipients);
		flushRosterChanges(server);
		broadcastConversationRoster(conversation, server);
		String speakerName = sender.getGameProfile().getName();
		String sourceLang = PlayerLanguageRegistry.getLanguage(sender.getUuid());
		String selfMode = hearing.visible().size() >= hearing.audibleOnly().size() ? "VISIBLE" : "AUDIBLE";
		dispatch(sender, sender, speakerName, rawText, sourceLang, sourceLang, selfMode,
				conversation.getId(), CompletableFuture.completedFuture(rawText));
		dispatchTo(hearing.visible(), sender, speakerName, rawText, sourceLang, conversation, "VISIBLE");
		dispatchTo(hearing.audibleOnly(), sender, speakerName, rawText, sourceLang, conversation, "AUDIBLE");
		return false;
	}

	/** 在 ConversationManager 的 tick 之后调用，使沉默期间的离组也及时同步。 */
	public void tick(MinecraftServer server) {
		flushRosterChanges(server);
		if (server.getTicks() % 20 == 0) {
			Set<UUID> active = new LinkedHashSet<>();
			for (Conversation conversation : conversationManager.snapshotConversations()) {
				active.add(conversation.getId());
				broadcastConversationRoster(conversation, server);
			}
			// 同一连通组内部移动也可能改变某个听众能收到的名单，按视图去重后只发变化。
			sentRosters.keySet().removeIf(view -> !active.contains(view.conversationId())
					|| server.getPlayerManager().getPlayer(view.listenerId()) == null);
		}
		hearingResolver.pruneCache();
	}

	private void flushRosterChanges(MinecraftServer server) {
		for (Conversation conversation : conversationManager.consumeRosterChanges()) {
			broadcastConversationRoster(conversation, server);
		}
	}

	private void broadcastConversationRoster(Conversation conversation, MinecraftServer server) {
		for (UUID listenerId : conversation.getParticipants()) {
			ServerPlayerEntity listener = server.getPlayerManager().getPlayer(listenerId);
			if (listener == null || listener.networkHandler == null) continue;
			if (ClientOnlyModeRegistry.isClientOnly(listenerId)) continue;
			if (!ServerPlayNetworking.canSend(listener.networkHandler, ConversationRosterPayload.ID)) continue;
			List<UUID> ids = new ArrayList<>();
			List<String> names = new ArrayList<>();
			ids.add(listenerId);
			names.add(listener.getGameProfile().getName());
			for (UUID id : conversation.getParticipants()) {
				if (id.equals(listenerId)) continue;
				ServerPlayerEntity participant = server.getPlayerManager().getPlayer(id);
				if (participant == null) continue;
				// 连通组的两端可能互相听不到；不能把全组身份当成可以全组广播的信息。
				if (!id.equals(listenerId) && hearingResolver.resolve(participant, listener) == HearingResolver.HearingLevel.NONE) continue;
				if (ids.size() == COMPATIBLE_ROSTER_LIMIT) break;
				ids.add(id);
				names.add(participant.getGameProfile().getName());
			}
			RosterView view = new RosterView(conversation.getId(), listenerId);
			if (ids.equals(sentRosters.get(view))) continue;
			ServerPlayNetworking.send(listener, new ConversationRosterPayload(conversation.getId(), ids, names));
			sentRosters.put(view, List.copyOf(ids));
		}
	}

	private void dispatchTo(List<ServerPlayerEntity> listeners, ServerPlayerEntity sender, String speakerName,
			String rawText, String sourceLang, Conversation conversation, String displayMode) {
		for (ServerPlayerEntity listener : listeners) {
			String targetLang = PlayerLanguageRegistry.getLanguage(listener.getUuid());
			CompletableFuture<String> translation;
			if (!ServerPlayNetworking.canSend(listener.networkHandler, SubtitlePayload.ID)
					|| ClientOnlyModeRegistry.isClientOnly(listener.getUuid())) {
				translation = CompletableFuture.completedFuture(rawText);
			} else {
				Set<UUID> present = new LinkedHashSet<>();
				for (UUID id : conversation.getParticipants()) {
					ServerPlayerEntity participant = sender.getServer().getPlayerManager().getPlayer(id);
					if (participant == null) continue;
					if (hearingResolver.resolve(participant, sender) == HearingResolver.HearingLevel.NONE) continue;
					if (hearingResolver.resolve(participant, listener) == HearingResolver.HearingLevel.NONE) continue;
					present.add(id);
				}
				translation = translationService.translate(rawText, sourceLang, targetLang,
						conversation.contextFor(sender.getUuid(), listener.getUuid(), present));
			}
			dispatch(listener, sender, speakerName, rawText, sourceLang, targetLang, displayMode,
					conversation.getId(), translation);
		}
	}

	private void dispatch(ServerPlayerEntity listener, ServerPlayerEntity sender, String speakerName,
			String rawText, String sourceLang, String targetLang, String displayMode, UUID conversationId,
			CompletableFuture<String> translation) {
		MinecraftServer server = sender.getServer();
		var connection = listener.networkHandler;
		deliveries.enqueue(listener.getUuid(), translation, translated -> {
			// 重生会更换实体但保留网络会话；重连则会更换 handler，因此比较会话
			// 而非实体既能丢弃旧连接的结果，也不会无故丢掉死亡重生期间的聊天。
			ServerPlayerEntity current = server.getPlayerManager().getPlayer(listener.getUuid());
			if (current == null || connection == null || current.networkHandler != connection) return;
			if (!ServerPlayNetworking.canSend(current.networkHandler, SubtitlePayload.ID)) {
				current.sendMessage(Text.literal("<" + speakerName + "> " + rawText), false);
				return;
			}
			// 模型偶尔返回说明文字或超长结果；回退原文比让包构造异常吞掉消息更可靠。
			String displayText = translated == null || translated.isBlank()
					|| translated.length() > SubtitlePayload.MAX_TEXT_LENGTH ? rawText : translated;
			ServerPlayNetworking.send(current, new SubtitlePayload(sender.getUuid(), speakerName,
					rawText, displayText, displayMode, conversationId, sourceLang, targetLang));
		}, server).exceptionally(error -> {
			MCCF.LOGGER.error("[MCCF] Failed to dispatch subtitle to {}", listener.getGameProfile().getName(), error);
			return null;
		});
	}

	public void removePlayer(UUID playerId) {
		deliveries.remove(playerId);
		sentRosters.keySet().removeIf(view -> view.listenerId().equals(playerId));
		hearingResolver.removePlayer(playerId);
	}

	public void clear() {
		deliveries.clear();
		sentRosters.clear();
		hearingResolver.clearCache();
	}
}
