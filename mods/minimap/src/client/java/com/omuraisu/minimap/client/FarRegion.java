package com.omuraisu.minimap.client;

import com.omuraisu.minimap.TerrainPreview;
import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.TerrainTile;

// リージョン 1 つ分の、届いた色と版と下書き。チャンクはリージョンの中の番号（TerrainRegions.index）で並べる。
// ファイルを読み終えたか・サーバーに知らせたか・ファイルに書いていない変化があるかも持つ（変えるのは FarSync）。
// メインスレッドでだけ触る
final class FarRegion {
	// 1 チャンク分のデータ。色 256 バイトのあとに、北の端と南の端の高さ（TerrainTile）を short 16 個ずつ
	static final int NORTH_EDGE = 16 * 16;
	static final int SOUTH_EDGE = NORTH_EDGE + 16 * 2;
	static final int TILE_BYTES = SOUTH_EDGE + 16 * 2;

	final int x;
	final int z;
	final byte[][] tiles = new byte[TerrainRegions.COUNT][];
	final int[] stamps = new int[TerrainRegions.COUNT];
	int count;
	byte[] preview;
	// ファイルを読み終えた
	boolean loaded;
	// サーバーに手持ちの版を知らせた
	boolean introduced;
	// まだファイルに書いていない変化がある
	boolean unsaved;

	FarRegion(int x, int z) {
		this.x = x;
		this.z = z;
	}

	void put(int index, byte[] data, int stamp) {
		if (tiles[index] == null) {
			count++;
		}
		tiles[index] = data;
		stamps[index] = stamp;
		unsaved = true;
	}

	boolean remove(int index) {
		if (tiles[index] == null) {
			return false;
		}
		tiles[index] = null;
		stamps[index] = 0;
		count--;
		unsaved = true;
		return true;
	}

	// ファイルから読んだ色。サーバーに知らせるまでは色が届かないので、読んだものをそのまま入れる
	void load(FarCache.Tiles read) {
		for (int index = 0; index < TerrainRegions.COUNT; index++) {
			if (read.data[index] != null && tiles[index] == null) {
				tiles[index] = read.data[index];
				stamps[index] = read.stamps[index];
				count++;
			}
		}
		loaded = true;
	}

	// 下書きの色（TerrainColors の packed id）。位置はリージョンの北西の角からのブロック数。下書きがなければ 0
	int preview(int blockX, int blockZ) {
		return preview == null ? 0 : Byte.toUnsignedInt(preview[blockX / TerrainPreview.STEP + blockZ / TerrainPreview.STEP * TerrainPreview.SIZE]);
	}

	// 届いたチャンクを、覚えておく形（TILE_BYTES）にする
	static byte[] tile(TerrainTile tile) {
		byte[] data = new byte[TILE_BYTES];
		System.arraycopy(tile.colors(), 0, data, 0, 16 * 16);
		for (int dx = 0; dx < 16; dx++) {
			setEdge(data, NORTH_EDGE, dx, tile.northEdge()[dx]);
			setEdge(data, SOUTH_EDGE, dx, tile.southEdge()[dx]);
		}
		return data;
	}

	static int edge(byte[] data, int offset, int dx) {
		return (short) ((data[offset + dx * 2] & 0xFF) << 8 | data[offset + dx * 2 + 1] & 0xFF);
	}

	private static void setEdge(byte[] data, int offset, int dx, short y) {
		data[offset + dx * 2] = (byte) (y >> 8);
		data[offset + dx * 2 + 1] = (byte) y;
	}
}
