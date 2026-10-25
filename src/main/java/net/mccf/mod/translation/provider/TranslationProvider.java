package net.mccf.mod.translation.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 只定义翻译后端契约；空间和收件人上下文由 SpatialChatHandler 筛选，缓存和词典由 TranslationService 处理。
 * 实现应只消费传入数据，不主动读取其他玩家或世界状态；接口本身不是权限隔离沙箱。
 * 翻译和模型列表使用异步结果，不能阻塞服务器主线程，标识与显示名方法仍同步返回。
 */
public interface TranslationProvider {

	/** Provider 的唯一标识符，用于在配置文件里选择激活哪个 Provider。 */
	String getId();

	/** 展示给管理员/玩家看的可读名称。 */
	String getDisplayName();

	/**
	 * 执行一次翻译请求。
	 *
	 * @param request 翻译请求，包含原文、目标语言、以及（可选的）有限对话上下文
	 * @return 异步返回翻译结果；失败时 future 应以异常完成，调用方负责降级处理
	 */
	CompletableFuture<TranslationResult> translate(TranslationRequest request);

	/**
	 * 拉取该 Provider 当前账号下可用的模型列表（"一键获取模型"功能）。
	 * 默认实现返回失败，代表该 Provider 不支持模型列表查询（例如 DeepL
	 * 是固定的翻译引擎，没有可选模型；Mock 也没有真实模型）。支持
	 * `/models` 一类接口的 Provider（OpenAI/Claude/Gemini/Kimi/DeepSeek/
	 * Ollama）应覆写此方法。
	 *
	 * @return 异步返回模型 ID 列表（已按字母排序），调用方负责展示
	 */
	default CompletableFuture<List<String>> listModels() {
		return CompletableFuture.failedFuture(
				new UnsupportedOperationException(getDisplayName() + " does not support listing models."));
	}

	/**
	 * 翻译请求。
	 *
	 * @param sourceText     原始文本（已经过世界词典占位符预处理）
	 * @param sourceLang     源语言代码（Minecraft locale 格式，如 "zh_cn"）
	 * @param targetLang     目标语言代码
     * @param contextMessages 对话历史中双方在发言当时都接收过、且原说话者当前仍可听见的消息。
     *                        Conversation 保留完整历史，但合组不会授予读取另一组既往消息的权限；
     *                        纯客户端模式可传空列表。该信息边界由调用方在构造请求前保证。
	 */
	record TranslationRequest(
			String sourceText,
			String sourceLang,
			String targetLang,
			List<String> contextMessages
	) {}

	/**
	 * 翻译结果。
	 *
	 * @param translatedText 译文（占位符尚未还原，由调用方还原）
	 */
	record TranslationResult(String translatedText) {}
}
