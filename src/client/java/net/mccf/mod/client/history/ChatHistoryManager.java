package net.mccf.mod.client.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端本地聊天历史记录：内存环形缓冲区 + 持久化到磁盘文件。
 *
 * 1.1.6 起改为持久化：历史记录存到 {@code config/mccf/chat-history.json}，
 * 重进游戏/重连服务器后保留。之前的"不落盘"设计是为了避免隐私/多服务器归属
 * 问题，但用户明确要求"重进游戏不丢失"——落盘的收益（跨会话回看）大于成本。
 *
 * 为什么参与者名单也要落盘：{@link ConversationRosterManager} 的数据是服务端
 * 下发的，断开连接后会被清空。如果不落盘参与者名单，重新加载历史记录时
 * 分组标题（"XX、YY 的对话"）就拿不到参与者名字，退化成"未知对话"。所以
 * 在 record 时把当时的参与者名单快照存到 {@link #PARTICIPANTS_CACHE}，
 * 落盘时一起序列化。
 *
 * 为什么用固定容量环形缓冲而不是无限增长的 List：长时间挂机的服务器聊天量可能
 * 很大，无限增长会造成内存泄漏和磁盘文件膨胀；固定 500 条覆盖"最近发生了什么"
 * 的实际需求，超出后自动丢弃最旧的一条。聊天消息和系统事件分别维护各自的
 * 环形缓冲区（各 500 条上限），互不挤占彼此的容量。
 *
 * 线程安全：SubtitlePayload 接收器和 ClientOnlyChatTranslator 的回调都是通过
 * {@code client.execute(...)} 切回客户端主线程后才调用 record，因此这里不需要
 * 额外加锁；但为了防御未来有人在非主线程误调用，仍用 synchronized 包一层，
 * 代价可忽略（历史记录写入频率远低于每 tick 级别）。
 *
 * 分组渲染支持（应用户要求，参照服务端 Conversation 分组机制）：
 * {@link #groupedSnapshot()} 把所有消息/事件按 conversationId 分组、组内按
 * 时间排序，包装成 {@link ConversationGroup} 列表返回，直接供
 * {@code ChatHistoryScreen} 渲染——渲染代码不需要自己再做分组/排序，
 * 只管遍历这个已经整理好的结构。conversationId 为 null 的消息（纯客户端
 * 模式下的 CLIENT_ONLY 消息，没有服务端 Conversation 概念）各自单独成组
 * （用消息自身的一个合成 key，保证不会被错误地跟别的无归属消息混在一起）。
 */
public final class ChatHistoryManager {

	private static final int MAX_ENTRIES = 500;

	private static final Deque<ChatHistoryEntry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);
	private static final Deque<ChatHistorySystemEvent> SYSTEM_EVENTS = new ArrayDeque<>(MAX_ENTRIES);

	/**
	 * conversationId → 参与者显示名列表的缓存。在 record 时从
	 * {@link ConversationRosterManager} 查询并快照存入，落盘时一起序列化。
	 * 重新加载历史记录时从文件恢复，groupedSnapshot() 优先从这里取名单
	 * 而非实时查 ConversationRosterManager（后者在断开连接后是空的）。
	 */
	private static final Map<UUID, List<String>> PARTICIPANTS_CACHE = new LinkedHashMap<>();

	/** 持久化文件路径。 */
	private static final Path HISTORY_FILE = Paths.get("config", "mccf", "chat-history.json");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 持久化 JSON 的顶层结构。 */
	private record PersistData(
			List<ChatHistoryEntry> entries,
			List<ChatHistorySystemEvent> systemEvents,
			Map<UUID, List<String>> participants
	) {}

	private ChatHistoryManager() {}

	public static synchronized void record(ChatHistoryEntry entry) {
		if (ENTRIES.size() >= MAX_ENTRIES) {
			ENTRIES.removeFirst();
		}
		ENTRIES.addLast(entry);

		// 如果这条消息有 conversationId，把当前的参与者名单快照存到缓存里。
		// 这样断开连接后 ConversationRosterManager 被清空，重新加载历史时
		// 仍然能从 PARTICIPANTS_CACHE 取到分组标题需要的参与者名单。
		if (entry.conversationId() != null) {
			List<String> names = ConversationRosterManager.get(entry.conversationId()).stream()
					.map(ConversationRosterManager.RosterEntry::displayName)
					.toList();
			if (!names.isEmpty()) {
				PARTICIPANTS_CACHE.put(entry.conversationId(), names);
			}
		}

		saveToFile();
	}

	public static synchronized void recordSystemEvent(ChatHistorySystemEvent event) {
		if (SYSTEM_EVENTS.size() >= MAX_ENTRIES) {
			SYSTEM_EVENTS.removeFirst();
		}
		SYSTEM_EVENTS.addLast(event);
		saveToFile();
	}

	/** 按时间正序返回当前所有聊天消息的快照（不含系统事件；供不需要分组的旧调用方使用）。 */
	public static synchronized List<ChatHistoryEntry> snapshot() {
		return new ArrayList<>(ENTRIES);
	}

	/**
	 * 一个对话分组块，对应服务端的一个 Conversation（或者一条无归属的
	 * CLIENT_ONLY 消息单独成组）——{@link ChatHistoryScreen} 按这个结构渲染
	 * 大标题（参与者名单）+ 时间线（消息与系统提示混排，按时间正序）。
	 *
	 * @param conversationId  分组依据；无归属消息时为 null
	 * @param participantNames 这个分组当前已知的参与者显示名（来自
	 *                         {@link ConversationRosterManager}，无归属消息
	 *                         时为空列表——没有服务端名单可用）
	 * @param items           这个分组内的所有消息/系统事件，按时间正序排列
	 * @param groupStartMillis 这个分组里最早一条记录的时间戳，用于分组之间
	 *                         按时间先后整体排序（最新的分组排在最前面，
	 *                         用组内最后一条的时间比较，见 groupedSnapshot 实现）
	 */
	public record ConversationGroup(
			UUID conversationId,
			List<String> participantNames,
			List<ChatTimelineItem> items,
			long groupStartMillis
	) {
		/** 这个分组里 ChatHistoryEntry（不含系统事件）的条数，供按消息条数排序使用。 */
		public long messageCount() {
			return items.stream().filter(i -> i instanceof ChatTimelineItem.Message).count();
		}

		/** 组内最后一条记录（消息或系统事件）的时间戳，供按时间排序使用。 */
		public long lastTimestampMillis() {
			return items.get(items.size() - 1).timestampMillis();
		}
	}

	/**
	 * 聊天历史筛选条件——三个维度可以同时组合使用（AND 关系）。任意一个字段为
	 * null 或空，表示该维度不参与筛选（即"不限"）。
	 *
	 * @param allowedSources      允许的消息来源集合；null 或空集合表示不限（全部来源都算）
	 * @param participantFilter   参与者显示名（精确匹配某个玩家名字）；null 或空字符串表示不限
	 * @param keyword             关键词，匹配消息的原文或译文（包含即可，大小写不敏感）；
	 *                             null 或空字符串表示不限
	 */
	public record FilterOptions(
			java.util.Set<ChatHistoryEntry.Source> allowedSources,
			String participantFilter,
			String keyword
	) {
		/** 不筛选任何维度的默认选项——等价于展示全部历史记录。 */
		public static FilterOptions none() {
			return new FilterOptions(null, null, null);
		}

		public boolean hasSourceFilter() {
			return allowedSources != null && !allowedSources.isEmpty();
		}

		public boolean hasParticipantFilter() {
			return participantFilter != null && !participantFilter.isBlank();
		}

		public boolean hasKeywordFilter() {
			return keyword != null && !keyword.isBlank();
		}

		public boolean isEmpty() {
			return !hasSourceFilter() && !hasParticipantFilter() && !hasKeywordFilter();
		}
	}

	/** 对话分组的排序方式。 */
	public enum SortMode {
		/** 按组内最后一条记录的时间倒序——最近有新消息的对话排最前面（默认行为）。 */
		TIME_DESC,
		/** 按组内最早一条记录的时间正序——最早开始的对话排最前面。 */
		TIME_ASC,
		/** 按参与人数从多到少；人数相同时退回 TIME_DESC 作为次要排序键，保证结果稳定。 */
		PARTICIPANT_COUNT_DESC,
		/** 按消息条数（不含系统事件）从多到少；条数相同时退回 TIME_DESC 作为次要排序键。 */
		MESSAGE_COUNT_DESC
	}

	/**
	 * 判断某个对话分组是否满足给定的筛选条件。
	 *
	 * 筛选粒度是"按对话分组"而不是"按单条消息"：只要这个分组里**有任意一条
	 * {@link ChatHistoryEntry} 消息**同时满足"来源在允许范围内 AND （未设置
	 * 参与者筛选，或该分组参与者名单包含指定玩家）AND （未设置关键词，或该
	 * 消息原文/译文命中关键词）"，就认为整个分组通过筛选——通过后分组里的
	 * 所有消息和系统提示都会被完整展示，不会只保留组内满足条件的那部分消息。
	 * 这样处理是因为"只隐藏组内不符合的单条消息、组标题还在"容易让人看不懂
	 * 为什么某条消息突然消失了，按对话分组展示更符合"看对话"而非"看碎片消息"
	 * 的使用场景。
	 *
	 * 参与者筛选对没有服务端参与者名单的分组（CLIENT_ONLY 无归属消息，
	 * {@code participantNames} 为空列表）恒不通过——这类消息没有"对话参与者"
	 * 概念，选中了参与者筛选就意味着用户想看"某人参与的对话"，这类分组不
	 * 符合，应该被排除。
	 */
	private static boolean matchesFilter(ConversationGroup group, FilterOptions filter) {
		if (filter == null || filter.isEmpty()) return true;

		if (filter.hasParticipantFilter() && !group.participantNames().contains(filter.participantFilter())) {
			return false;
		}

		// 走到这里说明参与者维度已经通过（或未设置）；接下来检查是否存在
		// 至少一条消息同时满足来源和关键词维度。
		String keywordLower = filter.hasKeywordFilter() ? filter.keyword().toLowerCase(java.util.Locale.ROOT) : null;
		for (ChatTimelineItem item : group.items()) {
			if (!(item instanceof ChatTimelineItem.Message m)) continue;
			ChatHistoryEntry entry = m.entry();

			if (filter.hasSourceFilter() && !filter.allowedSources().contains(entry.source())) continue;

			if (keywordLower != null) {
				String original = entry.originalText() == null ? "" : entry.originalText().toLowerCase(java.util.Locale.ROOT);
				String translated = entry.translatedText() == null ? "" : entry.translatedText().toLowerCase(java.util.Locale.ROOT);
				if (!original.contains(keywordLower) && !translated.contains(keywordLower)) continue;
			}

			// 这条消息同时满足来源和关键词维度（参与者维度已经在分组级别检查过）。
			return true;
		}
		return false;
	}

	/**
	 * 把所有聊天消息 + 系统事件按 conversationId 分组、组内按时间正序排列，
	 * 按 {@code filter} 过滤分组、按 {@code sortMode} 排序后返回。
	 *
	 * @param filter   筛选条件，传 {@link FilterOptions#none()} 或 null 表示不筛选
	 * @param sortMode 排序方式，传 null 时按 {@link SortMode#TIME_DESC} 处理
	 */
	public static synchronized List<ConversationGroup> groupedSnapshot(FilterOptions filter, SortMode sortMode) {
		SortMode effectiveSort = sortMode == null ? SortMode.TIME_DESC : sortMode;

		// key：真实 conversationId，或者对于无归属消息，用一个基于该消息自身
		// 身份（speakerId + timestampMillis 组合）合成的字符串 key，保证每条
		// 无归属消息各自独立成组，不会被意外聚在一起（它们之间没有任何关联性，
		// 混在一起显示反而误导，参照 CLIENT_ONLY 消息本来就没有"对话"概念）。
		Map<Object, List<ChatTimelineItem>> byKey = new LinkedHashMap<>();

		for (ChatHistoryEntry entry : ENTRIES) {
			Object key = entry.conversationId() != null
					? entry.conversationId()
					: "solo:" + entry.speakerId() + ":" + entry.timestampMillis();
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new ChatTimelineItem.Message(entry));
		}
		for (ChatHistorySystemEvent event : SYSTEM_EVENTS) {
			// 系统事件恒有 conversationId（见 ChatHistorySystemEvent 类文档），
			// 不会走到无归属分支。
			byKey.computeIfAbsent(event.conversationId(), k -> new ArrayList<>())
					.add(new ChatTimelineItem.SystemEvent(event));
		}

		List<ConversationGroup> groups = new ArrayList<>();
		for (Map.Entry<Object, List<ChatTimelineItem>> e : byKey.entrySet()) {
			List<ChatTimelineItem> items = e.getValue();
			items.sort(java.util.Comparator.comparingLong(ChatTimelineItem::timestampMillis));

			UUID conversationId = e.getKey() instanceof UUID uuid ? uuid : null;
			// 优先从 PARTICIPANTS_CACHE 取名单（支持持久化后重新加载的场景）；
			// 如果缓存里没有（比如刚连上服务器、缓存还没更新），实时查
			// ConversationRosterManager 作为兜底。
			List<String> participantNames = List.of();
			if (conversationId != null) {
				List<String> cached = PARTICIPANTS_CACHE.get(conversationId);
				if (cached != null && !cached.isEmpty()) {
					participantNames = cached;
				} else {
					participantNames = ConversationRosterManager.get(conversationId).stream()
							.map(ConversationRosterManager.RosterEntry::displayName)
							.toList();
				}
			}

			long groupStart = items.get(0).timestampMillis();
			groups.add(new ConversationGroup(conversationId, participantNames, items, groupStart));
		}

		if (filter != null && !filter.isEmpty()) {
			groups.removeIf(g -> !matchesFilter(g, filter));
		}

		switch (effectiveSort) {
			case TIME_ASC -> groups.sort(java.util.Comparator.comparingLong(ConversationGroup::groupStartMillis));
			case PARTICIPANT_COUNT_DESC -> groups.sort(
					java.util.Comparator.<ConversationGroup>comparingInt(g -> g.participantNames().isEmpty() ? 1 : g.participantNames().size())
							.reversed()
							.thenComparing(java.util.Comparator.comparingLong(ConversationGroup::lastTimestampMillis).reversed()));
			case MESSAGE_COUNT_DESC -> groups.sort(
					java.util.Comparator.<ConversationGroup>comparingLong(ConversationGroup::messageCount)
							.reversed()
							.thenComparing(java.util.Comparator.comparingLong(ConversationGroup::lastTimestampMillis).reversed()));
			case TIME_DESC -> groups.sort(java.util.Comparator.comparingLong(ConversationGroup::lastTimestampMillis).reversed());
		}

		return groups;
	}

	/** 无参默认版本：不筛选，按时间倒序——等价于本项目引入筛选/排序之前的原有行为。 */
	public static synchronized List<ConversationGroup> groupedSnapshot() {
		return groupedSnapshot(FilterOptions.none(), SortMode.TIME_DESC);
	}

	/**
	 * 收集当前历史记录里所有出现过的说话者显示名（去重，按首次出现顺序），
	 * 供参与者筛选下拉框使用。过滤掉空白名字（CLIENT_ONLY 来源的消息
	 * {@code speakerName()} 可能是空字符串，见 ChatHistoryEntry 相关处理）。
	 */
	public static synchronized List<String> knownSpeakerNames() {
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		for (ChatHistoryEntry entry : ENTRIES) {
			String name = entry.speakerName();
			if (name != null && !name.isBlank()) {
				names.add(name);
			}
		}
		return new ArrayList<>(names);
	}

	/**
	 * 清空所有历史记录并删除持久化文件。
	 *
	 * 1.1.6 起 clear() 只在玩家手动点"清空历史"按钮时调用——不再在断开连接时
	 * 自动清空（因为现在要持久化了）。断开连接时只清 SubtitleManager 和
	 * ConversationRosterManager 的内存状态，历史记录保留到文件里供下次加载。
	 */
	public static synchronized void clear() {
		ENTRIES.clear();
		SYSTEM_EVENTS.clear();
		PARTICIPANTS_CACHE.clear();
		deleteFile();
	}

	/**
	 * 把当前内存里的所有历史记录序列化到 {@code config/mccf/chat-history.json}。
	 * 每次 record/recordSystemEvent 后调用——聊天消息频率低，写一个小 JSON 文件
	 * 的开销可忽略。失败时静默忽略——落盘失败不影响内存功能，下次 record 会重试。
	 */
	public static synchronized void saveToFile() {
		try {
			Files.createDirectories(HISTORY_FILE.getParent());
			PersistData data = new PersistData(
					new ArrayList<>(ENTRIES),
					new ArrayList<>(SYSTEM_EVENTS),
					new LinkedHashMap<>(PARTICIPANTS_CACHE)
			);
			Files.writeString(HISTORY_FILE, GSON.toJson(data));
		} catch (Exception e) {
			// 静默忽略——落盘失败不影响内存功能
		}
	}

	/**
	 * 从 {@code config/mccf/chat-history.json} 加载历史记录到内存。
	 * 在客户端初始化时调用一次。失败时静默忽略——加载失败从空历史开始不影响功能。
	 */
	public static synchronized void loadFromFile() {
		try {
			if (!Files.exists(HISTORY_FILE)) return;
			String json = Files.readString(HISTORY_FILE);
			PersistData data = GSON.fromJson(json, PersistData.class);
			if (data == null) return;

			ENTRIES.clear();
			SYSTEM_EVENTS.clear();
			PARTICIPANTS_CACHE.clear();

			if (data.entries() != null) {
				for (ChatHistoryEntry e : data.entries()) {
					if (e == null) continue;
					if (ENTRIES.size() >= MAX_ENTRIES) break;
					ENTRIES.addLast(e);
				}
			}
			if (data.systemEvents() != null) {
				for (ChatHistorySystemEvent e : data.systemEvents()) {
					if (e == null) continue;
					if (SYSTEM_EVENTS.size() >= MAX_ENTRIES) break;
					SYSTEM_EVENTS.addLast(e);
				}
			}
			if (data.participants() != null) {
				PARTICIPANTS_CACHE.putAll(data.participants());
			}
		} catch (Exception e) {
			// 静默忽略——加载失败从空历史开始不影响功能
		}
	}

	/** 删除持久化文件——在 clear() 时调用。失败时静默忽略。 */
	private static synchronized void deleteFile() {
		try {
			Files.deleteIfExists(HISTORY_FILE);
		} catch (Exception e) {
			// 静默忽略
		}
	}
}
