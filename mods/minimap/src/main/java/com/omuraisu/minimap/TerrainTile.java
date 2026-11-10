package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;

// 1 チャンク分の地形の色（TerrainColors.tile）。北の端の行の明るさは、受け取った側がひとつ北のチャンクの southEdge と
// northEdge を比べて決める（TerrainColors.relief）。送るときは TerrainTiles にまとめる。
// stamp は色の版。セーブから読んだ色は正の数（リージョンファイルのヘッダーにある、チャンクを書いた時刻）、
// メモリにあるチャンクから作った色は負の数（最後に変わった tick から作る）。同じ版なら同じ色なので、送り直さない
public record TerrainTile(long chunk, int stamp, byte[] colors, short[] northEdge, short[] southEdge) {
	// クライアントがファイルに残すときの版。サーバーのどの版とも一致しないので、次に必ず送り直される
	public static final int STALE = Integer.MIN_VALUE;

	public TerrainTile withStamp(int newStamp) {
		return new TerrainTile(chunk, newStamp, colors, northEdge, southEdge);
	}

	static TerrainTile read(FriendlyByteBuf buf) {
		long chunk = buf.readLong();
		int stamp = buf.readInt();
		byte[] colors = new byte[16 * 16];
		buf.readBytes(colors);
		return new TerrainTile(chunk, stamp, colors, readEdge(buf), readEdge(buf));
	}

	void write(FriendlyByteBuf buf) {
		buf.writeLong(chunk);
		buf.writeInt(stamp);
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
