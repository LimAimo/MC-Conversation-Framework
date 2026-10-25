package net.mccf.mod.translation;

import net.mccf.mod.MCCF;
import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.translation.provider.TranslationProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只管词典替换、上下文缓存和翻译失败回退；空间判定与有序分发由 SpatialChatHandler 负责。
 * 每次请求有完整生命周期时限，防止一个挂起的 Provider 阻塞听众的后续消息。
 */
public class TranslationService {

	private static final int CACHE_MAX_SIZE = 5000;
	private static final long CACHE_TTL_MILLIS = 60 * 60 * 1000L;

	private final WorldDictionary dictionary;
	private final long requestTimeoutMillis;
	private final Map<String, TranslationProvider> providers = new ConcurrentHashMap<>();
	private volatile TranslationProvider activeProvider;
	private final AtomicLong failureCount = new AtomicLong();
	private final AtomicLong successCount = new AtomicLong();

	// Hash the context instead of retaining thousands of overlapping conversation histories.
	private record CacheKey(String sourceText, String sourceLang, String targetLang, String contextDigest) {}
	private record CacheEntry(String text, long writtenAt) {}

	// The cache monitor also protects generation and provider changes.
	private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
			return size() > CACHE_MAX_SIZE;
		}
	};
	private long cacheGeneration;

	public TranslationService(WorldDictionary dictionary) {
		this(dictionary, Duration.ofSeconds(35));
	}

	TranslationService(WorldDictionary dictionary, Duration timeout) {
		this.dictionary = dictionary;
		this.requestTimeoutMillis = timeout.toMillis();
		if (requestTimeoutMillis <= 0) throw new IllegalArgumentException("Translation timeout must be positive.");
	}

	public void registerProvider(TranslationProvider provider) {
		providers.put(provider.getId(), provider);
		MCCF.LOGGER.info("[MCCF] Registered translation provider: {}", provider.getId());
	}

	public boolean setActiveProvider(String id) {
		TranslationProvider provider = providers.get(id);
		if (provider == null) {
			MCCF.LOGGER.warn("[MCCF] Unknown translation provider: {}", id);
			return false;
		}
		synchronized (cache) {
			activeProvider = provider;
			clearCache();
		}
		MCCF.LOGGER.info("[MCCF] Active translation provider set to: {}", id);
		return true;
	}

	public TranslationProvider getActiveProvider() {
		return activeProvider;
	}

	public Map<String, TranslationProvider> getProviders() {
		return providers;
	}

	public CompletableFuture<List<String>> listModels(String providerId) {
		TranslationProvider provider = providers.get(providerId);
		if (provider == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown translation provider"));
		}
		try {
			return provider.listModels();
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(ex);
		}
	}

	/** Failures, including synchronous request-construction failures, fall back without caching. */
	public CompletableFuture<String> translate(String sourceText, String sourceLang, String targetLang,
			List<String> contextMessages) {
		TranslationProvider provider;
		long generation;
		List<String> context = contextMessages == null ? List.of() : List.copyOf(contextMessages);
		CacheKey key = new CacheKey(sourceText, sourceLang, targetLang, contextDigest(context));
		synchronized (cache) {
			provider = activeProvider;
			if (provider == null || sourceLang.equals(targetLang)) {
				return CompletableFuture.completedFuture(sourceText);
			}
			generation = cacheGeneration;
			CacheEntry entry = cache.get(key);
			if (entry != null) {
				if (System.currentTimeMillis() - entry.writtenAt() <= CACHE_TTL_MILLIS) {
					return CompletableFuture.completedFuture(entry.text());
				}
				cache.remove(key);
			}
		}

		CompletableFuture<String> translation;
		try {
			WorldDictionary.DictionaryPass pass = dictionary.applyPlaceholders(sourceText);
			var request = new TranslationProvider.TranslationRequest(
					pass.processedText(), sourceLang, targetLang, context);
			translation = provider.translate(request).thenApply(result ->
					dictionary.restorePlaceholders(result.translatedText(), pass.placeholderToTerm(), targetLang));
		} catch (RuntimeException ex) {
			translation = CompletableFuture.failedFuture(ex);
		}

		return translation.orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS).handle((result, error) -> {
			if (error != null) {
				// Exception messages from gateways may echo credentials or complete request URLs.
				MCCF.LOGGER.warn("[MCCF] Translation failed for provider {}; falling back to source text.", provider.getId());
				failureCount.incrementAndGet();
				return sourceText;
			}
			synchronized (cache) {
				// A request started before reload/clear must never repopulate the new cache.
				if (generation == cacheGeneration && provider == activeProvider) {
					cache.put(key, new CacheEntry(result, System.currentTimeMillis()));
				}
			}
			successCount.incrementAndGet();
			return result;
		});
	}

	private static String contextDigest(List<String> context) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String message : context) {
				byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
				digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Java runtime does not provide SHA-256", ex);
		}
	}

	public void clearCache() {
		synchronized (cache) {
			cacheGeneration++;
			cache.clear();
		}
	}

	public long getFailureCount() {
		return failureCount.get();
	}

	public long getSuccessCount() {
		return successCount.get();
	}

	public void resetFailureStats() {
		failureCount.set(0);
		successCount.set(0);
	}
}
