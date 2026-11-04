package com.omuraisu.minimap.client;

import java.util.Arrays;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

// 周囲の地形を 1 ブロック 1 テクセルで持つテクスチャ。
// 列 (x, z) はワールド座標の剰余の位置に固定し、歩いても新しく見える列だけ描けばよいようにする
final class MapTerrain {
	static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("minimap", "terrain");
	static final int VIEW = 128;
	// 表示は直前の tick から補間するので、速く動いても見えている範囲を描き終えているよう広めに持つ
	static final int SIZE = VIEW + 16;

	// 動かなくても 2 秒で一巡して、ブロックの変化や読み込まれたチャンクを反映する
	private static final int REFRESH_PER_TICK = SIZE * SIZE / 40;
	private static final int EMPTY_COLOR = 0xFF1A1E26;
	private static final int MAX_WATER_DEPTH = 16;

	private final int[] slotX = new int[SIZE * SIZE];
	private final int[] slotZ = new int[SIZE * SIZE];
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	private DynamicTexture texture;
	private ClientLevel level;
	private int minX;
	private int minZ;
	private int cursor;

	void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (client.level == null || player == null) {
			level = null;
			return;
		}
		if (texture == null) {
			texture = createTexture(client);
		}

		boolean changed = client.level != level;
		if (changed) {
			level = client.level;
			Arrays.fill(slotX, Integer.MIN_VALUE);
		}
		int newMinX = player.getBlockX() - SIZE / 2;
		int newMinZ = player.getBlockZ() - SIZE / 2;
		changed |= newMinX != minX || newMinZ != minZ;
		minX = newMinX;
		minZ = newMinZ;

		int feetY = player.getBlockY();
		NativeImage image = texture.getPixels();
		if (changed) {
			for (int z = minZ; z < minZ + SIZE; z++) {
				for (int x = minX; x < minX + SIZE; x++) {
					int slot = slot(x, z);
					if (slotX[slot] != x || slotZ[slot] != z) {
						paint(image, x, z, feetY);
					}
				}
			}
		}
		for (int i = 0; i < REFRESH_PER_TICK; i++) {
			paint(image, minX + cursor % SIZE, minZ + cursor / SIZE, feetY);
			cursor = (cursor + 1) % (SIZE * SIZE);
		}
		texture.upload();
	}

	private static DynamicTexture createTexture(Minecraft client) {
		DynamicTexture created = new DynamicTexture(TEXTURE::toString, SIZE, SIZE, false) {
			{
				// 端をまたぐ表示は UV のリピートに任せる
				sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
			}
		};
		client.getTextureManager().register(TEXTURE, created);
		return created;
	}

	private static int slot(int x, int z) {
		return Math.floorMod(z, SIZE) * SIZE + Math.floorMod(x, SIZE);
	}

	private void paint(NativeImage image, int x, int z, int feetY) {
		int slot = slot(x, z);
		slotX[slot] = x;
		slotZ[slot] = z;
		image.setPixel(Math.floorMod(x, SIZE), Math.floorMod(z, SIZE), color(x, z, feetY));
	}

	private int color(int x, int z, int feetY) {
		if (!isLoaded(x, z)) {
			return EMPTY_COLOR;
		}

		int y = surfaceY(x, z, feetY);
		BlockState state = null;
		MapColor color = MapColor.NONE;
		for (; y >= level.getMinY() && color == MapColor.NONE; y--) {
			state = level.getBlockState(pos.set(x, y, z));
			color = state.getMapColor(level, pos);
		}
		if (color == MapColor.NONE) {
			return EMPTY_COLOR;
		}
		y++;

		MapColor.Brightness brightness = state.getFluidState().isEmpty()
				? slopeBrightness(y, x, z, feetY)
				: depthBrightness(x, y, z);
		return ARGB.opaque(color.calculateARGBColor(brightness));
	}

	// 北より高いと明るく、低いと暗くして起伏を見せる
	private MapColor.Brightness slopeBrightness(int y, int x, int z, int feetY) {
		int north = isLoaded(x, z - 1) ? surfaceY(x, z - 1, feetY) : y;
		return y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
	}

	private MapColor.Brightness depthBrightness(int x, int y, int z) {
		int depth = 1;
		while (depth < MAX_WATER_DEPTH && !level.getFluidState(pos.set(x, y - depth, z)).isEmpty()) {
			depth++;
		}
		return depth <= 3 ? MapColor.Brightness.HIGH : depth <= 8 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
	}

	private boolean isLoaded(int x, int z) {
		return level.hasChunk(x >> 4, z >> 4);
	}

	private int surfaceY(int x, int z, int feetY) {
		if (!level.dimensionType().hasCeiling()) {
			return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		}
		// ネザーの天井を映さないよう、足元の高さから下を見る
		int y = Math.min(feetY + 2, level.getMaxY());
		while (y > level.getMinY() && level.getBlockState(pos.set(x, y, z)).isAir()) {
			y--;
		}
		return y;
	}
}
