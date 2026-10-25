package net.mccf.mod.client.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 只管界面草稿与快照合并，不负责网络或持久化；父 Screen 保留实例以跨控件重建保存输入。 */
public final class ProviderConfigDrafts {
	private final Map<String, ClientProviderConfig> drafts = new LinkedHashMap<>();
	private final Map<String, ClientProviderConfig> baselines = new LinkedHashMap<>();

	public ClientProviderConfig get(String id, ClientProviderConfig current) {
		if (!drafts.containsKey(id)) {
			baselines.put(id, current.copy());
			drafts.put(id, current.copy());
		}
		return drafts.get(id);
	}

	/** Refresh untouched fields, preserving edits made while a snapshot was in flight. */
	public void mergeSnapshot(Map<String, ClientProviderConfig> latest) {
		latest.forEach((id, incoming) -> {
			ClientProviderConfig draft = drafts.get(id);
			if (draft == null) return;
			ClientProviderConfig base = baselines.get(id);
			if (draft.clearApiKey && (incoming.apiKey == null || incoming.apiKey.isBlank())) draft.clearApiKey = false;
			if ((draft.endpointAction == ClientProviderConfig.EndpointAction.RESET_DEFAULT && !incoming.isCustomEndpoint)
					|| (draft.endpointAction == ClientProviderConfig.EndpointAction.CUSTOM && incoming.isCustomEndpoint
						&& Objects.equals(draft.endpoint, incoming.endpoint))) {
				draft.endpointAction = ClientProviderConfig.EndpointAction.UNCHANGED;
				draft.endpoint = incoming.endpoint;
				draft.isCustomEndpoint = incoming.isCustomEndpoint;
			}
			if (!draft.clearApiKey && Objects.equals(draft.apiKey, base.apiKey)) draft.apiKey = incoming.apiKey;
			if (Objects.equals(draft.model, base.model)) draft.model = incoming.model;
			if (draft.endpointAction == ClientProviderConfig.EndpointAction.UNCHANGED
					&& Objects.equals(draft.endpoint, base.endpoint)) {
				draft.endpoint = incoming.endpoint;
				draft.isCustomEndpoint = incoming.isCustomEndpoint;
			}
			if (draft.disableThinking == base.disableThinking) draft.disableThinking = incoming.disableThinking;
			baselines.put(id, incoming.copy());
		});
	}

	/** A new explicit sync action replaces a local draft, including its original baseline. */
	public void replace(String id, ClientProviderConfig current) {
		baselines.put(id, current.copy());
		drafts.put(id, current.copy());
	}
}
