package net.mccf.mod.translation.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Only verifies protocol bodies; no API keys or network requests are used. */
class ThinkingRequestTest {

	@Test
	void geminiThreeUsesThinkingLevelInsteadOfZeroBudget() {
		JsonObject thinking = thinking("gemini-3.5-flash");
		assertEquals("low", thinking.get("thinkingLevel").getAsString());
		assertFalse(thinking.has("thinkingBudget"));
	}

	@Test
	void gemini38DoesNotSendUnsupportedMinimalLevel() {
		assertEquals("low", thinking("gemini-3.8-flash").get("thinkingLevel").getAsString());
	}

	@Test
	void gemini25FlashCanDisableThinkingWithZeroBudget() {
		for (String model : new String[] {"gemini-2.5-flash", "gemini-2.5-flash-lite"}) {
			JsonObject thinking = thinking(model);
			assertEquals(0, thinking.get("thinkingBudget").getAsInt());
			assertFalse(thinking.has("thinkingLevel"));
		}
	}

	@Test
	void gemini25ProUsesItsMinimumSupportedBudgetInsteadOfZero() {
		JsonObject thinking = thinking("gemini-2.5-pro");
		assertEquals(128, thinking.get("thinkingBudget").getAsInt());
		assertFalse(thinking.has("thinkingLevel"));
	}

	@Test
	void geminiFlashLiteImageDoesNotReceiveUnsupportedLowLevel() {
		assertEquals("minimal", thinking("gemini-3.1-flash-lite-image-preview")
				.get("thinkingLevel").getAsString());
	}

	@Test
	void geminiFourUsesLevelsAndUnknownModelsKeepServerDefaults() {
		assertEquals("low", thinking("gemini-4-argon").get("thinkingLevel").getAsString());
		assertFalse(gemini("custom-gateway-model", true).has("generationConfig"));
		assertFalse(gemini("gemini-3.99-flash", true).has("generationConfig"));
	}

	@Test
	void leavingTheSwitchOffDoesNotOverrideGeminiThinkingDefaults() {
		assertFalse(gemini("gemini-3.5-flash", false).has("generationConfig"));
	}

	@Test
	void claude47And48RetainTheirSupportedDisabledThinkingParameter() {
		for (String model : new String[] {"claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4-8"}) {
			JsonObject body = JsonParser.parseString(ClaudeTranslationProvider.buildRequestBody(
					model, "Translate", "Hello", true)).getAsJsonObject();
			JsonObject thinking = body.getAsJsonObject("thinking");
			assertEquals("disabled", thinking.get("type").getAsString());
			assertFalse(thinking.has("budget_tokens"));
			assertFalse(body.has("output_config"));
		}
	}

	@Test
	void leavingTheSwitchOffDoesNotEnableClaudeThinking() {
		JsonObject body = JsonParser.parseString(ClaudeTranslationProvider.buildRequestBody(
				"claude-sonnet-4-6", "Translate", "Hello", false)).getAsJsonObject();
		assertFalse(body.has("thinking"));
	}

	private static JsonObject thinking(String model) {
		return gemini(model, true).getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig");
	}

	private static JsonObject gemini(String model, boolean disableThinking) {
		return JsonParser.parseString(GeminiTranslationProvider.buildRequestBody(model, "Translate", disableThinking))
				.getAsJsonObject();
	}
}
