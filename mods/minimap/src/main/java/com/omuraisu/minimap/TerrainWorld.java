package com.omuraisu.minimap;

import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// このワールドの ID。入ったときに送り、クライアントは遠くの地形の保存先を ID ごとに分ける。
// 同じアドレス（例: localhost）でも別のワールドなら別のファイルになり、前のワールドの地図が出ない
public record TerrainWorld(UUID id) implements CustomPacketPayload {
	public static final Type<TerrainWorld> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v5/world"));
	public static final StreamCodec<FriendlyByteBuf, TerrainWorld> CODEC = CustomPacketPayload.codec(TerrainWorld::write, TerrainWorld::read);

	private static TerrainWorld read(FriendlyByteBuf buf) {
		return new TerrainWorld(buf.readUUID());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUUID(id);
	}

	@Override
	public Type<TerrainWorld> type() {
		return TYPE;
	}
}
