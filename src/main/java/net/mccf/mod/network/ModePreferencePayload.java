package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 只管客户端向服务端声明翻译来源偏好，空间可听规则由 SpatialChatHandler 决定。
 * 在装有 MCCF 的服务器上，clientOnlyMode=true 表示服务端传原文供本地翻译，
 * 不恢复原版全服广播，也不绕过距离和维度限制。旧服务端不认识此通道时，
 * 客户端通过 canSend 检查跳过上报，并从已收到的 SubtitlePayload 提取文本。
 *
 * @param clientOnlyMode 是否由接收方客户端使用自己的 Provider 翻译
 */
public record ModePreferencePayload(boolean clientOnlyMode) implements CustomPayload {

	public static final CustomPayload.Id<ModePreferencePayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "mode_preference"));

	public static final PacketCodec<RegistryByteBuf, ModePreferencePayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOL, ModePreferencePayload::clientOnlyMode,
			ModePreferencePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
