package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * DeepL 翻译 Provider。此实现使用表单请求翻译单条原文，不发送 LLM system prompt。
 *
 * Endpoint: 免费版 https://api-free.deepl.com/v2/translate
 *          付费版 https://api.deepl.com/v2/translate
 * 通过 API Key 是否以 ":fx" 结尾自动判断走免费版还是付费版端点
 * （这是 DeepL 官方的 Key 命名约定）。
 * Auth: Authorization: DeepL-Auth-Key <key>
 *
 * DeepL 使用自己的语言代码体系（如 "EN"、"ZH"、"DE"），与 Minecraft 的
 * locale 格式（"en_us"、"zh_cn"）不同，需要做一次映射。DeepL 支持的
 * 语言种类少于 Minecraft 的语言列表，映射不到的语言会导致翻译失败，
 * 调用方（TranslationService）会捕获异常并回退显示原文。
 */
public class DeepLTranslationProvider implements TranslationProvider {

	public static final String ID = "deepl";

	private final ProviderConfig config;

	public DeepLTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "DeepL";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("DeepL API key not configured"));
		}

		String targetLang = toDeepLTargetLanguageCode(request.targetLang());
		if (targetLang == null) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException("DeepL does not support target language: " + request.targetLang()));
		}

		String endpoint = translationEndpoint();

		StringBuilder form = new StringBuilder();
		form.append("text=").append(HttpProviderSupport.urlEncode(request.sourceText()));
		form.append("&target_lang=").append(targetLang);
		String sourceLang = toDeepLSourceLanguageCode(request.sourceLang());
		if (sourceLang != null) {
			form.append("&source_lang=").append(sourceLang);
		}

		return HttpProviderSupport.postForm(endpoint, form.toString(), Map.of(
				"Authorization", "DeepL-Auth-Key " + config.apiKey.trim()
		)).thenApply(DeepLTranslationProvider::parseTranslateResponse)
				.thenApply(TranslationResult::new);
	}

	String translationEndpoint() {
		String base = config.endpoint == null ? "" : ChatCompletionsSupport.stripTrailingSlash(config.endpoint.trim());
		// Older configs stored the Free URL as an explicit default. Both official base URLs
		// should follow the key type; genuine custom gateways keep their configured address.
		if (base.isBlank() || base.equals("https://api-free.deepl.com") || base.equals("https://api.deepl.com")) {
			base = config.apiKey != null && config.apiKey.trim().endsWith(":fx")
					? "https://api-free.deepl.com" : "https://api.deepl.com";
		}
		return base + "/v2/translate";
	}

	static String toDeepLSourceLanguageCode(String minecraftLocale) {
		String targetCode = toDeepLTargetLanguageCode(minecraftLocale);
		if (targetCode == null) return null; // Includes "auto": omit source_lang for detection.
		return targetCode.split("-", 2)[0];
	}

	private static String parseTranslateResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonArray translations = root.getAsJsonArray("translations");
		if (translations == null || translations.isEmpty()) {
			throw new RuntimeException("No translations in DeepL response");
		}
		return translations.get(0).getAsJsonObject().get("text").getAsString().trim();
	}

	/**
	 * 把 Minecraft locale（如 "zh_cn"）映射到 DeepL 语言代码（如 "ZH"）。
	 * 只覆盖 DeepL 官方支持的语言列表中的常见项；不支持的语言返回 null，
	 * 调用方应据此判断是否需要跳过 DeepL、改用 LLM 类 Provider。
	 */
	static String toDeepLTargetLanguageCode(String minecraftLocale) {
		if (minecraftLocale == null) return null;
		return switch (minecraftLocale.toLowerCase(Locale.ROOT)) {
			case "en_us" -> "EN-US";
			case "en_gb" -> "EN-GB";
			case "zh_cn", "zh_sg" -> "ZH-HANS";
			case "zh_tw", "zh_hk" -> "ZH-HANT";
			case "ja_jp" -> "JA";
			case "ko_kr" -> "KO";
			case "de_de" -> "DE";
			case "fr_fr" -> "FR";
			case "es_es", "es_mx" -> "ES";
			case "it_it" -> "IT";
			case "pt_br" -> "PT-BR";
			case "pt_pt" -> "PT-PT";
			case "ru_ru" -> "RU";
			case "nl_nl" -> "NL";
			case "pl_pl" -> "PL";
			case "tr_tr" -> "TR";
			case "uk_ua" -> "UK";
			case "cs_cz" -> "CS";
			case "sv_se" -> "SV";
			case "da_dk" -> "DA";
			case "fi_fi" -> "FI";
			case "nb_no" -> "NB";
			case "el_gr" -> "EL";
			case "hu_hu" -> "HU";
			case "ro_ro" -> "RO";
			case "sk_sk" -> "SK";
			case "bg_bg" -> "BG";
			case "id_id" -> "ID";
			default -> null;
		};
	}
}
