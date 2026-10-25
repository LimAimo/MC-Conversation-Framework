package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * OpenAI 兼容格式（chat/completions）请求构建与响应解析的工具类。
 *
 * 这个类只管 OpenAI Chat Completions 协议本身的"格式"——怎么拼请求体、
 * 怎么解析 choices[0].message.content、怎么解析模型列表的 data[].id；
 * 不管具体 Provider 的鉴权头（Bearer / x-api-key / x-goog-api-key 各家不同）
 * 和 endpoint 构造（/v1/chat/completions vs /chat/completions vs /v1/models
 * 等路径差异由各 Provider 自己拼）。各 Provider 只需要把 model / systemPrompt /
 * userText 传给这里的 {@link #buildRequestBody}，把响应体传给
 * {@link #parseChatCompletionResponse}，就能复用同一份协议处理代码。
 *
 * 之所以从 OpenAiTranslationProvider 抽出来：原本这 5 个方法挂在 OpenAi 类里，
 * Kimi/DeepSeek/Claude/Gemini 都通过 OpenAiTranslationProvider.xxx 调用，
 * 把"OpenAI 是协议模板"和"OpenAI 是一个具体 Provider"两件事耦合在一起——
 * 既让 OpenAi 类承担了不属于它的协议公共职责，又让其他 Provider 在调用上
 * 依赖 OpenAi 类（语义上很怪：Gemini 依赖 OpenAi？）。抽成独立的工具类后，
 * Provider 之间的依赖关系更干净，OpenAi 也回归"一个普通 Provider"的角色，
 * 以后再加新的 OpenAI 兼容 Provider 直接调这里就行，不用再"借用" OpenAi。
 *
 * 注意：这里只覆盖"标准 OpenAI 格式"。Claude 的 Messages API、Gemini 的
 * generateContent 端点都有自己的请求/响应结构，不在本类范围内——那两个
 * Provider 自己有 buildRequestBody / parseXxxResponse。本类只被它们的
 * listModels() 借用 {@link #stripTrailingSlash} 和（Claude 的）{@link #parseModelListResponse}，
 * 因为这两个方法确实属于"OpenAI 兼容格式"范畴。
 */
public final class ChatCompletionsSupport {

	private ChatCompletionsSupport() {}

	/** 去掉 endpoint 末尾的 "/"，方便后续拼路径。各 Provider 的 endpoint 配置风格不一，有的带斜杠有的不带，统一在这里规整。 */
	public static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	/**
	 * 构造系统提示词：说明翻译任务 + 注入当前 Conversation 的完整对话上下文。
	 *
	 * 0.16.0 起不再截断上下文——整个 Conversation 生命周期内的所有消息都会写入
	 * prompt（应用户明确要求"一个 Conversation 从开始到结束作为完整上下文"）。
	 * 上下文范围已经由 Conversation 在上层按距离/参与者 + idle timeout 裁剪过，
	 * 一个对话组从创建到超时释放之间的所有发言都会传到这里。长对话会让 prompt
	 * 变长、token 消耗增加，这是用户知情接受的取舍；如果未来某家 Provider 的
	 * 模型上下文窗口太小导致请求失败，可以在 Provider 层单独加截断，不影响这里的
	 * 通用逻辑。
	 */
	public static String buildSystemPrompt(TranslationProvider.TranslationRequest request) {
		StringBuilder sb = new StringBuilder();
		sb.append("You are a real-time game chat translator. Translate the user's message from ")
				.append(request.sourceLang()).append(" to ").append(request.targetLang())
				.append(". Only output the translated text, nothing else — no quotes, no explanations.");

		List<String> context = request.contextMessages();
		if (context != null && !context.isEmpty()) {
			sb.append(" Recent conversation context (for tone/pronoun consistency only, do not translate these): ");
			// 0.16.0 起不截断，整个 Conversation 的所有上下文都写入——见方法 Javadoc。
			for (String msg : context) {
				sb.append("\"").append(msg.replace("\"", "'")).append("\" ");
			}
		}
		return sb.toString();
	}

	/**
	 * 构造标准 OpenAI Chat Completions 请求体（不带思考控制参数）。
	 *
	 * temperature=0.3：翻译需要一定稳定性（同一句话尽量给相同译文）但也不能完全
	 * greedy——0 容易让模型在某些歧义句上死板输出，0.3 是经验上"足够稳定又不僵化"
	 * 的常用值，社区翻译场景里被广泛采用。
	 */
	public static String buildRequestBody(String model, String systemPrompt, String userText) {
		return buildRequestBody(model, systemPrompt, userText, false);
	}

	/**
	 * 构造兼容 Chat Completions 的请求体，可选注入 {@code thinking.type=disabled}。
	 * 这个扩展字段并非所有模型都支持，调用的 Provider 负责根据模型约束决定是否发送。
	 * 固定采样参数的模型应使用 {@link #buildRequestBodyWithDefaultSampling}。
	 */
	public static String buildRequestBody(String model, String systemPrompt, String userText, boolean disableThinking) {
		return buildRequestBody(model, systemPrompt, userText, disableThinking, true);
	}

	/** Let the provider select sampling defaults when models enforce their own temperature. */
	public static String buildRequestBodyWithDefaultSampling(String model, String systemPrompt,
			String userText, boolean disableThinking) {
		return buildRequestBody(model, systemPrompt, userText, disableThinking, false);
	}

	private static String buildRequestBody(String model, String systemPrompt, String userText,
			boolean disableThinking, boolean includeTemperature) {
		String thinkingField = disableThinking ? ",\"thinking\":{\"type\":\"disabled\"}" : "";
		return "{"
				+ "\"model\":\"" + HttpProviderSupport.escapeJson(model) + "\","
				+ "\"messages\":["
				+ "{\"role\":\"system\",\"content\":\"" + HttpProviderSupport.escapeJson(systemPrompt) + "\"},"
				+ "{\"role\":\"user\",\"content\":\"" + HttpProviderSupport.escapeJson(userText) + "\"}"
				+ "]"
				+ (includeTemperature ? ",\"temperature\":0.3" : "")
				+ thinkingField
				+ "}";
	}

	/** 解析标准 OpenAI Chat Completions 响应：choices[0].message.content。 */
	public static String parseChatCompletionResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonArray choices = root.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new RuntimeException("No choices in response");
		}
		JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		return message.get("content").getAsString().trim();
	}

	/**
	 * 解析标准 OpenAI 模型列表响应：{"data":[{"id":"gpt-4o",...}, ...]}，按 id 字母排序。
	 *
	 * Kimi/DeepSeek 等 OpenAI 兼容厂商也用同样的 {"data":[...]} 结构，所以这个方法
	 * 被多个 Provider 共用。Claude 的 /v1/models 端点同样返回这个格式（Anthropic
	 * 兼容了 OpenAI 的模型列表 schema），所以 Claude 也复用这里。
	 */
	public static List<String> parseModelListResponse(String responseBody) {
		JsonObject root = HttpProviderSupport.parseResponseJson(responseBody);
		JsonArray data = root.getAsJsonArray("data");
		if (data == null) {
			throw new RuntimeException("No data array in models response");
		}
		List<String> ids = new java.util.ArrayList<>();
		for (var element : data) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.has("id")) {
				ids.add(obj.get("id").getAsString());
			}
		}
		ids.sort(String::compareTo);
		return ids;
	}
}
