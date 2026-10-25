package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 客户端 -> 服务端：提交配置界面里的修改（切换 Provider、改 API Key/模型/host）。
 *
 * 与 {@link ConfigSnapshotPayload} 使用相同的 JSON 结构，区别是这次由
 * 客户端发起、服务端消费。服务端在处理时会重新校验发送者是否为 op
 * （不能只信任客户端界面本身的只读/可编辑状态，防止绕过客户端限制的
 * 恶意包）。非 op 玩家发送的修改请求会被服务端直接丢弃并记录警告日志。
 */
public record UpdateConfigPayload(String json) implements CustomPayload {

    /**
     * 和 1.21.1 的 PacketCodecs.STRING 保持相同字符上限；编码器本身已有边界，
     * 这里提前拒绝不可编码的本地对象，避免保存界面到发包阶段才失败。
     * 字符数不同于 UTF-8 字节数，不能把该限制描述成固定 32KB 网络包。
     */
    private static final int MAX_JSON_LENGTH = 32767;

	public UpdateConfigPayload {
		if (json != null && json.length() > MAX_JSON_LENGTH) {
			throw new IllegalArgumentException(
					"UpdateConfigPayload json exceeds max length " + MAX_JSON_LENGTH +
					" (was " + json.length() + ")");
		}
	}

	public static final CustomPayload.Id<UpdateConfigPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "update_config"));

	public static final PacketCodec<RegistryByteBuf, UpdateConfigPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, UpdateConfigPayload::json,
			UpdateConfigPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
