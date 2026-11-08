package com.omuraisu.minimap;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 1 チャンク分の地形の色（TerrainColors.chunk）
public record TerrainTile(Identifier dimension, long chunk, byte[] colors) implements CustomPacketPayload {
	public static final Type<TerrainTile> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "terrain_tile"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainTile> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, TerrainTile::dimension,
			ByteBufCodecs.LONG, TerrainTile::chunk,
			ByteBufCodecs.byteArray(16 * 16), TerrainTile::colors,
			TerrainTile::new);

	@Override
	public Type<TerrainTile> type() {
		return TYPE;
	}
}
