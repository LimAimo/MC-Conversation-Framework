package net.mccf.mod.translation;

import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.translation.provider.TranslationProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TranslationServiceTest {
	@Test
	void hangingProviderFallsBackWithinDeadlineAndCanBeRetried() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		TranslationService service = new TranslationService(new WorldDictionary(), Duration.ofMillis(50));
		service.registerProvider(provider("test", request -> calls.incrementAndGet() == 1
				? new CompletableFuture<>() : result("recovered")));
		service.setActiveProvider("test");
		assertEquals("bank", translate(service, List.of()).get(2, TimeUnit.SECONDS));
		assertEquals("recovered", translate(service, List.of()).get(2, TimeUnit.SECONDS));
		assertEquals(1, service.getFailureCount());
	}

	@Test
	void cachesOnlyForTheSameConversationContext() {
		AtomicInteger calls = new AtomicInteger();
		TranslationService service = service(request -> {
			calls.incrementAndGet();
			return result(String.join("/", request.contextMessages()));
		});
		assertEquals("river", translate(service, List.of("river")).join());
		assertEquals("money", translate(service, List.of("money")).join());
		assertEquals("river", translate(service, List.of("river")).join());
		assertEquals(2, calls.get());
	}

	@Test
	void contextFingerprintPreservesMessageBoundaries() {
		AtomicInteger calls = new AtomicInteger();
		TranslationService service = service(request -> result("translation " + calls.incrementAndGet()));
		assertEquals("translation 1", translate(service, List.of("ab", "c")).join());
		assertEquals("translation 2", translate(service, List.of("a", "bc")).join());
	}

	@Test
	void clearCachePreventsEarlierRequestsFromOverwritingFreshResults() {
		AtomicInteger calls = new AtomicInteger();
		CompletableFuture<TranslationProvider.TranslationResult> old = new CompletableFuture<>();
		TranslationService service = service(request -> calls.incrementAndGet() == 1 ? old : result("fresh"));
		CompletableFuture<String> oldResult = translate(service, List.of());
		service.clearCache();
		assertEquals("fresh", translate(service, List.of()).join());
		old.complete(new TranslationProvider.TranslationResult("old"));
		assertEquals("old", oldResult.join());
		assertEquals("fresh", translate(service, List.of()).join());
		assertEquals(2, calls.get());
	}

	@Test
	void providerSwitchInvalidatesCachedTranslationsAndPendingWrites() {
		CompletableFuture<TranslationProvider.TranslationResult> old = new CompletableFuture<>();
		TranslationService service = service(request -> old);
		CompletableFuture<String> oldResult = translate(service, List.of());
		service.registerProvider(provider("other", request -> result("new provider")));
		assertTrue(service.setActiveProvider("other"));
		assertEquals("new provider", translate(service, List.of()).join());
		old.complete(new TranslationProvider.TranslationResult("old provider"));
		assertEquals("old provider", oldResult.join());
		assertEquals("new provider", translate(service, List.of()).join());
	}

	@Test
	void synchronousFailuresFallBackAndAreRetried() {
		AtomicInteger calls = new AtomicInteger();
		TranslationService service = service(request -> {
			if (calls.incrementAndGet() == 1) throw new IllegalArgumentException("bad endpoint");
			return result("recovered");
		});
		assertEquals("bank", assertDoesNotThrow(() -> translate(service, List.of())).join());
		assertEquals("recovered", translate(service, List.of()).join());
		assertEquals("recovered", translate(service, List.of()).join());
		assertEquals(2, calls.get());
		assertEquals(1, service.getFailureCount());
		assertEquals(1, service.getSuccessCount());
	}

	@Test
	void asynchronousFailuresAreNeverCached() {
		AtomicInteger calls = new AtomicInteger();
		TranslationService service = service(request -> calls.incrementAndGet() == 1
				? CompletableFuture.failedFuture(new IllegalStateException("offline")) : result("recovered"));
		assertEquals("bank", translate(service, List.of()).join());
		assertEquals("recovered", translate(service, List.of()).join());
		assertEquals(2, calls.get());
	}

	@Test
	void providerReceivesAnImmutableSnapshotOfTheContext() {
		AtomicReference<List<String>> received = new AtomicReference<>();
		TranslationService service = service(request -> {
			received.set(request.contextMessages());
			return result("translated");
		});
		List<String> context = new ArrayList<>(List.of("before"));
		translate(service, context).join();
		context.add("after");
		assertEquals(List.of("before"), received.get());
		assertThrows(UnsupportedOperationException.class, () -> received.get().add("mutate"));
	}

	private static CompletableFuture<String> translate(TranslationService service, List<String> context) {
		return service.translate("bank", "en_us", "zh_cn", context);
	}

	private static CompletableFuture<TranslationProvider.TranslationResult> result(String text) {
		return CompletableFuture.completedFuture(new TranslationProvider.TranslationResult(text));
	}

	private static TranslationService service(Function<TranslationProvider.TranslationRequest,
			CompletableFuture<TranslationProvider.TranslationResult>> action) {
		TranslationService service = new TranslationService(new WorldDictionary());
		service.registerProvider(provider("test", action));
		service.setActiveProvider("test");
		return service;
	}

	private static TranslationProvider provider(String id, Function<TranslationProvider.TranslationRequest,
			CompletableFuture<TranslationProvider.TranslationResult>> action) {
		return new TranslationProvider() {
			public String getId() { return id; }
			public String getDisplayName() { return id; }
			public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
				return action.apply(request);
			}
		};
	}
}
