package net.mccf.mod.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.mccf.mod.network.ConfigSnapshotPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端本地保存的配置状态：从服务端快照接收到的数据 + Screen 里正在编辑
 * 但尚未提交的改动。全局单例（客户端同一时刻只会打开一个配置 Screen）。
 *
 * 支持的 Provider 顺序固定，配置 Screen 的"切换 Provider"按钮按这个顺序循环。
 */
public class ClientConfigState {

	public static final String[] PROVIDER_IDS = {
			"mock", "openai", "claude", "gemini", "deepl", "kimi", "deepseek", "zhipu", "ollama"
	};

	/** 生成某个 Provider 在语言文件里对应的翻译 key，例如 "openai" -> "mccf.provider.openai"。 */
	public static String providerNameKey(String providerId) {
		return "mccf.provider." + providerId;
	}

	/** 不支持"一键获取模型"的 Provider（DeepL 是固定引擎无模型概念，Mock 无真实模型）。 */
	public static final java.util.Set<String> NO_MODEL_LIST_SUPPORT = java.util.Set.of("mock", "deepl");

	/**
	 * 支持"强制关闭思考"的 Provider——DeepSeek（V4 系列默认开思考）、
	 * Kimi（K2.x 系列默认开思考）、Claude（4.6 及更早支持关闭参数）、
	 * Gemini（flash 系列支持 thinkingBudget=0）、智谱（GLM-5/5.2 默认开思考）。
	 * 其余 Provider（OpenAI 默认模型 gpt-4o-mini 不是推理模型、DeepL 是固定
	 * 翻译引擎、Ollama 本地模型看具体模型、Mock 是占位符）没有统一的"思考"
	 * 概念，配置界面不会为它们展示这个开关。
	 *
	 * 只对确认支持这个能力的 Provider 生效，具体每家用什么参数关闭、参数是否
	 * 对所有模型代次都有效，见各自 TranslationProvider 实现类的注释——这些
	 * 限制不由这里的集合本身表达，而是通过打开开关时的确认弹窗告知玩家。
	 */
	public static final java.util.Set<String> THINKING_CAPABLE_PROVIDERS =
			java.util.Set.of("deepseek", "kimi", "claude", "gemini", "zhipu");

	private static final Gson GSON = new GsonBuilder().create();

	public boolean canEdit = false;
	public String activeProvider = "mock";
	/**
	 * 1.1.7 新增：applySnapshot 时记录的"上一个 activeProvider"。
	 * 用于 ServerConfigPanel.onSnapshotUpdated 区分两种快照到达场景：
	 * (1) init 重建后的 requestSnapshot 回包——activeProvider 没变，
	 *     selectedProvider 应保留玩家正在查看的（1.1.4 修复的场景）；
	 * (2) 别人/命令改了 activeProvider 的广播——如果玩家没在看别的
	 *     Provider（selectedProvider == previousActiveProvider），
	 *     选中框应跟随到新 activeProvider，并滚动到该 Provider。
	 * 没有 previousActiveProvider 就无法区分"玩家在看别的"和"玩家在看旧的
	 * activeProvider"——两者都是 selectedProvider != 新 activeProvider，但前者
	 * 应保留、后者应跟随。
	 */
	public String previousActiveProvider = "mock";
	/**
	 * UI 中"待启用"的 Provider：0.10.0 起，玩家点击"保存"时会把当前左侧列表
	 * 选中查看的 Provider 写入此字段（不再有单独的"设为默认"/"保存并启用"
	 * 按钮，保存这一个动作同时完成"保存字段改动"和"设为默认"两件事，见
	 * ServerConfigPanel#onSave）。收到服务端快照后由 applySnapshot 重置为
	 * 服务端确认值。
	 */
	public String pendingActiveProvider = "mock";
	public final Map<String, ClientProviderConfig> providers = new LinkedHashMap<>();

	/** 是否已经从服务端收到过至少一次快照（用于 Screen 判断是否还在等待数据）。 */
	public boolean hasReceivedSnapshot = false;

	// showOriginalText / showOriginalTextInChat 已移至 ClientOnlyTranslationConfig
	// 作为客户端个人偏好（1.1.1），不再从服务端快照同步、不随 UpdateConfigPayload 提交。

	private static ClientConfigState instance;

	public static ClientConfigState get() {
		if (instance == null) {
			instance = new ClientConfigState();
		}
		return instance;
	}

	public void applySnapshot(ConfigSnapshotPayload payload) {
		JsonObject root = GSON.fromJson(payload.json(), JsonObject.class);
		if (root == null) return;

		this.canEdit = root.has("canEdit") && root.get("canEdit").getAsBoolean();
		// 1.1.7：记录旧 activeProvider，供 ServerConfigPanel.onSnapshotUpdated 判断
		// 是否应该让选中框跟随到新 activeProvider（见 previousActiveProvider 字段注释）。
		this.previousActiveProvider = this.activeProvider;
		this.activeProvider = root.has("activeProvider") ? root.get("activeProvider").getAsString() : "mock";
		this.pendingActiveProvider = this.activeProvider;

		providers.clear();
		if (root.has("providers")) {
			JsonObject providersJson = root.getAsJsonObject("providers");
			for (String id : providersJson.keySet()) {
				JsonObject pc = providersJson.getAsJsonObject(id);
				ClientProviderConfig cpc = new ClientProviderConfig(
						getOrEmpty(pc, "apiKey"),
						getOrEmpty(pc, "model"),
						getOrEmpty(pc, "endpoint"),
						pc.has("isCustomEndpoint") && pc.get("isCustomEndpoint").getAsBoolean()
				);
				// disableThinking 不在 4 参构造函数里（那几个参数是历史上就有的
				// 字段，构造函数签名保持不变以免影响其他调用点），单独读取赋值。
				cpc.disableThinking = pc.has("disableThinking") && pc.get("disableThinking").getAsBoolean();
				providers.put(id, cpc);
			}
		}
		// 补齐快照里没有的 Provider（不应该发生，但防止 UI 因缺 key 报 NPE）。
		for (String id : PROVIDER_IDS) {
			providers.putIfAbsent(id, new ClientProviderConfig());
		}

		this.hasReceivedSnapshot = true;
	}

	private static String getOrEmpty(JsonObject obj, String key) {
		return obj.has(key) ? obj.get(key).getAsString() : "";
	}

	public ClientProviderConfig getOrCreate(String providerId) {
		return providers.computeIfAbsent(providerId, id -> new ClientProviderConfig());
	}

	/**
	 * 构造提交给服务端的 JSON——只包含当前内存里的编辑结果。
	 *
	 * endpoint 字段的发送逻辑采用三态（见 {@link ClientProviderConfig.EndpointAction}）：
	 * UNCHANGED 时不发送 endpoint / resetEndpoint 字段，服务端见到字段缺失就
	 * 保持原值——这样玩家切换 Provider 看一眼（输入框本来就空）不会误触发"恢复默认"。
	 * 这与旧逻辑（用 isCustomEndpoint + endpoint.isBlank() 自动派生 resetEndpoint）
	 * 的关键区别在于：旧逻辑无法表达"不改"这个意图。
	 */
	public String buildUpdateJson() {
		JsonObject root = new JsonObject();
		root.addProperty("activeProvider", pendingActiveProvider == null ? activeProvider : pendingActiveProvider);

		JsonObject providersJson = new JsonObject();
		for (Map.Entry<String, ClientProviderConfig> entry : providers.entrySet()) {
			ClientProviderConfig pc = entry.getValue();
			JsonObject pcJson = new JsonObject();
			pcJson.addProperty("apiKey", pc.apiKey == null ? "" : pc.apiKey);
			pcJson.addProperty("model", pc.model == null ? "" : pc.model);
			pcJson.addProperty("disableThinking", pc.disableThinking);

			// endpoint 三态：根据玩家在 UI 上的显式意图决定发什么给服务端，
			// 而不是从输入框文本反推。endpointAction 是 transient 字段，
			// 只在配置 Screen 编辑期间由 onResetEndpoint / onSave 显式设置。
			ClientProviderConfig.EndpointAction action = pc.endpointAction;
			if (action == null) action = ClientProviderConfig.EndpointAction.UNCHANGED;
			switch (action) {
				case CUSTOM -> {
					pcJson.addProperty("endpoint", pc.endpoint == null ? "" : pc.endpoint);
					pcJson.addProperty("resetEndpoint", false);
				}
				case RESET_DEFAULT -> {
					pcJson.addProperty("endpoint", "");
					pcJson.addProperty("resetEndpoint", true);
				}
				case UNCHANGED -> {
					// 故意不写 endpoint / resetEndpoint 字段，服务端见到字段缺失
					// 就保持原值——这是"玩家没动 endpoint"的显式表达。
				}
			}

			providersJson.add(entry.getKey(), pcJson);
		}
		root.add("providers", providersJson);

		return GSON.toJson(root);
	}
}
