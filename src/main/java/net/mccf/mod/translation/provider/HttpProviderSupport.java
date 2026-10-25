package net.mccf.mod.translation.provider;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 各 HTTP 翻译 Provider 共用的请求发送逻辑。
 *
 * 所有 Provider 共享同一个 {@link HttpClient}（内部维护连接池，重复创建代价较高）。
 * 统一在这里做超时、异常包装，各 Provider 只需要关心"怎么拼请求体"和
 * "怎么解析响应体"这两件事。
 */
final class HttpProviderSupport {

	static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * 用于 JSON 字符串转义的 Gson 实例。
	 *
	 * 用静态字段而不是每次 new Gson()：Gson 实例本身是线程安全且无状态的，
	 * 复用同一个实例避免每次调用 escapeJson 都重新构造 TypeAdapter 映射。
	 * 不用 GsonBuilder.setPrettyPrinting——escapeJson 只用于拼接到一行
	 * JSON body 里，不需要换行缩进。
	 */
	private static final Gson GSON = new Gson();

	private HttpProviderSupport() {}

	/** 发送一个 GET 请求并返回响应体字符串，用于模型列表这类只读接口。 */
	static CompletableFuture<String> getJson(String url, java.util.Map<String, String> headers) {
		return send(() -> {
			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(REQUEST_TIMEOUT)
					.GET();

			headers.forEach(builder::header);

			return builder.build();
		});
	}

	/**
	 * 发送一个 POST 请求并返回响应体字符串。非 2xx 状态码会让返回的
	 * future 以异常方式完成，调用方（TranslationService）会捕获并降级为原文。
	 */
	static CompletableFuture<String> postJson(String url, String body, java.util.Map<String, String> headers) {
		return send(() -> {
			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(REQUEST_TIMEOUT)
					.POST(HttpRequest.BodyPublishers.ofString(body));

			headers.forEach(builder::header);

			return builder.build();
		});
	}

	/** 表单编码版本（DeepL 用表单而不是 JSON body）。 */
	static CompletableFuture<String> postForm(String url, String formBody, java.util.Map<String, String> headers) {
		return send(() -> {
			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(REQUEST_TIMEOUT)
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(formBody));

			headers.forEach(builder::header);

			return builder.build();
		});
	}

	private static CompletableFuture<String> send(Supplier<HttpRequest> request) {
		try {
			return CLIENT.sendAsync(request.get(), HttpResponse.BodyHandlers.ofString())
					// 限制整个响应体的完成时间，模型列表请求也不能因上游挂起而永久锁住按钮。
					.orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
					.handle((response, error) -> {
						// Do not retain URLs, headers, or error bodies: proxies may echo API keys.
						if (error != null) {
							throw new CompletionException(new IllegalStateException("Provider network request failed"));
						}
						if (response.statusCode() / 100 != 2) {
							throw new CompletionException(new IllegalStateException("Provider returned HTTP " + response.statusCode()));
						}
						return response.body();
					});
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException("Invalid provider endpoint or request configuration"));
		}
	}

	static JsonObject parseResponseJson(String responseBody) {
		try {
			return JsonParser.parseString(responseBody).getAsJsonObject();
		} catch (RuntimeException ex) {
			throw new IllegalStateException("Provider returned invalid JSON");
		}
	}

	/**
	 * JSON 字符串转义，用于手写 JSON body 的字段值拼接。
	 *
	 * 为什么用 Gson 而不是手写循环：手写 JSON 转义看似简单（处理 "、\、
	 * \n、\r、\t 和 < 0x20 的控制字符），但边界情况多——代理对（surrogate
	 * pairs）、BOM、各种 Unicode 边界字符的转义策略容易遗漏或写错，而且
	 * 不同 JSON 解析器对某些控制字符的容忍度不同，手写实现踩坑不易察觉。
	 * Gson 的 StringSerializer 经过充分测试，覆盖 RFC 8259 的全部边界情况，
	 * 直接复用比自己维护一份转义循环可靠得多。
	 *
	 * 返回的是不带引号的转义文本——调用方（各 Provider 的 buildRequestBody）
	 * 在拼 JSON 时自己负责加引号（例如 "\"model\":\"" + escapeJson(model) + "\""）。
	 * Gson.toJson(text) 会返回带引号的完整 JSON 字符串字面量，这里去掉首尾
	 * 引号以保持与原手写实现一致的契约，避免改动所有调用方。
	 *
	 * 注意：Gson 默认会开启 HTML 转义（把 <、>、= 等转成 U+XXXX 形式）。这与原
	 * 手写实现的行为略有差异，但语义上等价——任何符合标准的 JSON 解析器
	 * 都会把 U+003C 解析回 <，下游 AI Provider 看到的字符串内容完全相同，
	 * 不影响翻译结果。如果未来需要字节级一致性（例如为了对比 baseline 响应），
	 * 可以改用 new GsonBuilder().disableHtmlEscaping().create()。
	 */
	static String escapeJson(String s) {
		String json = GSON.toJson(s);
		return json.substring(1, json.length() - 1);
	}

	/** URL 表单编码。 */
	static String urlEncode(String s) {
		return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
	}
}
