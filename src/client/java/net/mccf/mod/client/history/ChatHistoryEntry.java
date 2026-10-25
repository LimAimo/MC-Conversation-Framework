package net.mccf.mod.client.history;

import java.util.UUID;

/**
 * 聊天历史记录里的一条消息。
 *
 * 涵盖三类来源，用 {@link Source} 区分（历史界面按来源决定展示样式，
 * 比如"自己发的"不需要显示"⇄"翻译标记）：
 * - SELF：自己发的消息（服务端自身回显 / 或纯客户端模式下自己发的消息不翻译）。
 * - VISIBLE：别人发的、能看到说话者时收到的消息（服务端空间化分发，VISIBLE displayMode）。
 * - AUDIBLE：别人发的、听得到但看不到时收到的消息（AUDIBLE displayMode，物品栏字幕）。
 * - CLIENT_ONLY：纯客户端模式下本地翻译追加的消息（无法区分 VISIBLE/AUDIBLE，因为
 *   没有服务端空间化参与，统一归为一类）。
 *
 * @param speakerId      说话者 UUID，SELF 类型即玩家自己的 UUID
 * @param speakerName    说话者显示名
 * @param originalText   原文
 * @param translatedText 译文（与原文相同语言时可能等于原文）
 * @param source         消息来源分类
 * @param timestampMillis 接收时刻（System.currentTimeMillis()），历史界面按时间倒序展示
 * @param conversationId 服务端 Conversation id——SELF/VISIBLE/AUDIBLE 三类来源都有
 *                       （服务端总会给说话者归组，见 SpatialChatHandler 的顺序调整），
 *                       CLIENT_ONLY 恒为 null（纯客户端模式没有服务端 Conversation
 *                       概念，见类文档）。历史界面按这个字段把消息分组展示成
 *                       "XX、YY、ZZ 的对话"这样的对话块；null 时该条消息单独展示，
 *                       不参与任何分组。
 * @param sourceLang     说话者的语言代码，CLIENT_ONLY 恒为 null（那条路径不追踪
 *                       语言代码，只有译文文本，见 ClientOnlyChatTranslator）。
 * @param targetLang     接收者（也就是本地玩家）看到的目标语言代码，CLIENT_ONLY
 *                       恒为 null，理由同上。sourceLang 与 targetLang 相同时
 *                       （或任一为 null）历史界面不显示语言标签——相同语言之间
 *                       没有发生真正的翻译，画一个"中文→中文"的箭头没有意义。
 */
public record ChatHistoryEntry(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		Source source,
		long timestampMillis,
		UUID conversationId,
		String sourceLang,
		String targetLang
) {
	public enum Source {
		SELF, VISIBLE, AUDIBLE, CLIENT_ONLY
	}
}
