package com.omuraisu.minimap;

import net.minecraft.world.level.ChunkPos;

// 遠くの地形は 32 × 32 チャンク（リージョンファイルと同じ区切り）ごとに扱う。リージョンは ChunkPos.pack(リージョンの x, z) で表す
public final class TerrainRegions {
	public static final int CHUNKS = 32;
	public static final int COUNT = CHUNKS * CHUNKS;
	public static final int BLOCKS = CHUNKS * 16;

	private TerrainRegions() {
	}

	public static int coordinate(int chunk) {
		return Math.floorDiv(chunk, CHUNKS);
	}

	public static long key(int chunkX, int chunkZ) {
		return ChunkPos.pack(coordinate(chunkX), coordinate(chunkZ));
	}

	// リージョンの中の番号（リージョンファイルのヘッダーの並びと同じ）
	public static int index(int chunkX, int chunkZ) {
		return Math.floorMod(chunkX, CHUNKS) + Math.floorMod(chunkZ, CHUNKS) * CHUNKS;
	}
}
