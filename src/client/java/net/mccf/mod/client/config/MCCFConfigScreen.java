package net.mccf.mod.client.config;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.network.RequestConfigPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

/**
 * MCCF 的游戏内配置界面。两个入口都指向这个 Screen：
 * - ModMenu 集成（{@code MCCFModMenuIntegration}）
 * - 按键绑定直接呼出（见 {@code MCCFClient} 里的按键监听）
 *
 * 整体布局：顶部一排标签页按钮，切换"服务端配置"/"本地设置"两个面板；
 * 每个面板内部都是"左侧 Provider 列表 + 右侧该 Provider 的设置"的两栏布局
 * （具体实现见 {@link ProviderConfigPanel} 及其两个子类）。这个 Screen 本身
 * 只负责标签页切换和整体尺寸计算，不直接持有输入框——所有字段控件都在
 * 对应的 Panel 里，随标签切换整体添加/移除。
 *
 * 界面尺寸取"接近全屏"：不再是原来居中的一小块，而是四周留一点边距、
 * 铺满大部分窗口，容纳左右两栏布局。
 *
 * 服务端网络回调（{@link net.mccf.mod.client.MCCFClient} 里通过
 * {@code instanceof MCCFConfigScreen} 触发）统一转发给"服务端配置"面板，
 * 这个类名和这几个方法签名必须保持稳定。
 */
public class MCCFConfigScreen extends Screen {

	/** 界面四周留白，营造"接近全屏但不贴边"的效果。 */
	private static final int MARGIN = 20;
	/** 标签栏高度。 */
	private static final int TAB_BAR_HEIGHT = 24;
	/** 标签栏与下方面板之间的间隙——留出空间给面板内部"当前 Provider 名称"标题行。 */
	private static final int TAB_BAR_GAP = 20;
	/**
	 * 底部提示文字区域的预留高度。
	 *
	 * 历史变更：早期版本这里同时常驻画"Provider 说明 + 状态消息"多行文字，
	 * 需要预留 100px 才够用；但 Provider 说明这类不紧急的信息后来改成了
	 * 鼠标悬浮在 Provider 标题上才弹出的 tooltip（见
	 * ServerConfigPanel/LocalConfigPanel#renderExtra），不再常驻占用这块
	 * 区域——这是应用户反馈修复的：截图显示旧版本在这里空出了接近 1.8/4
	 * 屏幕高度的空白，因为预留空间是按"最坏情况全部常驻"估算的，但日常
	 * 使用中 Provider 说明常驻占用的那部分其实完全用不上。
	 *
	 * 现在这块区域只需要容纳：
	 * - LocalConfigPanel：最多 2 行常驻文字（服务器检测状态 + 操作状态消息），
	 *   每行 18px，合计 36px。
	 * - ServerConfigPanel：最多 1 行常驻文字（加载中/超时未安装/保存状态，
	 *   18px）+ 请求超时时额外出现的"重试"按钮（20px 高 + 4px 间距），
	 *   合计 42px。
	 * 取两者较大值 42px，预留 50px 留一点余量。两个面板统一用这个值，即使
	 * LocalConfigPanel 用不上按钮那部分空间也无妨——换来的是两个标签页
	 * "控件区下边界"位置一致，切换标签页时不会感觉界面在跳动。
	 *
	 * 这个值必须和 ServerConfigPanel/LocalConfigPanel 里 renderLeftBottomHints
	 * 用的行距（18px）、以及 ServerConfigPanel 的"重试"按钮尺寸保持同步——如果
	 * 以后改了那边的行距/行数/按钮高度，这里也要跟着调整，否则控件区和提示
	 * 文字/按钮区又会重新出现"共享同一条边界线导致视觉重叠"的问题（这正是
	 * 0.9.0 之前的实际状况，见更新日志里的踩坑记录）。
	 */
	private static final int BOTTOM_HINT_AREA_HEIGHT = 50;

	private final Screen parent;

	private ServerConfigPanel serverPanel;
	private LocalConfigPanel localPanel;
	private final ServerConfigPanel.DraftState serverDrafts = new ServerConfigPanel.DraftState();
	private final LocalConfigPanel.DraftState localDraft = new LocalConfigPanel.DraftState();
	private boolean requestedServerSnapshot;

	/** 当前显示哪个面板。默认服务端配置——多数玩家打开设置是为了改服务器上生效的 Provider。 */
	private Tab activeTab = Tab.SERVER;

	/**
	 * 顶部标签栏每个标签的命中区域。1.1.6 改为自绘标签栏（还原创建新世界界面的扁平标签视觉），
	 * 不再用 ButtonWidget——init 里计算位置存这里，render 画标签、mouseClicked 检测点击都读它。
	 */
	private final List<TabRect> tabRects = new ArrayList<>();

	/**
	 * 跨 init() 重建保留的"上次选中 Provider"——分 server/local 两个标签页各自独立保存。
	 *
	 * 1.1.3 修复"切换 Provider 后获取模型列表回来 Provider 切回 activeProvider"和
	 * "强制关闭思考警告屏幕点'是'后仍显示关"两个 bug。根因是 init() 重建时 new 了一个
	 * 全新 Panel 实例，新 Panel 的 selectedProvider 通过 initialSelectedProvider() 读
	 * state.activeProvider，丢失了玩家在旧 Panel 里临时切换查看的 selectedProvider。
	 *
	 * 修复：init() 重建前先读出旧 Panel 的 selectedProvider 存到这两个字段，新 Panel
	 * 构造后通过 setPreservedSelectedProvider() 传回去。这样 ConfirmScreen / ModelSelectionScreen
	 * 关闭触发 init() 重建时，玩家选中的 Provider 能正确保留。
	 */
	private String lastSelectedServerProvider;
	private String lastSelectedLocalProvider;

	/**
	 * 1.1.5 新增：服务端配置标签页的可见性状态。
	 *
	 * 根据当前连接状态动态判断：
	 * - HIDDEN：隐藏整个"服务端配置"标签（未进入世界 / 单人世界无人加入 / 服务端未装 MCCF）
	 * - READ_ONLY：显示标签但控件灰色不可编辑 + 顶部黄色横幅（服务器非 op）
	 * - EDITABLE：显示标签且可编辑（op + 服务端装了 MCCF / 单人 LAN 有人加入）
	 */
	private enum ServerPanelVisibility { HIDDEN, READ_ONLY, EDITABLE }

	/** 上次计算的可见性状态——用于检测变化触发重建。 */
	private ServerPanelVisibility lastServerVisibility = null;

	/**
	 * "未进入世界"防抖：player 变 null 后 500ms 内仍视为"在世界中"，
	 * 避免维度切换等过渡瞬间状态闪烁。用户明确要求"以防万一加防抖"。
	 */
	private long playerNullSinceMillis = 0;
	private boolean consideredInWorld = false;

	/**
	 * 判断当前是否"在世界中"（player 非 null），带 500ms 防抖。
	 * player 从非 null 变 null 时，500ms 内仍返回 true；500ms 后才返回 false。
	 * player 从 null 变非 null 时立即返回 true。
	 */
	private boolean isInWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		boolean playerExists = client.player != null;
		if (playerExists) {
			playerNullSinceMillis = 0;
			consideredInWorld = true;
		} else if (consideredInWorld) {
			if (playerNullSinceMillis == 0) {
				playerNullSinceMillis = System.currentTimeMillis();
			}
			if (System.currentTimeMillis() - playerNullSinceMillis < 500) {
				return true; // 防抖窗口内仍视为在世界
			}
			consideredInWorld = false;
		}
		return consideredInWorld;
	}

	/**
	 * 计算服务端配置标签页当前应有的可见性状态。
	 *
	 * 判断顺序（先匹配先返回）：
	 * 1. 未进入世界（防抖）→ HIDDEN
	 * 2. 单人世界且无人通过 LAN 加入 → HIDDEN（单人游戏你跟谁翻译）
	 * 3. 服务端未装 MCCF → HIDDEN
	 * 4. 非 op → READ_ONLY
	 * 5. 否则 → EDITABLE
	 */
	private ServerPanelVisibility computeServerVisibility() {
		// 1. 未进入世界
		if (!isInWorld()) {
			return ServerPanelVisibility.HIDDEN;
		}

		MinecraftClient client = MinecraftClient.getInstance();

		// 2. 单人世界：只有自己时隐藏（没人需要跨语言交流），有人通过 LAN 加入时可编辑
		if (client.isIntegratedServerRunning() && client.getServer() != null) {
			int playerCount = client.getServer().getPlayerManager().getPlayerList().size();
			if (playerCount > 1) {
				return ServerPanelVisibility.EDITABLE; // 单人 LAN，有人加入
			}
			return ServerPanelVisibility.HIDDEN; // 单人世界，只有自己
		}

		// 3. 多人服务器：检查服务端是否装了 MCCF
		if (!ClientPlayNetworking.canSend(RequestConfigPayload.ID)) {
			return ServerPanelVisibility.HIDDEN; // 服务端未装 MCCF
		}

		// 4. 根据 op 状态判断
		if (!ClientConfigState.get().canEdit) {
			return ServerPanelVisibility.READ_ONLY; // 非 op
		}

		return ServerPanelVisibility.EDITABLE;
	}

	private enum Tab { SERVER, LOCAL }

	public MCCFConfigScreen(Screen parent) {
		super(Text.translatable("mccf.config.title"));
		this.parent = parent;
		// 1.1.5：实际跑在纯客户端模式时默认选本地设置标签——玩家在纯客户端模式下
		// 最需要配置的是本地翻译 Provider（服务端配置即使可见也只是只读查看或无关）。
		// isClientOnlyModeActive 涵盖两种情况：(1) 手动强制纯客户端模式；(2) 自动检测
		// 到服务器没装 MCCF——这两种情况下玩家实际都在用本地翻译，默认选本地设置
		// 能省掉一次手动切标签的操作。放在构造函数而非 init() 里，因为 init 会被
		// setScreen 触发重建（窗口尺寸变化、子 Screen 关闭返回等），每次重建不应该
		// 覆盖玩家手动切换的标签——构造函数只在 new 的时候调一次，正好是"首次打开"。
		if (ClientOnlyModeManager.isClientOnlyModeActive()) {
			this.activeTab = Tab.LOCAL;
		}
	}

	@Override
	protected void init() {
		int contentLeft = MARGIN;
		int contentTop = MARGIN + TAB_BAR_HEIGHT + TAB_BAR_GAP;
		int contentRight = this.width - MARGIN;
		int contentBottom = this.height - MARGIN - BOTTOM_HINT_AREA_HEIGHT;

		// 1.1.5 新增：计算服务端标签页可见性，决定是否显示服务端标签按钮和 panel。
		// HIDDEN 状态下完全不创建服务端标签按钮和 panel，只显示本地设置标签。
		// 玩家不需要看到"服务端配置"这个标签——单人世界没人翻译、服务端没装 MCCF
		// 配了也没用、未进入世界更没意义。隐藏比"灰色不可用"更干净。
		ServerPanelVisibility visibility = computeServerVisibility();
		lastServerVisibility = visibility;
		boolean serverTabVisible = visibility != ServerPanelVisibility.HIDDEN;

		// 1.1.6：服务端标签隐藏时强制切到本地设置（之前在 ButtonWidget 分支里处理，
		// 改为自绘标签后统一在这里处理）。
		if (!serverTabVisible) {
			activeTab = Tab.LOCAL;
		}
		// 计算标签栏布局——自绘标签用，render 画标签 + mouseClicked 检测点击都读 tabRects。
		buildTabRects(serverTabVisible);

		// 1.1.3 修复：重建前先从旧 Panel 读出 selectedProvider 保留下来。
		if (serverPanel != null) {
			serverPanel.captureDraft();
			lastSelectedServerProvider = serverPanel.getSelectedProvider();
		}
		if (localPanel != null) {
			localPanel.captureDraft();
			lastSelectedLocalProvider = localPanel.getSelectedProvider();
		}

		if (serverTabVisible) {
			serverPanel = new ServerConfigPanel(this, contentLeft, contentTop, contentRight, contentBottom, this.height / 2, serverDrafts);
			if (lastSelectedServerProvider != null) {
				serverPanel.setPreservedSelectedProvider(lastSelectedServerProvider);
			}
			serverPanel.init(this::addDrawableChild);
			// 1.1.5：把可见性状态传给 panel，让它决定控件灰色 + 横幅。
			// ServerPanelVisibility.HIDDEN 时 panel 根本不创建，这里只需转换 READ_ONLY/EDITABLE。
			serverPanel.setVisibility(visibility == ServerPanelVisibility.READ_ONLY
					? ProviderConfigPanel.PanelVisibility.READ_ONLY : ProviderConfigPanel.PanelVisibility.EDITABLE);
			if (!requestedServerSnapshot) {
				serverPanel.requestSnapshot();
				requestedServerSnapshot = true;
			}
		} else {
			serverPanel = null;
		}

		localPanel = new LocalConfigPanel(this, contentLeft, contentTop, contentRight, contentBottom, this.height / 2, localDraft);
		if (lastSelectedLocalProvider != null) {
			localPanel.setPreservedSelectedProvider(lastSelectedLocalProvider);
		}
		localPanel.init(this::addDrawableChild);

		applyTabVisibility();
	}

	/**
	 * 1.1.6：标签文字——不再用 » « 前缀标识选中态（自绘标签用底部高亮线标识选中，
	 * 比 » « 更接近创建新世界界面的扁平标签风格）。
	 */
	private Text tabLabel(Tab tab) {
		String key = tab == Tab.SERVER ? "mccf.config.tab.server" : "mccf.config.tab.local";
		return Text.translatable(key);
	}

	private void switchTab(Tab tab) {
		if (activeTab == tab) return;
		// 1.1.5：服务端标签隐藏时不允许切到 SERVER——tabRects 里没有 SERVER 项就说明隐藏了。
		if (tab == Tab.SERVER && tabRects.stream().noneMatch(tr -> tr.tab == Tab.SERVER)) return;
		activeTab = tab;
		applyTabVisibility();
	}

	/** 只显示当前标签对应的 Panel 控件，另一个标签的控件整体隐藏（不可见也不可交互）。 */
	private void applyTabVisibility() {
		boolean showServer = activeTab == Tab.SERVER && serverPanel != null;
		if (serverPanel != null) serverPanel.setVisible(showServer);
		localPanel.setVisible(!showServer);
	}

	// ----- 以下方法由 MCCFClient 的网络接收器通过 instanceof MCCFConfigScreen 触发 -----

	/** 收到服务端最新配置快照后调用。 */
	public void onSnapshotUpdated() {
		if (serverPanel != null) {
			serverPanel.onSnapshotUpdated();
		}
	}

	/**
	 * 1.1.7 新增：供 ServerConfigPanel 的 Mock 确认弹窗回调使用——先 setScreen 触发 init 重建，
	 * 然后调此方法在新 panel 上执行 performSave。见 ServerConfigPanel.onSave 的注释。
	 */
	public void triggerServerPerformSave() {
		if (serverPanel != null) {
			serverPanel.triggerPerformSave();
		}
	}

	/** 收到服务端模型列表查询结果后调用。 */
	public void onModelsResult(boolean success, String requestId, String providerId, java.util.List<String> models, String error) {
		if (serverPanel != null) {
			serverPanel.onModelsResult(success, requestId, providerId, models, error);
		}
	}

	/** ModelSelectionScreen 选中模型后的回调。 */
	public void setModelFromSelection(String model) {
		if (serverPanel != null) {
			serverPanel.setModelFromSelection(serverPanel.getSelectedProvider(), model);
		}
	}

	/** Route asynchronous local selections to the current widgets after any resize/rebuild. */
	public void setLocalModelFromSelection(String providerId, String model) {
		if (localPanel != null) localPanel.setModelFromSelection(providerId, model);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// 1.1.5 实时刷新：每帧检查服务端标签可见性是否变化，变化时重建界面。
		// 状态变化的场景：玩家从非 op 被 op、服务端装了 MCCF、单人世界有人通过 LAN 加入等。
		// 重建开销可接受——只在状态真正变化时才 clearChildren + init，不是每帧都做。
		ServerPanelVisibility currentVisibility = computeServerVisibility();
		if (lastServerVisibility != currentVisibility) {
			lastServerVisibility = currentVisibility;
			this.clearChildren();
			this.init();
		}

		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 4, Colors.WHITE);
		renderTabBar(context, mouseX, mouseY);

		if (activeTab == Tab.SERVER && serverPanel != null) {
			serverPanel.render(context, mouseX, mouseY, delta);
		} else if (localPanel != null) {
			localPanel.render(context, mouseX, mouseY, delta);
		}
	}

	/**
	 * 1.1.6：自绘顶部标签栏——还原创建新世界界面的扁平标签视觉。
	 *
	 * 之前用 ButtonWidget 画标签（带边框的按钮 + » « 文字前缀标识选中），视觉上像
	 * 普通按钮不像"标签页"。改为自绘：纯文字标签，选中态用底部 2px 白色高亮线标识
	 *（和创建新世界界面的"游戏/世界/更多"标签一致），非选中态文字灰色、hover 浅灰。
	 * 底部画一条暗色分隔线把标签栏和下方面板内容隔开。
	 *
	 * 不用原生 TabNavigationWidget 的原因：它绑定 TabManager 的控件增删机制，而 MCCF
	 * 的 Panel 用 setVisible 管控件可见性而非增删，模式不匹配；且 TabNavigationWidget
	 * 没有暴露 setPosition，y 坐标不可控（默认 0 会和标题重叠）。自绘能精确控制位置和样式。
	 */
	private void renderTabBar(DrawContext context, int mouseX, int mouseY) {
		int tabY = MARGIN;
		for (TabRect tr : tabRects) {
			boolean active = tr.tab == activeTab;
			boolean hovered = tr.contains(mouseX, mouseY);
			// 选中白色、hover 浅灰、默认灰色——和创建新世界标签的颜色层次一致。
			int color = active ? Colors.WHITE : (hovered ? Colors.LIGHT_GRAY : Colors.GRAY);
			Text label = tabLabel(tr.tab);
			int textX = tr.x + (tr.width - textRenderer.getWidth(label)) / 2;
			int textY = tabY + (TAB_BAR_HEIGHT - textRenderer.fontHeight) / 2;
			context.drawTextWithShadow(textRenderer, label, textX, textY, color);
			// 选中标签底部画 2px 高亮线——这是创建新世界标签的标志性视觉。
			if (active) {
				context.fill(tr.x, tabY + TAB_BAR_HEIGHT - 2, tr.x + tr.width, tabY + TAB_BAR_HEIGHT, Colors.WHITE);
			}
		}
		// 标签栏底部分隔线——用半透明深灰，和原版选项界面的分隔线风格一致。
		context.fill(MARGIN, tabY + TAB_BAR_HEIGHT, this.width - MARGIN, tabY + TAB_BAR_HEIGHT + 1, 0xFF555555);
	}

	/**
	 * 1.1.6：计算标签栏布局——每个标签的 x 坐标和宽度，存到 tabRects。
	 * 标签宽度按文字宽度 + 两侧 padding 计算，整体居中排列，标签间留 8px 间隔。
	 */
	private void buildTabRects(boolean serverTabVisible) {
		tabRects.clear();
		List<Tab> visible = new ArrayList<>();
		if (serverTabVisible) visible.add(Tab.SERVER);
		visible.add(Tab.LOCAL);

		int tabGap = 8;
		int tabPadding = 20;
		int[] widths = new int[visible.size()];
		int totalWidth = 0;
		for (int i = 0; i < visible.size(); i++) {
			widths[i] = textRenderer.getWidth(tabLabel(visible.get(i))) + tabPadding * 2;
			totalWidth += widths[i];
			if (i > 0) totalWidth += tabGap;
		}
		int x = (this.width - totalWidth) / 2;
		for (int i = 0; i < visible.size(); i++) {
			tabRects.add(new TabRect(visible.get(i), x, widths[i]));
			x += widths[i] + tabGap;
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 1.1.6：自绘标签的点击检测——左键点击落在某个 TabRect 内就切换标签。
		if (button == 0) {
			for (TabRect tr : tabRects) {
				if (tr.contains(mouseX, mouseY)) {
					switchTab(tr.tab);
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	/** 1.1.6：标签栏命中区域——存标签枚举、x 坐标、宽度，供 render 画和 mouseClicked 检测。 */
	private class TabRect {
		final Tab tab;
		final int x;
		final int width;

		TabRect(Tab tab, int x, int width) {
			this.tab = tab;
			this.x = x;
			this.width = width;
		}

		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width
					&& mouseY >= MARGIN && mouseY < MARGIN + TAB_BAR_HEIGHT;
		}
	}
}
