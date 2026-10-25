package net.mccf.mod.client.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "标签页内容"的共同骨架：左侧 Provider 列表 + 右侧对应设置面板。
 * {@link ServerConfigPanel}（服务端配置）和 {@link LocalConfigPanel}（本地设置）
 * 都继承这个类，保证两个标签页视觉/交互风格一致（需求确认里明确要求过）。
 *
 * 子类只需要实现"右侧设置区要放哪些控件"（{@link #buildRightPanel}）和
 * "如何取得/展示某个 Provider 的当前状态"，左侧列表的创建、Provider 是否为
 * activeProvider 的判断、选中查看的切换逻辑都在这里统一处理。
 *
 * 可见性管理：Screen 级别只有一个 init()，两个标签页的控件全部在 init() 时
 * 一次性创建好并 addDrawableChild 到同一个 Screen 里，切换标签靠整体设置
 * visible/active 隐藏非当前标签的控件——这比"动态增删 children"更简单可靠，
 * 不用操心 Minecraft Screen 内部 children 列表的时序问题。
 */
public abstract class ProviderConfigPanel {

	protected static final int LIST_WIDTH = 200;
	protected static final int GUTTER = 14;

	protected final Screen screen;
	protected final int left, top, right, bottom;
	/** 屏幕中心 Y 坐标——提示文字统一在此处下方绘制。 */
	protected final int screenCenterY;

	protected ProviderListWidget listWidget;
	/** 当前"选中查看"的 Provider——不等于已启用的 Provider，见类注释。 */
	protected String selectedProvider;

	/** 本面板创建的所有控件，用于统一 setVisible。 */
	private final List<ClickableWidget> ownedWidgets = new ArrayList<>();
	/** 当前标签页是否可见——子类计算字段 active 时应该与"字段本身是否可编辑"做 AND。 */
	protected boolean tabVisible = true;
	/**
	 * 外部传入的"上次选中 Provider"，用于跨 Screen 重建保留选中状态。
	 *
	 * 1.1.3 修复"切换 Provider 后获取模型列表，回来 Provider 切回 activeProvider 丢失更改"
	 * 和"强制关闭思考警告屏幕点'是'后仍显示关"两个 bug 的核心字段——这两个 bug 同根因：
	 * ModelSelectionScreen / ConfirmScreen 关闭后 setScreen 触发 MCCFConfigScreen.init() 重建，
	 * 新 panel 的 selectedProvider 通过 initialSelectedProvider() 读 state.activeProvider，
	 * 丢失了玩家在旧 panel 里临时切换查看的 selectedProvider。
	 *
	 * 修复方式：MCCFConfigScreen 在 init() 重建前把旧 panel 的 selectedProvider 读出来，
	 * 传给新 panel 的这个字段；init() 时优先用这个值，没有才 fallback 到 initialSelectedProvider()。
	 * 玩家首次打开界面时这个值为 null，行为和旧版一致（用 activeProvider）。
	 */
	private String preservedSelectedProvider;

	protected ProviderConfigPanel(Screen screen, int left, int top, int right, int bottom, int screenCenterY) {
		this.screen = screen;
		this.left = left;
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		this.screenCenterY = screenCenterY;
		// 故意不在这里调用 initialSelectedProvider()：它是 abstract、由子类实现，而子类
		// 实现里访问的是子类自己的实例字段（ServerConfigPanel.state、LocalConfigPanel.config）。
		// Java 的实例字段初始化器在"父类构造器返回之后、子类构造器体之前"才执行——也就是说
		// 此刻从父类构造器回调子类的 override 时，state/config 仍是默认值 null，直接 NPE。
		// 这正是 0.4.0 配置界面一打开就崩 "Rendering screen / serverPanel is null" 的根因：
		// MCCFConfigScreen.init() 第 79 行 new ServerConfigPanel(...) 在 super() 里抛了 NPE，
		// 赋值没完成、serverPanel 保持 null，init() 的异常被上层吞掉后 render() 硬崩。
		// selectedProvider 改到 init() 里赋值（那时子类构造已全部完成、字段就绪），见 init()。
	}

	/**
	 * 1.1.5 新增：面板可见性状态（仅 ServerConfigPanel 使用，LocalConfigPanel 始终 EDITABLE）。
	 * HIDDEN 由 MCCFConfigScreen 通过不创建 panel 处理，panel 层面只会收到 READ_ONLY 或 EDITABLE。
	 * - READ_ONLY：控件灰色不可编辑，顶部黄色横幅提示"你不是管理员"
	 * - EDITABLE：控件正常可编辑
	 * "显示原文"两个开关不受此状态影响（客户端个人偏好，不需要 op 权限）。
	 */
	protected enum PanelVisibility { READ_ONLY, EDITABLE }
	protected PanelVisibility visibility = PanelVisibility.EDITABLE;

	public void setVisibility(PanelVisibility visibility) {
		this.visibility = visibility != null ? visibility : PanelVisibility.EDITABLE;
	}

	/** 初始化时列表应该选中哪个 Provider。 */
	protected abstract String initialSelectedProvider();

	/**
	 * 设置外部保留的 selectedProvider——由 MCCFConfigScreen 在 init() 重建前调用，
	 * 把旧 panel 的 selectedProvider 传给新 panel，避免重建后丢失玩家临时切换的查看状态。
	 */
	public void setPreservedSelectedProvider(String providerId) {
		this.preservedSelectedProvider = providerId;
	}

	/** 获取当前 selectedProvider，供 MCCFConfigScreen 在重建前读取保留。 */
	public String getSelectedProvider() {
		return selectedProvider;
	}

	/** 当前"已启用/生效中"的 Provider id——决定列表里哪一项打勾。 */
	protected abstract String activeProviderId();

	/** 右侧设置区的控件创建。返回创建的控件不需要手动 add——统一由本类处理。 */
	protected abstract void buildRightPanel(int panelLeft, int panelTop, int panelRight, int panelBottom);

	/** 玩家在列表里选中了不同的 Provider（仅切换查看，非立即生效）。 */
	protected abstract void onProviderSelected(String providerId);

	/** 面板自己的补充渲染（状态文字、提示等）。 */
	protected abstract void renderExtra(DrawContext context, int mouseX, int mouseY, float delta);

	/**
	 * 创建左侧列表 + 触发子类创建右侧控件。
	 *
	 * @param addChild 挂载普通可绘制控件（通常是 {@code Screen::addDrawableChild}）。
	 *        左侧 Provider 列表现在是一个 {@link ClickableWidget}，随 Screen 一起
	 *        渲染和接收输入，不再使用 addSelectableChild + 手动 render 的模式。
	 */
	public void init(Consumer<ClickableWidget> addChild) {
		ownedWidgets.clear();

		// 在这里（而非构造器里）确定初始选中的 Provider：此刻子类构造已全部完成，
		// initialSelectedProvider() 里访问的 state/config 等字段均已初始化。原先在父类
		// 构造器里调用这个 override 会踩"字段初始化器晚于父类构造器"的坑导致 NPE
		// （详见本类构造器注释），这里 deferred 赋值既绕开该坑，又不改变"列表初始选中项"
		// 的语义。selectedProvider 在构造完成到 init() 之间是 null，但这段窗口内没有任何
		// 代码读取它（MCCFConfigScreen.init() 是构造完立刻 init()），所以安全。
		//
		// 1.1.3 修复：优先使用 preservedSelectedProvider（外部保留的值）——这个值由
		// MCCFConfigScreen 在 init() 重建前从旧 panel 读出传入，用于跨 Screen 重建保留
		// 玩家临时切换查看的 Provider。首次打开时为 null，fallback 到 initialSelectedProvider()。
		this.selectedProvider = preservedSelectedProvider != null
				? preservedSelectedProvider
				: initialSelectedProvider();

		listWidget = new ProviderListWidget(left, top, LIST_WIDTH, bottom - top,
				ClientConfigState.PROVIDER_IDS, selectedProvider, this::activeProviderId,
				this::selectProvider);
		addChild.accept(listWidget);

		// 1.1.5：打开界面时自动滚动到当前选中的 Provider，避免玩家每次打开都要手动滚。
		// 这里用 selectedProvider 而不是 activeProvider——玩家临时切到 B 查看，关掉界面
		// 重新打开（如果 selectedProvider 被保留），应该滚到 B 而不是 activeProvider。
		// 如果是首次打开（selectedProvider == activeProvider），两者一致。
		listWidget.scrollToProvider(selectedProvider);

		int panelLeft = left + LIST_WIDTH + GUTTER;
		buildRightPanel(panelLeft, top, right, bottom);

		for (ClickableWidget widget : ownedWidgets) {
			addChild.accept(widget);
		}
	}

	/**
	 * 1.1.5 新增：当前选中的 Provider 是否为 Mock。
	 * Mock 不需要 API Key/endpoint/model/disableThinking/获取模型/恢复默认等配置——
	 * 这些控件在 Mock 选中时应该隐藏。显示原文两个开关仍保留（它们是客户端全局偏好，
	 * 与 Provider 无关）。保存按钮也保留（玩家点保存就是"把 Mock 设为生效 Provider"）。
	 */
	protected boolean isMockSelected() {
		return "mock".equals(selectedProvider);
	}

	/** 子类在 buildRightPanel 里创建控件后调用这个方法登记，而不是直接 add——统一走 setVisible 管理。 */
	protected <T extends ClickableWidget> T own(T widget) {
		ownedWidgets.add(widget);
		return widget;
	}

	private void selectProvider(String providerId) {
		if (providerId.equals(selectedProvider)) return;
		captureDraft();
		selectedProvider = providerId;
		onProviderSelected(providerId);
	}

	/** Preserve form values before provider switches, tab changes and Screen.init rebuilds. */
	public abstract void captureDraft();

	/**
	 * 切换标签页可见性。同时设置 visible（控制渲染 + 官方输入路由的第一道防线）
	 * 和触发子类重新计算每个控件的 active（第二道防线：即使 Minecraft 某个
	 * 版本对 visible=false 控件的事件路由处理不够彻底，active=false 也能确保
	 * 不会误触）。两道防线保证切到未显示的标签页时那些控件绝对不可交互。
	 */
	public void setVisible(boolean visible) {
		captureDraft();
		this.tabVisible = visible;
		if (listWidget != null) {
			listWidget.visible = visible;
		}
		for (ClickableWidget widget : ownedWidgets) {
			widget.visible = visible;
		}
		onTabVisibilityChanged();
	}

	/** 子类在这里根据 tabVisible + 自己的编辑态逻辑，重新计算每个控件的 active。 */
	protected abstract void onTabVisibilityChanged();

	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// 左侧列表 widget 已通过 addChild 加入 Screen，由 Screen 统一渲染，
		// 这里只需绘制右侧面板自身的额外内容。
		renderExtra(context, mouseX, mouseY, delta);
	}

	protected void drawLabel(DrawContext context, Text text, int x, int y, int color) {
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x, y, color);
	}

	protected void drawLabel(DrawContext context, Text text, int x, int y) {
		drawLabel(context, text, x, y, Colors.WHITE);
	}

	/**
	 * 单条提示信息：文字内容 + 颜色。用于 {@link #renderLeftBottomHints}。
	 * 空文本（{@code text.getString().isEmpty()}）的行会被跳过，不占用垂直空间——
	 * 调用方可以无脑传入"可能为空"的状态消息，不用自己先判断是否为空再决定传不传。
	 */
	protected record HintLine(Text text, int color) {}

	/**
	 * 在左侧 Provider 列表正下方绘制若干行提示文字（Provider 说明、检测状态、
	 * 操作状态消息等），左对齐、从上往下排列，自动按 {@link #LIST_WIDTH} 换行。
	 *
	 * 为什么搬到这里而不是继续用屏幕底部居中：应用户明确要求——左侧列表下方
	 * 那块区域（列表宽度 × 列表底部到屏幕底部）原本完全空置，提示文字挪过去
	 * 既利用了空间，也让"这段提示是关于左侧列表选中的这个 Provider"这层
	 * 关联在视觉上更直接（挨着列表，而不是远在屏幕正中）。
	 *
	 * 换行处理：`LIST_WIDTH`（200px）比屏幕宽度窄得多，部分语言的提示文案
	 * （尤其英语，"Requires API Key. Free tier key ends with :fx" 这类）
	 * 在这个宽度下几乎肯定需要换行，不像原来的"屏幕居中"版本可以假设一行
	 * 能放下。这里用 {@code textRenderer.trimToWidth} 反复裁切实现手动换行——
	 * 与 0.9.0 之前 LocalConfigPanel 里"强制服务器模式"警告文字的换行逻辑
	 * 是同一种写法（那段代码后来因为改成 ConfirmScreen 弹窗被删除，这次是
	 * 同一手法用在新的地方，不是重复踩坑）。
	 *
	 * @param x     左对齐起始 x 坐标（通常传 {@link #left}，即列表左边缘）
	 * @param y     第一行的起始 y 坐标（通常是列表底部 {@link #bottom} 往下留一点间隙）
	 * @param lines 要绘制的提示行，按顺序从上往下排列；某一行为空文本时整行跳过
	 * @return 最后一行绘制完毕后的下一个可用 y 坐标——供调用方在提示文字之后
	 *         紧接着放置其他元素（例如"重试"按钮）时使用，避免像素级硬编码
	 *         "假设提示文字占几行"而导致重叠或留白不一致（换行行数是动态的，
	 *         不同语言、不同 Provider 的文案长度不同，硬编码位置几乎肯定会出错）。
	 */
	protected int renderLeftBottomHints(DrawContext context, int x, int y, HintLine... lines) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int maxWidth = LIST_WIDTH;
		int lineHeight = textRenderer.fontHeight + 2;
		int currentY = y;

		for (HintLine line : lines) {
			String remaining = line.text().getString();
			if (remaining.isEmpty()) continue;

			while (!remaining.isEmpty()) {
				String trimmed = textRenderer.trimToWidth(remaining, maxWidth);
				if (trimmed.isEmpty()) {
					// 极端情况（单个字符都超宽，理论上不会发生在这个字体+宽度组合下，
					// 但防御性地避免死循环）：至少画一个字符再退出这一行。
					trimmed = remaining.substring(0, 1);
				}
				context.drawTextWithShadow(textRenderer, trimmed, x, currentY, line.color());
				currentY += lineHeight;
				if (trimmed.length() >= remaining.length()) break;
				remaining = remaining.substring(trimmed.length());
			}
		}
		return currentY;
	}
}
