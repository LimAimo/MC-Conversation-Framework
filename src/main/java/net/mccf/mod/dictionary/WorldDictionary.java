package net.mccf.mod.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 世界词典：服务器统一维护的专有名词映射表。
 *
 * 设计目的（对应项目原则"信息一致性"）：
 * 不同的 AI Provider、不同的调用时机，可能把同一个专有名词（NPC 名字、地名、
 * 剧情术语）翻译成不一样的东西。这会破坏玩家的沉浸感和剧情理解。
 *
 * 世界词典在翻译前对原文做"预处理占位替换"：把词典中的词替换为一个不会被
 * 翻译引擎改写的占位符，翻译完成后再替换回词典中配置好的目标语言版本。
 * 这样无论底层用哪个 AI Provider，专有名词的翻译结果永远一致、可控。
 *
 * 配置文件：config/mccf/dictionary.json
 * 格式： { "词条": { "en_us": "...", "zh_cn": "...", "ja_jp": "..." }, ... }
 */
public class WorldDictionary {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path dictionaryFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(MCCF.MOD_ID).resolve("dictionary.json");
	}

	/** term -> (langCode -> translation) */
	private final Map<String, Map<String, String>> entries;

	/**
	 * 已编译的词条正则缓存。key 是 term，value 是 Pattern.compile(Pattern.quote(term))。
	 *
	 * 之所以缓存：applyPlaceholders 会被每条消息 × 每个听众 × 每个词条触发，
	 * 而词条集合是相对稳定的（管理员不会每秒改词典）。Pattern.compile 涉及
	 * 正则 NFA 构造，开销远高于单次 matcher.find()，反复编译同样的 term 是纯浪费。
	 *
	 * 之所以只缓存在内存、不持久化到文件：Pattern 对象无法直接序列化，且词条
	 * 集合很小（典型几十到几百条）、编译耗时也仅微秒级——重启时重编一次完全
	 * 可接受，引入持久化只会增加一致性维护负担（词条变了要同步刷新文件、
	 * 文件损坏要回退等），收益不抵复杂度。内存够快，词条变更频率低，足够了。
	 *
	 * 失效策略：addEntry / removeEntry 时整体清空。词条变更频率远低于消息频率，
	 * 整表清空比维护"哪些 term 的 Pattern 失效了"简单可靠；下次 applyPlaceholders
	 * 会通过 computeIfAbsent 自动重新填满。
	 */
	private final Map<String, Pattern> compiledPatternCache = new ConcurrentHashMap<>();

	private WorldDictionary(Map<String, Map<String, String>> entries) {
		this.entries = entries;
	}

	/** An empty in-memory dictionary, also usable outside a running game. */
	public WorldDictionary() {
		this(new LinkedHashMap<>());
	}

	public static WorldDictionary loadOrCreate() {
		Path dictionaryFile = dictionaryFile();
		try {
			if (Files.exists(dictionaryFile)) {
				try (Reader reader = Files.newBufferedReader(dictionaryFile)) {
					Type type = new TypeToken<LinkedHashMap<String, Map<String, String>>>() {}.getType();
					Map<String, Map<String, String>> loaded = GSON.fromJson(reader, type);
					if (loaded != null) {
						MCCF.LOGGER.info("[MCCF] Loaded world dictionary with {} entries.", loaded.size());
						return new WorldDictionary(loaded);
					}
				}
			}
		} catch (IOException | com.google.gson.JsonParseException e) {
			MCCF.LOGGER.error("[MCCF] Failed to read dictionary; using defaults in memory and preserving the file.", e);
			return new WorldDictionary(defaultEntries());
		}

		WorldDictionary dict = new WorldDictionary(defaultEntries());
		dict.save();
		return dict;
	}

	private static Map<String, Map<String, String>> defaultEntries() {
		Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
		// 示例条目：服务器管理员可自行编辑 dictionary.json 增删。
		Map<String, String> example = new LinkedHashMap<>();
		example.put("en_us", "Force Merge");
		example.put("zh_cn", "强制合并");
		defaults.put("Force Merge", example);
		return defaults;
	}

	public void save() {
		Path dictionaryFile = dictionaryFile();
		try {
			Files.createDirectories(dictionaryFile.getParent());
			try (Writer writer = Files.newBufferedWriter(dictionaryFile)) {
				GSON.toJson(entries, writer);
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to save dictionary.", e);
		}
	}

	public void addEntry(String term, String langCode, String translation) {
		entries.computeIfAbsent(term, k -> new LinkedHashMap<>()).put(langCode, translation);
		save();
		// 词条集合变了：清掉编译缓存。新增的 term 下次会自动 compile；
		// 已删 term 的 Pattern 留着虽无害但占内存，整体清空最简单。
		compiledPatternCache.clear();
	}

	public boolean removeEntry(String term) {
		boolean removed = entries.remove(term) != null;
		if (removed) {
			save();
			compiledPatternCache.clear();
		}
		return removed;
	}

	public Map<String, Map<String, String>> getEntries() {
		return entries;
	}

	/**
	 * 用占位符替换文本中出现的词典词条，返回替换后的文本与占位符映射表。
	 * 占位符使用不会被常见翻译引擎误处理的格式：〔MCCF_DICT_n〕
	 */
	public DictionaryPass applyPlaceholders(String text) {
		Map<String, String> placeholderToTerm = new LinkedHashMap<>();
		String result = text;
		int index = 0;
		for (String term : entries.keySet()) {
			if (term.isBlank()) continue;
			// 用缓存的编译结果：applyPlaceholders 是消息分发路径上的热点，
			// 每条消息 × 每个听众 × 每个词条都会进这里，不缓存的话 Pattern.compile
			// 的开销会随词条数线性放大。
			Pattern pattern = compiledPatternCache.computeIfAbsent(term,
					t -> Pattern.compile(Pattern.quote(t)));
			Matcher matcher = pattern.matcher(result);
			if (matcher.find()) {
				String placeholder = "\u3014MCCF_DICT_" + index + "\u3015";
				result = matcher.replaceAll(Matcher.quoteReplacement(placeholder));
				placeholderToTerm.put(placeholder, term);
				index++;
			}
		}
		return new DictionaryPass(result, placeholderToTerm);
	}

	/**
	 * 翻译完成后，将占位符替换为目标语言下词典配置的对应译文。
	 * 若词典中没有该语言的条目，则回退为词条原文。
	 */
	public String restorePlaceholders(String translatedText, Map<String, String> placeholderToTerm, String targetLang) {
		String result = translatedText;
		for (Map.Entry<String, String> e : placeholderToTerm.entrySet()) {
			String placeholder = e.getKey();
			String term = e.getValue();
			String replacement = entries.getOrDefault(term, Map.of()).getOrDefault(targetLang, term);
			result = result.replace(placeholder, replacement);
		}
		return result;
	}

	/** applyPlaceholders 的返回结果：预处理后的文本 + 占位符映射表。 */
	public record DictionaryPass(String processedText, Map<String, String> placeholderToTerm) {}
}
