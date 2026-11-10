package com.omuraisu.minimap.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.TerrainColors;
import com.omuraisu.minimap.TerrainRegions;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

// マップ画面を開いている間の、リージョンごとのテクスチャ。覚えている色（FarRegion）を塗り、tick ごとにまとめて送る。
// 塗るのは色が変わったときで、送るのは tick の終わりに画素数の上限まで。開いていない間は持たない
final class FarTextures {
	private static final int REGION_CHUNKS = TerrainRegions.CHUNKS;
	private static final int REGION_BLOCKS = TerrainRegions.BLOCKS;
	// 1 tick に作ったり送ったりするテクスチャの画素数。等倍のリージョン 4 枚分
	private static final int UPLOAD_BUDGET = 4 * REGION_BLOCKS * REGION_BLOCKS;
	// 引いているときは 1 画素に何ブロックも入るので、間引いた小さいテクスチャにする（2 の何乗ブロックで 1 画素か）
	private static final int MAX_LEVEL = 2;
	// packed id ごとの色。色のない列（0）は透明
	private static final int[] ARGB = new int[256];

	static {
		for (int packed = 4; packed < ARGB.length; packed++) {
			try {
				ARGB[packed] = TerrainColors.argb(packed);
			} catch (RuntimeException e) {
				// 使われていない番号
			}
		}
	}

	private final Long2ObjectMap<Texture> textures = new Long2ObjectOpenHashMap<>();
	// テクスチャを作っている次元。開いていなければ null
	private Identifier dimension;
	private int level;
	private int uploadCursor;

	// 次元か間引き方が変わったら、作ってあるテクスチャを捨てる。scale は 1 ブロックの実ピクセル
	void prepare(Minecraft client, Identifier dimension, double scale) {
		int newLevel = Math.clamp((int) Math.floor(-Math.log(scale) / Math.log(2)), 0, MAX_LEVEL);
		if (!dimension.equals(this.dimension) || newLevel != level) {
			release(client);
			this.dimension = dimension;
			level = newLevel;
		}
	}

	void close(Minecraft client) {
		release(client);
		dimension = null;
	}

	void forEach(RegionConsumer consumer) {
		for (Long2ObjectMap.Entry<Texture> entry : textures.long2ObjectEntrySet()) {
			if (entry.getValue().uploaded) {
				long key = entry.getLongKey();
				consumer.accept(entry.getValue().id, ChunkPos.getX(key) * REGION_BLOCKS, ChunkPos.getZ(key) * REGION_BLOCKS, REGION_BLOCKS);
			}
		}
	}

	// 見えている範囲（チャンクの座標）のテクスチャを作り、届いた分を送る。外れたテクスチャは捨てる
	void update(Minecraft client, FarDimension cache, int minX, int minZ, int maxX, int maxZ) {
		int minRX = TerrainRegions.coordinate(minX);
		int minRZ = TerrainRegions.coordinate(minZ);
		int maxRX = TerrainRegions.coordinate(maxX);
		int maxRZ = TerrainRegions.coordinate(maxZ);
		var entries = textures.long2ObjectEntrySet().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			int rx = ChunkPos.getX(entry.getLongKey());
			int rz = ChunkPos.getZ(entry.getLongKey());
			if (rx < minRX || rx > maxRX || rz < minRZ || rz > maxRZ) {
				client.getTextureManager().release(entry.getValue().id);
				entries.remove();
			}
		}

		// 色が届き続けているリージョンばかりを送らないよう、前の tick に送り終えたところの次から見る
		int size = REGION_BLOCKS >> level;
		int budget = UPLOAD_BUDGET;
		int width = maxRX - minRX + 1;
		int count = width * (maxRZ - minRZ + 1);
		int first = Math.floorMod(uploadCursor, count);
		for (int i = 0; i < count && budget > 0; i++) {
			int slot = (first + i) % count;
			long key = ChunkPos.pack(minRX + slot % width, minRZ + slot / width);
			Texture texture = textures.get(key);
			if (texture == null) {
				FarRegion region = cache.regions.get(key);
				if (region == null || region.count == 0 && region.preview == null) {
					continue;
				}
				texture = create(client, region, size);
				textures.put(key, texture);
			}
			if (texture.dirty) {
				texture.texture.upload();
				texture.dirty = false;
				texture.uploaded = true;
				budget -= size * size;
				uploadCursor = slot + 1;
			}
		}
	}

	// チャンク 1 つの色が変わった
	void repaint(FarDimension cache, int chunkX, int chunkZ) {
		long key = TerrainRegions.key(chunkX, chunkZ);
		Texture texture = texture(cache, key);
		FarRegion region = texture == null ? null : cache.regions.get(key);
		if (region != null) {
			paint(texture.texture.getPixels(), region, Math.floorMod(chunkX, REGION_CHUNKS), Math.floorMod(chunkZ, REGION_CHUNKS), level);
			texture.dirty = true;
		}
	}

	// リージョンをファイルから読み終えた
	void repaintRegion(FarDimension cache, FarRegion region) {
		Texture texture = texture(cache, ChunkPos.pack(region.x, region.z));
		if (texture != null) {
			paint(texture.texture.getPixels(), region, level);
			texture.dirty = true;
		}
	}

	// 下書きが届いた。色の届いていないチャンクだけ塗り直す
	void repaintPreview(FarDimension cache, FarRegion region) {
		Texture texture = texture(cache, ChunkPos.pack(region.x, region.z));
		if (texture != null) {
			for (int index = 0; index < TerrainRegions.COUNT; index++) {
				if (region.tiles[index] == null) {
					paint(texture.texture.getPixels(), region, index % REGION_CHUNKS, index / REGION_CHUNKS, level);
				}
			}
			texture.dirty = true;
		}
	}

	// 捨てたリージョン
	void remove(Minecraft client, FarDimension cache, FarRegion region) {
		if (cache.id.equals(dimension)) {
			Texture texture = textures.remove(ChunkPos.pack(region.x, region.z));
			if (texture != null) {
				client.getTextureManager().release(texture.id);
			}
		}
	}

	private Texture texture(FarDimension cache, long key) {
		return cache.id.equals(dimension) ? textures.get(key) : null;
	}

	private Texture create(Minecraft client, FarRegion region, int size) {
		Identifier id = Identifier.fromNamespaceAndPath("minimap", "far/" + region.x + "_" + region.z);
		NativeImage image = new NativeImage(size, size, true);
		paint(image, region, level);
		DynamicTexture texture = new DynamicTexture(id::toString, size, size, false) {
		};
		texture.setPixels(image);
		client.getTextureManager().register(id, texture);
		return new Texture(id, texture);
	}

	private void release(Minecraft client) {
		for (Texture texture : textures.values()) {
			client.getTextureManager().release(texture.id);
		}
		textures.clear();
	}

	private static void paint(NativeImage image, FarRegion region, int level) {
		for (int cz = 0; cz < REGION_CHUNKS; cz++) {
			for (int cx = 0; cx < REGION_CHUNKS; cx++) {
				paint(image, region, cx, cz, level);
			}
		}
	}

	// リージョンの中のチャンク 1 つ分を塗る。1 画素に入る列のうち北西の 1 列の色にする。
	// 色が届いていないチャンクは下書きの色、それもなければ透明にして背景の色を見せる
	private static void paint(NativeImage image, FarRegion region, int chunkX, int chunkZ, int level) {
		byte[] tile = region.tiles[chunkX + chunkZ * REGION_CHUNKS];
		int texels = 16 >> level;
		for (int tz = 0; tz < texels; tz++) {
			for (int tx = 0; tx < texels; tx++) {
				int x = tx << level;
				int z = tz << level;
				int packed = tile != null ? Byte.toUnsignedInt(tile[z * 16 + x]) : region.preview(chunkX * 16 + x, chunkZ * 16 + z);
				image.setPixel(chunkX * texels + tx, chunkZ * texels + tz, ARGB[packed]);
			}
		}
	}

	interface RegionConsumer {
		void accept(Identifier texture, int minBlockX, int minBlockZ, int size);
	}

	private static final class Texture {
		private final Identifier id;
		private final DynamicTexture texture;
		private boolean dirty = true;
		private boolean uploaded;

		private Texture(Identifier id, DynamicTexture texture) {
			this.id = id;
			this.texture = texture;
		}
	}
}
