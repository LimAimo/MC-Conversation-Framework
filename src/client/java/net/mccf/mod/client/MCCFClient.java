package net.mccf.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.chat.ClientOnlyChatTranslator;
import net.mccf.mod.client.config.ClientConfigState;
import net.mccf.mod.client.config.ClientOnlyTranslationConfig;
import net.mccf.mod.client.config.MCCFConfigScreen;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.client.subtitle.HotbarSubtitleRenderer;
import net.mccf.mod.client.subtitle.SubtitleManager;
import net.mccf.mod.network.ConfigSnapshotPayload;
import net.mccf.mod.network.LanguageReportPayload;
import net.mccf.mod.network.ModelsResultPayload;
import net.mccf.mod.network.SubtitlePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * MCCF 客户端入口。
 *
 * 职责：
 * 1. 加入服务器时上报本机 Minecraft 语言设置（原则："客户端自动检测语言"）。
 * 2. 接收服务端字幕包，按 displayMode 分流：VISIBLE（看得见说话者）走原版聊天栏、
 *    AUDIBLE（看不到说话者）走 {@link HotbarSubtitleRenderer} 物品栏上方字幕。
 * 3. 接收服务端配置快照包，写入 {@link ClientConfigState}，供配置界面显示。
 * 4. 注册 AUDIBLE 模式的 HUD 字幕渲染器（{@link HotbarSubtitleRenderer}）。
 * 5. 注册按键绑定，独立于 ModMenu 呼出配置界面（{@link MCCFConfigScreen}）。
 *
 * 关于 VISIBLE 模式走聊天栏的决策历史：
 * 早期版本（0.3.0~0.4.0）曾尝试用 WorldRenderEvents.AFTER_ENTITIES 把字幕
 * 渲染到说话者模型旁边的世界空间里（WorldSubtitleRenderer），但根因始终未定位、
 * 字幕实测不显示。0.4.0 起临时把 VISIBLE 改走原版聊天栏作为绕开方案；0.16.0
 * 正式确认这个绕开方案转正——WorldSubtitleRenderer 类已删除，WorldRenderEvents
 * 不再注册，VISIBLE 走聊天栏成为正式行为而非临时降级。HearingResolver 仍然区分
 * VISIBLE/AUDIBLE 两档（距离 + 视线判定），只是 VISIBLE 的展示载体从"世界空间
 * 悬浮字幕"变成"原版聊天栏"——近处说话走聊天框、远处喊话走物品栏字幕的语义不变。
 *
 * 注：目标版本固定在 1.21.1（1.21.x 系列早期稳定版本）。KeyBinding 的分类参数在
 * 1.21.9 起从字符串改为 KeyBinding.Category 对象，按键检测方法也从 wasPressed()
 * 改名为 consumeClick()；1.21.1 仍用旧写法（字符串分类 + wasPressed()），本类按此实现。
 * （早期版本还依赖 WorldRenderEvents 实现世界空间字幕，该 API 在 1.21.9+ 因渲染
 * 管线重构被移除；0.16.0 起本项目不再使用 WorldRenderEvents，这个版本兼容顾虑
 * 已随之消除。）
 */
public class MCCFClient implements ClientModInitializer {

	private static KeyBinding openConfigKey;
	private static KeyBinding openHistoryKey;

	/**
	 * 首次启动提示是否已经发过。1.1.2 起从内存 static boolean 改为持久化到
	 * {@link ClientOnlyModeManager}（client-mode.json），避免老玩家每次重启客户端
	 * 被同一条提示刷屏。
	 *
	 * 这里保留字段是为了让 JOIN 事件回调里读起来更直观，实际查询/标记都委托给
	 * ClientOnlyModeManager 的持久化版本。重启后从磁盘加载的值会覆盖此字段。
	 */
	private static boolean tipped = false;

	@Override
	public void onInitializeClient() {
		// 纯客户端模式：读取玩家上次保存的手动模式覆盖设置，并注册本地聊天翻译。
		// 详见 ClientOnlyModeManager / ClientOnlyChatTranslator 的类注释。
		ClientOnlyModeManager.load();
		ClientOnlyChatTranslator.register();

		// 1.1.6：聊天历史记录持久化——从 config/mccf/chat-history.json 加载上次的历史，
		// 重进游戏/重连服务器后保留。详见 ChatHistoryManager 类注释。
		net.mccf.mod.client.history.ChatHistoryManager.loadFromFile();

		// 0.16.0 起 VISIBLE 模式走原版聊天栏（不再有世界空间渲染器），客户端只需要
		// 注册 AUDIBLE 模式的 HUD 渲染器。WorldRenderEvents.AFTER_ENTITIES 的注册
		// 已随 WorldSubtitleRenderer 一并移除——见本类 Javadoc 的决策历史说明。
		HotbarSubtitleRenderer hotbarRenderer = new HotbarSubtitleRenderer();

		// 挂载在所有原版 HUD 层之后渲染，确保字幕不被物品栏/生命值等遮挡。
		// 1.21.1 上用 HudRenderCallback.EVENT（旧 API）；HudElementRegistry 是
		// Fabric API 后续版本（约 1.21.6+）才引入的新 API，1.21.1 上不存在。
		HudRenderCallback.EVENT.register((context, tickCounter) -> hotbarRenderer.render(context, tickCounter));

		ClientPlayNetworking.registerGlobalReceiver(SubtitlePayload.ID, (payload, context) ->
				context.client().execute(() -> {
					// 说话者是不是自己：SpatialChatHandler#dispatchSelfEcho 会给说话者本人发一份
					// 原文回显包（speakerId == 自己的 UUID，displayMode 跟随本次发言时其他听众的
					// 主导模式，见该方法注释）。这个分支必须最先判断——自己的回显不该走
					// "仅译文一行"格式（那是给别人消息设计的），也不该被当成"我收到了自己说的话"
					// 走 client-only 本地翻译流程。
					boolean isSelf = context.client().player != null
							&& payload.speakerId().equals(context.client().player.getUuid());

					if (isSelf) {
						// 自己发的消息：按服务端判定的主导模式展示，不翻译、不加任何标记——
						// 玩家显然知道自己说了什么，这里只是让消息重新出现在自己的视野里
						// （因为 SpatialChatHandler 拦截了原版广播，不补这一条自己就完全看不到）。
						// VISIBLE（多数听众能看到我）走聊天框，AUDIBLE（多数听众只能听到）走
						// 物品栏字幕——与 SpatialChatHandler#dispatchSelfEcho 的"跟随主导模式"
						// 逻辑对应，让自己的回显形式与当前对话情境（面对面 / 隔墙喊话）一致。
						if ("AUDIBLE".equals(payload.displayMode())) {
							SubtitleManager.onReceive(payload);
						} else {
							addVisibleToChatHud(payload.speakerName(), payload.originalText());
						}
						recordConversationHistory(payload,
								net.mccf.mod.client.history.ChatHistoryEntry.Source.SELF);
					} else if (ClientOnlyModeManager.isClientOnlyModeActive()) {
					// 退回方案：旧服务端不认识 ModePreferencePayload，依旧会拦截原版聊天改发
					// SubtitlePayload。客户端收不到 CHAT 事件，ClientOnlyChatTranslator 的 CHAT
					// 监听器不会触发，只能从 SubtitlePayload 里拿文本走本地翻译。
					// 优先用 originalText（原文）让本地 Provider 按玩家自己语言翻译；
					// 1.1.1 起服务端始终携带原文，但兼容旧服务端时仍需退到 translatedText。
					// 1.1.2 起把 payload.speakerName() 也传过去，让历史记录能正确显示说话者名
					// 而不是解析失败留空显示 "?"——SubtitlePayload 的原文不带 "<玩家名>" 前缀，
					// 旧版 translateAndAppend 内部的 parseSpeakerName 解析不到，必须显式传入。
					//
					// 1.1.8 修复：说话者非 client-only 时（包括 1.1.8 新增的"服务端给 client-only
					// 听众发 SubtitlePayload"路径，以及旧服务端退回方案），原版广播被服务端拦截，
					// client-only 听众收不到原版 CHAT 事件，ChatHud 里不会有原文。必须在这里
					// 手动 addVisibleToChatHud 显示原文，否则玩家只看到翻译追加行看不到原文。
					// 说话者也是 client-only 时 onChatMessage return true 放行原版广播，不会走到
					// 这里（dispatchTo 不被调用），所以不会出现"原版 CHAT + 这里手动"重复显示。
					String sourceText = payload.originalText();
					if (sourceText == null || sourceText.isBlank()) sourceText = payload.translatedText();
					if (sourceText != null && !sourceText.isBlank()) {
						addVisibleToChatHud(payload.speakerName(), sourceText);
						ClientOnlyChatTranslator.translateAndAppend(
								sourceText, payload.speakerId().toString(), payload.speakerName());
					}
					} else if ("VISIBLE".equals(payload.displayMode())) {
						// VISIBLE（看得到说话者）走原版聊天框。这是 0.16.0 起的正式行为——
						// 早期版本（0.3.0~0.4.0）曾尝试用世界空间渲染器（WorldSubtitleRenderer）
						// 把字幕画到说话者模型旁边，但根因始终未定位、实测不显示，0.4.0 起临时
						// 改走聊天框，0.16.0 正式确认这个方案转正（WorldSubtitleRenderer 已删除，
						// 详见本类 Javadoc 的决策历史）。服务端按距离/视线把听众拆成 visible /
						// audibleOnly 两批，只有 visible 的人会收到 VISIBLE 包，因此聊天栏里天然
						// 只出现"我看得见的那几位"说的话——满足"聊天框内容只能是我看到的这几位"
						// 的需求。AUDIBLE（看不到）依旧走 SubtitleManager → 物品栏上方字幕，
						// 保持原时长（2.5~8s）不变。
						//
						// 是否显示原文：1.1.1 起改为客户端个人偏好（ClientOnlyTranslationConfig#
						// showOriginalTextInChat），不再由服务端 op 配置控制。服务端始终在
						// originalText 里携带原文，客户端根据本地偏好决定是否额外追加一行原文。
						// 开启时按 <名字> 原文 + ⇄ 译文 两行展示（模仿纯客户端模式
						// ClientOnlyChatTranslator 的追加格式）；关闭时维持"仅译文一行"格式。
						boolean showOriginal = ClientOnlyTranslationConfig.get().showOriginalTextInChat;
						if (showOriginal && payload.originalText() != null && !payload.originalText().isBlank()) {
							addVisibleToChatHud(payload.speakerName(), payload.originalText());
							MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
									Text.literal("⇄ " + payload.translatedText()).formatted(Formatting.GRAY));
						} else {
							addVisibleToChatHud(payload.speakerName(), payload.translatedText());
						}
						recordConversationHistory(payload,
								net.mccf.mod.client.history.ChatHistoryEntry.Source.VISIBLE);
					} else {
						SubtitleManager.onReceive(payload);
						recordConversationHistory(payload,
								net.mccf.mod.client.history.ChatHistoryEntry.Source.AUDIBLE);
					}
				}));

		// Conversation 参与者名单更新：服务端只在名单真正变化时才发（见服务端
		// SpatialChatHandler#broadcastConversationRoster）。客户端更新本地
		// ConversationRosterManager 记录，并据此生成一条系统提示写入历史——
		// 如果这是这个 Conversation 第一次被记录，提示"开始了一段新对话"；
		// 否则提示"XX 加入了对话"（列出这次真正新增的人，可能是一个或多个）。
		ClientPlayNetworking.registerGlobalReceiver(
				net.mccf.mod.network.ConversationRosterPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					boolean isFirst = net.mccf.mod.client.history.ConversationRosterManager
							.isFirstRoster(payload.conversationId());
					var newlyAdded = net.mccf.mod.client.history.ConversationRosterManager.update(
							payload.conversationId(), payload.participantIds(), payload.participantNames());

					if (isFirst) {
						net.mccf.mod.client.history.ChatHistoryManager.recordSystemEvent(
								new net.mccf.mod.client.history.ChatHistorySystemEvent(
										payload.conversationId(),
										net.mccf.mod.client.history.ChatHistorySystemEvent.Type.CONVERSATION_STARTED,
										java.util.List.of(),
										System.currentTimeMillis()));
					} else if (!newlyAdded.isEmpty()) {
						List<String> names = newlyAdded.stream()
								.map(net.mccf.mod.client.history.ConversationRosterManager.RosterEntry::displayName)
								.toList();
						net.mccf.mod.client.history.ChatHistoryManager.recordSystemEvent(
								new net.mccf.mod.client.history.ChatHistorySystemEvent(
										payload.conversationId(),
										net.mccf.mod.client.history.ChatHistorySystemEvent.Type.PARTICIPANT_JOINED,
										names,
										System.currentTimeMillis()));
					}
				}));

		// 配置界面数据同步：服务端下发的快照写入本地状态，若配置 Screen 正开着则刷新其显示。
		ClientPlayNetworking.registerGlobalReceiver(ConfigSnapshotPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					ClientConfigState.get().applySnapshot(payload);
					if (context.client().currentScreen instanceof MCCFConfigScreen screen) {
						screen.onSnapshotUpdated();
					}
				}));

		// 配置界面"一键获取模型"的结果：解析 JSON，转发给正开着的配置 Screen 展示。
		ClientPlayNetworking.registerGlobalReceiver(ModelsResultPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					if (!(context.client().currentScreen instanceof MCCFConfigScreen screen)) return;
					try {
						com.google.gson.JsonObject root =
								com.google.gson.JsonParser.parseString(payload.json()).getAsJsonObject();
						boolean success = root.has("success") && root.get("success").getAsBoolean();
						String providerId = root.has("providerId") ? root.get("providerId").getAsString() : "";
						if (success) {
							java.util.List<String> models = new java.util.ArrayList<>();
							if (root.has("models")) {
								root.getAsJsonArray("models").forEach(e -> models.add(e.getAsString()));
							}
							screen.onModelsResult(true, providerId, models, null);
						} else {
							String error = root.has("error") ? root.get("error").getAsString() : "Unknown error";
							screen.onModelsResult(false, providerId, java.util.List.of(), error);
						}
					} catch (Exception e) {
						screen.onModelsResult(false, "", java.util.List.of(), "Failed to parse response.");
					}
				}));

		// 每次加入服务器（含切换服务器）时上报当前客户端语言设置，并刷新
		// "服务器是否装了 MCCF" 的自动检测结果（纯客户端模式判定的依据之一）。
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String language = detectClientLanguage(client);
			ClientPlayNetworking.send(new LanguageReportPayload(language));
			ClientOnlyModeManager.onJoinServer();

			// O1 首次启动提示：本 Mod 的配置按键没有默认绑定，玩家装完可能完全不知道
			// 有这个 Mod 存在。1.1.2 起从内存 static 标记改为持久化到 client-mode.json，
			// 老玩家重启客户端不会被同一条提示刷屏（升级到 1.1.2 后会再提示一次让用户
			// 感知到改进，之后不再重复）。
			// 放在 JOIN 事件里（而不是 onInitializeClient）是因为 client.player 在
			// 初始化阶段还不存在，发消息必须等进入游戏世界后才能调用。
			// 1.1.2 起提示文案同时给出"ModMenu 入口"和"按键绑定"两条路径——
			// 不是所有玩家都用按键绑定（默认未绑定），ModMenu 是更直观的入口。
			if (!ClientOnlyModeManager.hasTippedFirstJoin() && client.player != null) {
				client.player.sendMessage(
						Text.translatable("mccf.tip.first_join").formatted(Formatting.GRAY), false);
				ClientOnlyModeManager.markTippedFirstJoin();
				tipped = true;
			}
		});

		// 断开连接时清空本地字幕状态 + 对话名单 + 重置模式检测结果。
		// 1.1.6 起不再清空 ChatHistoryManager——历史记录现在持久化到文件，
		// 断开连接后保留，重连/重进游戏后可以回看。ConversationRosterManager
		// 仍要清空（它是服务端下发的实时数据，断连后失效），但 ChatHistoryManager
		// 里缓存的参与者名单（PARTICIPANTS_CACHE）不受影响，分组标题仍能正常显示。
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SubtitleManager.clear();
			net.mccf.mod.client.history.ConversationRosterManager.clear();
			ClientOnlyModeManager.onDisconnect();
		});

		// 独立按键呼出配置界面。默认未绑定具体键位（InputUtil.UNKNOWN_KEY），
		// 玩家需要自己在"设置 -> 控制 -> 按键绑定"里指定一个键；不装 ModMenu 也能用。
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.mccf.open_config",
				InputUtil.Type.KEYSYM,
				InputUtil.UNKNOWN_KEY.getCode(),
				"key.category.mccf.main"
		));

		// 独立按键呼出聊天历史记录界面。同样默认未绑定，需要玩家自己指定键位；
		// 也可以从主配置界面的"聊天历史记录"按钮进入，两个入口等价，快捷键只是
		// 省去打开配置界面这一步的便捷方式（对应用户"需要绑定快捷键或通过模组
		// 设置进入"的诉求，两种方式都提供）。
		openHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.mccf.open_history",
				InputUtil.Type.KEYSYM,
				InputUtil.UNKNOWN_KEY.getCode(),
				"key.category.mccf.main"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConfigKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new MCCFConfigScreen(null));
				}
			}
			while (openHistoryKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new net.mccf.mod.client.config.ChatHistoryScreen(null));
				}
			}
		});

		// 暂停菜单（Esc 菜单）的"聊天历史记录"按钮入口通过 Mixin 注入，见
		// GameMenuScreenMixin。不在这里用 ScreenEvents.afterInit 是因为
		// Screen.addDrawableChild 是 protected，外部包无法调用——Mixin 把代码
		// 注入到 GameMenuScreen 内部才能合法调用 protected 方法。
	}

	private String detectClientLanguage(MinecraftClient client) {
		// Options.language 存的就是 Minecraft locale 格式（如 "zh_cn"），
		// 与我们在 fabric.mod.json / lang 文件里使用的格式一致，无需转换。
		String language = client.options.language;
		return (language == null || language.isBlank()) ? "en_us" : language;
	}

	/**
	 * 把一条聊天消息（SELF/VISIBLE/AUDIBLE 三种来源共用）写入
	 * {@link net.mccf.mod.client.history.ChatHistoryManager}，携带
	 * conversationId/sourceLang/targetLang——这几个字段都是从同一个
	 * SubtitlePayload 上取的，三处调用点字段来源完全一致，抽成公共方法
	 * 避免每处都重复写一遍完整的 record 构造。
	 */
	private static void recordConversationHistory(SubtitlePayload payload,
			net.mccf.mod.client.history.ChatHistoryEntry.Source source) {
		net.mccf.mod.client.history.ChatHistoryManager.record(
				new net.mccf.mod.client.history.ChatHistoryEntry(
						payload.speakerId(), payload.speakerName(),
						payload.originalText(), payload.translatedText(),
						source, System.currentTimeMillis(),
						payload.conversationId(), payload.sourceLang(), payload.targetLang()));
	}

	/**
	 * 把一条 VISIBLE 消息追加进原版聊天栏。默认"仅译文一行"格式，视觉上和
	 * 正常聊天 (<Steve> 文本) 一致；如果服务端开启了 showOriginalTextInChat
	 * （见调用点判断 payload.originalText() 是否非空），则改为两行——第一行
	 * <名字> 原文，第二行灰色 "⇄ 译文"，格式模仿纯客户端模式
	 * ClientOnlyChatTranslator 的追加样式（应用户明确要求）。
	 */
	private static void addVisibleToChatHud(String speakerName, String translatedText) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.inGameHud == null) return;
		if (translatedText == null || translatedText.isBlank()) return;

		Text line = Text.literal("<" + speakerName + "> ").append(Text.literal(translatedText));
		client.inGameHud.getChatHud().addMessage(line);
	}
}
