package net.mccf.mod.context;

import net.mccf.mod.config.MCCFConfig;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * 这个类只管对话成员的合并、空间拆分和过期，不发送网络包；名单同步由
 * SpatialChatHandler 消费变更后完成。所有修改都在服务器主线程执行。
 *
 * 对话组按同维度、conversationRange 内的连接关系维持，避免移动后仍永久
 * 留在旧组。连通组并不表示所有成员互相听见，发送端还必须针对每位听众
 * 筛选消息的实际收件人与当前空间关系，不能把整组历史直接交给翻译服务。
 */
public class ConversationManager {

	private final IntSupplier timeoutSeconds;
	private final DoubleSupplier conversationRange;
	private final Map<UUID, Conversation> conversations = new LinkedHashMap<>();
	private final Map<UUID, UUID> playerToConversation = new HashMap<>();
	private final Set<UUID> changedRosters = new LinkedHashSet<>();

	public ConversationManager(MCCFConfig config) {
		this(() -> config.conversationIdleTimeoutSeconds, () -> config.conversationRange);
	}

	// 参数源保留 /reload 原地更新的语义，也让空间规则能脱离游戏实例回归测试。
	ConversationManager(IntSupplier timeoutSeconds, DoubleSupplier conversationRange) {
		this.timeoutSeconds = timeoutSeconds;
		this.conversationRange = conversationRange;
	}

	public record UtteranceResult(Conversation conversation, Set<UUID> newlyJoined) {}

	/** 这里只存空间值，不持有实体，避免分组规则依赖实体生命周期。 */
	public record Location(Object dimension, double x, double y, double z) {
		public boolean within(Location other, double range) {
			if (other == null || !dimension.equals(other.dimension)) return false;
			double dx = x - other.x, dy = y - other.y, dz = z - other.z;
			return dx * dx + dy * dy + dz * dz <= range * range;
		}
	}

	public static Map<UUID, Location> snapshotLocations(MinecraftServer server) {
		Map<UUID, Location> positions = new LinkedHashMap<>();
		for (var player : server.getPlayerManager().getPlayerList()) {
			positions.put(player.getUuid(), new Location(player.getWorld().getRegistryKey(),
					player.getX(), player.getY(), player.getZ()));
		}
		return positions;
	}

	public UtteranceResult recordUtterance(UUID speakerId, Set<UUID> audience, long currentTick) {
		Set<UUID> involvedIds = new LinkedHashSet<>();
		UUID own = playerToConversation.get(speakerId);
		if (own != null) involvedIds.add(own);
		for (UUID listenerId : audience) {
			UUID id = playerToConversation.get(listenerId);
			if (id != null) involvedIds.add(id);
		}
		Conversation target = involvedIds.isEmpty()
				? new Conversation(UUID.randomUUID(), currentTick)
				: conversations.get(involvedIds.iterator().next());
		conversations.put(target.getId(), target);
		Set<UUID> before = new LinkedHashSet<>(target.getParticipants());
		boolean merged = false;
		for (UUID id : involvedIds) {
			if (id.equals(target.getId())) continue;
			Conversation source = conversations.remove(id);
			changedRosters.remove(id);
			if (source == null) continue;
			merged = true;
			target.getParticipants().addAll(source.getParticipants());
			target.getRecentMessages().addAll(source.getRecentMessages());
		}
		target.addParticipant(speakerId);
		target.getParticipants().addAll(audience);
		for (UUID id : target.getParticipants()) playerToConversation.put(id, target.getId());
		Set<UUID> joined = new LinkedHashSet<>(target.getParticipants());
		joined.removeAll(before);
		if (!joined.isEmpty()) changedRosters.add(target.getId());
		// 两组历史可能交错产生，按 tick 稳定排序才能保留真实的上下文顺序。
		if (merged) {
			List<Conversation.ContextMessage> ordered = new ArrayList<>(target.getRecentMessages());
			ordered.sort(Comparator.comparingLong(Conversation.ContextMessage::tick));
			target.getRecentMessages().clear();
			target.getRecentMessages().addAll(ordered);
		}
		return new UtteranceResult(target, Set.copyOf(joined));
	}

	public void reconcileParticipants(Map<UUID, Location> positions) {
		for (Conversation original : new ArrayList<>(conversations.values())) {
			Set<UUID> remaining = new LinkedHashSet<>(original.getParticipants());
			remaining.retainAll(positions.keySet());
			List<Set<UUID>> components = new ArrayList<>();
			while (!remaining.isEmpty()) {
				UUID first = remaining.iterator().next();
				remaining.remove(first);
				Set<UUID> component = new LinkedHashSet<>();
				ArrayDeque<UUID> pending = new ArrayDeque<>();
				pending.add(first);
				while (!pending.isEmpty()) {
					UUID next = pending.removeFirst();
					component.add(next);
					for (UUID candidate : new ArrayList<>(remaining)) {
						if (positions.get(next).within(positions.get(candidate), conversationRange.getAsDouble())) {
							remaining.remove(candidate);
							pending.addLast(candidate);
						}
					}
				}
				components.add(component);
			}
			if (components.size() == 1 && components.getFirst().equals(original.getParticipants())) continue;
			conversations.remove(original.getId());
			changedRosters.remove(original.getId());
			for (UUID id : original.getParticipants()) playerToConversation.remove(id);
			for (int i = 0; i < components.size(); i++) {
				Set<UUID> members = components.get(i);
				Conversation split = new Conversation(i == 0 ? original.getId() : UUID.randomUUID(),
						original.getLastActivityTick());
				split.getParticipants().addAll(members);
				for (Conversation.ContextMessage message : original.getRecentMessages()) {
					if (members.contains(message.speakerId())) split.getRecentMessages().addLast(message);
				}
				conversations.put(split.getId(), split);
				for (UUID id : members) playerToConversation.put(id, split.getId());
				changedRosters.add(split.getId());
			}
		}
	}

	public void removeParticipant(UUID playerId) {
		UUID id = playerToConversation.remove(playerId);
		Conversation conversation = conversations.get(id);
		if (conversation == null) return;
		conversation.removeParticipant(playerId);
		if (conversation.isEmpty()) {
			conversations.remove(id);
			changedRosters.remove(id);
		} else {
			changedRosters.add(id);
		}
	}

	public Conversation getConversationFor(UUID playerId) {
		return conversations.get(playerToConversation.get(playerId));
	}

	public List<Conversation> consumeRosterChanges() {
		List<Conversation> changed = new ArrayList<>();
		for (UUID id : changedRosters) {
			Conversation conversation = conversations.get(id);
			if (conversation != null) changed.add(conversation);
		}
		changedRosters.clear();
		return changed;
	}

	public List<Conversation> snapshotConversations() { return List.copyOf(conversations.values()); }

	public void tick(MinecraftServer server) {
		// 发言前还会立即重算；后台每秒处理沉默期间的移动，避免每 tick
		// 对静止的大组重复进行平方级距离计算。
		if (server.getTicks() % 20 == 0) reconcileParticipants(snapshotLocations(server));
		expire(server.getTicks());
	}

	void expire(long currentTick) {
		for (Conversation conversation : new ArrayList<>(conversations.values())) {
			if (currentTick - conversation.getLastActivityTick() <= timeoutSeconds.getAsInt() * 20L) continue;
			conversations.remove(conversation.getId());
			changedRosters.remove(conversation.getId());
			for (UUID id : conversation.getParticipants()) playerToConversation.remove(id);
		}
	}

	public void clear() {
		conversations.clear();
		playerToConversation.clear();
		changedRosters.clear();
	}

	public int getActiveConversationCount() { return conversations.size(); }
	Map<UUID, Conversation> debugSnapshot() { return new HashMap<>(conversations); }
}
