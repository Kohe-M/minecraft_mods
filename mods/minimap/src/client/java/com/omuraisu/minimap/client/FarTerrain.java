package com.omuraisu.minimap.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.TerrainColors;
import com.omuraisu.minimap.TerrainMissing;
import com.omuraisu.minimap.TerrainRequest;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainTile;
import com.omuraisu.minimap.TerrainTiles;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

// マップ画面で描画距離の外に出す地形。サーバーから届いたチャンクの色を接続している間だけ覚えておき、
// 開いている間は 32 × 32 チャンクずつのテクスチャにして描く。読み込み済みのチャンクは MapTerrain に任せる。
// 届いた色はファイルにも残し（FarCache）、次に開いたときはまずそれを出して、サーバーから届いたものに差し替える。
// 開いていない間も、プレイヤーの周りのマップ距離の分を頼んでおく
public final class FarTerrain {
	private static final int REGION_CHUNKS = 32;
	private static final int REGION_BLOCKS = REGION_CHUNKS * 16;
	// 届いていないチャンクを、この間隔で全部頼み直す（サーバーは前の依頼を置き換え、読んでいる途中のものは読み直さない）
	private static final int REQUEST_INTERVAL = 5;
	// マップ画面を開いていない間は、サーバーも少しずつしか進めないので、頼み直す間隔を空ける
	private static final int BACKGROUND_REQUEST_INTERVAL = 20;
	// 開いていない間に、離れた色を捨てる間隔と、届いた色をファイルに書く間隔
	private static final int BACKGROUND_PRUNE_INTERVAL = 200;
	private static final int BACKGROUND_SAVE_INTERVAL = 1200;
	// 1 tick に作ったり送ったりするテクスチャの画素数。等倍のリージョン 4 枚分
	private static final int UPLOAD_BUDGET = 4 * REGION_BLOCKS * REGION_BLOCKS;
	// 引いているときは 1 画素に何ブロックも入るので、間引いた小さいテクスチャにする（2 の何乗ブロックで 1 画素か）
	private static final int MAX_LEVEL = 2;
	// 覚えておく範囲。読み込める範囲より少し広くして、行き来しても頼み直さずに済むようにする
	private static final int KEEP_RADIUS = TerrainServer.RADIUS + REGION_CHUNKS;
	// 1 チャンク分のデータ。色 256 バイトのあとに、北の端と南の端の高さ（TerrainTile）を short 16 個ずつ
	private static final int NORTH_EDGE = 16 * 16;
	private static final int SOUTH_EDGE = NORTH_EDGE + 16 * 2;
	private static final int TILE_BYTES = SOUTH_EDGE + 16 * 2;

	private final Map<Identifier, Dimension> dimensions = new HashMap<>();
	private final FarCache disk = new FarCache();
	private boolean requesting;
	private int ticks;
	private int received;

	// 開いている間だけ持つ
	private final Long2ObjectMap<Region> regions = new Long2ObjectOpenHashMap<>();
	private Identifier regionDimension;
	private int level;

	// 届いた色。北の端の行は北のチャンクと比べた後の明るさ。テストから確かめる
	public byte[] colors(Identifier dimension, int chunkX, int chunkZ) {
		Dimension cache = dimensions.get(dimension);
		byte[] tile = cache == null ? null : cache.chunks.get(ChunkPos.pack(chunkX, chunkZ));
		return tile == null ? null : Arrays.copyOf(tile, 16 * 16);
	}

	public int received() {
		return received;
	}

	// 覚えているチャンクの数。テストから確かめる
	public int count(Identifier dimension) {
		Dimension cache = dimensions.get(dimension);
		return cache == null ? 0 : cache.chunks.size();
	}

	void receive(Minecraft client, TerrainTiles payload) {
		for (TerrainTile tile : payload.tiles()) {
			receive(client, payload.dimension(), tile);
		}
	}

	// 北の端の行は、北のチャンクの色が届いていればその高さと比べて明るさを決める。南のチャンクが先に届いていれば、その北の端も決め直す。
	// テクスチャへの反映は tick でまとめて送る
	private void receive(Minecraft client, Identifier dimension, TerrainTile tile) {
		long chunk = tile.chunk();
		int chunkX = ChunkPos.getX(chunk);
		int chunkZ = ChunkPos.getZ(chunk);
		Dimension cache = dimension(dimension);
		byte[] data = new byte[TILE_BYTES];
		System.arraycopy(tile.colors(), 0, data, 0, 16 * 16);
		for (int dx = 0; dx < 16; dx++) {
			setEdge(data, NORTH_EDGE, dx, tile.northEdge()[dx]);
			setEdge(data, SOUTH_EDGE, dx, tile.southEdge()[dx]);
		}
		cache.chunks.put(chunk, data);
		cache.missing.remove(chunk);
		cache.stale.remove(chunk);
		cache.regions.add(regionOf(chunkX, chunkZ));
		cache.changes(chunkX, chunkZ).put(regionIndex(chunkX, chunkZ), data);
		received++;

		resolveNorthRow(client, dimension, cache, chunkX, chunkZ, data);
		repaint(dimension, chunkX, chunkZ, data);
		byte[] south = cache.chunks.get(ChunkPos.pack(chunkX, chunkZ + 1));
		if (south != null) {
			resolveNorthRow(client, dimension, cache, chunkX, chunkZ + 1, south);
			repaint(dimension, chunkX, chunkZ + 1, south);
		}
	}

	// 未生成と答えられたチャンクは、次から生成を頼む。ファイルから出した色があれば、もうないものとして消す
	void missing(TerrainMissing payload) {
		Dimension cache = dimension(payload.dimension());
		for (long chunk : payload.chunks()) {
			if (cache.stale.contains(chunk)) {
				remove(payload.dimension(), cache, chunk);
			}
			if (!cache.chunks.containsKey(chunk)) {
				cache.missing.add(chunk);
			}
		}
	}

	// 自分で読み込んだチャンクは古くなっているかもしれないので、離れたらもう一度頼む
	void forget(Identifier dimension, ChunkPos chunk) {
		Dimension cache = dimensions.get(dimension);
		if (cache != null) {
			remove(dimension, cache, chunk.pack());
			cache.missing.remove(chunk.pack());
		}
	}

	// 接続するたびに、そのワールド・サーバーの保存先を開く。null なら残さない
	void openCache(Path root) {
		disk.open(root);
	}

	void clear() {
		saveChanges();
		disk.close();
		dimensions.clear();
		requesting = false;
	}

	// 開いたときに、離れた場所の色と未生成の印を捨て（ファイルには残る）、近くのファイルを読む
	void open(Minecraft client, Identifier dimension, ChunkPos player) {
		saveChanges();
		Dimension cache = dimension(dimension);
		forgetFar(cache, player);
		loadFiles(client, dimension, cache, player, KEEP_RADIUS);
	}

	// マップ画面を開いていない間、毎 tick 呼ぶ。プレイヤーの周り distance チャンク（マップ距離）の地形を近い順に頼んでおき、
	// 開いたときにすぐ出せるようにする。手元に読み込まれているチャンクは手元の地形で描けるので頼まない（開いてから頼む）。
	// distance が 0 なら頼まない
	void tickBackground(Minecraft client, Identifier dimension, ChunkPos player, int distance) {
		ticks++;
		if (ticks % BACKGROUND_SAVE_INTERVAL == 0) {
			saveChanges();
		}
		if (distance <= 0) {
			cancel();
			return;
		}
		Dimension cache = dimension(dimension);
		if (ticks % BACKGROUND_PRUNE_INTERVAL == 0) {
			forgetFar(cache, player);
		}
		if (ticks % BACKGROUND_REQUEST_INTERVAL != 0) {
			return;
		}
		int radius = Math.min(distance, TerrainServer.RADIUS);
		loadFiles(client, dimension, cache, player, radius);
		request(cache, player.x() - radius, player.z() - radius, player.x() + radius, player.z() + radius,
				player.x(), player.z(), client.level);
	}

	// 離れた場所の色と未生成の印を捨てる（ファイルには残る）。離れたリージョンのファイルは、戻ってきたら読み直す
	private void forgetFar(Dimension cache, ChunkPos player) {
		LongIterator chunks = cache.chunks.keySet().iterator();
		while (chunks.hasNext()) {
			long chunk = chunks.nextLong();
			if (!isNear(player, chunk, KEEP_RADIUS)) {
				chunks.remove();
				cache.stale.remove(chunk);
			}
		}
		cache.missing.removeIf((long chunk) -> !isNear(player, chunk, KEEP_RADIUS));
		int centerX = Math.floorDiv(player.x(), REGION_CHUNKS);
		int centerZ = Math.floorDiv(player.z(), REGION_CHUNKS);
		int keepRegions = KEEP_RADIUS / REGION_CHUNKS + 1;
		cache.cachedRegions.removeIf((long region) -> Math.max(Math.abs(ChunkPos.getX(region) - centerX), Math.abs(ChunkPos.getZ(region) - centerZ)) > keepRegions);
	}

	// プレイヤーの周り radius チャンクにかかる、まだ読んでいないリージョンのファイルを読む
	private void loadFiles(Minecraft client, Identifier dimension, Dimension cache, ChunkPos player, int radius) {
		if (!disk.isOpen()) {
			return;
		}
		int minRX = Math.floorDiv(player.x() - radius, REGION_CHUNKS);
		int minRZ = Math.floorDiv(player.z() - radius, REGION_CHUNKS);
		int maxRX = Math.floorDiv(player.x() + radius, REGION_CHUNKS);
		int maxRZ = Math.floorDiv(player.z() + radius, REGION_CHUNKS);
		for (int rz = minRZ; rz <= maxRZ; rz++) {
			for (int rx = minRX; rx <= maxRX; rx++) {
				if (cache.cachedRegions.add(ChunkPos.pack(rx, rz))) {
					int regionX = rx;
					int regionZ = rz;
					disk.load(dimension, regionX, regionZ, TILE_BYTES)
							.thenAccept(tiles -> client.execute(() -> merge(dimension, cache, regionX, regionZ, tiles)));
				}
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
			request(cache, minX, minZ, maxX, maxZ, Math.clamp(centerX, minX, maxX), Math.clamp(centerZ, minZ, maxZ), null);
		}
	}

	void close(Minecraft client) {
		cancel();
		releaseRegions(client);
		regionDimension = null;
		saveChanges();
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

	// ファイルから読んだ色を、まだ届いていないチャンクにだけ入れる。サーバーに頼み直すまでは古いかもしれない
	private void merge(Identifier dimension, Dimension cache, int regionX, int regionZ, Int2ObjectMap<byte[]> tiles) {
		if (dimensions.get(dimension) != cache) {
			return;
		}
		long key = ChunkPos.pack(regionX, regionZ);
		Region region = dimension.equals(regionDimension) ? regions.get(key) : null;
		Changes changes = cache.changes.get(key);
		for (Int2ObjectMap.Entry<byte[]> entry : tiles.int2ObjectEntrySet()) {
			int index = entry.getIntKey();
			int chunkX = regionX * REGION_CHUNKS + index % REGION_CHUNKS;
			int chunkZ = regionZ * REGION_CHUNKS + index / REGION_CHUNKS;
			long chunk = ChunkPos.pack(chunkX, chunkZ);
			if (cache.chunks.containsKey(chunk) || changes != null && changes.removed.contains(index)) {
				continue;
			}
			cache.chunks.put(chunk, entry.getValue());
			cache.stale.add(chunk);
			cache.regions.add(key);
			if (region != null) {
				paint(region.texture.getPixels(), Math.floorMod(chunkX, REGION_CHUNKS), Math.floorMod(chunkZ, REGION_CHUNKS), entry.getValue(), level);
				region.dirty = true;
			}
		}
	}

	private void remove(Identifier dimension, Dimension cache, long chunk) {
		if (cache.chunks.remove(chunk) == null) {
			return;
		}
		cache.stale.remove(chunk);
		int chunkX = ChunkPos.getX(chunk);
		int chunkZ = ChunkPos.getZ(chunk);
		cache.changes(chunkX, chunkZ).remove(regionIndex(chunkX, chunkZ));
		Region region = dimension.equals(regionDimension) ? regions.get(regionOf(chunkX, chunkZ)) : null;
		if (region != null) {
			paint(region.texture.getPixels(), Math.floorMod(chunkX, REGION_CHUNKS), Math.floorMod(chunkZ, REGION_CHUNKS), null, level);
			region.dirty = true;
		}
	}

	// 届いた色と消したチャンクをファイルに書く。色は後から北の端を決め直すので、書く時点の中身を写す
	private void saveChanges() {
		for (Map.Entry<Identifier, Dimension> entry : dimensions.entrySet()) {
			for (Long2ObjectMap.Entry<Changes> region : entry.getValue().changes.long2ObjectEntrySet()) {
				Int2ObjectMap<byte[]> updates = new Int2ObjectOpenHashMap<>();
				region.getValue().updates.int2ObjectEntrySet().forEach(update -> updates.put(update.getIntKey(), update.getValue().clone()));
				disk.save(entry.getKey(), ChunkPos.getX(region.getLongKey()), ChunkPos.getZ(region.getLongKey()), TILE_BYTES,
						updates, new IntOpenHashSet(region.getValue().removed));
			}
			entry.getValue().changes.clear();
		}
	}

	private static int regionIndex(int chunkX, int chunkZ) {
		return Math.floorMod(chunkX, REGION_CHUNKS) + Math.floorMod(chunkZ, REGION_CHUNKS) * REGION_CHUNKS;
	}

	// 中心から外へ四角く回り、まだ届いていないチャンクを集める。未生成と答えられたものは生成の方に入れ、
	// 生成を待つ間もセーブにあるチャンクを先に読んでもらえるようにする。
	// background はマップ画面を開いていない間の依頼で、そのレベルに読み込まれているチャンクは頼まない
	private void request(Dimension cache, int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ, ClientLevel background) {
		if (!ClientPlayNetworking.canSend(TerrainRequest.TYPE)) {
			return;
		}
		Request request = new Request(cache, minX, minZ, maxX, maxZ, background);
		int maxRing = Math.max(Math.max(centerX - minX, maxX - centerX), Math.max(centerZ - minZ, maxZ - centerZ));
		for (int ring = 0; ring <= maxRing && !request.isFull(); ring++) {
			for (int x = centerX - ring; x <= centerX + ring; x++) {
				request.collect(x, centerZ - ring);
				if (ring > 0) {
					request.collect(x, centerZ + ring);
				}
			}
			for (int z = centerZ - ring + 1; z <= centerZ + ring - 1; z++) {
				request.collect(centerX - ring, z);
				request.collect(centerX + ring, z);
			}
		}
		for (long chunk : request.refresh) {
			if (request.read.size() >= TerrainRequest.MAX_READ) {
				break;
			}
			request.read.add(chunk);
		}
		if (request.read.isEmpty() && request.generate.isEmpty()) {
			cancel();
			return;
		}
		ClientPlayNetworking.send(new TerrainRequest(request.read, request.generate, background != null));
		requesting = true;
	}

	// 見えなくなったら、まだ読み始めていない分は取り消す
	private void cancel() {
		if (requesting && ClientPlayNetworking.canSend(TerrainRequest.TYPE)) {
			ClientPlayNetworking.send(TerrainRequest.CANCEL);
		}
		requesting = false;
	}

	// 北のチャンクの南の端の高さと比べる。北の色が届いていなければ、手元に読み込まれているチャンクから出す
	private void resolveNorthRow(Minecraft client, Identifier dimension, Dimension cache, int chunkX, int chunkZ, byte[] data) {
		byte[] north = cache.chunks.get(ChunkPos.pack(chunkX, chunkZ - 1));
		int[] northY;
		if (north != null) {
			northY = new int[16];
			for (int dx = 0; dx < 16; dx++) {
				northY[dx] = edge(north, SOUTH_EDGE, dx);
			}
		} else {
			northY = localSouthEdge(client, dimension, chunkX, chunkZ - 1);
		}
		for (int dx = 0; dx < 16; dx++) {
			int packed = Byte.toUnsignedInt(data[dx]);
			data[dx] = (byte) TerrainColors.relief(packed, edge(data, NORTH_EDGE, dx), northY == null ? TerrainColors.UNKNOWN_Y : northY[dx]);
		}
	}

	private static int[] localSouthEdge(Minecraft client, Identifier dimension, int chunkX, int chunkZ) {
		ClientLevel level = client.level;
		if (level == null || client.player == null || !level.dimension().identifier().equals(dimension)
				|| !MapTerrain.isLoaded(level, chunkX, chunkZ)) {
			return null;
		}
		TerrainColors.Blocks blocks = TerrainColors.of(level.getChunk(chunkX, chunkZ));
		boolean ceiling = level.dimensionType().hasCeiling();
		int feetY = client.player.getBlockY();
		int[] heights = new int[16];
		for (int dx = 0; dx < 16; dx++) {
			heights[dx] = TerrainColors.surfaceY(blocks, chunkX * 16 + dx, chunkZ * 16 + 15, ceiling, feetY);
		}
		return heights;
	}

	private void repaint(Identifier dimension, int chunkX, int chunkZ, byte[] data) {
		Region region = dimension.equals(regionDimension) ? regions.get(regionOf(chunkX, chunkZ)) : null;
		if (region != null) {
			paint(region.texture.getPixels(), Math.floorMod(chunkX, REGION_CHUNKS), Math.floorMod(chunkZ, REGION_CHUNKS), data, level);
			region.dirty = true;
		}
	}

	private static int edge(byte[] data, int offset, int dx) {
		return (short) ((data[offset + dx * 2] & 0xFF) << 8 | data[offset + dx * 2 + 1] & 0xFF);
	}

	private static void setEdge(byte[] data, int offset, int dx, short y) {
		data[offset + dx * 2] = (byte) (y >> 8);
		data[offset + dx * 2 + 1] = (byte) y;
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

	// 1 画素に入る列のうち北西の 1 列で塗る。色のない列は透明にして、背景の色を見せる。colors が null なら全部透明にする
	private static void paint(NativeImage image, int chunkX, int chunkZ, byte[] colors, int level) {
		int texels = 16 >> level;
		for (int tz = 0; tz < texels; tz++) {
			for (int tx = 0; tx < texels; tx++) {
				int packed = colors == null ? 0 : Byte.toUnsignedInt(colors[(tz << level) * 16 + (tx << level)]);
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
		// ファイルから出して、まだサーバーに頼み直していないチャンク
		private final LongSet stale = new LongOpenHashSet();
		// ファイルを読みに行ったリージョン
		private final LongSet cachedRegions = new LongOpenHashSet();
		// まだファイルに書いていない変化（リージョンごと）
		private final Long2ObjectMap<Changes> changes = new Long2ObjectOpenHashMap<>();
		// 未生成と答えられたチャンク。生成はサーバーがセーブを確かめてから始めるので、他の誰かが生成していても困らない
		private final LongSet missing = new LongOpenHashSet();
		// 色が 1 つでも届いたリージョン
		private final LongSet regions = new LongOpenHashSet();

		private Changes changes(int chunkX, int chunkZ) {
			return changes.computeIfAbsent(regionOf(chunkX, chunkZ), key -> new Changes());
		}
	}

	private static final class Changes {
		private final Int2ObjectMap<byte[]> updates = new Int2ObjectOpenHashMap<>();
		private final IntSet removed = new IntOpenHashSet();

		private void put(int index, byte[] data) {
			updates.put(index, data);
			removed.remove(index);
		}

		private void remove(int index) {
			updates.remove(index);
			removed.add(index);
		}
	}

	private static final class Request {
		private final Dimension cache;
		private final int minX;
		private final int minZ;
		private final int maxX;
		private final int maxZ;
		// 開いていない間の依頼なら、手元に読み込まれているチャンクを飛ばすためのレベル
		private final ClientLevel background;
		private final List<Long> read = new ArrayList<>();
		private final List<Long> generate = new ArrayList<>();
		// ファイルから出したチャンク。届いていないチャンクより後に頼む
		private final List<Long> refresh = new ArrayList<>();

		private Request(Dimension cache, int minX, int minZ, int maxX, int maxZ, ClientLevel background) {
			this.cache = cache;
			this.minX = minX;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxZ = maxZ;
			this.background = background;
		}

		private boolean isFull() {
			return read.size() >= TerrainRequest.MAX_READ && generate.size() >= TerrainRequest.MAX_GENERATE;
		}

		private void collect(int x, int z) {
			if (x < minX || x > maxX || z < minZ || z > maxZ || background != null && MapTerrain.isLoaded(background, x, z)) {
				return;
			}
			long chunk = ChunkPos.pack(x, z);
			if (cache.chunks.containsKey(chunk)) {
				if (cache.stale.contains(chunk) && refresh.size() < TerrainRequest.MAX_READ) {
					refresh.add(chunk);
				}
				return;
			}
			if (cache.missing.contains(chunk)) {
				if (generate.size() < TerrainRequest.MAX_GENERATE) {
					generate.add(chunk);
				}
			} else if (read.size() < TerrainRequest.MAX_READ) {
				read.add(chunk);
			}
		}
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
