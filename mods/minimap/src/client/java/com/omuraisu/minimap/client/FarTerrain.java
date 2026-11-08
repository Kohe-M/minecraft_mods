package com.omuraisu.minimap.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.TerrainColors;
import com.omuraisu.minimap.TerrainRequest;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainTile;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

// マップ画面で描画距離の外に出す地形。サーバーから届いたチャンクの色を接続している間だけ覚えておき、
// 開いている間は 32 × 32 チャンクずつのテクスチャにして描く。読み込み済みのチャンクは MapTerrain に任せる
public final class FarTerrain {
	private static final int REGION_CHUNKS = 32;
	private static final int REGION_BLOCKS = REGION_CHUNKS * 16;
	private static final int REQUEST_INTERVAL = 10;
	// 頼んでからこれだけ届かなければ、もう一度頼む
	private static final int RETRY_TICKS = 40;
	// 1 tick に作ったり送ったりするテクスチャの画素数。等倍のリージョン 4 枚分
	private static final int UPLOAD_BUDGET = 4 * REGION_BLOCKS * REGION_BLOCKS;
	// 引いているときは 1 画素に何ブロックも入るので、間引いた小さいテクスチャにする（2 の何乗ブロックで 1 画素か）
	private static final int MAX_LEVEL = 2;
	// 覚えておく範囲。読み込める範囲より少し広くして、行き来しても頼み直さずに済むようにする
	private static final int KEEP_RADIUS = TerrainServer.RADIUS + REGION_CHUNKS;

	private final Map<Identifier, Dimension> dimensions = new HashMap<>();
	private final Long2IntMap requested = new Long2IntOpenHashMap();
	private boolean requesting;
	private int ticks;
	private int received;

	// 開いている間だけ持つ
	private final Long2ObjectMap<Region> regions = new Long2ObjectOpenHashMap<>();
	private Identifier regionDimension;
	private int level;

	// 届いた色。テストから確かめる
	public byte[] colors(Identifier dimension, int chunkX, int chunkZ) {
		Dimension cache = dimensions.get(dimension);
		return cache == null ? null : cache.chunks.get(ChunkPos.pack(chunkX, chunkZ));
	}

	public int received() {
		return received;
	}

	// テクスチャへの反映は tick でまとめて送る
	void receive(TerrainTile tile) {
		long chunk = tile.chunk();
		int chunkX = ChunkPos.getX(chunk);
		int chunkZ = ChunkPos.getZ(chunk);
		Dimension cache = dimension(tile.dimension());
		cache.chunks.put(chunk, tile.colors());
		long key = regionOf(chunkX, chunkZ);
		cache.regions.add(key);
		requested.remove(chunk);
		received++;

		Region region = tile.dimension().equals(regionDimension) ? regions.get(key) : null;
		if (region != null) {
			paint(region.texture.getPixels(), Math.floorMod(chunkX, REGION_CHUNKS), Math.floorMod(chunkZ, REGION_CHUNKS), tile.colors(), level);
			region.dirty = true;
		}
	}

	// 自分で読み込んだチャンクは古くなっているかもしれないので、離れたらもう一度頼む
	void forget(Identifier dimension, ChunkPos chunk) {
		Dimension cache = dimensions.get(dimension);
		if (cache != null) {
			cache.chunks.remove(chunk.pack());
		}
	}

	void clear() {
		dimensions.clear();
		requested.clear();
		requesting = false;
	}

	// 開いたときに、離れた場所の色を捨てる
	void open(Identifier dimension, ChunkPos player) {
		LongIterator chunks = dimension(dimension).chunks.keySet().iterator();
		while (chunks.hasNext()) {
			if (!isNear(player, chunks.nextLong(), KEEP_RADIUS)) {
				chunks.remove();
			}
		}
	}

	// マップ画面から毎 tick 呼ぶ。範囲はチャンクの座標で、center に近いものから頼む。scale は 1 ブロックの実ピクセル。
	// 手元で描けるチャンクも頼む。手元のテクスチャの端は丸めで 1 画素ずれることがあり、下に何もないとすき間が見えるため
	void tick(Minecraft client, Identifier dimension, ChunkPos player, double scale,
			int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ) {
		ticks++;
		int newLevel = Math.clamp((int) Math.floor(-Math.log(scale) / Math.log(2)), 0, MAX_LEVEL);
		if (!dimension.equals(regionDimension) || newLevel != level) {
			releaseRegions(client);
			regionDimension = dimension;
			level = newLevel;
		}
		minX = Math.max(minX, player.x() - TerrainServer.RADIUS);
		minZ = Math.max(minZ, player.z() - TerrainServer.RADIUS);
		maxX = Math.min(maxX, player.x() + TerrainServer.RADIUS);
		maxZ = Math.min(maxZ, player.z() + TerrainServer.RADIUS);
		if (minX > maxX || minZ > maxZ) {
			cancel();
			return;
		}
		Dimension cache = dimension(dimension);
		updateRegions(client, cache, minX, minZ, maxX, maxZ);
		if (ticks % REQUEST_INTERVAL == 0) {
			request(cache, minX, minZ, maxX, maxZ, Math.clamp(centerX, minX, maxX), Math.clamp(centerZ, minZ, maxZ));
		}
	}

	void close(Minecraft client) {
		cancel();
		requested.clear();
		releaseRegions(client);
		regionDimension = null;
	}

	void forEachRegion(RegionConsumer consumer) {
		for (Long2ObjectMap.Entry<Region> entry : regions.long2ObjectEntrySet()) {
			if (entry.getValue().uploaded) {
				long key = entry.getLongKey();
				consumer.accept(entry.getValue().id, ChunkPos.getX(key) * REGION_BLOCKS, ChunkPos.getZ(key) * REGION_BLOCKS, REGION_BLOCKS);
			}
		}
	}

	private Dimension dimension(Identifier dimension) {
		return dimensions.computeIfAbsent(dimension, id -> new Dimension());
	}

	// 中心から外へ四角く回り、まだ届いていないチャンクを集める
	private void request(Dimension cache, int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ) {
		if (!ClientPlayNetworking.canSend(TerrainRequest.TYPE)) {
			return;
		}
		List<Long> chunks = new ArrayList<>();
		int maxRing = Math.max(Math.max(centerX - minX, maxX - centerX), Math.max(centerZ - minZ, maxZ - centerZ));
		for (int ring = 0; ring <= maxRing && chunks.size() < TerrainRequest.MAX_CHUNKS; ring++) {
			for (int x = centerX - ring; x <= centerX + ring; x++) {
				collect(cache, chunks, x, centerZ - ring, minX, minZ, maxX, maxZ);
				if (ring > 0) {
					collect(cache, chunks, x, centerZ + ring, minX, minZ, maxX, maxZ);
				}
			}
			for (int z = centerZ - ring + 1; z <= centerZ + ring - 1; z++) {
				collect(cache, chunks, centerX - ring, z, minX, minZ, maxX, maxZ);
				collect(cache, chunks, centerX + ring, z, minX, minZ, maxX, maxZ);
			}
		}
		if (chunks.isEmpty()) {
			cancel();
			return;
		}
		for (long chunk : chunks) {
			requested.put(chunk, ticks);
		}
		ClientPlayNetworking.send(new TerrainRequest(chunks));
		requesting = true;
	}

	private void collect(Dimension cache, List<Long> chunks, int x, int z, int minX, int minZ, int maxX, int maxZ) {
		if (chunks.size() >= TerrainRequest.MAX_CHUNKS || x < minX || x > maxX || z < minZ || z > maxZ) {
			return;
		}
		long chunk = ChunkPos.pack(x, z);
		if (cache.chunks.containsKey(chunk) || ticks - requested.getOrDefault(chunk, Integer.MIN_VALUE / 2) < RETRY_TICKS) {
			return;
		}
		chunks.add(chunk);
	}

	// 見えなくなったら、まだ読み込み始めていない分は取り消す
	private void cancel() {
		if (requesting && ClientPlayNetworking.canSend(TerrainRequest.TYPE)) {
			ClientPlayNetworking.send(new TerrainRequest(List.of()));
		}
		requesting = false;
	}

	// 見えている範囲のテクスチャを作り、届いた分を送る。外れたテクスチャは捨てる
	private void updateRegions(Minecraft client, Dimension cache, int minX, int minZ, int maxX, int maxZ) {
		int minRX = Math.floorDiv(minX, REGION_CHUNKS);
		int minRZ = Math.floorDiv(minZ, REGION_CHUNKS);
		int maxRX = Math.floorDiv(maxX, REGION_CHUNKS);
		int maxRZ = Math.floorDiv(maxZ, REGION_CHUNKS);
		var entries = regions.long2ObjectEntrySet().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			int rx = ChunkPos.getX(entry.getLongKey());
			int rz = ChunkPos.getZ(entry.getLongKey());
			if (rx < minRX || rx > maxRX || rz < minRZ || rz > maxRZ) {
				client.getTextureManager().release(entry.getValue().id);
				entries.remove();
			}
		}

		int size = REGION_BLOCKS >> level;
		int budget = UPLOAD_BUDGET;
		for (int rz = minRZ; rz <= maxRZ && budget > 0; rz++) {
			for (int rx = minRX; rx <= maxRX && budget > 0; rx++) {
				long key = ChunkPos.pack(rx, rz);
				Region region = regions.get(key);
				if (region == null && cache.regions.contains(key)) {
					region = createRegion(client, cache, rx, rz, size);
					regions.put(key, region);
				}
				if (region != null && region.dirty) {
					region.texture.upload();
					region.dirty = false;
					region.uploaded = true;
					budget -= size * size;
				}
			}
		}
	}

	private Region createRegion(Minecraft client, Dimension cache, int rx, int rz, int size) {
		Identifier id = Identifier.fromNamespaceAndPath("minimap", "far/" + rx + "_" + rz);
		NativeImage image = new NativeImage(size, size, true);
		for (int cz = 0; cz < REGION_CHUNKS; cz++) {
			for (int cx = 0; cx < REGION_CHUNKS; cx++) {
				byte[] colors = cache.chunks.get(ChunkPos.pack(rx * REGION_CHUNKS + cx, rz * REGION_CHUNKS + cz));
				if (colors != null) {
					paint(image, cx, cz, colors, level);
				}
			}
		}
		DynamicTexture texture = new DynamicTexture(id::toString, size, size, false) {
		};
		texture.setPixels(image);
		client.getTextureManager().register(id, texture);
		return new Region(id, texture);
	}

	private void releaseRegions(Minecraft client) {
		for (Region region : regions.values()) {
			client.getTextureManager().release(region.id);
		}
		regions.clear();
	}

	// 1 画素に入る列のうち北西の 1 列で塗る。色のない列は透明にして、背景の色を見せる
	private static void paint(NativeImage image, int chunkX, int chunkZ, byte[] colors, int level) {
		int texels = 16 >> level;
		for (int tz = 0; tz < texels; tz++) {
			for (int tx = 0; tx < texels; tx++) {
				int packed = Byte.toUnsignedInt(colors[(tz << level) * 16 + (tx << level)]);
				image.setPixel(chunkX * texels + tx, chunkZ * texels + tz, packed == 0 ? 0 : TerrainColors.argb(packed));
			}
		}
	}

	private static long regionOf(int chunkX, int chunkZ) {
		return ChunkPos.pack(Math.floorDiv(chunkX, REGION_CHUNKS), Math.floorDiv(chunkZ, REGION_CHUNKS));
	}

	private static boolean isNear(ChunkPos center, long chunk, int radius) {
		return Math.max(Math.abs(ChunkPos.getX(chunk) - center.x()), Math.abs(ChunkPos.getZ(chunk) - center.z())) <= radius;
	}

	interface RegionConsumer {
		void accept(Identifier texture, int minBlockX, int minBlockZ, int size);
	}

	private static final class Dimension {
		private final Long2ObjectMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();
		// 色が 1 つでも届いたリージョン
		private final LongSet regions = new LongOpenHashSet();
	}

	private static final class Region {
		private final Identifier id;
		private final DynamicTexture texture;
		private boolean dirty = true;
		private boolean uploaded;

		private Region(Identifier id, DynamicTexture texture) {
			this.id = id;
			this.texture = texture;
		}
	}
}
