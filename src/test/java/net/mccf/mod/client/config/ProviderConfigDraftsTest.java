package net.mccf.mod.client.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderConfigDraftsTest {
	@Test
	void snapshotRefreshesUntouchedFieldsWithoutLosingModelKeyOrThinkingEdits() {
		var original = new ClientProviderConfig("saved-key", "old-model", "https://old.example", true);
		var drafts = new ProviderConfigDrafts();
		var editing = drafts.get("deepseek", original);
		editing.apiKey = "new-key";
		editing.model = "chosen-model";
		editing.disableThinking = true;
		var incoming = new ClientProviderConfig("saved-key", "old-model", "https://new.example", true);
		drafts.mergeSnapshot(Map.of("deepseek", incoming));
		// Simulates constructing a new panel for the same parent screen after a child closes.
		var restored = drafts.get("deepseek", incoming);
		assertEquals("new-key", restored.apiKey);
		assertEquals("chosen-model", restored.model);
		assertTrue(restored.disableThinking);
		assertEquals("https://new.example", restored.endpoint);
		assertEquals("old-model", incoming.model, "The authoritative snapshot must remain untouched");
		drafts.mergeSnapshot(Map.of("deepseek", incoming));
		assertEquals("chosen-model", restored.model, "Repeated snapshots must not wipe the same edit");
	}

	@Test
	void explicitDeletionSurvivesAnOlderSnapshotAndIsAcknowledgedByAnEmptyKey() {
		var saved = new ClientProviderConfig("secret", "model", "", false);
		var drafts = new ProviderConfigDrafts();
		var editing = drafts.get("openai", saved);
		editing.apiKey = "";
		editing.clearApiKey = true;
		drafts.mergeSnapshot(Map.of("openai", saved));
		assertTrue(editing.clearApiKey);
		assertEquals("", editing.apiKey);
		drafts.mergeSnapshot(Map.of("openai", new ClientProviderConfig("", "model", "", false)));
		assertFalse(editing.clearApiKey);
		assertEquals("", editing.apiKey);
	}

	@Test
	void pendingEndpointResetSurvivesOldCustomSnapshotAndAcknowledgesOfficialDefault() {
		var saved = new ClientProviderConfig("key", "model", "https://custom.example", true);
		var drafts = new ProviderConfigDrafts();
		var editing = drafts.get("openai", saved);
		editing.endpoint = "";
		editing.endpointAction = ClientProviderConfig.EndpointAction.RESET_DEFAULT;
		drafts.mergeSnapshot(Map.of("openai", saved));
		assertEquals(ClientProviderConfig.EndpointAction.RESET_DEFAULT, editing.endpointAction);
		assertEquals("", editing.endpoint);
		var acknowledged = new ClientProviderConfig("key", "model", "https://official.example", false);
		drafts.mergeSnapshot(Map.of("openai", acknowledged));
		assertEquals(ClientProviderConfig.EndpointAction.UNCHANGED, editing.endpointAction);
		assertEquals("https://official.example", editing.endpoint);
		assertFalse(editing.isCustomEndpoint);
	}

	@Test
	void savingSelectedProviderUsesExplicitDeletionAndLeavesOtherProvidersOut() {
		var state = new ClientConfigState();
		state.providers.put("claude", new ClientProviderConfig("unrelated-secret", "model", "", false));
		var draft = new ClientProviderConfig("", "chosen-model", "", false);
		draft.clearApiKey = true;
		var update = JsonParser.parseString(state.buildUpdateJson("openai", draft)).getAsJsonObject();
		assertEquals("openai", update.get("activeProvider").getAsString());
		var providers = update.getAsJsonObject("providers");
		assertEquals(1, providers.size());
		assertTrue(providers.getAsJsonObject("openai").get("clearApiKey").getAsBoolean());
		draft.clearApiKey = false;
		var unchanged = JsonParser.parseString(state.buildUpdateJson("openai", draft)).getAsJsonObject();
		assertFalse(unchanged.getAsJsonObject("providers").getAsJsonObject("openai").has("clearApiKey"));
	}

	@Test
	void localRuntimeConversionRetainsThinkingSettingAndCredentials() {
		var config = new ClientProviderConfig("key", "model", "https://gateway.example", true);
		config.disableThinking = true;
		var runtime = config.toProviderConfig();
		assertTrue(runtime.disableThinking);
		assertEquals("key", runtime.apiKey);
		assertEquals("model", runtime.model);
		assertEquals("https://gateway.example", runtime.endpoint);
	}
}
