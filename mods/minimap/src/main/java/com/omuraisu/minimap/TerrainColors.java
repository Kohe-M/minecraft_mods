package com.omuraisu.minimap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

// 地図の 1 列の色。クライアントが持つチャンク・サーバーが読み込んでいるチャンク・セーブから読んだチャンクで同じ色になるよう、ここだけで決める。
// 色は MapColor の packed id（色 × 4 + 明るさ）で、0 は色のない列
public final class TerrainColors {
	// ひとつ北の列の高さが分からないとき。起伏は平らに見せる
	public static final int UNKNOWN_Y = Integer.MIN_VALUE;
	// 北と比べない列（流体と色のない列）。タイルに short で載せる
	public static final int NO_RELIEF = Short.MIN_VALUE;

	private static final int MAX_WATER_DEPTH = 16;

	private TerrainColors() {
	}

	// 色を決めるのに使うブロックの読み方。座標はワールド座標
	public interface Blocks {
		BlockState getBlockState(int x, int y, int z);

		int minY();

		int maxY();

		// 一番上の空気でないブロックの高さ（Heightmap.Types.WORLD_SURFACE と同じ）。なければ minY() - 1
		int surfaceY(int x, int z);
	}

	public static Blocks of(ChunkAccess chunk) {
		return new ChunkBlocks(chunk);
	}

	// 列の一番上のブロックの高さ。天井のある次元は天井を映さないよう、足元の高さから下を見る
	public static int surfaceY(Blocks blocks, int x, int z, boolean ceiling, int feetY) {
		if (!ceiling) {
			return blocks.surfaceY(x, z);
		}
		int y = Math.min(feetY + 2, blocks.maxY());
		while (y > blocks.minY() && blocks.getBlockState(x, y, z).isAir()) {
			y--;
		}
		return y;
	}

	// 北より高いと明るく、低いと暗くして起伏を見せる。水は深さで濃さを変える
	public static int packedColor(Blocks blocks, int x, int z, int surfaceY, int northY) {
		long column = column(blocks, x, z, surfaceY);
		return relief(color(column), reliefY(column), northY);
	}

	// 北と比べる前の色の明るさを、北の列の高さで決め直す。y は北と比べる高さ（NO_RELIEF なら決め直さない）
	public static int relief(int packed, int y, int northY) {
		if (y == NO_RELIEF) {
			return packed;
		}
		MapColor.Brightness brightness;
		if (northY == UNKNOWN_Y || y == northY) {
			brightness = MapColor.Brightness.NORMAL;
		} else {
			brightness = y > northY ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
		}
		return packed & ~3 | brightness.id;
	}

	public static int argb(int packed) {
		return ARGB.opaque(MapColor.byId(packed >> 2).calculateARGBColor(MapColor.Brightness.byId(packed & 3)));
	}

	// チャンク 1 つ分の色を北から 1 行ずつ並べる。北の端の行はひとつ北のチャンクがないと明るさを決められないので、
	// 北と比べる高さを添え、受け取った側が relief で決める。南の端の行の高さも、南のチャンクのために添える
	public static TerrainTile tile(Identifier dimension, ChunkPos pos, Blocks blocks, boolean ceiling, int feetY) {
		byte[] colors = new byte[16 * 16];
		short[] northEdge = new short[16];
		short[] southEdge = new short[16];
		int[] previous = new int[16];
		int minX = pos.getMinBlockX();
		int minZ = pos.getMinBlockZ();
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int x = minX + dx;
				int z = minZ + dz;
				int y = surfaceY(blocks, x, z, ceiling, feetY);
				long column = column(blocks, x, z, y);
				if (dz == 0) {
					colors[dx] = (byte) relief(color(column), reliefY(column), UNKNOWN_Y);
					northEdge[dx] = (short) reliefY(column);
				} else {
					colors[dz * 16 + dx] = (byte) relief(color(column), reliefY(column), previous[dx]);
				}
				previous[dx] = y;
			}
		}
		for (int dx = 0; dx < 16; dx++) {
			southEdge[dx] = (short) previous[dx];
		}
		return new TerrainTile(dimension, pos.pack(), colors, northEdge, southEdge);
	}

	// 北と比べる前の色（明るさは NORMAL。流体は深さで決めた明るさ）と、北と比べる高さを 1 つにまとめる
	private static long column(Blocks blocks, int x, int z, int surfaceY) {
		BlockState state = null;
		MapColor color = MapColor.NONE;
		int y = surfaceY;
		for (; y >= blocks.minY() && color == MapColor.NONE; y--) {
			state = blocks.getBlockState(x, y, z);
			color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		}
		if (color == MapColor.NONE) {
			return pack(0, NO_RELIEF);
		}
		y++;
		if (!state.getFluidState().isEmpty()) {
			return pack(Byte.toUnsignedInt(color.getPackedId(depthBrightness(blocks, x, y, z))), NO_RELIEF);
		}
		return pack(Byte.toUnsignedInt(color.getPackedId(MapColor.Brightness.NORMAL)), y);
	}

	private static long pack(int packed, int reliefY) {
		return (long) reliefY << 32 | packed;
	}

	private static int color(long column) {
		return (int) column;
	}

	private static int reliefY(long column) {
		return (int) (column >> 32);
	}

	private static MapColor.Brightness depthBrightness(Blocks blocks, int x, int y, int z) {
		int depth = 1;
		while (depth < MAX_WATER_DEPTH && !blocks.getBlockState(x, y - depth, z).getFluidState().isEmpty()) {
			depth++;
		}
		return depth <= 3 ? MapColor.Brightness.HIGH : depth <= 8 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
	}

	private static final class ChunkBlocks implements Blocks {
		private final ChunkAccess chunk;
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		private ChunkBlocks(ChunkAccess chunk) {
			this.chunk = chunk;
		}

		@Override
		public BlockState getBlockState(int x, int y, int z) {
			return chunk.getBlockState(pos.set(x, y, z));
		}

		@Override
		public int minY() {
			return chunk.getMinY();
		}

		@Override
		public int maxY() {
			return chunk.getMaxY();
		}

		@Override
		public int surfaceY(int x, int z) {
			return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		}
	}
}
