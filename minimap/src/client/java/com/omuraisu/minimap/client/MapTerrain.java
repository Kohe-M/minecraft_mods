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

// プレイヤーの周囲 size 四方の地形を 1 ブロック 1 テクセルで持つテクスチャ。
// 列 (x, z) はワールド座標の剰余の位置に固定し、歩いても新しく見える列だけ描けばよいようにする
final class MapTerrain {
	static final int EMPTY_COLOR = 0xFF1A1E26;

	private static final int MAX_WATER_DEPTH = 16;

	final Identifier texture;
	final int size;

	// 動かなくても 2 秒で一巡して、ブロックの変化や読み込まれたチャンクを反映する
	private final int refreshPerTick;
	private final int[] slotX;
	private final int[] slotZ;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	private DynamicTexture dynamicTexture;
	private ClientLevel level;
	private int minX;
	private int minZ;
	private int cursor;
	private boolean dirty;

	MapTerrain(String name, int size) {
		this.texture = Identifier.fromNamespaceAndPath("minimap", name);
		this.size = size;
		this.refreshPerTick = size * size / 40;
		this.slotX = new int[size * size];
		this.slotZ = new int[size * size];
	}

	// 描き終えている範囲の西端
	int minX() {
		return minX;
	}

	// 描き終えている範囲の北端
	int minZ() {
		return minZ;
	}

	void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (client.level == null || player == null) {
			level = null;
			return;
		}
		if (dynamicTexture == null) {
			dynamicTexture = createTexture(client);
		}

		boolean changed = client.level != level;
		if (changed) {
			level = client.level;
			Arrays.fill(slotX, Integer.MIN_VALUE);
		}
		int newMinX = player.getBlockX() - size / 2;
		int newMinZ = player.getBlockZ() - size / 2;
		changed |= newMinX != minX || newMinZ != minZ;
		minX = newMinX;
		minZ = newMinZ;

		int feetY = player.getBlockY();
		NativeImage image = dynamicTexture.getPixels();
		if (changed) {
			for (int z = minZ; z < minZ + size; z++) {
				for (int x = minX; x < minX + size; x++) {
					int slot = slot(x, z);
					if (slotX[slot] != x || slotZ[slot] != z) {
						paint(image, x, z, feetY);
					}
				}
			}
		}
		for (int i = 0; i < refreshPerTick; i++) {
			paint(image, minX + cursor % size, minZ + cursor / size, feetY);
			cursor = (cursor + 1) % (size * size);
		}
		if (dirty) {
			dynamicTexture.upload();
			dirty = false;
		}
	}

	void release(Minecraft client) {
		if (dynamicTexture != null) {
			client.getTextureManager().release(texture);
			dynamicTexture = null;
		}
		level = null;
	}

	private DynamicTexture createTexture(Minecraft client) {
		DynamicTexture created = new DynamicTexture(texture::toString, size, size, false) {
			{
				// 端をまたぐ表示は UV のリピートに任せる
				sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
			}
		};
		client.getTextureManager().register(texture, created);
		return created;
	}

	private int slot(int x, int z) {
		return Math.floorMod(z, size) * size + Math.floorMod(x, size);
	}

	private void paint(NativeImage image, int x, int z, int feetY) {
		int slot = slot(x, z);
		slotX[slot] = x;
		slotZ[slot] = z;
		int color = color(x, z, feetY);
		int textureX = Math.floorMod(x, size);
		int textureZ = Math.floorMod(z, size);
		if (image.getPixel(textureX, textureZ) != color) {
			image.setPixel(textureX, textureZ, color);
			dirty = true;
		}
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
