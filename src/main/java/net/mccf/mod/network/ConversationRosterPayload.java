package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.mccf.mod.MCCF;

import java.util.List;
import java.util.UUID;

/**
 * 这个类只管服务端到客户端的名单编解码及输入边界，不决定参与者或接收范围；
 * SpatialChatHandler 只发送收件人当前听力范围内的名单。
 *
 * 名单独立于 {@link SubtitlePayload}，因为每条消息都携带完整名单会造成带宽浪费——一个活跃的 5 人对话组
 * 每人每次发言都要重复广播全部 5 个人的 UUID+名字，而这份名单在多数时候
 * 相邻两次发言之间根本没有变化）。改为独立的包，只在参与者集合真正发生变化
 * 或收件人可听见的成员范围变化时才发送一次；每个收件人的名单分别筛选，
 * 不能因为同属一个连通组就获得远处成员的身份。
 *
 * 客户端收到后更新本地"conversationId -> 参与者名单"的映射（见客户端
 * ChatHistoryManager 相关逻辑），聊天历史记录界面据此渲染大标题
 * （"LimAimo、test、Alex 的对话"）和"XX 加入了对话"提示。
 *
 * @param conversationId 对应服务端 {@link net.mccf.mod.context.Conversation} 的 id
 * @param participantIds   收件人自身优先，之后按组内插入顺序列出当前可听的参与者；
 *                         发送端最多 128 人，以兼容相同频道下的旧客户端解码器
 * @param participantNames 与 participantIds 一一对应的显示名列表，长度必须相同
 */
public record ConversationRosterPayload(
		UUID conversationId,
		List<UUID> participantIds,
		List<String> participantNames
) implements CustomPayload {

	/** 单个 Conversation 参与者数量上限，防御性限制，避免恶意/异常数据构造超大包。 */
	// 新解码器允许有界的较大名单；实际发送仍截为旧协议的 128 人，
	// 因为频道没有能力协商，不能把新解码上限当成旧客户端可接受的数量。
	public static final int MAX_PARTICIPANTS = 1024;

	public ConversationRosterPayload {
		if (participantIds.size() != participantNames.size()) {
			throw new IllegalArgumentException(
					"ConversationRosterPayload participantIds/participantNames size mismatch: " +
					participantIds.size() + " vs " + participantNames.size());
		}
		if (participantIds.size() > MAX_PARTICIPANTS) {
			throw new IllegalArgumentException(
					"ConversationRosterPayload participant count exceeds max " + MAX_PARTICIPANTS +
					" (was " + participantIds.size() + ")");
		}
		participantIds = List.copyOf(participantIds);
		participantNames = List.copyOf(participantNames);
	}

	public static final CustomPayload.Id<ConversationRosterPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "conversation_roster"));

	// 手写计数和列表循环，使分配之前的边界检查与线上字段顺序集中可见，
	// 避免调整集合工具方法时意外改变现有协议。
	public static final PacketCodec<RegistryByteBuf, ConversationRosterPayload> CODEC = PacketCodec.of(
			(payload, buf) -> {
				Uuids.PACKET_CODEC.encode(buf, payload.conversationId());

				List<UUID> ids = payload.participantIds();
				buf.writeVarInt(ids.size());
				for (UUID id : ids) {
					Uuids.PACKET_CODEC.encode(buf, id);
				}

				List<String> names = payload.participantNames();
				buf.writeVarInt(names.size());
				for (String name : names) {
					PacketCodecs.STRING.encode(buf, name);
				}
			},
			buf -> {
			UUID conversationId = Uuids.PACKET_CODEC.decode(buf);

			// 名单是 S2C 包；先校验长度再分配，避免异常服务端让客户端预分配无界内存。
			// 非法包抛异常交给网络层处理，不能假定网络层一定静默丢弃而不会断开连接。
			int idCount = buf.readVarInt();
			if (idCount < 0 || idCount > MAX_PARTICIPANTS) {
				throw new IllegalArgumentException(
						"ConversationRosterPayload idCount out of range [0, " + MAX_PARTICIPANTS +
						"]: " + idCount);
			}
			List<UUID> ids = new java.util.ArrayList<>(idCount);
			for (int i = 0; i < idCount; i++) {
				ids.add(Uuids.PACKET_CODEC.decode(buf));
			}

			int nameCount = buf.readVarInt();
			if (nameCount < 0 || nameCount > MAX_PARTICIPANTS) {
				throw new IllegalArgumentException(
						"ConversationRosterPayload nameCount out of range [0, " + MAX_PARTICIPANTS +
						"]: " + nameCount);
			}
			List<String> names = new java.util.ArrayList<>(nameCount);
			for (int i = 0; i < nameCount; i++) {
				names.add(PacketCodecs.STRING.decode(buf));
			}

			return new ConversationRosterPayload(conversationId, ids, names);
		}
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
