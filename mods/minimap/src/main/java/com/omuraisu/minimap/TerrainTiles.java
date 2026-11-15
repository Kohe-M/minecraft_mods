package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 同じ次元のチャンクの地形の色をまとめて送る。1 つずつ送るよりパケットの数と圧縮の手間が減る
public record TerrainTiles(Identifier dimension, List<TerrainTile> tiles) implements CustomPacketPayload {
	// 1 つのパケットに入れる数。1 チャンク 330 バイトほど（32 KiB 未満に収める）
	public static final int MAX_TILES = 64;
	public static final Type<TerrainTiles> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v5/tiles"));
	public static final StreamCodec<FriendlyByteBuf, TerrainTiles> CODEC = CustomPacketPayload.codec(TerrainTiles::write, TerrainTiles::read);

	private static TerrainTiles read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_TILES) {
			throw new IllegalArgumentException("too many tiles: " + count);
		}
		List<TerrainTile> tiles = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			tiles.add(TerrainTile.read(buf));
		}
		return new TerrainTiles(dimension, tiles);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeVarInt(tiles.size());
		for (TerrainTile tile : tiles) {
			tile.write(buf);
		}
	}

	@Override
	public Type<TerrainTiles> type() {
		return TYPE;
	}
}
