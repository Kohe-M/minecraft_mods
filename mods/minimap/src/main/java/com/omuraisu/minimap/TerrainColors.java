package com.omuraisu.minimap;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

// 地図の 1 列の色。クライアントが持つチャンクとサーバーで読み込んだチャンクで同じ色になるよう、ここだけで決める。
// 色は MapColor の packed id（色 × 4 + 明るさ）で、0 は色のない列
public final class TerrainColors {
	// ひとつ北の列の高さが分からないとき。起伏は平らに見せる
	public static final int UNKNOWN_Y = Integer.MIN_VALUE;

	private static final int MAX_WATER_DEPTH = 16;

	private TerrainColors() {
	}

	// 列の一番上のブロックの高さ。天井のある次元は天井を映さないよう、足元の高さから下を見る
	public static int surfaceY(ChunkAccess chunk, int x, int z, boolean ceiling, int feetY) {
		if (!ceiling) {
			return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int y = Math.min(feetY + 2, chunk.getMaxY());
		while (y > chunk.getMinY() && chunk.getBlockState(pos.set(x, y, z)).isAir()) {
			y--;
		}
		return y;
	}

	// 北より高いと明るく、低いと暗くして起伏を見せる。水は深さで濃さを変える
	public static int packedColor(ChunkAccess chunk, int x, int z, int surfaceY, int northY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState state = null;
		MapColor color = MapColor.NONE;
		int y = surfaceY;
		for (; y >= chunk.getMinY() && color == MapColor.NONE; y--) {
			state = chunk.getBlockState(pos.set(x, y, z));
			color = state.getMapColor(chunk, pos);
		}
		if (color == MapColor.NONE) {
			return 0;
		}
		y++;

		MapColor.Brightness brightness;
		if (!state.getFluidState().isEmpty()) {
			brightness = depthBrightness(chunk, pos, x, y, z);
		} else if (northY == UNKNOWN_Y || y == northY) {
			brightness = MapColor.Brightness.NORMAL;
		} else {
			brightness = y > northY ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
		}
		return Byte.toUnsignedInt(color.getPackedId(brightness));
	}

	public static int argb(int packed) {
		return ARGB.opaque(MapColor.byId(packed >> 2).calculateARGBColor(MapColor.Brightness.byId(packed & 3)));
	}

	// チャンク 1 つ分の色を北から 1 行ずつ並べる。north はひとつ北のチャンクで、null なら北の端の起伏は平らに見せる
	public static byte[] chunk(ChunkAccess chunk, ChunkAccess north, boolean ceiling, int feetY) {
		byte[] colors = new byte[16 * 16];
		int[] heights = new int[16 * 16];
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int x = minX + dx;
				int z = minZ + dz;
				int y = surfaceY(chunk, x, z, ceiling, feetY);
				int northY;
				if (dz > 0) {
					northY = heights[(dz - 1) * 16 + dx];
				} else {
					northY = north != null ? surfaceY(north, x, z - 1, ceiling, feetY) : UNKNOWN_Y;
				}
				heights[dz * 16 + dx] = y;
				colors[dz * 16 + dx] = (byte) packedColor(chunk, x, z, y, northY);
			}
		}
		return colors;
	}

	private static MapColor.Brightness depthBrightness(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		int depth = 1;
		while (depth < MAX_WATER_DEPTH && !chunk.getFluidState(pos.set(x, y - depth, z)).isEmpty()) {
			depth++;
		}
		return depth <= 3 ? MapColor.Brightness.HIGH : depth <= 8 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
	}
}
