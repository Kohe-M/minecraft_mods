package com.omuraisu.minimap;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 地形を送ってほしいチャンク（ChunkPos.pack）。先に読み込むものから並べる。
// 届くたびに前の依頼のうち読み込み始めていないものは取り消す。空なら取り消すだけ
public record TerrainRequest(List<Long> chunks) implements CustomPacketPayload {
	public static final int MAX_CHUNKS = 1024;
	public static final Type<TerrainRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "terrain_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list(MAX_CHUNKS)), TerrainRequest::chunks,
			TerrainRequest::new);

	@Override
	public Type<TerrainRequest> type() {
		return TYPE;
	}
}
