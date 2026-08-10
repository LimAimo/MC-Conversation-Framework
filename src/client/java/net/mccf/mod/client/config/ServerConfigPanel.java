package net.mccf.mod.client.config;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.client.util.LogExporter;
import net.mccf.mod.network.RequestConfigPayload;
import net.mccf.mod.network.RequestModelsPayload;
import net.mccf.mod.network.UpdateConfigPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

/**
 * "服务端配置"标签页：左侧 Provider 列表 + 右侧该 Provider 的 apiKey / model /
 * endpoint 等设置，需要 op 权限才能编辑，改动经由 {@link UpdateConfigPayload}
 * 提交给服务端、对所有玩家生效。
 *
 * 与旧版 MCCFConfigScreen 的行为差异（新版 UI 改动，见需求确认）：
 * - 左侧列表点击只切换"选中查看"（{@link #selectedProvider}），不会立即生效。
 * - 点"保存"时，当前选中查看的 Provider 会**同时**成为待启用目标——不再需要
 *   单独的"保存并启用"/"设为默认"按钮，保存这一个动作同时做两件事：
 *   (a) 保存当前查看 Provider 的字段改动（API Key / 模型 / Endpoint）；
 *   (b) 把当前查看的 Provider 设为 activeProvider。这是应用户明确要求的
 *   简化——"选择某个 Provider 再点保存，就代表把它设为默认"，不需要额外
 *   再点一次"设为默认"。
 * - 其余字段级别的行为（清除 Key、恢复默认 Endpoint、获取模型列表、只读态）
 *   与旧版一致，原样迁移。
 */
public class ServerConfigPanel extends ProviderConfigPanel {

	private final ClientConfigState state = ClientConfigState.get();

	/** 请求快照后多久（毫秒）算超时——超时后不再显示"加载中"，改为提示未安装/无法连接 + 重试按钮。 */
	private static final long SNAPSHOT_TIMEOUT_MS = 5000;

	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget saveButton;
	private ButtonWidget fetchModelsButton;
	private ButtonWidget clearApiKeyButton;
	/** 1.1.5 提升为字段：Mock/非 op 时需要隐藏这个按钮。 */
	private ButtonWidget resetEndpointButton;
	private ButtonWidget retryButton;
	/** "字幕显示原文"开关——AUDIBLE 模式物品栏字幕是否同时显示原文和译文。客户端个人偏好，1.1.1 起从服务端配置迁移。 */
	private net.minecraft.client.gui.widget.CyclingButtonWidget<Boolean> showOriginalTextButton;
	/** "聊天栏显示原文"开关——VISIBLE 模式聊天栏是否同时显示原文和译文。客户端个人偏好。 */
	private net.minecraft.client.gui.widget.CyclingButtonWidget<Boolean> showOriginalTextInChatButton;
	/**
	 * "强制关闭思考"开关——只在 selectedProvider 属于
	 * {@link ClientConfigState#THINKING_CAPABLE_PROVIDERS} 时可见（其余
	 * Provider 没有"思考"概念，见该集合注释）。每个 Provider 各自独立存储
	 * 在 {@code ClientProviderConfig#disableThinking} 里（应用户明确要求
	 * "只想关 DeepSeek 的思考，不想关 Kimi 的"这类精细控制），不是全局开关。
	 */
	private net.minecraft.client.gui.widget.CyclingButtonWidget<Boolean> disableThinkingButton;

	private Text statusMessage = Text.empty();
	private int statusColor = Colors.YELLOW;
	/** 玩家是否点了"清除密钥"按钮，语义同旧版 MCCFConfigScreen。 */
	private boolean userClearedApiKey = false;
	/**
	 * 最近一次向服务端请求配置快照的时刻（{@link System#currentTimeMillis()}）。
	 * 用于在 {@link #renderExtra} 里判断是否已经等待超过 {@link #SNAPSHOT_TIMEOUT_MS}
	 * 仍未收到回包——这种情况通常意味着服务器没有安装 MCCF（能连接、能发送请求，
	 * 但服务端不认识这个通道/不会回应），而不是"还在加载中"。0 表示尚未发送过请求
	 * （理论上不会发生，构造器里 canSend 检查通过就会立即请求一次；但如果玩家在
	 * 未连接任何服务器时打开过配置界面，这个值会保持 0，此时也不应该显示超时提示，
	 * 因为压根没发出去请求，见 renderExtra 里的判断）。
	 */
	private long snapshotRequestedAtMillis = 0;

	public ServerConfigPanel(Screen screen, int left, int top, int right, int bottom, int screenCenterY) {
		super(screen, left, top, right, bottom, screenCenterY);
		requestSnapshot();
	}

	/**
	 * 向服务端请求最新配置快照，并记录请求时刻用于超时判断。
	 * canSend 检查避免玩家尚未进入任何世界/连接任何服务器时调用 send() 抛异常
	 * 导致崩溃（这是旧版就有的已知坑）——未连接时 snapshotRequestedAtMillis 保持
	 * 0，renderExtra 据此不显示"加载中"也不显示"超时未安装"，直接留空（跟
	 * LocalConfigPanel 处理"未连接时不显示检测行"是同一个思路：没有连接这件事
	 * 本身不构成"加载"或"超时"，就不该显示任何相关状态）。
	 */
	private void requestSnapshot() {
		if (ClientPlayNetworking.canSend(RequestConfigPayload.ID)) {
			ClientPlayNetworking.send(new RequestConfigPayload(true));
			snapshotRequestedAtMillis = System.currentTimeMillis();
		}
	}

	@Override
	protected String initialSelectedProvider() {
		return state.activeProvider;
	}

	@Override
	protected String activeProviderId() {
		return state.activeProvider;
	}

	@Override
	protected void buildRightPanel(int panelLeft, int panelTop, int panelRight, int panelBottom) {
		int panelWidth = panelRight - panelLeft;
		int fieldHeight = 20;
		// 动态间距：8 行控件（高 20）+ 7 个间距。
		int spacing = Math.max(22, Math.min(36, (panelBottom - panelTop - 160) / 7));
		int y = panelTop;

		int apiKeyFieldWidth = panelWidth - 44;
		apiKeyField = own(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, panelLeft, y, apiKeyFieldWidth, fieldHeight,
				Text.translatable("mccf.config.api_key")));
		apiKeyField.setMaxLength(512);
		apiKeyField.setPlaceholder(Text.translatable("mccf.config.api_key.placeholder"));
		apiKeyField.setRenderTextProvider((text, firstCharacterIndex) ->
				Text.literal("•".repeat(text.length())).asOrderedText());

		clearApiKeyButton = own(ButtonWidget.builder(
						Text.translatable("mccf.config.clear_api_key"), button -> onClearApiKey())
				.dimensions(panelLeft + apiKeyFieldWidth + 4, y, 40, fieldHeight)
				.build());
		y += spacing;

		modelField = own(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, panelLeft, y, panelWidth, fieldHeight,
				Text.translatable("mccf.config.model")));
		modelField.setMaxLength(128);
		modelField.setPlaceholder(Text.translatable("mccf.config.model.placeholder"));
		y += spacing;

		endpointField = own(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, panelLeft, y, panelWidth, fieldHeight,
				Text.translatable("mccf.config.endpoint")));
		endpointField.setMaxLength(256);
		endpointField.setPlaceholder(Text.translatable("mccf.config.endpoint.placeholder"));
		y += spacing;

		// "强制关闭思考"开关：只在 selectedProvider 支持思考控制时才有意义，
		// 初始可见性/取值在 refreshFieldsFromState 里根据当前查看的 Provider
		// 同步（这里先按当前 selectedProvider 构造，保证 buildRightPanel 首次
		// 运行时状态就是对的，不用等第一次 refreshFieldsFromState 才更新）。
		// 打开开关（false -> true）时会弹出确认警告，见 onDisableThinkingToggled——
		// 应用户明确要求"警告屏幕"说明部分模型可能不支持、以及关闭思考的好处。
		ClientProviderConfig initialPc = state.getOrCreate(selectedProvider);
		disableThinkingButton = own(net.minecraft.client.gui.widget.CyclingButtonWidget.onOffBuilder(initialPc.disableThinking)
				.build(panelLeft, y, panelWidth, fieldHeight,
						Text.translatable("mccf.config.disable_thinking"),
						(button, value) -> onDisableThinkingToggled(button, value)));
		y += spacing;

		// "字幕显示原文"和"聊天栏显示原文"——1.1.1 起从服务端 op 配置改为客户端
		// 个人偏好，每个玩家独立决定要不要看原文，不受服务器/op 限制。开关直接读写
		// ClientOnlyTranslationConfig（落盘到 client-only-config.json），立即生效
		// 不需要点"保存"、不需要 op 权限。放在"服务端配置"标签页是因为这两个开关
		// 在语义上是"翻译展示设置"，和 Provider 配置属于同一组展示场景。
		ClientOnlyTranslationConfig localConfig = ClientOnlyTranslationConfig.get();
		showOriginalTextButton = own(net.minecraft.client.gui.widget.CyclingButtonWidget.onOffBuilder(localConfig.showOriginalText)
				.build(panelLeft, y, panelWidth, fieldHeight,
						Text.translatable("mccf.config.show_original_audible"),
						(button, value) -> { localConfig.showOriginalText = value; localConfig.save(); }));
		y += spacing;
		showOriginalTextInChatButton = own(net.minecraft.client.gui.widget.CyclingButtonWidget.onOffBuilder(localConfig.showOriginalTextInChat)
				.build(panelLeft, y, panelWidth, fieldHeight,
						Text.translatable("mccf.config.show_original_chat"),
						(button, value) -> { localConfig.showOriginalTextInChat = value; localConfig.save(); }));
		y += spacing;

		int halfWidth = (panelWidth - 8) / 2;
		resetEndpointButton = own(ButtonWidget.builder(Text.translatable("mccf.config.reset_endpoint"), button -> onResetEndpoint())
				.dimensions(panelLeft, y, halfWidth, fieldHeight)
				.build());
		fetchModelsButton = own(ButtonWidget.builder(
						Text.translatable("mccf.config.fetch_models"), button -> onFetchModels())
				.dimensions(panelLeft + halfWidth + 8, y, halfWidth, fieldHeight)
				.build());
		y += spacing;

		int thirdWidth = (panelWidth - 8) / 3;
		saveButton = own(ButtonWidget.builder(Text.translatable("mccf.config.save"), button -> onSave())
				.dimensions(panelLeft, y, thirdWidth, fieldHeight)
				.build());
		own(ButtonWidget.builder(Text.translatable("mccf.config.export_log"), button -> onExportLog())
				.dimensions(panelLeft + thirdWidth + 4, y, thirdWidth, fieldHeight)
				.build());
		own(ButtonWidget.builder(Text.translatable("mccf.config.close"), button -> screen.close())
				.dimensions(panelLeft + (thirdWidth + 4) * 2, y, thirdWidth, fieldHeight)
				.build());

		// "重试"按钮：只在快照请求超时（服务器可能没装 MCCF）时才显示，见 renderExtra
		// 里的超时判断。放在左侧提示区域下方而不是右侧控件序列里——它不是一个常规的
		// 表单操作，是"加载失败后的补救动作"，默认应该是不存在的（active=false 且
		// visible=false，双重保险防止意外可交互，与父类 setVisible 的两道防线思路一致）。
		// 初始 y 坐标只是占位——提示文字的实际换行行数是动态的（取决于语言/文案长度），
		// 真正的位置在 renderExtra 里每帧根据 renderLeftBottomHints 的返回值用
		// setPosition 重新计算，这里给的坐标只要合法（在屏幕范围内）即可。
		retryButton = own(ButtonWidget.builder(Text.translatable("mccf.config.retry"), button -> onRetry())
				.dimensions(left, bottom + 6, LIST_WIDTH, fieldHeight)
				.build());
		retryButton.visible = false;
		retryButton.active = false;

		refreshFieldsFromState();
		applyEditability();
	}

	@Override
	protected void onProviderSelected(String providerId) {
		// 切换查看时重置"清除密钥"标记——每个 Provider 的 Key 独立保存，
		// 玩家在 A Provider 点了清除不意味着切到 B Provider 也想清除 B 的 Key。
		userClearedApiKey = false;
		refreshFieldsFromState();
		applyEditability();

		// 1.1.5：Mock 选中时不弹确认弹窗——应用户要求，Mock 警告只在"保存"时弹
		// （见 onSave 里的 Mock 确认）。选中只切换查看，玩家可能只是想看一眼 Mock
		// 的配置长什么样（虽然 Mock 没什么可配置的），不构成"要启用 Mock"的意图。
		// 选中就弹窗会打断"随便点点看看"的浏览行为，体验上过于侵入。
	}

	/** 把 state 里当前查看 Provider 的数据填入输入框。 */
	private void refreshFieldsFromState() {
		if (apiKeyField == null) return; // buildRightPanel 尚未跑完（初次 init 时序）
		ClientProviderConfig pc = state.getOrCreate(selectedProvider);
		apiKeyField.setText(pc.apiKey == null ? "" : pc.apiKey);
		modelField.setText(pc.model == null ? "" : pc.model);
		endpointField.setText(pc.endpoint == null ? "" : pc.endpoint);

		// "强制关闭思考"开关：只在当前查看的 Provider 支持这个能力时可见——
		// 切换 Provider 时（而不只是收到快照时）也要同步，因为玩家在界面里
		// 点左侧列表切换查看的 Provider 是最常见的触发这个刷新的方式。
		// 0.16.2：visible 必须 AND tabVisible——两个 Panel（ServerConfigPanel
		// 和 LocalConfigPanel）用同一套 left/top/right/bottom 坐标，各自的
		// disableThinkingButton 位置完全重叠。如果这里只按 supportsThinking
		// 设 visible，非活动标签页的按钮也会显示出来，和活动标签页的同位置
		// 按钮叠在一起，玩家就会看到"两个强制关闭思考按钮"（一个是 Server
		// 的、一个是 Local 的，值还可能不同）。setVisible(false) 设的 visible
		// 会被这里覆盖，所以必须显式 AND tabVisible 才安全。
		if (disableThinkingButton != null) {
			boolean supportsThinking = ClientConfigState.THINKING_CAPABLE_PROVIDERS.contains(selectedProvider);
			disableThinkingButton.visible = supportsThinking && tabVisible;
			disableThinkingButton.setValue(pc.disableThinking);
		}
	}

	private void applyEditability() {
		boolean isMock = selectedProvider.equals("mock");
		boolean isDeepL = selectedProvider.equals("deepl");
		boolean supportsModelList = !ClientConfigState.NO_MODEL_LIST_SUPPORT.contains(selectedProvider);
		// 1.1.5：可编辑性同时依赖 visibility（权限状态机）和 state.canEdit（服务端快照确认的 op 状态）。
		// visibility == READ_ONLY 时即使 state.canEdit 还没收到快照，也强制灰色。
		boolean canEdit = visibility == PanelVisibility.EDITABLE && state.canEdit;

		// 1.1.5：非 op 玩家打开服务端配置时锁定 Provider 列表——禁止手动滚动 + 禁止点击切换 + 全部灰显。
		// 列表在 init 时已自动定位到 activeProvider，非 op 玩家只能看 activeProvider 的状态，
		// 切到别的 Provider 查看没有意义（看了也改不了）。op 玩家解锁，可自由滚动查看任意 Provider。
		// 灰显配合横幅提示，让玩家一眼看出"这不是给我操作的"，而非"列表能用、只是碰巧不能选"。
		if (listWidget != null) {
			listWidget.setScrollLocked(!canEdit);
			listWidget.setSelectionLocked(!canEdit);
			listWidget.setGreyedOut(!canEdit);
		}

		// 1.1.5：Mock 选中时隐藏 API Key/endpoint/model/disableThinking/恢复默认/获取模型/清除 Key。
		// 非 op 选中时隐藏同样的控件（外加隐藏保存按钮）。两种情况都保留"显示原文"两个开关
		// （客户端个人偏好，与 Provider 和 op 权限都无关）。Mock 保留保存按钮（玩家点保存
		// 就是"把 Mock 设为生效 Provider"）；非 op 隐藏保存按钮（没东西可保存）。
		boolean hideProviderControls = isMock || !canEdit;
		boolean hideSaveButton = !canEdit; // Mock 保留保存按钮

		apiKeyField.visible = tabVisible && !hideProviderControls;
		apiKeyField.active = tabVisible && canEdit && !isMock;
		clearApiKeyButton.visible = tabVisible && !hideProviderControls;
		clearApiKeyButton.active = tabVisible && canEdit && !isMock;
		modelField.visible = tabVisible && !hideProviderControls;
		modelField.active = tabVisible && canEdit && !isMock && !isDeepL;
		endpointField.visible = tabVisible && !hideProviderControls;
		endpointField.active = tabVisible && canEdit && !isMock;
		if (disableThinkingButton != null) {
			boolean supportsThinking = ClientConfigState.THINKING_CAPABLE_PROVIDERS.contains(selectedProvider);
			disableThinkingButton.visible = tabVisible && !hideProviderControls && supportsThinking;
			disableThinkingButton.active = tabVisible && canEdit && supportsThinking;
		}
		if (resetEndpointButton != null) {
			resetEndpointButton.visible = tabVisible && !hideProviderControls;
		}
		if (fetchModelsButton != null) {
			fetchModelsButton.visible = tabVisible && !hideProviderControls;
			fetchModelsButton.active = tabVisible && canEdit && supportsModelList;
		}
		saveButton.visible = tabVisible && !hideSaveButton;
		saveButton.active = tabVisible && canEdit;
		// "显示原文"两个开关是客户端个人偏好，不受 op 权限限制——只依赖标签页可见性。
		if (showOriginalTextButton != null) showOriginalTextButton.active = tabVisible;
		if (showOriginalTextInChatButton != null) showOriginalTextInChatButton.active = tabVisible;

		if (!canEdit) {
			statusMessage = Text.translatable("mccf.config.no_permission");
			statusColor = Colors.YELLOW;
		}
	}

	@Override
	protected void onTabVisibilityChanged() {
		if (apiKeyField == null) return; // 尚未 buildRightPanel
		applyEditability();
		// retryButton 的可见性平时完全由 renderExtra 每帧根据超时状态计算——但
		// renderExtra 只在这个标签页是当前活动标签时才会被调用（见该方法开头的
		// 注释）。切到另一个标签页时 renderExtra 不再执行，如果切走那一刻恰好
		// 处于"超时显示中"的状态，retryButton.visible 会保持 true 残留下去
		// （虽然 setVisible(false) 已经把它设为不可见，但如果玩家再切回来，
		// 在下一帧 renderExtra 重新计算之前，理论上有一帧的状态是不确定的）。
		// 这里在切走/切入时都强制先关闭，确保每次进入这个标签页都是从"未显示
		// 重试按钮"的干净状态开始，由 renderExtra 决定要不要重新显示它。
		if (retryButton != null && !tabVisible) {
			retryButton.visible = false;
			retryButton.active = false;
		}
	}

	/** 收到服务端最新快照后调用。 */
	public void onSnapshotUpdated() {
		// 收到了真实回包，不再是"等待中/超时"状态——归零计时，renderExtra 里
		// hasReceivedSnapshot 已经为 true，会走"显示 statusMessage"分支，
		// 但归零这个字段仍有意义：万一以后新增"重新连接后再次显示加载"之类
		// 的场景，这个字段的状态要保持干净，不留一个过期的历史时间戳。
		snapshotRequestedAtMillis = 0;
		// 1.1.7 修复：服务端切换 Provider 后选中框不跟随 + 不滚动的问题。
		//
		// 1.1.4 删除了 `selectedProvider = state.activeProvider`（防止 init 重建后
		// requestSnapshot 回包把玩家正在查看的 Provider 重置回 activeProvider）。
		// 但这导致另一个问题：别人/命令改了 activeProvider 后，客户端收到广播快照，
		// 选中框不跟随——DeepSeek 改成 Kimi 后，选中框还在 DeepSeek，只有 Kimi 有 ✓。
		//
		// 用 state.previousActiveProvider 区分两种场景：
		// - selectedProvider == previousActiveProvider：玩家没在看别的 Provider（选中框
		//   就在旧 activeProvider 上），activeProvider 变了应跟随——这是"别人改配置，我也没在看别的"。
		// - selectedProvider != previousActiveProvider：玩家在看别的 Provider（手动切到 B
		//   查看），activeProvider 变了不打断玩家查看——这是"我在看 B，别人改了 A，保留 B"。
		//   （1.1.4 注释里"不打断玩家正在查看的 Provider"指的就是这种情况。）
		//
		// previousActiveProvider 由 ClientConfigState.applySnapshot 在更新 activeProvider
		// 之前记录——此时 onSnapshotUpdated 还没被调用，state.previousActiveProvider 是旧值。
		if (selectedProvider.equals(state.previousActiveProvider)
				&& !selectedProvider.equals(state.activeProvider)) {
			selectedProvider = state.activeProvider;
		}
		if (listWidget != null) {
			listWidget.setSelectedProvider(selectedProvider);
			// 1.1.7：选中框跟随到新 activeProvider 后，如果该 Provider 不在当前可视区域内
			// （跨页），自动滚动到该 Provider——否则玩家看不到选中框切过去了。
			listWidget.scrollToProvider(selectedProvider);
		}
		refreshFieldsFromState();
		applyEditability();
		// 两个"显示原文"开关的值来自客户端偏好，不随服务端快照变化，但 init 重建后
		// 需要重新同步按钮显示（init 会创建新 widget，初始值用的是构造时的值，
		// 如果玩家在别的界面改过，这里要刷新到最新）。
		ClientOnlyTranslationConfig localConfig = ClientOnlyTranslationConfig.get();
		if (showOriginalTextButton != null) showOriginalTextButton.setValue(localConfig.showOriginalText);
		if (showOriginalTextInChatButton != null) showOriginalTextInChatButton.setValue(localConfig.showOriginalTextInChat);
		statusMessage = Text.translatable("mccf.config.saved");
		statusColor = Colors.GREEN;
	}

	/** 收到服务端模型列表查询结果后调用。 */
	public void onModelsResult(boolean success, String providerId, java.util.List<String> models, String error) {
		if (success) {
			if (models.isEmpty()) {
				statusMessage = Text.translatable("mccf.config.models_empty");
				statusColor = Colors.YELLOW;
				return;
			}
			statusMessage = Text.translatable("mccf.config.models_opened");
			statusColor = Colors.YELLOW;
			if (MinecraftClient.getInstance() != null) {
				String currentModel = state.getOrCreate(providerId).model;
				MinecraftClient.getInstance().setScreen(
						new ModelSelectionScreen(screen, providerId, models, currentModel, this::setModelFromSelection));
			}
		} else {
			statusMessage = Text.translatable("mccf.config.fetch_failed", error);
			statusColor = Colors.YELLOW;
		}
	}

	/**
	 * ModelSelectionScreen 选中模型后的回调。
	 *
	 * 1.1.3 修复：用方法参数 providerId 而不是 selectedProvider 字段写入 state——
	 * ModelSelectionScreen 关闭可能触发 init() 重建，新 panel 的 selectedProvider
	 * 可能和发起获取模型时的旧 panel 不同（虽然 1.1.3 的 preservedSelectedProvider
	 * 修复已经让两者一致，但用 providerId 参数更明确、不依赖 panel 实例状态）。
	 * modelField.setText 仍然只在 modelField 非 null 时调用——重建后旧 panel 的
	 * modelField 已被丢弃，新 panel 的 modelField 会通过 refreshFieldsFromState
	 * 从 state（已被这里写入）读取最新值，不需要旧 panel 手动 setText。
	 */
	public void setModelFromSelection(String model) {
		// providerId 由 onModelsResult 创建 ModelSelectionScreen 时传入，固定为
		// 发起获取模型时玩家选中的 Provider，不随 panel 重建变化。
		String providerId = selectedProvider;
		state.getOrCreate(providerId).model = model;
		if (modelField != null) {
			modelField.setText(model);
		}
	}

	private void onResetEndpoint() {
		if (!state.canEdit) return;
		ClientProviderConfig pc = state.getOrCreate(selectedProvider);
		pc.endpoint = "";
		pc.isCustomEndpoint = false;
		pc.endpointAction = ClientProviderConfig.EndpointAction.RESET_DEFAULT;
		endpointField.setText("");
		statusMessage = Text.translatable("mccf.config.endpoint_reset");
		statusColor = Colors.YELLOW;
	}

	/**
	 * "强制关闭思考"开关的点击回调。
	 *
	 * 打开（false -> true）时先弹出确认警告，说明部分新一代模型可能不支持
	 * 这个设置（会导致翻译请求失败），同时说明关闭思考的好处（翻译更快、
	 * 更省 token）——应用户明确要求"警告屏幕"。玩家点确认才真正把值写入
	 * {@code ClientProviderConfig#disableThinking}；点取消则把按钮显示值
	 * 还原回 false（避免按钮显示"开"但实际配置值还是"关"这种不一致）。
	 *
	 * 关闭（true -> false）不需要确认——关掉一个"可能有风险的设置"永远是
	 * 安全的操作，不用像打开那样拦截一次。
	 *
	 * 没有联网/API 查询能判断"当前这个具体模型是否真的支持关闭思考"（调研
	 * 过 DeepSeek/Kimi/Claude/Gemini/智谱，没有任何一家的 listModels 接口
	 * 返回这个信息），所以警告是通用的、不针对具体模型名做智能判断——具体
	 * 某个模型是否真的支持，需要玩家自己验证。
	 */
	private void onDisableThinkingToggled(net.minecraft.client.gui.widget.CyclingButtonWidget<Boolean> button, boolean newValue) {
		if (!newValue) {
			// 关闭：直接生效，不需要确认。
			state.getOrCreate(selectedProvider).disableThinking = false;
			return;
		}

		MinecraftClient.getInstance().setScreen(new net.minecraft.client.gui.screen.ConfirmScreen(
				confirmed -> {
					// 必须先更新状态，再 setScreen 切回配置界面——setScreen 会触发
					// MCCFConfigScreen.init() 重建所有面板控件，重建时 refreshFieldsFromState
					// 会从 state 读取 disableThinking 的值来设置按钮显示。如果先 setScreen
					// 再改状态，init 重建读到的还是旧值 false，按钮就显示"关"；等状态改成
					// true 时按钮已经画完了，不会再刷新——这就是"点'是'后仍显示关"的根因。
					// 旧代码反过来了（先 setScreen 后改状态），1.1.1 修复。
					// 取消分支不需要手动 button.setValue(false)——init 重建会创建全新的
					// 按钮 widget，lambda 里捕获的 button 是旧 widget，操作它没有意义；
					// 新按钮会从 state（仍是 false）正确读取并显示"关"。
					if (confirmed) {
						state.getOrCreate(selectedProvider).disableThinking = true;
					}
					MinecraftClient.getInstance().setScreen(screen);
				},
				Text.translatable("mccf.config.disable_thinking_warning_title"),
				Text.translatable("mccf.config.disable_thinking_warning_body")));
	}

	private void onClearApiKey() {
		if (!state.canEdit) return;
		apiKeyField.setText("");
		userClearedApiKey = true;
		statusMessage = Text.translatable("mccf.config.api_key_cleared");
		statusColor = Colors.YELLOW;
	}

	private void onFetchModels() {
		if (!state.canEdit) return;
		if (!ClientPlayNetworking.canSend(RequestModelsPayload.ID)) {
			statusMessage = Text.translatable("mccf.config.not_connected");
			statusColor = Colors.YELLOW;
			return;
		}

		com.google.gson.JsonObject requestRoot = new com.google.gson.JsonObject();
		requestRoot.addProperty("providerId", selectedProvider);
		requestRoot.addProperty("apiKey", apiKeyField.getText());
		requestRoot.addProperty("endpoint", endpointField.getText());

		ClientPlayNetworking.send(new RequestModelsPayload(requestRoot.toString()));
		statusMessage = Text.translatable("mccf.config.fetching_models");
		statusColor = Colors.YELLOW;
	}

	private void onExportLog() {
		String result = LogExporter.export(LogExporter.ExportMode.BOTH);
		statusMessage = Text.literal(result);
		statusColor = Colors.YELLOW;
	}

	/**
	 * 保存：把当前查看的 Provider 的字段改动写入，并且——按用户明确要求——
	 * 把当前查看的 Provider 直接设为待启用目标（{@code pendingActiveProvider}）。
	 * 不再需要单独的"设为默认"按钮，保存这一步就代表"我选的就是这个"。
	 */
	private void onSave() {
		if (visibility != PanelVisibility.EDITABLE || !state.canEdit) return;
		if (!ClientPlayNetworking.canSend(UpdateConfigPayload.ID)) {
			statusMessage = Text.translatable("mccf.config.not_connected");
			statusColor = Colors.YELLOW;
			return;
		}

		// 1.1.5 Mock 警告（B1c 第二步）：保存 Mock 时弹确认屏幕。
		// 已保存 Mock（activeProvider == mock）不弹——玩家已经确认过了。
		if (selectedProvider.equals("mock") && !"mock".equals(state.activeProvider)) {
			MinecraftClient.getInstance().setScreen(new net.minecraft.client.gui.screen.ConfirmScreen(
					confirmed -> {
						// 1.1.7 修复：必须先 setScreen 后 performSave（和 LocalConfigPanel 一致）。
						// 旧版顺序相反（先 performSave 后 setScreen），setScreen 触发 init 重建，
						// 新 panel 构造函数调 requestSnapshot() 发 RequestConfigPayload。服务端回的
						// ConfigSnapshotPayload 触发 applySnapshot，第81行 pendingActiveProvider 被重置
						// 为 activeProvider——如果 requestSnapshot 回包在 UpdateConfigPayload 的
						// broadcast 之前到达，state 会被临时重置回旧值，导致第一次点确认"不应用"。
						// 改为先 setScreen（触发 init 重建 + requestSnapshot），然后通过
						// triggerServerPerformSave 在新 panel 上执行 performSave——确保 performSave
						// 在 init 重建之后执行，且读的是新 panel 的字段。
						MinecraftClient.getInstance().setScreen(screen);
						if (confirmed && screen instanceof MCCFConfigScreen mccf) {
							mccf.triggerServerPerformSave();
						}
					},
					Text.translatable("mccf.config.mock_warning_title"),
					Text.translatable("mccf.config.mock_warning_body")));
			return;
		}

		performSave();
	}

	/**
	 * 1.1.7 新增：供 MCCFConfigScreen 在 init 重建后调用——Mock 确认弹窗回调需要
	 * 先 setScreen 触发重建，再在新 panel 上执行 performSave。performSave 读的是
	 * 新 panel 的 selectedProvider（通过 preservedSelectedProvider 保留了 "mock"），
	 * Mock 不需要 apiKey/model/endpoint（字段为空不影响），state 是单例。
	 */
	void triggerPerformSave() {
		performSave();
	}

	/** 实际执行保存——从 onSave 抽取，供 Mock 确认回调和正常路径共用。 */
	private void performSave() {
		ClientProviderConfig pc = state.getOrCreate(selectedProvider);
		String enteredKey = apiKeyField.getText();
		if (userClearedApiKey) {
			pc.apiKey = "";
		} else if (!enteredKey.isBlank()) {
			pc.apiKey = enteredKey;
		}
		userClearedApiKey = false;

		pc.model = modelField.getText();
		String enteredEndpoint = endpointField.getText();
		pc.endpoint = enteredEndpoint;
		if (!enteredEndpoint.isBlank()) {
			pc.endpointAction = ClientProviderConfig.EndpointAction.CUSTOM;
			pc.isCustomEndpoint = true;
		} else if (pc.endpointAction != ClientProviderConfig.EndpointAction.RESET_DEFAULT) {
			pc.endpointAction = ClientProviderConfig.EndpointAction.UNCHANGED;
			pc.isCustomEndpoint = false;
		}

		// 保存 = 同时把当前查看的 Provider 设为激活目标，见方法注释。
		state.pendingActiveProvider = selectedProvider;

		ClientPlayNetworking.send(new UpdateConfigPayload(state.buildUpdateJson()));
		statusMessage = Text.translatable("mccf.config.saving");
		statusColor = Colors.YELLOW;
	}

	/** 玩家点击"重试"按钮：重新发送快照请求，重置超时计时。 */
	private void onRetry() {
		requestSnapshot();
	}

	@Override
	protected void renderExtra(DrawContext context, int mouseX, int mouseY, float delta) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int centerX = screen.width / 2;

		Text providerTitle = Text.translatable(ClientConfigState.providerNameKey(selectedProvider));
		int titleY = top - 14;
		context.drawCenteredTextWithShadow(textRenderer, providerTitle, centerX, titleY, Colors.WHITE);

		// 1.1.5 新增：右侧配置区顶部横幅渲染。
		// 横幅优先级（从高到低）：Mock 红条（已保存）> Mock 黄条（仅查看）> 权限黄条（非 op）。
		// 横幅位置：右侧配置区顶部（API Key 输入框上方），高度约 24px（两行文字）。
		int panelLeft = left + LIST_WIDTH + GUTTER;
		int panelWidth = right - panelLeft;
		int bannerY = top - 4; // 在控件区顶部稍微往上一点，给横幅留位置

		// Mock 横幅：选中查看 Mock 时黄条，已保存 Mock 时红条
		boolean isMockSelected = "mock".equals(selectedProvider);
		boolean isMockActive = "mock".equals(state.activeProvider);
		int bannerHeight = 0;
		if (isMockActive) {
			// 红色常驻条（保存后）
			bannerHeight = drawBanner(context, textRenderer, panelLeft, bannerY, panelWidth,
					Text.translatable("mccf.config.mock_warning_persistent"), 0x55FF0000, 0xFF5555);
		} else if (isMockSelected) {
			// 黄色警告条（仅查看未保存）
			bannerHeight = drawBanner(context, textRenderer, panelLeft, bannerY, panelWidth,
					Text.translatable("mccf.config.mock_warning"), 0x55FFFF00, 0xFFFF55);
		} else if (visibility == PanelVisibility.READ_ONLY) {
			// 权限黄条（非 op）
			bannerHeight = drawBanner(context, textRenderer, panelLeft, bannerY, panelWidth,
					Text.translatable("mccf.config.no_permission"), 0x55FFFF00, 0xFFFF55);
			// 1.1.5：非 op 时右侧控件全部隐藏（见 applyEditability），留下一大块空白。
			// 在控件区第一行位置（横幅下方、原本是 API Key 输入框的位置）画一行说明文字，
			// 告诉玩家当前生效的 Provider 名称——非 op 只需要知道"现在用的是哪个 Provider"。
			// 位置在横幅下方 8px——横幅高度现在是自适应的（换行后可能不止 20px），
			// 不能再用固定 28px 偏移，否则多行横幅下方会重叠。用 drawBanner 返回的实际高度计算。
			Text readOnlyHint = Text.translatable("mccf.config.readonly_hint",
					Text.translatable(ClientConfigState.providerNameKey(state.activeProvider)));
			int hintY = bannerY + bannerHeight + 8;
			String hintStr = readOnlyHint.getString();
			// 文字可能超宽，按 panelWidth 换行绘制——复用 renderLeftBottomHints 的换行逻辑
			// 不合适（那是左对齐到列表宽度），这里直接用 textRenderer.trimToWidth 手动换行。
			int lineHeight = textRenderer.fontHeight + 2;
			int currentY = hintY;
			String remaining = hintStr;
			while (!remaining.isEmpty()) {
				String trimmed = textRenderer.trimToWidth(remaining, panelWidth);
				if (trimmed.isEmpty()) trimmed = remaining.substring(0, 1);
				context.drawTextWithShadow(textRenderer, trimmed, panelLeft, currentY, Colors.LIGHT_GRAY);
				currentY += lineHeight;
				if (trimmed.length() >= remaining.length()) break;
				remaining = remaining.substring(trimmed.length());
			}
		}

		// 三态判断（快照请求状态），同 1.1.2 逻辑
		boolean neverRequested = snapshotRequestedAtMillis == 0;
		boolean timedOut = !neverRequested
				&& !state.hasReceivedSnapshot
				&& System.currentTimeMillis() - snapshotRequestedAtMillis > SNAPSHOT_TIMEOUT_MS;

		HintLine statusLine;
		if (state.hasReceivedSnapshot) {
			statusLine = new HintLine(statusMessage, statusColor);
		} else if (neverRequested) {
			statusLine = new HintLine(Text.empty(), Colors.LIGHT_GRAY);
		} else if (timedOut) {
			statusLine = new HintLine(Text.translatable("mccf.config.not_installed"), 0xFF5555);
		} else {
			statusLine = new HintLine(Text.translatable("mccf.config.loading"), Colors.LIGHT_GRAY);
		}

		int afterHintsY = renderLeftBottomHints(context, left, bottom + 6, statusLine);

		if (retryButton != null) {
			retryButton.visible = timedOut;
			retryButton.active = timedOut;
			if (timedOut) {
				retryButton.setPosition(left, afterHintsY + 4);
			}
		}
	}

	/**
	 * 1.1.5 新增：在右侧配置区顶部画一个横幅（背景色 + 文字），用于权限提示和 Mock 警告。
	 *
	 * 1.1.5 修复"横幅字显示不全"：旧版用 trimToWidth 截断文字（超宽直接砍掉），
	 * 导致德语/法语/俄语等长文案在窄配置区里被截断看不全。改为按 width-8 宽度换行，
	 * 横幅高度随行数自适应（每行 fontHeight+2，上下各 2px padding）。
	 * 返回横幅实际高度，调用方据此调整后续元素位置（如非 op 提示文字），
	 * 避免固定 28px 偏移在多行横幅下方重叠。
	 *
	 * @param x         横幅左边界
	 * @param y         横幅顶部 y 坐标
	 * @param width     横幅宽度
	 * @param text      横幅文字
	 * @param bgColor   背景色（含透明度，如 0x55FFFF00）
	 * @param textColor 文字颜色
	 * @return 横幅实际高度（像素），调用方用于定位后续元素
	 */
	private int drawBanner(DrawContext context, net.minecraft.client.font.TextRenderer textRenderer,
			int x, int y, int width, Text text, int bgColor, int textColor) {
		// 先按 width-8 宽度把文字拆成多行——trimToWidth 返回"不超宽的最长前缀"，
		// 循环切掉已处理部分直到剩余为空，得到所有行。和 renderLeftBottomHints 的
		// 换行逻辑同款手法（不是重复踩坑，是同一套换行工具用在横幅场景）。
		String remaining = text.getString();
		java.util.List<String> lines = new java.util.ArrayList<>();
		while (!remaining.isEmpty()) {
			String trimmed = textRenderer.trimToWidth(remaining, width - 8);
			if (trimmed.isEmpty()) {
				// 极端情况（单字符都超宽，防御性避免死循环）：至少取一个字符。
				trimmed = remaining.substring(0, 1);
			}
			lines.add(trimmed);
			if (trimmed.length() >= remaining.length()) break;
			remaining = remaining.substring(trimmed.length());
		}

		int lineHeight = textRenderer.fontHeight + 2;
		int padding = 4; // 上下各 2px
		int bannerHeight = lines.size() * lineHeight + padding;

		// 横幅背景
		context.fill(x, y, x + width, y + bannerHeight, bgColor);

		// 每行文字水平居中绘制，从顶部 padding/2 开始往下排
		int currentY = y + padding / 2;
		for (String line : lines) {
			int textX = x + (width - textRenderer.getWidth(line)) / 2;
			context.drawTextWithShadow(textRenderer, line, textX, currentY, textColor);
			currentY += lineHeight;
		}

		return bannerHeight;
	}
}
