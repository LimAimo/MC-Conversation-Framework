package net.mccf.mod.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MCCFConfigTest {
    @Test void freshConfigPreservesAutomaticEndpointSelection() {
        MCCFConfig config = new MCCFConfig();
        assertEquals("", config.getProviderConfig("deepl").endpoint);
        assertEquals("https://api.openai.com", config.getProviderConfig("openai").effectiveEndpoint("openai"));
    }

    @Test void invalidRangesAndMissingFieldsAreNormalized() {
        MCCFConfig config = new MCCFConfig();
        config.hearingRange = 10;
        config.subtitleVisibleRange = Double.NaN;
        config.conversationRange = Double.POSITIVE_INFINITY;
        config.conversationIdleTimeoutSeconds = -1;
        config.activeProvider = null;
        config.providers = null;
        config.validateAndClamp();
        assertEquals(10, config.subtitleVisibleRange);
        assertEquals(10, config.conversationRange);
        assertEquals(120, config.conversationIdleTimeoutSeconds);
        assertEquals("mock", config.activeProvider);
        assertNotNull(config.getProviderConfig("kimi"));
    }

    @Test void migratesRetiredOfficialKimiDefaultButPreservesPrivateGatewayModel() {
        MCCFConfig config = new MCCFConfig();
        ProviderConfig kimi = config.getProviderConfig("kimi");
        kimi.model = "kimi-k2.5";
        config.validateAndClamp();
        assertEquals(ProviderDefaults.get("kimi").model(), kimi.model);
        kimi.model = "kimi-k2.5";
        kimi.endpoint = "https://private-gateway.example";
        config.validateAndClamp();
        assertEquals("kimi-k2.5", kimi.model);
    }
}
