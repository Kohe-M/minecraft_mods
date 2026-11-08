package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;

// 1 チャンク分の地形の色（TerrainColors.tile）。北の端の行の明るさは、受け取った側がひとつ北のチャンクの southEdge と
// northEdge を比べて決める（TerrainColors.relief）。送るときは TerrainTiles にまとめる
public record TerrainTile(long chunk, byte[] colors, short[] northEdge, short[] southEdge) {
	static TerrainTile read(FriendlyByteBuf buf) {
		long chunk = buf.readLong();
		byte[] colors = new byte[16 * 16];
		buf.readBytes(colors);
		return new TerrainTile(chunk, colors, readEdge(buf), readEdge(buf));
	}

	void write(FriendlyByteBuf buf) {
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
}
