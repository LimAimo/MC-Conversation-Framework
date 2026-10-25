package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.mccf.mod.MCCF;

import java.util.UUID;

/**
 * 只管服务端到客户端的字幕数据契约；空间判定由 SpatialChatHandler、展示由 MCCFClient 负责。
 *
 * 每当一名玩家说的话被判定为"某个监听者能收到"，服务端就为该监听者
 * 单独构造一份 SubtitlePayload 并发送——注意这是"点对点"而非广播，
 * 保证了"信息只会传播到真正能够接收到它的人"（不同玩家收到的字幕
 * 内容、显示方式都可能不同，因为各自的距离/遮挡/目标语言都不同）。
 *
 * @param speakerId      说话者 UUID（客户端据此识别自身回显与历史发言者）
 * @param speakerName    说话者显示名（用于屏幕下方字幕的前缀，如 "Alice: ..."）
 * @param originalText   原文
 * @param translatedText 译文（若客户端语言与说话者相同，等于原文）
 * @param displayMode    "VISIBLE" 显示在聊天栏 / "AUDIBLE" 显示在物品栏上方
 * @param conversationId 这句话所属的 Conversation（服务端 {@link net.mccf.mod.context.Conversation}
 *                       的 id）。客户端聊天历史记录界面据此把消息分组展示（同一个
 *                       Conversation 的消息归为同一个对话块，参见
 *                       {@code ChatHistoryEntry#conversationId}）。玩家自己回显
 *                       时同样携带
 *                       这个 id——说话者自己发的消息当然也属于这个 Conversation。
 * @param sourceLang     说话者的语言代码（{@code PlayerLanguageRegistry.getLanguage(speakerId)}
 *                       在服务端算出的值）。客户端聊天历史记录界面用它和 targetLang
 *                       一起渲染语言标签（例如"中文→英语"），单纯依赖文字内容本身
 *                       猜不出这两种语言分别是什么，必须由服务端一并告知。
 * @param targetLang     接收这条消息的听众自己的语言代码（即 translatedText 翻译成
 *                       的目标语言）。自己回显（SELF）时 sourceLang == targetLang
 *                       （因为回显不翻译，原文=译文，语言也相同）。
 */
public record SubtitlePayload(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		String displayMode,
		UUID conversationId,
		String sourceLang,
		String targetLang
) implements CustomPayload {

    /**
     * 限制客户端需要展示的文本长度；原版聊天通常不超过 256 字符，译文留到 4096。
     * 这是 S2C 业务边界，不是恶意客户端向服务端发包的入口；PacketCodecs.STRING
     * 自身还有 32767 字符上限。发送端可用此常量提前回退超长 Provider 输出。
     */
    public static final int MAX_TEXT_LENGTH = 4096;

	public SubtitlePayload {
		if (originalText != null && originalText.length() > MAX_TEXT_LENGTH) {
			throw new IllegalArgumentException(
					"SubtitlePayload originalText exceeds max length " + MAX_TEXT_LENGTH +
					" (was " + originalText.length() + ")");
		}
		if (translatedText != null && translatedText.length() > MAX_TEXT_LENGTH) {
			throw new IllegalArgumentException(
					"SubtitlePayload translatedText exceeds max length " + MAX_TEXT_LENGTH +
					" (was " + translatedText.length() + ")");
		}
	}

	public static final CustomPayload.Id<SubtitlePayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "subtitle"));

	// 手动实现 PacketCodec（encode/decode），而不是用 PacketCodec.tuple(...) 辅助方法：
	// 项目里此前所有 payload 最多只用到 5 个字段的 tuple 重载，字段数一多是否有
	// 对应重载没有先例可以核对，手写 encode/decode 完全不依赖这个不确定因素，
	// 只用最基础、确定存在的 RegistryByteBuf 读写方法和 PacketCodec.of(...) 两参数
	// 版本（encoder + decoder 各自一个 lambda，这是 PacketCodec 接口最原始的构造
	// 方式，不涉及任何"重载数量"的不确定性）。
	public static final PacketCodec<RegistryByteBuf, SubtitlePayload> CODEC = PacketCodec.of(
			(payload, buf) -> {
				Uuids.PACKET_CODEC.encode(buf, payload.speakerId());
				PacketCodecs.STRING.encode(buf, payload.speakerName());
				PacketCodecs.STRING.encode(buf, payload.originalText());
				PacketCodecs.STRING.encode(buf, payload.translatedText());
				PacketCodecs.STRING.encode(buf, payload.displayMode());
				Uuids.PACKET_CODEC.encode(buf, payload.conversationId());
				PacketCodecs.STRING.encode(buf, payload.sourceLang());
				PacketCodecs.STRING.encode(buf, payload.targetLang());
			},
			buf -> new SubtitlePayload(
					Uuids.PACKET_CODEC.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf),
					Uuids.PACKET_CODEC.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf)
			)
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
