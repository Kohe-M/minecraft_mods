package com.omuraisu.minimap.client;

import com.omuraisu.minimap.TerrainRegions;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.resources.Identifier;

// 次元 1 つ分の、覚えているリージョン。メインスレッドでだけ触る
final class FarDimension {
	final Identifier id;
	final Long2ObjectMap<FarRegion> regions = new Long2ObjectOpenHashMap<>();
	// 天井のある次元（色が足元の高さで変わる）
	boolean ceiling;

	FarDimension(Identifier id) {
		this.id = id;
	}

	FarRegion region(int chunkX, int chunkZ) {
		return regions.get(TerrainRegions.key(chunkX, chunkZ));
	}

	byte[] tile(int chunkX, int chunkZ) {
		FarRegion region = region(chunkX, chunkZ);
		return region == null ? null : region.tiles[TerrainRegions.index(chunkX, chunkZ)];
	}
}
