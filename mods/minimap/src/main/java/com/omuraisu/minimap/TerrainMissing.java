package com.omuraisu.minimap;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// TerrainRequest の read のうち、まだ生成されていなかったチャンク。クライアントは次から generate で頼む
public record TerrainMissing(Identifier dimension, List<Long> chunks) implements CustomPacketPayload {
	public static final Type<TerrainMissing> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v2/missing"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainMissing> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, TerrainMissing::dimension,
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list(TerrainRequest.MAX_READ)), TerrainMissing::chunks,
			TerrainMissing::new);

	@Override
	public Type<TerrainMissing> type() {
		return TYPE;
	}
}
