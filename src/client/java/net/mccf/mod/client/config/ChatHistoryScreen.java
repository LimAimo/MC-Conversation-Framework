package net.mccf.mod.client.config;

import net.mccf.mod.client.history.ChatHistoryEntry;
import net.mccf.mod.client.history.ChatHistoryManager;
import net.mccf.mod.client.history.ChatHistorySystemEvent;
import net.mccf.mod.client.history.ChatTimelineItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天历史记录界面：展示 {@link ChatHistoryManager} 记录下的所有消息，
 * 按服务端 Conversation 分组展示（对应服务端 {@code net.mccf.mod.context.Conversation}
 * 的对话合并/拆分机制，应用户明确要求"搬一下多人上下文的那个管理机制"）。
 *
 * 存在的意义：字幕会自动淡出（AUDIBLE 2.5~8 秒、VISIBLE 也是临时消息），玩家
 * 稍微一走神就会错过内容；这个界面让玩家能随时回溯"刚才这段时间发生了什么对话"，
 * 不需要争分夺秒地在字幕消失前读完。
 *
 * 入口：Esc 暂停菜单的"聊天历史记录"按钮（{@code GameMenuScreenMixin} 注入），
 * 或独立按键绑定（见 {@code MCCFClient} 的 openHistoryKey）——两者都不受 op
 * 权限限制，因为历史记录是纯本地展示数据，不涉及任何服务端配置。
 *
 * 1.1.6 新增功能：
 * - 历史记录持久化到 {@code config/mccf/chat-history.json}，重进游戏/重连服务器后保留
 * - 分组标题显示时间范围（如"XX 的对话 14:23-14:31"）
 * - 右键消息行复制译文（或原文）到剪贴板
 * - 导出当前筛选后的历史到 txt 文件，自动打开导出文件夹
 * - "跳转最新"按钮快速滚动到列表底部
 * - "清空"按钮（带确认弹窗）清除所有历史记录和持久化文件
 * - 长消息被截断时 hover 显示完整内容 tooltip
 *
 * 只读界面：没有编辑/删除单条记录的功能（不是聊天工具，是"回看"工具）。
 *
 * 分组方式（{@link ChatHistoryManager#groupedSnapshot(ChatHistoryManager.FilterOptions,
 * ChatHistoryManager.SortMode)} 已经整理好）：
 * - 大标题：这个 Conversation 里出现过的所有人名（"LimAimo、test、Alex 的对话"）+ 时间范围——
 *   用 {@code ConversationRosterManager} 记录的、服务端下发的权威参与者名单，
 *   而不是客户端自己猜。
 * - 组内混排：消息（原文+译文+语言标签）和系统提示（"开始了一段新对话"/
 *   "XX 加入了对话"）按时间正序穿插展示。
 * - 无归属消息（纯客户端模式的 CLIENT_ONLY，没有服务端 Conversation 概念）
 *   各自单独成组，不与其他消息混在一起。
 *
 * 筛选 + 排序（应用户要求新增）：标题栏最右边有一个小按钮，点击展开/收起一个
 * 筛选/排序面板——平时收起不占用列表可用高度，展开时才显示完整控件。
 */
public class ChatHistoryScreen extends Screen {

	private final Screen parent;
	private HistoryListWidget listWidget;
	private boolean isEmpty;

	/** 展示用的时间格式：只显示时:分:秒，历史记录本身就限定在"最近一段"，日期没有意义。 */
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/** 分组标题里的时间范围格式：只精确到分钟，秒级精度在分组标题里没有意义。 */
	private static final DateTimeFormatter RANGE_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	/** 导出文件名用的时间戳格式。 */
	private static final DateTimeFormatter EXPORT_TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	/**
	 * 每条记录的行高（像素）。
	 *
	 * 1.1.6 改为双行显示后需要容纳两行文字：每行 9px × 2 = 18px + 上下各 1px padding = 20px。
	 * 1.21.1 的 EntryListWidget itemHeight 是列表级的（不支持每条目自定义高度，
	 * 那是 1.21.8 才引入的），所以 GroupTitleWidget 和 SystemEventWidget 也会
	 * 是 20px——它们在 20px 里居中单行文字即可，不影响显示效果。
	 */
	private static final int ITEM_HEIGHT = 20;

	/**
	 * 筛选/排序面板展开时的高度。
	 * 3 行控件：行1（来源+重置，16px）+ 2px 间隙 + 行2（参与者+排序，16px）+ 2px 间隙
	 * + 行3（关键词+显示译文，16px）+ 8px 底部间隙 = 60px。
	 * 之前是 104px 导致行3下方有大片空白。
	 */
	private static final int FILTER_PANEL_HEIGHT = 60;

	/** 面板是否展开——默认收起，不影响原有"打开即看列表"的体验。 */
	private boolean filterPanelExpanded = false;

	/** 当前选中的来源筛选（空集合 = 不筛选/全选）。 */
	private final Set<ChatHistoryEntry.Source> selectedSources = EnumSet.noneOf(ChatHistoryEntry.Source.class);
	/** 当前选中的参与者筛选；"" 表示"全部"（不筛选）。 */
	private String selectedParticipant = "";
	private String keywordInput = "";
	private ChatHistoryManager.SortMode sortMode = ChatHistoryManager.SortMode.TIME_DESC;
	private boolean showTranslated = true;

	/** 当前被截断的消息行的完整文字（供 render 里画 tooltip 用）。 */
	private String hoveredTruncatedText = null;

	private ButtonWidget filterToggleButton;
	private ButtonWidget sourceSelfButton;
	private ButtonWidget sourceVisibleButton;
	private ButtonWidget sourceAudibleButton;
	private ButtonWidget sourceClientOnlyButton;
	private CyclingButtonWidget<String> participantButton;
	private TextFieldWidget keywordField;
	private CyclingButtonWidget<ChatHistoryManager.SortMode> sortButton;
	private CyclingButtonWidget<Boolean> showTranslatedButton;
	private ButtonWidget exportButton;
	private ButtonWidget clearButton;
	private ButtonWidget resetFilterButton;
	private ButtonWidget jumpLatestButton;

	public ChatHistoryScreen(Screen parent) {
		super(Text.translatable("mccf.history.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int toggleWidth = 60;
		filterToggleButton = addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.filter.button"), button -> toggleFilterPanel())
				.dimensions(this.width - toggleWidth - 8, 6, toggleWidth, 16)
				.build());

		buildFilterPanelWidgets();
		rebuildList();

		// 底部按钮栏布局：[导出] [清空]   [关闭]   [↓最新]
		// 左下角两个操作按钮，关闭居中，跳转最新在右下角。
		// 重置按钮移到了筛选面板第一行（和来源切换同一行），不在这里。
		int bottomY = this.height - 28;
		int smallBtnWidth = 60;

		exportButton = addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.export"), button -> exportHistory())
				.dimensions(8, bottomY, smallBtnWidth, 20)
				.build());

		clearButton = addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.clear"), button -> showClearConfirm())
				.dimensions(8 + smallBtnWidth + 4, bottomY, smallBtnWidth, 20)
				.build());

		addDrawableChild(ButtonWidget.builder(Text.translatable("mccf.history.close"), button -> close())
				.dimensions(this.width / 2 - 60, bottomY, 120, 20)
				.build());

		jumpLatestButton = addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.jump_latest"), button -> jumpToLatest())
				.dimensions(this.width - smallBtnWidth - 8, bottomY, smallBtnWidth, 20)
				.build());
	}

	private void buildFilterPanelWidgets() {
		int panelTop = 26;
		int rowHeight = 18;
		int col1 = 8;
		int fieldHeight = 16;

		int sourceY = panelTop;
		int sourceX = col1;
		sourceSelfButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.SELF,
				"mccf.history.source.self", sourceX, sourceY));
		sourceX += sourceSelfButton.getWidth() + 4;
		sourceVisibleButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.VISIBLE,
				"mccf.history.source.visible", sourceX, sourceY));
		sourceX += sourceVisibleButton.getWidth() + 4;
		sourceAudibleButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.AUDIBLE,
				"mccf.history.source.audible", sourceX, sourceY));
		sourceX += sourceAudibleButton.getWidth() + 4;
		sourceClientOnlyButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.CLIENT_ONLY,
				"mccf.history.source.client_only", sourceX, sourceY));

		// 1.1.6：重置筛选按钮放在来源切换同一行的最右边——它和筛选条件逻辑相关，
		// 放在筛选面板里比放底部按钮栏更合理。右对齐到面板右侧。
		sourceX += sourceClientOnlyButton.getWidth() + 4;
		int resetWidth = 40;
		resetFilterButton = addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.reset"), button -> resetFilters())
				.dimensions(this.width - resetWidth - 8, sourceY, resetWidth, 16)
				.build());

		int row2Y = panelTop + rowHeight;
		int halfWidth = (this.width - 16 - 8) / 2;

		List<String> participantOptions = new ArrayList<>();
		participantOptions.add("");
		participantOptions.addAll(ChatHistoryManager.knownSpeakerNames());
		participantButton = addDrawableChild(CyclingButtonWidget.<String>builder(name ->
						name.isEmpty() ? Text.translatable("mccf.history.filter.participant_all") : Text.literal(name))
				.values(participantOptions)
				.initially(participantOptions.contains(selectedParticipant) ? selectedParticipant : "")
				.build(col1, row2Y, halfWidth, fieldHeight,
						Text.translatable("mccf.history.filter.participant"),
						(button, value) -> { selectedParticipant = value; rebuildList(); }));

		sortButton = addDrawableChild(CyclingButtonWidget.<ChatHistoryManager.SortMode>builder(mode ->
						Text.translatable(sortModeKey(mode)))
				.values(List.of(ChatHistoryManager.SortMode.values()))
				.initially(sortMode)
				.build(col1 + halfWidth + 8, row2Y, halfWidth, fieldHeight,
						Text.translatable("mccf.history.sort.label"),
						(button, value) -> { sortMode = value; rebuildList(); }));

		int row3Y = row2Y + rowHeight;
		keywordField = addDrawableChild(new TextFieldWidget(
				MinecraftClient.getInstance().textRenderer, col1, row3Y, this.width - 16, fieldHeight,
				Text.translatable("mccf.history.filter.keyword")));
		keywordField.setMaxLength(64);
		keywordField.setPlaceholder(Text.translatable("mccf.history.filter.keyword"));
		keywordField.setText(keywordInput);

		int keywordWidth = (this.width - 16 - 8) / 2;
		keywordField.setWidth(keywordWidth);
		showTranslatedButton = addDrawableChild(CyclingButtonWidget.onOffBuilder(showTranslated)
				.build(col1 + keywordWidth + 8, row3Y, keywordWidth, fieldHeight,
						Text.translatable("mccf.history.filter.show_translated"),
						(button, value) -> { showTranslated = value; rebuildList(); }));
		// 1.1.6：关键词输入立即生效——每输入一个字符就触发 rebuildList。
		// 历史记录最多 500 条，重建开销可忽略。
		keywordField.setChangedListener(text -> { keywordInput = text; rebuildList(); });

		syncFilterPanelVisibility();
	}

	private ButtonWidget makeSourceToggle(ChatHistoryEntry.Source source, String labelKey, int x, int y) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String label = Text.translatable(labelKey).getString();
		int width = textRenderer.getWidth(label) + 20;
		return ButtonWidget.builder(sourceToggleText(source, labelKey), button -> {
					if (selectedSources.contains(source)) {
						selectedSources.remove(source);
					} else {
						selectedSources.add(source);
					}
					button.setMessage(sourceToggleText(source, labelKey));
					rebuildList();
				})
				.dimensions(x, y, width, 16)
				.build();
	}

	private Text sourceToggleText(ChatHistoryEntry.Source source, String labelKey) {
		String prefix = selectedSources.contains(source) ? "✓ " : "";
		return Text.literal(prefix).append(Text.translatable(labelKey));
	}

	private String sortModeKey(ChatHistoryManager.SortMode mode) {
		return switch (mode) {
			case TIME_DESC -> "mccf.history.sort.time_desc";
			case TIME_ASC -> "mccf.history.sort.time_asc";
			case PARTICIPANT_COUNT_DESC -> "mccf.history.sort.participant_count";
			case MESSAGE_COUNT_DESC -> "mccf.history.sort.message_count";
		};
	}

	private void toggleFilterPanel() {
		filterPanelExpanded = !filterPanelExpanded;
		syncFilterPanelVisibility();
		rebuildList();
	}

	private void syncFilterPanelVisibility() {
		boolean v = filterPanelExpanded;
		if (sourceSelfButton != null) sourceSelfButton.visible = v;
		if (sourceVisibleButton != null) sourceVisibleButton.visible = v;
		if (sourceAudibleButton != null) sourceAudibleButton.visible = v;
		if (sourceClientOnlyButton != null) sourceClientOnlyButton.visible = v;
		if (resetFilterButton != null) resetFilterButton.visible = v;
		if (participantButton != null) participantButton.visible = v;
		if (keywordField != null) keywordField.visible = v;
		if (sortButton != null) sortButton.visible = v;
		if (showTranslatedButton != null) showTranslatedButton.visible = v;
	}

	private void rebuildList() {
		ChatHistoryManager.FilterOptions filter = new ChatHistoryManager.FilterOptions(
				selectedSources.isEmpty() ? null : EnumSet.copyOf(selectedSources),
				selectedParticipant.isEmpty() ? null : selectedParticipant,
				keywordInput.isEmpty() ? null : keywordInput);

		if (listWidget != null) {
			remove(listWidget);
		}

		int listTop = filterPanelExpanded ? 26 + FILTER_PANEL_HEIGHT : 32;
		listWidget = new HistoryListWidget(MinecraftClient.getInstance(),
				this.width, listTop, this.height - 40, ITEM_HEIGHT);

		List<ChatHistoryManager.ConversationGroup> groups =
				ChatHistoryManager.groupedSnapshot(filter, sortMode);
		isEmpty = groups.isEmpty();

		for (ChatHistoryManager.ConversationGroup group : groups) {
			listWidget.addEntry(new GroupTitleWidget(group));

			// 组内消息按时间正序显示（从早到晚）——groupedSnapshot 返回的 items
			// 已经按 timestampMillis 正序排列，直接遍历即可。之前这里有个
			// Collections.reverse() 把组内顺序反过来变成从晚到早，阅读体验差
			// （从上往下读应该是从早到晚的时间线，像聊天软件一样）。
			for (ChatTimelineItem item : group.items()) {
				if (item instanceof ChatTimelineItem.Message m) {
					listWidget.addEntry(new HistoryEntryWidget(m.entry(), showTranslated));
				} else if (item instanceof ChatTimelineItem.SystemEvent s) {
					listWidget.addEntry(new SystemEventWidget(s.event()));
				}
			}
		}
		addSelectableChild(listWidget);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		listWidget.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 12, Colors.WHITE);

		if (isEmpty) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.history.empty"),
					this.width / 2, this.height / 2, Colors.LIGHT_GRAY);
		}

		// 1.1.6：长消息被截断时 hover 显示完整内容 tooltip。
		// 手动按 maxWidth 拆行，避免超长消息的 tooltip 超出屏幕边界。
		if (hoveredTruncatedText != null) {
			int tooltipMaxWidth = Math.min(this.width - 40, 600);
			List<Text> lines = wrapText(hoveredTruncatedText, tooltipMaxWidth);
			context.drawTooltip(textRenderer, lines, mouseX, mouseY);
			hoveredTruncatedText = null;
		}

		// 导出/清空/跳转最新按钮在列表为空时禁用
		boolean hasContent = !isEmpty;
		exportButton.active = hasContent;
		clearButton.active = hasContent;
		jumpLatestButton.active = hasContent;

		// 1.1.6：重置按钮只有当前有非默认筛选条件时才可点击
		boolean hasActiveFilter = !selectedSources.isEmpty() || !selectedParticipant.isEmpty()
				|| !keywordInput.isEmpty()
				|| sortMode != ChatHistoryManager.SortMode.TIME_DESC || !showTranslated;
		resetFilterButton.active = hasActiveFilter;
	}

	/**
	 * 把长文本按 maxWidth 拆成多行 Text，用于 tooltip 显示——1.21.1 的
	 * {@code drawTooltip(Text)} 不自动换行，超长消息会导致 tooltip 超出屏幕边界。
	 * 按空格拆词，逐词累加直到超过 maxWidth 就换行。
	 */
	private List<Text> wrapText(String text, int maxWidth) {
		List<Text> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ", -1)) {
			if (current.length() > 0) {
				String test = current + " " + word;
				if (textRenderer.getWidth(test) > maxWidth) {
					lines.add(Text.literal(current.toString()));
					current = new StringBuilder(word);
				} else {
					current.append(" ").append(word);
				}
			} else {
				current.append(word);
			}
		}
		if (current.length() > 0) {
			lines.add(Text.literal(current.toString()));
		}
		return lines.isEmpty() ? List.of(Text.literal(text)) : lines;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// 1.1.6：关键词输入已经在 setChangedListener 里立即触发 rebuildList，
		// 不再需要回车确认。直接走 super 让 TextFieldWidget 处理输入。
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	/**
	 * 导出当前筛选后的历史记录到 txt 文件，导出完成后自动打开导出文件夹。
	 * 文件路径：{@code mccf-exports/chat-history-yyyyMMdd_HHmmss.txt}
	 * 用 {@link Util#getOperatingSystem()} 打开文件夹——跨平台兼容性比 AWT Desktop 好。
	 */
	private void exportHistory() {
		try {
			Path exportDir = Paths.get("mccf-exports");
			Files.createDirectories(exportDir);
			String timestamp = EXPORT_TIMESTAMP.format(LocalDateTime.now());
			Path file = exportDir.resolve("chat-history-" + timestamp + ".txt");

			ChatHistoryManager.FilterOptions filter = new ChatHistoryManager.FilterOptions(
					selectedSources.isEmpty() ? null : EnumSet.copyOf(selectedSources),
					selectedParticipant.isEmpty() ? null : selectedParticipant,
					keywordInput.isEmpty() ? null : keywordInput);
			List<ChatHistoryManager.ConversationGroup> groups =
					ChatHistoryManager.groupedSnapshot(filter, sortMode);

			StringBuilder sb = new StringBuilder();
			for (ChatHistoryManager.ConversationGroup group : groups) {
				String title = buildGroupTitle(group);
				long start = group.groupStartMillis();
				long end = group.lastTimestampMillis();
				sb.append("=== ").append(title).append(" (")
						.append(RANGE_FORMAT.format(Instant.ofEpochMilli(start))).append("-")
						.append(RANGE_FORMAT.format(Instant.ofEpochMilli(end))).append(") ===\n");

				// 组内消息按时间正序导出（从早到晚），和界面显示顺序一致。
				// 1.1.6：导出格式改为双行，和界面呈现一致——
				// 第一行 [时间] <说话者> 原文，第二行缩进 ⇄ 译文 [源语言→目标语言]。
				for (ChatTimelineItem item : group.items()) {
					if (item instanceof ChatTimelineItem.Message m) {
						ChatHistoryEntry e = m.entry();
						String name = e.speakerName() == null || e.speakerName().isBlank() ? "?" : e.speakerName();
						sb.append("[").append(TIME_FORMAT.format(Instant.ofEpochMilli(e.timestampMillis()))).append("] ");
						sb.append("<").append(name).append("> ").append(e.originalText()).append("\n");

						boolean hasTranslation = e.translatedText() != null
								&& !e.translatedText().isBlank()
								&& !e.translatedText().equals(e.originalText());
						if (showTranslated && hasTranslation) {
							sb.append("    ⇄ ").append(e.translatedText());
							boolean hasLangLabel = e.sourceLang() != null && e.targetLang() != null
									&& !e.sourceLang().equals(e.targetLang());
							if (hasLangLabel) {
								sb.append(" [").append(e.sourceLang()).append("→").append(e.targetLang()).append("]");
							}
							sb.append("\n");
						}
					} else if (item instanceof ChatTimelineItem.SystemEvent s) {
						sb.append("    --- ").append(buildSystemEventText(s.event())).append(" ---\n");
					}
				}
				sb.append("\n");
			}

			Files.writeString(file, sb.toString());
			Util.getOperatingSystem().open(exportDir.toUri());
		} catch (Exception e) {
			// 导出失败时静默忽略——玩家可以重试
		}
	}

	/** 构建分组标题文字（复用于界面渲染和导出）。 */
	private static String buildGroupTitle(ChatHistoryManager.ConversationGroup group) {
		if (group.conversationId() == null) {
			if (!group.items().isEmpty() && group.items().get(0) instanceof ChatTimelineItem.Message m) {
				String name = m.entry().speakerName();
				return (name == null || name.isBlank()) ? "?" : name;
			}
			return "?";
		}
		if (group.participantNames().isEmpty()) {
			return Text.translatable("mccf.history.unknown_conversation").getString();
		}
		String names = String.join(
				Text.translatable("mccf.history.name_separator").getString(), group.participantNames());
		return Text.translatable("mccf.history.conversation_title", names).getString();
	}

	private static String sourceLabel(ChatHistoryEntry.Source source) {
		return switch (source) {
			case SELF -> Text.translatable("mccf.history.source.self").getString();
			case VISIBLE -> Text.translatable("mccf.history.source.visible").getString();
			case AUDIBLE -> Text.translatable("mccf.history.source.audible").getString();
			case CLIENT_ONLY -> Text.translatable("mccf.history.source.client_only").getString();
		};
	}

	private static String buildSystemEventText(ChatHistorySystemEvent event) {
		if (event.type() == ChatHistorySystemEvent.Type.CONVERSATION_STARTED) {
			return Text.translatable("mccf.history.conversation_started").getString();
		}
		String names = String.join(
				Text.translatable("mccf.history.name_separator").getString(), event.involvedNames());
		return Text.translatable("mccf.history.participant_joined", names).getString();
	}

	/** 弹出确认弹窗，确认后清空所有历史记录 + 删除持久化文件。 */
	private void showClearConfirm() {
		ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
			if (confirmed) {
				ChatHistoryManager.clear();
				rebuildList();
			}
			if (client != null) {
				client.setScreen(this);
			}
		}, Text.translatable("mccf.history.clear.confirm_title"),
				Text.translatable("mccf.history.clear.confirm"));
		if (client != null) {
			client.setScreen(confirm);
		}
	}

	/**
	 * 跳转到最新消息——根据当前排序模式决定滚到顶部还是底部。
	 *
	 * TIME_DESC（默认）：最新对话排在最上面，滚到顶部（scrollAmount = 0）。
	 * TIME_ASC：最新对话排在最下面，滚到底部（scrollAmount = max）。
	 * PARTICIPANT_COUNT_DESC / MESSAGE_COUNT_DESC：不按时间排序，"最新"语义
	 * 不明确，默认滚到底部（大多数列表的"最新"在底部的惯例）。
	 *
	 * 1.21.1 的 API：{@code setScrollAmount(double)} + {@code getMaxScroll()}。
	 * 注意是 getMaxScroll() 不是 getMaxScrollY()（后者是 1.20.x 以前的命名，
	 * 1.21.1 改名了）；是 setScrollAmount() 不是 setScrollY()（同理）。
	 */
	private void jumpToLatest() {
		if (listWidget == null) return;
		if (sortMode == ChatHistoryManager.SortMode.TIME_DESC) {
			listWidget.setScrollAmount(0);
		} else {
			listWidget.setScrollAmount(listWidget.getMaxScroll());
		}
	}

	/**
	 * 1.1.6：重置所有筛选条件到默认值。
	 *
	 * 清空来源/参与者/关键词，排序恢复 TIME_DESC，显示译文恢复 true。
	 * 通过 clearChildren + init 重建所有控件，让按钮显示状态正确同步
	 * （source 按钮的 ✓ 前缀、participantButton 的"全部"、sortButton 的
	 * 默认选项、keywordField 的空文本都会在重建时从字段值读取）。
	 */
	private void resetFilters() {
		selectedSources.clear();
		selectedParticipant = "";
		keywordInput = "";
		sortMode = ChatHistoryManager.SortMode.TIME_DESC;
		showTranslated = true;
		this.clearChildren();
		this.init();
	}

	/**
	 * 列表 widget：复用 {@link AlwaysSelectedEntryListWidget} 的滚动条与裁剪实现。
	 *
	 * 1.21.1 构造函数签名：{@code EntryListWidget(MinecraftClient, int width, int height, int y, int itemHeight)}
	 * ——第 4 参数是列表顶部 y 坐标，第 5 参数是 itemHeight。
	 */
	private static class HistoryListWidget extends AlwaysSelectedEntryListWidget<HistoryListEntry> {
		HistoryListWidget(MinecraftClient client, int screenWidth, int top, int bottom, int itemHeight) {
			super(client, screenWidth, bottom - top, top, itemHeight);
		}

		@Override
		public int addEntry(HistoryListEntry entry) {
			return super.addEntry(entry);
		}

		/**
		 * 1.1.6：覆盖 mouseClicked 以支持右键复制。
		 *
		 * 1.21.1 的 EntryListWidget.mouseClicked 只传递左键（button == 0）到 entry
		 * 来处理选中逻辑——右键不会传递到 entry 的 mouseClicked，所以
		 * HistoryEntryWidget.mouseClicked 里的右键复制逻辑永远不会被调用。
		 *
		 * 这里先调 super 处理左键选择和滚动条，右键手动通过 mouseY 和 scrollAmount
		 * 计算鼠标落在哪个 entry 上（不依赖 getEntryAtPosition——该方法在 1.21.1
		 * Yarn mapping 中可能是 protected 且名字不确定），如果是 HistoryEntryWidget
		 * 就调用 copyToClipboard。
		 */
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (super.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
			if (button == 1 && this.isMouseOver(mouseX, mouseY)) {
				double relativeY = mouseY - this.getY() + this.getScrollAmount();
				// 1.21.1 的 EntryListWidget 用 itemHeight 字段而非 getItemHeight() 方法
				// （后者是 1.20.x 以前的命名）。itemHeight 是 protected 字段，子类可直接访问。
				int entryIndex = (int) (relativeY / this.itemHeight);
				if (entryIndex >= 0 && entryIndex < this.children().size()) {
					HistoryListEntry entry = this.children().get(entryIndex);
					if (entry instanceof HistoryEntryWidget hew) {
						hew.copyToClipboard();
						return true;
					}
				}
			}
			return false;
		}
	}

	private abstract static class HistoryListEntry extends AlwaysSelectedEntryListWidget.Entry<HistoryListEntry> {
	}

	/**
	 * 对话分组大标题：列出这个 Conversation 里出现过的所有参与者显示名 + 时间范围
	 * （"LimAimo、test、Alex 的对话 14:23-14:31"）。
	 *
	 * 1.1.6 新增时间范围显示：取组内第一条和最后一条记录的时间戳，格式化为 HH:mm-HH:mm。
	 * 用分钟级精度而非秒级——分组标题是给玩家一个"这段对话大概在什么时间段"的锚点，
	 * 秒级精度在标题里太细碎没有意义。
	 */
	private static class GroupTitleWidget extends HistoryListEntry {
		private final ChatHistoryManager.ConversationGroup group;

		GroupTitleWidget(ChatHistoryManager.ConversationGroup group) {
			this.group = group;
		}

		@Override
		public Text getNarration() {
			return Text.literal(buildTitle());
		}

		private String buildTitle() {
			String base = buildGroupTitle(group);
			// 1.1.6：追加时间范围，让玩家一眼知道这段对话发生在什么时间段。
			if (!group.items().isEmpty()) {
				long start = group.groupStartMillis();
				long end = group.lastTimestampMillis();
				String range = RANGE_FORMAT.format(Instant.ofEpochMilli(start))
						+ "-" + RANGE_FORMAT.format(Instant.ofEpochMilli(end));
				return base + " " + range;
			}
			return base;
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			String title = buildTitle();

			// 1.1.6：左侧白色竖线（2px 宽）+ 白色文字，去掉之前的绿色背景条——
			// 竖线更干净不突兀，白色文字和消息行的彩色文字形成层次区分。
			// scale 从 1.3 降到 1.1——1.3 倍标题过大，在 20px 行高里显得头重脚轻，
			// 1.1 倍既能和正文形成层次区分，又不会喧宾夺主。
			context.fill(x, y, x + 2, y + entryHeight, Colors.WHITE);

			float scale = 1.1f;
			context.getMatrices().push();
			context.getMatrices().scale(scale, scale, 1.0f);
			int scaledX = (int) ((x + 6) / scale);
			int scaledY = (int) ((y + Math.max(0, (entryHeight - textRenderer.fontHeight * scale) / 2)) / scale);
			String trimmed = textRenderer.trimToWidth(title, (int) ((entryWidth - 10) / scale));
			context.drawTextWithShadow(textRenderer, trimmed, scaledX, scaledY, Colors.WHITE);
			context.getMatrices().pop();
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return false;
		}
	}

	/**
	 * 系统提示行："开始了一段新对话" / "XX 加入了对话"。灰色居中风格。
	 */
	private static class SystemEventWidget extends HistoryListEntry {
		private final ChatHistorySystemEvent event;

		SystemEventWidget(ChatHistorySystemEvent event) {
			this.event = event;
		}

		@Override
		public Text getNarration() {
			return Text.literal(buildSystemEventText(event));
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			String text = buildSystemEventText(event);
			String trimmed = textRenderer.trimToWidth(text, entryWidth - 8);

			// 1.1.6：系统提示（"开始了一段新对话"等）缩小到 0.85 倍——它是分隔性辅助信息，
			// 不应该和消息正文一样大喧宾夺主。缩小后垂直水平居中。
			float scale = 0.85f;
			context.getMatrices().push();
			context.getMatrices().scale(scale, scale, 1.0f);
			int textW = textRenderer.getWidth(trimmed);
			int scaledX = (int) ((x + (entryWidth - textW * scale) / 2) / scale);
			int scaledY = (int) ((y + (entryHeight - textRenderer.fontHeight * scale) / 2) / scale);
			context.drawTextWithShadow(textRenderer, trimmed, scaledX, scaledY, Colors.GRAY);
			context.getMatrices().pop();
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return false;
		}
	}

	/**
	 * 单条历史消息条目。双行渲染，类似原版聊天栏的呈现风格：
	 * - 第一行：`<说话者> 原文`，说话者名字按来源着色，右侧对齐时间戳
	 * - 第二行：`⇄ 译文 [源语言→目标语言]`，浅灰色缩进显示
	 *
	 * 去掉了旧版的 [来源] 前缀——来源信息通过说话者名字的颜色区分：
	 * SELF 绿色、VISIBLE 白色、AUDIBLE 浅灰、CLIENT_ONLY 青色。
	 *
	 * 1.1.6 新增交互：
	 * - 右键点击复制译文（或原文，如果无译文）到剪贴板——方便玩家把翻译结果粘贴到
	 *   聊天栏或其他地方使用。不弹提示框，复制后玩家直接 Ctrl+V 验证。
	 * - 长消息被截断时 hover 显示完整内容 tooltip——1.21.1 的 EntryListWidget 行高
	 *   是列表级统一的（不支持每条目自定义高度），长消息只能截断 + 省略号，tooltip
	 *   是在不改变行高的前提下让玩家看到完整内容的唯一方案。
	 */
	private static class HistoryEntryWidget extends HistoryListEntry {
		private final ChatHistoryEntry entry;
		private final boolean showTranslated;

		HistoryEntryWidget(ChatHistoryEntry entry, boolean showTranslated) {
			this.entry = entry;
			this.showTranslated = showTranslated;
		}

		@Override
		public Text getNarration() {
			return Text.literal(entry.speakerName() + ": " + entry.originalText());
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;

			// 说话者名字按来源着色——替代旧版的 [来源] 前缀，更接近原版聊天栏风格。
			int nameColor = switch (entry.source()) {
				case SELF -> 0x55FF55;
				case VISIBLE -> Colors.WHITE;
				case AUDIBLE -> Colors.LIGHT_GRAY;
				case CLIENT_ONLY -> 0x55FFFF;
			};

			String speakerName = entry.speakerName() == null || entry.speakerName().isBlank()
					? "?" : entry.speakerName();

			String timeStr = TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMillis()));
			int timeWidth = textRenderer.getWidth(timeStr);

			// 第一行：<说话者> 原文 ——说话者名字带颜色，原文白色，右侧时间戳。
			String line1Prefix = "<" + speakerName + "> ";
			String line1 = line1Prefix + entry.originalText();
			int line1MaxWidth = entryWidth - timeWidth - 12;
			String line1Trimmed = textRenderer.trimToWidth(line1, line1MaxWidth);
			boolean line1Truncated = line1Trimmed.length() < line1.length();
			if (line1Truncated) {
				String ellipsis = "...";
				int avail = Math.max(1, line1MaxWidth - textRenderer.getWidth(ellipsis));
				line1Trimmed = textRenderer.trimToWidth(line1, avail) + ellipsis;
			}

			// 第一行画说话者名字（带颜色）+ 原文（白色），时间戳右对齐。
			// 是否有译文行——决定第一行的垂直位置。1.21.1 的 EntryListWidget itemHeight
			// 是列表级统一的（不支持每条目自定义高度），所以无译文的行也是 20px 高。
			// 如果无译文时文字顶在 y+2，下方会留一大片空白显得空旷；改为垂直居中
			// （y + (entryHeight - fontHeight) / 2）视觉更协调。有译文时保持 y+2 / y+11 双行布局。
			boolean hasTranslation = showTranslated && entry.translatedText() != null
					&& !entry.translatedText().isBlank()
					&& !entry.translatedText().equals(entry.originalText());
			int line1Y = hasTranslation ? y + 2 : y + (entryHeight - textRenderer.fontHeight) / 2;
			int prefixWidth = textRenderer.getWidth(line1Prefix);
			context.drawTextWithShadow(textRenderer, line1Prefix, x + 4, line1Y, nameColor);
			// 原文部分——trimToWidth 是对整行做的，所以需要按 prefix 后剩余宽度再截一次，
			// 但这里直接用已截断的整行减去 prefix 长度来画即可（截断点在原文里时原文也会被截）。
			String line1Remainder = line1Trimmed.length() > line1Prefix.length()
					? line1Trimmed.substring(line1Prefix.length()) : "";
			context.drawTextWithShadow(textRenderer, line1Remainder, x + 4 + prefixWidth, line1Y, Colors.WHITE);
			context.drawTextWithShadow(textRenderer, timeStr, x + entryWidth - timeWidth - 4, line1Y, Colors.LIGHT_GRAY);

			// 第二行：⇄ 译文 [源语言→目标语言] ——浅灰色，缩进对齐。
			// 只有在 showTranslated 且有译文时才画；没有译文的行（自己说话或同语言）第二行留空。
			boolean truncatedAny = line1Truncated;
			if (hasTranslation) {
				String line2 = "⇄ " + entry.translatedText();
				boolean hasLangLabel = entry.sourceLang() != null && entry.targetLang() != null
						&& !entry.sourceLang().equals(entry.targetLang());
				if (hasLangLabel) {
					line2 = line2 + " [" + entry.sourceLang() + "→" + entry.targetLang() + "]";
				}
				int line2MaxWidth = entryWidth - 12;
				String line2Trimmed = textRenderer.trimToWidth(line2, line2MaxWidth);
				boolean line2Truncated = line2Trimmed.length() < line2.length();
				if (line2Truncated) {
					String ellipsis = "...";
					int avail = Math.max(1, line2MaxWidth - textRenderer.getWidth(ellipsis));
					line2Trimmed = textRenderer.trimToWidth(line2, avail) + ellipsis;
					truncatedAny = true;
				}
				int line2Y = y + 11;
				context.drawTextWithShadow(textRenderer, line2Trimmed, x + 8, line2Y, Colors.LIGHT_GRAY);
			}

			// 被截断时 hover 显示完整内容——拼接双行完整文本传给 tooltip。
			if (hovered && truncatedAny) {
				Screen currentScreen = MinecraftClient.getInstance().currentScreen;
				if (currentScreen instanceof ChatHistoryScreen chs) {
					StringBuilder sb = new StringBuilder();
					sb.append("<").append(speakerName).append("> ").append(entry.originalText());
					if (showTranslated && entry.translatedText() != null
							&& !entry.translatedText().isBlank()
							&& !entry.translatedText().equals(entry.originalText())) {
						sb.append("\n⇄ ").append(entry.translatedText());
						if (entry.sourceLang() != null && entry.targetLang() != null
								&& !entry.sourceLang().equals(entry.targetLang())) {
							sb.append(" [").append(entry.sourceLang()).append("→").append(entry.targetLang()).append("]");
						}
					}
					chs.hoveredTruncatedText = sb.toString();
				}
			}
		}

		/**
		 * 1.1.6：复制译文（或原文）到剪贴板。
		 *
		 * 优先复制译文——玩家回看历史时通常是"想复制翻译结果给别人看"，
		 * 如果没有译文（自己说的话或同语言消息）则复制原文。
		 *
		 * 由 HistoryListWidget.mouseClicked 在右键点击时调用——1.21.1 的
		 * EntryListWidget 不把右键传递到 entry，所以复制逻辑不能放在
		 * entry 的 mouseClicked 里，而是放在 HistoryListWidget 层面处理。
		 */
		public void copyToClipboard() {
			String textToCopy = entry.translatedText() != null
					&& !entry.translatedText().isBlank()
					&& !entry.translatedText().equals(entry.originalText())
					? entry.translatedText() : entry.originalText();
			MinecraftClient.getInstance().keyboard.setClipboard(textToCopy);
		}
	}
}
