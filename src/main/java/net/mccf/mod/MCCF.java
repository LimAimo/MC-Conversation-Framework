package net.mccf.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.mccf.mod.command.ConfigSyncHandler;
import net.mccf.mod.command.MCCFCommand;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.context.ConversationManager;
import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.network.ConfigSnapshotPayload;
import net.mccf.mod.network.ConversationRosterPayload;
import net.mccf.mod.network.LanguageReportPayload;
import net.mccf.mod.network.ModePreferencePayload;
import net.mccf.mod.network.ModelsResultPayload;
import net.mccf.mod.network.RequestConfigPayload;
import net.mccf.mod.network.RequestModelsPayload;
import net.mccf.mod.network.SubtitlePayload;
import net.mccf.mod.network.UpdateConfigPayload;
import net.mccf.mod.spatial.ClientOnlyModeRegistry;
import net.mccf.mod.spatial.PlayerLanguageRegistry;
import net.mccf.mod.spatial.SpatialChatHandler;
import net.mccf.mod.translation.TranslationService;
import net.mccf.mod.translation.provider.MockTranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCCF (MC Conversation Framework) 主入口。
 *
 * 整个模组围绕三个核心原则运作，详见项目说明：
 * 1. 语言不再是交流障碍 -> {@link TranslationService}
 * 2. 信息只会传播到真正能够接收到它的人 -> {@link SpatialChatHandler}
 * 3. AI 不应该拥有全知视角，而应该像玩家的感官一样工作 -> {@link ConversationManager}
 *
 * 本类只负责：加载配置、注册网络通道、挂载事件、启动各子系统的生命周期任务。
 * 具体逻辑全部委托给对应子系统，保持入口类简洁。
 */
public class MCCF implements ModInitializer {

	public static final String MOD_ID = "mccf";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 全局单例引用，方便命令/事件访问核心子系统。生命周期与服务器一致。 */
	private static ConversationManager conversationManager;
	private static TranslationService translationService;
	private static WorldDictionary worldDictionary;
	private static MCCFConfig config;

	@Override
	public void onInitialize() {
		LOGGER.info("[MCCF] Initializing MC Conversation Framework...");

		// 1. 加载配置与世界词典
		config = MCCFConfig.loadOrCreate();
		worldDictionary = WorldDictionary.loadOrCreate();

		// 2. 初始化翻译服务，注册全部内置 Provider（Mock + 8 个真实 Provider）并激活配置中指定的 Provider。
		//    每个 Provider 从 config.providers 里读取自己的 API Key/模型/host。
		translationService = new TranslationService(worldDictionary);
		registerAllProviders();

		// 3. 初始化对话上下文管理器
		conversationManager = new ConversationManager(config);

		// 4. 注册网络负载类型（服务端 <-> 客户端）
		PayloadTypeRegistry.playS2C().register(SubtitlePayload.ID, SubtitlePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ConversationRosterPayload.ID, ConversationRosterPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(LanguageReportPayload.ID, LanguageReportPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestConfigPayload.ID, RequestConfigPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ConfigSnapshotPayload.ID, ConfigSnapshotPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UpdateConfigPayload.ID, UpdateConfigPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestModelsPayload.ID, RequestModelsPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ModelsResultPayload.ID, ModelsResultPayload.CODEC);
		// 客户端模式偏好上报通道：客户端告诉服务端"我要不要纯客户端模式"。
		// 旧服务端没注册这个通道，客户端 canSend 检查会返回 false，自动退回从 SubtitlePayload 取文本的方案。
		PayloadTypeRegistry.playC2S().register(ModePreferencePayload.ID, ModePreferencePayload.CODEC);

		// 5. 拦截原版聊天事件，交给空间化处理器
		SpatialChatHandler chatHandler = new SpatialChatHandler(conversationManager, translationService, config);
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(chatHandler::onChatMessage);

		// 6. 每 tick 驱动 Conversation 生命周期检查（超时自动释放等）
		ServerTickEvents.END_SERVER_TICK.register(conversationManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(chatHandler::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			chatHandler.clear();
			conversationManager.clear();
			PlayerLanguageRegistry.clear();
			ClientOnlyModeRegistry.clear();
			translationService.clearCache();
		});

		// 7. 接收客户端语言上报，写入 PlayerLanguageRegistry
		ServerPlayNetworking.registerGlobalReceiver(LanguageReportPayload.ID, (payload, context) ->
				PlayerLanguageRegistry.setLanguage(context.player().getUuid(), payload.languageCode()));

        // Fabric 当前的 play 接收器已在服务器线程回调；显式 execute 仅保留统一调度边界，
        // 不是补救 ConcurrentHashMap 的“半状态”。模式偏好只改变翻译来源。

		ServerPlayNetworking.registerGlobalReceiver(ModePreferencePayload.ID, (payload, context) -> {
			var playerId = context.player().getUuid();
			context.server().execute(() ->
					ClientOnlyModeRegistry.setClientOnly(playerId, payload.clientOnlyMode()));
		});

		// 7b. 配置界面：客户端请求快照 -> 服务端按其 op 状态构造并回发
		ServerPlayNetworking.registerGlobalReceiver(RequestConfigPayload.ID, (payload, context) -> {
			var player = context.player();
			context.server().execute(() ->
					ConfigSyncHandler.sendSnapshotTo(player, config));
		});

		// 7c. 配置界面：客户端提交修改 -> 服务端校验 op 权限后应用并保存
		ServerPlayNetworking.registerGlobalReceiver(UpdateConfigPayload.ID, (payload, context) -> {
			var player = context.player();
			context.server().execute(() -> {
				// 1.1.5：记录旧 activeProvider，用于判断本次提交是否切换了 Provider。
				// 只有 activeProvider 变化时才需要广播给其他在线玩家——非 op 玩家只关心
				// activeProvider（apiKey 对他们不可见、model/endpoint 变了也和他们无关），
				// 只改 API Key 时广播给非 op 是无意义的流量。op 玩家如果开着配置界面，
				// 也只关心 activeProvider 是否变了（Key/model 变了他们刷新界面时会重新请求快照）。
				String oldActiveProvider = config.activeProvider;
				var error = ConfigSyncHandler.applyUpdateJson(player, config, payload.json());
				if (error.isEmpty()) {
					// 成功：如果 activeProvider 变了，广播给所有在线玩家；否则只回发给提交者。
					// 广播时每个玩家按各自 op 状态构造快照（非 op 的 apiKey 脱敏为空），
					// 见 ConfigSyncHandler.broadcastSnapshot 的注释说明。
					if (!config.activeProvider.equals(oldActiveProvider)) {
						ConfigSyncHandler.broadcastSnapshot(context.server(), config);
					} else {
						ConfigSyncHandler.sendSnapshotTo(player, config);
					}
				} else {
					// 失败：只回发给提交者，让界面回到真实服务端状态（拒绝的修改不会生效）。
					ConfigSyncHandler.sendSnapshotTo(player, config);
					error.ifPresent(msg -> player.sendMessage(net.minecraft.text.Text.literal("[MCCF] " + msg), false));
				}
			});
		});

		// 7d. 配置界面："一键获取模型"——用临时 Key/endpoint 查询，不落盘
		ServerPlayNetworking.registerGlobalReceiver(RequestModelsPayload.ID, (payload, context) -> {
			var player = context.player();
			ConfigSyncHandler.handleModelsRequest(player, config, payload.json())
					.thenAccept(resultJson -> context.server().execute(() -> {
						if (player.networkHandler != null && ServerPlayNetworking.canSend(player, ModelsResultPayload.ID)) {
							ServerPlayNetworking.send(player, new ModelsResultPayload(resultJson));
						}
					}));
		});

		// 8. 玩家加入时主动推送一份配置快照，让 ClientConfigState 立即反映真实服务端状态。
		//    1.1.5 新增：旧版玩家加入后 ClientConfigState.activeProvider 一直是默认值 "mock"，
		//    直到玩家手动打开配置界面发 RequestConfigPayload 才会更新。这导致：
		//    (1) 非 op 玩家在配置界面看到的"当前生效 Provider"可能不准；
		//    (2) 依赖 activeProvider 做客户端展示的逻辑（如横幅提示）读到的是假默认值。
		//    加入时推一份快照彻底解决——和 broadcastSnapshot 配合，配置变更不再需要重连同步。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			var player = handler.getPlayer();
			server.execute(() -> ConfigSyncHandler.sendSnapshotTo(player, config));
		});

		// 9. 玩家离线时清理其语言记录、模式偏好与所属 Conversation（避免过期占用）
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var playerId = handler.getPlayer().getUuid();
			PlayerLanguageRegistry.remove(playerId);
			ClientOnlyModeRegistry.remove(playerId);
			conversationManager.removeParticipant(playerId);
			chatHandler.removePlayer(playerId);
		});

		// 9. 注册 /mccf 管理命令
		MCCFCommand.register();

		LOGGER.info("[MCCF] Initialization complete.");
	}

	public static ConversationManager getConversationManager() {
		return conversationManager;
	}

	public static TranslationService getTranslationService() {
		return translationService;
	}

	public static WorldDictionary getWorldDictionary() {
		return worldDictionary;
	}

	public static MCCFConfig getConfig() {
		return config;
	}

	/**
	 * 完整重载：从磁盘重新读取 config.json 与 dictionary.json，并重建所有
	 * TranslationProvider 实例、清空翻译缓存。供 /mccf reload 命令调用。
	 *
	 * 为什么 reload 要完整读盘 + 重建 + 清缓存三者同步：
	 * 配置变更会同时影响三处状态——配置对象本身的值（如 hearingRange）、
	 * Provider 实例（API Key/模型/host 改了就要重建）、以及翻译缓存
	 * （旧缓存是基于旧 Provider 和旧词典生成的，必须作废）。只做其中
	 * 一步会导致新旧状态不一致，例如换了 API Key 但缓存里还是旧 Provider
	 * 的翻译结果，管理员会以为 reload 没生效。
	 *
	 * 为什么不直接替换 config/dictionary 引用而是原地拷贝字段：
	 * ConversationManager、SpatialChatHandler、HearingResolver 都在构造时
	 * 持有了 config 的引用且字段是 final，如果换成新对象，这些子系统还会
	 * 指向旧 config，reload 对它们不生效。原地修改字段值能让所有持有引用
	 * 的子系统立即看到新值。WorldDictionary 同理——TranslationService 持有
	 * final 的 dictionary 引用，所以也要原地清空再填充 entries，而不是替换
	 * 整个对象。
	 */
	public static void reload() {
		// 1. 重新从磁盘读取配置，把字段原地拷贝到现有 config 对象，
		//    让所有持有 config 引用的子系统（ConversationManager 等）立即看到新值。
		MCCFConfig fresh = MCCFConfig.loadOrCreate();
		config.subtitleVisibleRange = fresh.subtitleVisibleRange;
		config.hearingRange = fresh.hearingRange;
		config.conversationRange = fresh.conversationRange;
		config.conversationIdleTimeoutSeconds = fresh.conversationIdleTimeoutSeconds;
		config.enableOcclusionCheck = fresh.enableOcclusionCheck;
		config.activeProvider = fresh.activeProvider;
		// showOriginalText / showOriginalTextInChat 已移至客户端偏好（1.1.1），
		// 不再由服务端配置控制，reload 不需要拷贝这两个字段。
		config.providers = fresh.providers;

		// 2. 重新从磁盘读取词典，原地清空再填充 entries。
		//    TranslationService 持有 final 的 dictionary 引用，不能替换对象。
		WorldDictionary freshDict = WorldDictionary.loadOrCreate();
		worldDictionary.getEntries().clear();
		worldDictionary.getEntries().putAll(freshDict.getEntries());

		// 3. 用新配置重建所有 Provider 实例（registerAllProviders 内部会读取
		//    config.providers 里的 API Key/模型/host 重新构造每个 Provider）。
		registerAllProviders();

		// 4. 清空翻译缓存。registerAllProviders 末尾已调过一次 clearCache，
		//    这里显式再调一次保证语义清晰，也防御未来 registerAllProviders
		//    实现变动后漏调的情况——reload 的契约就是"缓存必须清空"。
		translationService.clearCache();

		LOGGER.info("[MCCF] Config and dictionary reloaded from disk.");
	}

	/**
	 * 用当前 {@link #config} 里的 Provider 配置（API Key / 模型 / host）
	 * 重新构造全部 Provider 实例并注册。每次配置界面/命令改动了某个
	 * Provider 的设置后都应该调用一次，让新请求使用新的配置对象。
	 * 在途请求保留当时的 Provider；清缓存代次阻止旧结果回填新缓存。
	 */
	public static void registerAllProviders() {
		translationService.registerProvider(new MockTranslationProvider());
		// 1.1.2 修复：早期 0.15.0 加入智谱时这里漏掉了 "zhipu"，导致选了智谱后 setActiveProvider
		// 找不到该 Provider 静默 fallback 到 Mock——配置界面看起来配好了但实际完全没翻译。
		// 跨越 0.15.0~1.1.1 多个版本没人发现，根因是 ProviderDefaults/ProviderFactory/ClientConfigState
		// 都包含了 zhipu（玩家界面能正常看到/选择/填 Key），唯独运行时注册表漏了，造成"看着配好了
		// 实际没生效"的假象。修复方式就是把 "zhipu" 加进这个列表。
		for (String id : java.util.List.of("openai", "claude", "gemini", "deepl", "kimi", "deepseek", "zhipu", "ollama")) {
			translationService.registerProvider(
					net.mccf.mod.translation.provider.ProviderFactory.create(id, config.getProviderConfig(id)));
		}
		if (!translationService.setActiveProvider(config.activeProvider)) {
			LOGGER.warn("[MCCF] Configured provider '{}' not found, falling back to mock.", config.activeProvider);
			translationService.setActiveProvider(MockTranslationProvider.ID);
		}
		translationService.clearCache();
	}
}
