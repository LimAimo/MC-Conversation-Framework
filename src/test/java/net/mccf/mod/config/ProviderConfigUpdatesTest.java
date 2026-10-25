package net.mccf.mod.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProviderConfigUpdatesTest {
    private Map<String, ProviderConfig> saved() {
        return Map.of("openai", new ProviderConfig("saved-key", "old-model", "https://gateway.example"));
    }

    @Test void explicitClearDeletesKeyWhileBlankLegacyKeyPreservesIt() {
        assertEquals("", ProviderConfigUpdates.parse("openai", saved(),
                "{\"providers\":{\"openai\":{\"apiKey\":\"\",\"clearApiKey\":true}}}").providers().get("openai").apiKey);
        assertEquals("saved-key", ProviderConfigUpdates.parse("openai", saved(),
                "{\"providers\":{\"openai\":{\"apiKey\":\"\"}}}").providers().get("openai").apiKey);
    }

    @Test void malformedLaterFieldDoesNotMutateSavedProvider() {
        Map<String, ProviderConfig> original = saved();
        assertThrows(IllegalArgumentException.class, () -> ProviderConfigUpdates.parse("openai", original,
                "{\"activeProvider\":\"kimi\",\"providers\":{\"openai\":{\"apiKey\":\"new-key\",\"disableThinking\":\"wrong\"}}}"));
        assertEquals("saved-key", original.get("openai").apiKey);
        assertEquals("old-model", original.get("openai").model);
    }

    @Test void returnedProvidersAreIndependentCopies() {
        Map<String, ProviderConfig> original = saved();
        ProviderConfigUpdates.Update update = ProviderConfigUpdates.parse("openai", original,
                "{\"providers\":{\"openai\":{\"model\":\"new-model\"}}}");
        assertNotSame(original.get("openai"), update.providers().get("openai"));
        assertEquals("old-model", original.get("openai").model);
        assertEquals("new-model", update.providers().get("openai").model);
    }

    @Test void endpointResetIsExplicitAndOmittedEndpointIsPreserved() {
        assertEquals("", ProviderConfigUpdates.parse("openai", saved(),
                "{\"providers\":{\"openai\":{\"resetEndpoint\":true}}}").providers().get("openai").endpoint);
        assertEquals("https://gateway.example", ProviderConfigUpdates.parse("openai", saved(),
                "{\"providers\":{\"openai\":{\"model\":\"new-model\"}}}").providers().get("openai").endpoint);
    }

    @Test void rejectsInvalidProviderNamesAndFieldTypes() {
        for (String json : new String[]{"null", "[]", "{\"providers\":null}",
                "{\"activeProvider\":\"unknown\"}", "{\"providers\":{\"unknown\":{}}}",
                "{\"providers\":{\"openai\":{\"apiKey\":null}}}"}) {
            assertThrows(RuntimeException.class, () -> ProviderConfigUpdates.parse("openai", saved(), json), json);
        }
    }

    @Test void endpointValidationRejectsMalformedUrlsWithoutEchoingCredentials() {
        for (String endpoint : new String[]{"https://bad host/secret", "file:///tmp/file", "https://user:secret@example.com", "https://example.com/#secret"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ProviderConfigUpdates.validateEndpoint(endpoint));
            assertFalse(error.getMessage().contains("secret"));
        }
        assertDoesNotThrow(() -> ProviderConfigUpdates.validateEndpoint("http://localhost:11434"));
        assertDoesNotThrow(() -> ProviderConfigUpdates.validateEndpoint(""));
    }
}
