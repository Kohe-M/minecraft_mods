package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// まだ生成していない地形の下書き。リージョン 1 つ分を 4 ブロックに 1 つの色（TerrainColors の packed id）で、北から 1 行ずつ並べる。
// 生成せずにワールドのシードから見積もった色（TerrainPreviewer）なので、本物の色（TerrainTile）が届いたチャンクはそちらを描く
public record TerrainPreview(Identifier dimension, int regionX, int regionZ, byte[] colors) implements CustomPacketPayload {
	// 色 1 つのブロック数と、リージョンの 1 辺に並ぶ色の数
	public static final int STEP = 4;
	public static final int SIZE = TerrainRegions.BLOCKS / STEP;
	public static final Type<TerrainPreview> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v4/preview"));
	public static final StreamCodec<FriendlyByteBuf, TerrainPreview> CODEC = CustomPacketPayload.codec(TerrainPreview::write, TerrainPreview::read);

	private static TerrainPreview read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int regionX = buf.readVarInt();
		int regionZ = buf.readVarInt();
		byte[] colors = new byte[SIZE * SIZE];
		buf.readBytes(colors);
		return new TerrainPreview(dimension, regionX, regionZ, colors);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeVarInt(regionX);
		buf.writeVarInt(regionZ);
		buf.writeBytes(colors);
	}

	@Override
	public Type<TerrainPreview> type() {
		return TYPE;
	}
}
