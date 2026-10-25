package net.mccf.mod.client.config;

/**
 * 客户端内存中的单个 Provider 配置副本，供配置 Screen 编辑。
 * 与服务端 {@code net.mccf.mod.config.ProviderConfig} 字段一一对应。
 *
 * 只管"承载当前编辑中的字段值"，不管"玩家对这些字段的编辑意图"——后者
 * 由 {@link EndpointAction} 承载，目的是把"输入框内容"和"玩家想做什么"
 * 解耦，避免输入框留空同时表示"不改"和"清空"两种含义。
 *
 * @field endpoint API 基础地址（含 Ollama 在内所有 Provider 通用）；
 *        为空表示使用官方默认地址，非空表示玩家自定义了 endpoint。
 * @field isCustomEndpoint 标记 endpoint 是否为玩家自定义值（true）还是
 *        服务端下发的默认值（false），供界面判断"恢复默认"按钮是否需要高亮/可用。
 *        注意：这个字段在引入 {@link EndpointAction} 后已经退居二线，仅用于
 *        从服务端快照恢复初始显示状态；真正的"是否发送 resetEndpoint"由
 *        endpointAction 三态决定。保留它是为了不破坏 applySnapshot 的现有逻辑。
 * @field endpointAction 三态标记，记录玩家对 endpoint 的编辑意图（不改 /
 *        自定义 / 恢复默认）。transient 保证不参与 Gson 序列化——这是纯 UI
 *        状态，只在配置 Screen 编辑期间存活，不落盘也不随网络包发送。每次
 *        从服务端快照重建时都会回到 UNCHANGED，符合"没动过就是不改"的直觉。
 */
public class ClientProviderConfig {
	public String apiKey = "";
	public String model = "";
	public String endpoint = "";
	public boolean isCustomEndpoint = false;
	/**
	 * 是否强制关闭该 Provider 的思考模式。与服务端 {@code ProviderConfig
	 * #disableThinking} 字段一一对应，见其注释了解各 Provider 的支持情况
	 * 与限制。默认 false（不干预默认行为）。
	 */
	public boolean disableThinking = false;
	/**
	 * 玩家对 endpoint 的编辑意图。默认 UNCHANGED——从服务端快照构造时玩家
	 * 还没对 endpoint 做任何改动。UI 层（onResetEndpoint / onSave）会显式
	 * 修改这个字段，buildUpdateJson 读取它决定发什么给服务端。
	 */
	public transient EndpointAction endpointAction = EndpointAction.UNCHANGED;
	/** Explicit deletion intent; a blank key alone still means keep the saved key. */
	public transient boolean clearApiKey = false;

	public ClientProviderConfig() {}

	public ClientProviderConfig(String apiKey, String model, String endpoint, boolean isCustomEndpoint) {
		this.apiKey = apiKey;
		this.model = model;
		this.endpoint = endpoint;
		this.isCustomEndpoint = isCustomEndpoint;
		// 从服务端快照构造时，默认 UNCHANGED——玩家还没对 endpoint 做任何改动，
		// 保存时不应该发 endpoint 字段，服务端保持原值即可。
		this.endpointAction = EndpointAction.UNCHANGED;
	}

	public ClientProviderConfig copy() {
		ClientProviderConfig copy = new ClientProviderConfig(apiKey, model, endpoint, isCustomEndpoint);
		copy.disableThinking = disableThinking;
		copy.endpointAction = endpointAction;
		copy.clearApiKey = clearApiKey;
		return copy;
	}

	/** Keep runtime Provider configuration in sync with every saved local option. */
	public net.mccf.mod.config.ProviderConfig toProviderConfig() {
		var result = new net.mccf.mod.config.ProviderConfig(apiKey, model, endpoint);
		result.disableThinking = disableThinking;
		return result;
	}

	/**
	 * endpoint 编辑意图的三态枚举。
	 *
	 * 为什么要三态：原来的二值逻辑（isCustomEndpoint + endpoint 是否为空）无法
	 * 区分"玩家不想改 endpoint"和"玩家想恢复默认 endpoint"——两种情况下输入框
	 * 都可能是空的，但服务端应该做的动作完全不同（前者保持原值，后者显式重置）。
	 * 原来的代码用 `!pc.isCustomEndpoint && endpoint.isBlank()` 自动派生
	 * resetEndpoint，结果玩家只是切到某个 Provider 看一眼（输入框本来就是空的）
	 * 也会触发"恢复默认"，把玩家之前自定义的 endpoint 冲掉。三态枚举让 UI 能
	 * 显式表达玩家意图，消除"输入框留空"的歧义。
	 *
	 * 决策历史：曾经考虑过给 isCustomEndpoint 加第三个状态（null 表示"不改"），
	 * 但 boolean 加 null 语义混乱、容易 NPE，不如单独开一个枚举字段清晰。
	 */
	public enum EndpointAction {
		/** 保持服务端当前值不变，buildUpdateJson 不发送 endpoint / resetEndpoint 字段。 */
		UNCHANGED,
		/** 使用输入框里的自定义 endpoint 值，发送 endpoint 字段 + resetEndpoint=false。 */
		CUSTOM,
		/** 显式恢复为官方默认 endpoint，发送 endpoint="" + resetEndpoint=true。 */
		RESET_DEFAULT
	}
}
