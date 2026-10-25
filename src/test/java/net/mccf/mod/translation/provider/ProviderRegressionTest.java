package net.mccf.mod.translation.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.mccf.mod.config.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegressionTest {

	private HttpServer server;
	private String endpoint;
	private final AtomicReference<String> requestBody = new AtomicReference<>();

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String response = exchange.getRequestURI().getPath().endsWith("/translate")
					? "{\"translations\":[{\"text\":\"Hello\"}]}"
					: "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var body = exchange.getResponseBody()) { body.write(bytes); }
		});
		server.start();
		endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void deepLUsesChineseSourceCodeAndPreservesTargetVariety() {
		var provider = new DeepLTranslationProvider(new ProviderConfig("key:fx", "", endpoint));
		assertEquals("Hello", provider.translate(request("zh_cn", "en_gb")).join().translatedText());
		assertTrue(requestBody.get().contains("source_lang=ZH"));
		assertFalse(requestBody.get().contains("source_lang=ZH-HANS"));
		assertTrue(requestBody.get().contains("target_lang=EN-GB"));
		provider.translate(request("zh_tw", "zh_cn")).join();
		assertTrue(requestBody.get().contains("source_lang=ZH"));
		assertTrue(requestBody.get().contains("target_lang=ZH-HANS"));
	}

	@Test
	void deepLAutomaticLanguageDetectionOmitsSourceLanguage() {
		var provider = new DeepLTranslationProvider(new ProviderConfig("key:fx", "", endpoint));
		provider.translate(request("auto", "zh_tw")).join();
		assertFalse(requestBody.get().contains("source_lang"));
		assertTrue(requestBody.get().contains("target_lang=ZH-HANT"));
	}

	@Test
	void deepLChoosesAccountEndpointForBothNewAndLegacyDefaults() {
		assertEquals("https://api.deepl.com/v2/translate", deepLEndpoint("paid-key", ""));
		assertEquals("https://api-free.deepl.com/v2/translate", deepLEndpoint("free-key:fx", ""));
		assertEquals("https://api.deepl.com/v2/translate", deepLEndpoint("paid-key", "https://api-free.deepl.com/"));
		assertEquals("https://api-free.deepl.com/v2/translate", deepLEndpoint("free-key:fx", "https://api.deepl.com"));
		assertEquals(endpoint + "/proxy/v2/translate", deepLEndpoint("paid-key", endpoint + "/proxy/"));
	}

	@Test
	void kimiOmitsSamplingParametersAndUsesSupportedThinkingControl() {
		ProviderConfig config = new ProviderConfig("test-key", "", endpoint);
		config.disableThinking = true;
		assertEquals("你好", new KimiTranslationProvider(config).translate(request("en_us", "zh_cn")).join().translatedText());
		JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
		assertEquals("kimi-k2.6", body.get("model").getAsString());
		assertFalse(body.has("temperature"));
		assertFalse(body.has("top_p"));
		assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
	}

	@Test
	void kimiDoesNotSendDisabledThinkingToThinkingOnlyModels() {
		ProviderConfig config = new ProviderConfig("test-key", "kimi-k3", endpoint);
		config.disableThinking = true;
		new KimiTranslationProvider(config).translate(request("en_us", "zh_cn")).join();
		JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
		assertFalse(body.has("temperature"));
		assertFalse(body.has("thinking"));
	}

	@Test
	void malformedEndpointProducesFailedFutureWithoutExposingCredentials() {
		String badEndpoint = "https://bad host?api_key=secret-test-key";
		CompletableFuture<String> future = assertDoesNotThrow(() -> HttpProviderSupport.postJson(
				badEndpoint, "{}", Map.of("Authorization", "Bearer secret-test-key")));
		CompletionException error = assertThrows(CompletionException.class, future::join);
		assertFalse(error.toString().contains("secret-test-key"));
		assertFalse(error.getCause().toString().contains("secret-test-key"));
	}

	@Test
	void httpErrorsDoNotExposeUrlsOrServerBodies() {
		server.createContext("/fail", exchange -> {
			byte[] bytes = "secret-test-key".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(401, bytes.length);
			try (var body = exchange.getResponseBody()) { body.write(bytes); }
		});
		CompletionException error = assertThrows(CompletionException.class, () ->
				HttpProviderSupport.getJson(endpoint + "/fail?key=secret-test-key", Map.of()).join());
		assertTrue(error.toString().contains("401"));
		assertFalse(error.toString().contains("secret-test-key"));
		assertNull(error.getCause().getCause());
	}

	@Test
	void invalidJsonDoesNotIncludeResponseBodyInException() {
		IllegalStateException error = assertThrows(IllegalStateException.class, () ->
				HttpProviderSupport.parseResponseJson("secret-test-key invalid-json"));
		assertFalse(error.toString().contains("secret-test-key"));
		assertNull(error.getCause());
	}

	private static TranslationProvider.TranslationRequest request(String source, String target) {
		return new TranslationProvider.TranslationRequest("你好", source, target, List.of());
	}

	private static String deepLEndpoint(String key, String endpoint) {
		return new DeepLTranslationProvider(new ProviderConfig(key, "", endpoint)).translationEndpoint();
	}
}
