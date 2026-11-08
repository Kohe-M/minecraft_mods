package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 1 チャンク分の地形の色（TerrainColors.tile）。北の端の行の明るさは、受け取った側がひとつ北のチャンクの southEdge と
// northEdge を比べて決める（TerrainColors.relief）
public record TerrainTile(Identifier dimension, long chunk, byte[] colors, short[] northEdge, short[] southEdge) implements CustomPacketPayload {
	public static final Type<TerrainTile> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_tile"));
	public static final StreamCodec<FriendlyByteBuf, TerrainTile> CODEC = CustomPacketPayload.codec(TerrainTile::write, TerrainTile::read);

	private static TerrainTile read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		long chunk = buf.readLong();
		byte[] colors = new byte[16 * 16];
		buf.readBytes(colors);
		return new TerrainTile(dimension, chunk, colors, readEdge(buf), readEdge(buf));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeLong(chunk);
		buf.writeBytes(colors);
		writeEdge(buf, northEdge);
		writeEdge(buf, southEdge);
	}

	private static short[] readEdge(FriendlyByteBuf buf) {
		short[] edge = new short[16];
		for (int i = 0; i < edge.length; i++) {
			edge[i] = buf.readShort();
		}
		return edge;
	}

	private static void writeEdge(FriendlyByteBuf buf, short[] edge) {
		for (short y : edge) {
			buf.writeShort(y);
		}
	}

	@Override
	public Type<TerrainTile> type() {
		return TYPE;
	}
}
