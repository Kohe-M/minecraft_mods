package com.omuraisu.minimap.client;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.TerrainColors;
import com.omuraisu.minimap.TerrainHave;
import com.omuraisu.minimap.TerrainPreview;
import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.TerrainRemoved;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainStamps;
import com.omuraisu.minimap.TerrainTile;
import com.omuraisu.minimap.TerrainTiles;
import com.omuraisu.minimap.TerrainView;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

// マップ画面で描画距離の外に出す地形。サーバーから届いたチャンクの色を 32 × 32 チャンク（リージョン）ごとに覚え、
// 開いている間はリージョンごとのテクスチャにして描く。読み込み済みのチャンクは MapTerrain に任せる。
// サーバーには見ている範囲（TerrainView）と、リージョンごとに持っている色の版（TerrainHave）を知らせるだけで、
// 何を送るかはサーバーが決める。届いた色は版と一緒にファイルにも残し（FarCache）、次に入ったときは版の違うチャンクだけが届く。
// まだ生成していない場所には、サーバーが見積もった下書き（TerrainPreview）を描き、本物の色が届いたチャンクから置き換える。
// 開いていない間も、プレイヤーの周りのマップ距離の分を知らせておく
public final class FarTerrain {
	private static final int REGION_CHUNKS = TerrainRegions.CHUNKS;
	private static final int REGION_BLOCKS = TerrainRegions.BLOCKS;
	// 見ている範囲が動いても、この間隔（tick）より短くは知らせ直さない
	private static final int VIEW_INTERVAL = 5;
	// 開いていない間に、離れたリージョンを捨てる間隔と、届いた色をファイルに書く間隔
	private static final int BACKGROUND_PRUNE_INTERVAL = 200;
	private static final int BACKGROUND_SAVE_INTERVAL = 1200;
	// 1 tick に作ったり送ったりするテクスチャの画素数。等倍のリージョン 4 枚分
	private static final int UPLOAD_BUDGET = 4 * REGION_BLOCKS * REGION_BLOCKS;
	// 引いているときは 1 画素に何ブロックも入るので、間引いた小さいテクスチャにする（2 の何乗ブロックで 1 画素か）
	private static final int MAX_LEVEL = 2;
	// 覚えておく範囲。読み込める範囲より少し広くして、行き来しても読み直さずに済むようにする
	private static final int KEEP_REGIONS = (TerrainServer.RADIUS + REGION_CHUNKS) / REGION_CHUNKS + 1;
	// 1 チャンク分のデータ。色 256 バイトのあとに、北の端と南の端の高さ（TerrainTile）を short 16 個ずつ
	private static final int NORTH_EDGE = 16 * 16;
	private static final int SOUTH_EDGE = NORTH_EDGE + 16 * 2;
	private static final int TILE_BYTES = SOUTH_EDGE + 16 * 2;
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

	private final Map<Identifier, Dimension> dimensions = new HashMap<>();
	private final FarCache disk = new FarCache();
	// サーバーに手持ちを知らせてある次元と、最後に知らせた範囲
	private Identifier introduced;
	private TerrainView sent;
	private int lastSent;
	private int ticks;
	private int received;

	// 開いている間だけ持つ
	private final Long2ObjectMap<Texture> textures = new Long2ObjectOpenHashMap<>();
	private Identifier textureDimension;
	private int level;
	private int uploadCursor;

	// 届いた色。北の端の行は北のチャンクと比べた後の明るさ。テストから確かめる
	public byte[] colors(Identifier dimension, int chunkX, int chunkZ) {
		Dimension cache = dimensions.get(dimension);
		byte[] tile = cache == null ? null : cache.tile(chunkX, chunkZ);
		return tile == null ? null : Arrays.copyOf(tile, 16 * 16);
	}

	// 届いた色の版。持っていなければ 0。テストから確かめる
	public int stamp(Identifier dimension, int chunkX, int chunkZ) {
		Dimension cache = dimensions.get(dimension);
		Region region = cache == null ? null : cache.regions.get(TerrainRegions.key(chunkX, chunkZ));
		return region == null ? 0 : region.stamps[TerrainRegions.index(chunkX, chunkZ)];
	}

	public int received() {
		return received;
	}

	// 覚えているチャンクの数。テストから確かめる
	public int count(Identifier dimension) {
		Dimension cache = dimensions.get(dimension);
		int count = 0;
		if (cache != null) {
			for (Region region : cache.regions.values()) {
				count += region.count;
			}
		}
		return count;
	}

	// 下書きの色（TerrainColors の packed id）。届いていなければ 0。テストから確かめる
	public int preview(Identifier dimension, int blockX, int blockZ) {
		Dimension cache = dimensions.get(dimension);
		Region region = cache == null ? null : cache.regions.get(TerrainRegions.key(blockX >> 4, blockZ >> 4));
		if (region == null || region.preview == null) {
			return 0;
		}
		return Byte.toUnsignedInt(region.preview[Math.floorMod(blockX, REGION_BLOCKS) / TerrainPreview.STEP
				+ Math.floorMod(blockZ, REGION_BLOCKS) / TerrainPreview.STEP * TerrainPreview.SIZE]);
	}

	// 北の端の行は、北のチャンクの色が届いていればその高さと比べて明るさを決める。南のチャンクが先に届いていれば、その北の端も決め直す。
	// テクスチャへの反映は tick でまとめて送る
	void receive(Minecraft client, TerrainTiles payload) {
		Identifier dimension = payload.dimension();
		Dimension cache = dimensions.get(dimension);
		if (cache == null) {
			return;
		}
		for (TerrainTile tile : payload.tiles()) {
			int chunkX = ChunkPos.getX(tile.chunk());
			int chunkZ = ChunkPos.getZ(tile.chunk());
			Region region = cache.regions.get(TerrainRegions.key(chunkX, chunkZ));
			// 捨てたリージョンの色が遅れて届いた。もう一度覚え始めるときに知らせ直すので、サーバーも送り直す
			if (region == null || !region.introduced) {
				continue;
			}
			byte[] data = new byte[TILE_BYTES];
			System.arraycopy(tile.colors(), 0, data, 0, 16 * 16);
			for (int dx = 0; dx < 16; dx++) {
				setEdge(data, NORTH_EDGE, dx, tile.northEdge()[dx]);
				setEdge(data, SOUTH_EDGE, dx, tile.southEdge()[dx]);
			}
			region.put(TerrainRegions.index(chunkX, chunkZ), data, tile.stamp());
			received++;

			resolveNorthRow(client, dimension, cache, chunkX, chunkZ, data);
			repaint(dimension, cache, chunkX, chunkZ);
			byte[] south = cache.tile(chunkX, chunkZ + 1);
			if (south != null) {
				resolveNorthRow(client, dimension, cache, chunkX, chunkZ + 1, south);
				repaint(dimension, cache, chunkX, chunkZ + 1);
			}
		}
	}

	// 色は前に届いたものと同じで、版だけが変わったチャンク
	void stamps(TerrainStamps payload) {
		Dimension cache = dimensions.get(payload.dimension());
		if (cache == null) {
			return;
		}
		for (int i = 0; i < payload.chunks().length; i++) {
			int chunkX = ChunkPos.getX(payload.chunks()[i]);
			int chunkZ = ChunkPos.getZ(payload.chunks()[i]);
			Region region = cache.regions.get(TerrainRegions.key(chunkX, chunkZ));
			int index = TerrainRegions.index(chunkX, chunkZ);
			if (region != null && region.introduced && region.tiles[index] != null) {
				region.stamps[index] = payload.stamps()[i];
				region.unsaved = true;
			}
		}
	}

	// サーバーにもうないチャンク（別のワールドの色がファイルに残っていた、など）
	void removed(TerrainRemoved payload) {
		Dimension cache = dimensions.get(payload.dimension());
		if (cache == null) {
			return;
		}
		for (long chunk : payload.chunks()) {
			int chunkX = ChunkPos.getX(chunk);
			int chunkZ = ChunkPos.getZ(chunk);
			Region region = cache.regions.get(TerrainRegions.key(chunkX, chunkZ));
			if (region != null && region.remove(TerrainRegions.index(chunkX, chunkZ))) {
				repaint(payload.dimension(), cache, chunkX, chunkZ);
			}
		}
	}

	// まだ生成していない場所の下書き。色の届いていないチャンクに描く
	void preview(TerrainPreview payload) {
		Dimension cache = dimensions.get(payload.dimension());
		Region region = cache == null ? null : cache.regions.get(ChunkPos.pack(payload.regionX(), payload.regionZ()));
		if (region == null) {
			return;
		}
		region.preview = payload.colors();
		Texture texture = payload.dimension().equals(textureDimension) ? textures.get(ChunkPos.pack(region.x, region.z)) : null;
		if (texture != null) {
			for (int index = 0; index < TerrainRegions.COUNT; index++) {
				if (region.tiles[index] == null) {
					paint(texture.texture.getPixels(), region, index % REGION_CHUNKS, index / REGION_CHUNKS, level);
				}
			}
			texture.dirty = true;
		}
	}

	// 接続するたびに、そのワールド・サーバーの保存先を開く。null なら残さない
	void openCache(Path root) {
		disk.open(root);
	}

	void clear() {
		save();
		disk.close();
		dimensions.clear();
		introduced = null;
		sent = null;
	}

	// 開いたときに、離れたリージョンを捨てる（ファイルには残る）
	void open(Minecraft client, Identifier dimension, ChunkPos player) {
		prune(client, dimension, dimension(dimension), player);
	}

	// マップ画面を開いていない間、毎 tick 呼ぶ。プレイヤーの周り distance チャンク（マップ距離）を見ている範囲としてサーバーに知らせ、
	// 開いたときにすぐ出せるようにする。distance が 0 なら知らせない
	void tickBackground(Minecraft client, Identifier dimension, ChunkPos player, int distance) {
		ticks++;
		if (ticks % BACKGROUND_SAVE_INTERVAL == 0) {
			save();
		}
		Dimension cache = dimension(dimension);
		cache.ceiling = client.level.dimensionType().hasCeiling();
		if (ticks % BACKGROUND_PRUNE_INTERVAL == 0) {
			prune(client, dimension, cache, player);
		}
		if (distance <= 0) {
			cancel();
			return;
		}
		int radius = Math.min(distance, TerrainServer.RADIUS);
		request(client, dimension, cache, new TerrainView(dimension, player.x() - radius, player.z() - radius, player.x() + radius, player.z() + radius,
				player.x(), player.z(), true));
	}

	// マップ画面から毎 tick 呼ぶ。範囲はチャンクの座標で、center に近いものから届く。scale は 1 ブロックの実ピクセル。
	// 手元で描けるチャンクも範囲に入れる。手元のテクスチャの端は丸めで 1 画素ずれることがあり、下に何もないとすき間が見えるため
	void tick(Minecraft client, Identifier dimension, ChunkPos player, double scale,
			int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ) {
		ticks++;
		int newLevel = Math.clamp((int) Math.floor(-Math.log(scale) / Math.log(2)), 0, MAX_LEVEL);
		if (!dimension.equals(textureDimension) || newLevel != level) {
			releaseTextures(client);
			textureDimension = dimension;
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
		cache.ceiling = client.level.dimensionType().hasCeiling();
		request(client, dimension, cache, new TerrainView(dimension, minX, minZ, maxX, maxZ, Math.clamp(centerX, minX, maxX), Math.clamp(centerZ, minZ, maxZ), false));
		updateTextures(client, cache, minX, minZ, maxX, maxZ);
	}

	void close(Minecraft client) {
		cancel();
		releaseTextures(client);
		textureDimension = null;
		save();
	}

	void forEachRegion(RegionConsumer consumer) {
		for (Long2ObjectMap.Entry<Texture> entry : textures.long2ObjectEntrySet()) {
			if (entry.getValue().uploaded) {
				long key = entry.getLongKey();
				consumer.accept(entry.getValue().id, ChunkPos.getX(key) * REGION_BLOCKS, ChunkPos.getZ(key) * REGION_BLOCKS, REGION_BLOCKS);
			}
		}
	}

	private Dimension dimension(Identifier dimension) {
		return dimensions.computeIfAbsent(dimension, id -> new Dimension());
	}

	// 範囲にかかるリージョンを覚え始め（ファイルがあれば読み）、読み終えたものからサーバーに手持ちの版を知らせる。
	// サーバーは知らされたリージョンだけを送るので、ファイルを読み終える前に同じ色が届くことはない
	private void request(Minecraft client, Identifier dimension, Dimension cache, TerrainView view) {
		if (!ClientPlayNetworking.canSend(TerrainView.TYPE)) {
			return;
		}
		if (!dimension.equals(introduced)) {
			// 違う次元のことを知らせると、サーバーは前の次元の分をすべて忘れる
			for (Dimension other : dimensions.values()) {
				other.regions.values().forEach(region -> region.introduced = false);
			}
			introduced = dimension;
			sent = null;
		}
		for (int regionZ = TerrainRegions.coordinate(view.minZ()); regionZ <= TerrainRegions.coordinate(view.maxZ()); regionZ++) {
			for (int regionX = TerrainRegions.coordinate(view.minX()); regionX <= TerrainRegions.coordinate(view.maxX()); regionX++) {
				long key = ChunkPos.pack(regionX, regionZ);
				Region region = cache.regions.get(key);
				if (region == null) {
					Region created = new Region(regionX, regionZ);
					region = created;
					cache.regions.put(key, created);
					disk.load(dimension, regionX, regionZ, TILE_BYTES).thenAccept(tiles -> client.execute(() -> loaded(dimension, cache, created, tiles)));
				}
				if (region.loaded && !region.introduced) {
					region.introduced = true;
					ClientPlayNetworking.send(new TerrainHave(dimension, regionX, regionZ, region.stamps.clone()));
				}
			}
		}
		if (!view.equals(sent) && (sent == null || ticks - lastSent >= VIEW_INTERVAL)) {
			ClientPlayNetworking.send(view);
			sent = view;
			lastSent = ticks;
		}
	}

	// 見ている範囲がなくなったら、サーバーに止めてもらう
	private void cancel() {
		if (sent != null && !sent.isEmpty() && ClientPlayNetworking.canSend(TerrainView.TYPE)) {
			sent = TerrainView.none(sent.dimension());
			ClientPlayNetworking.send(sent);
		}
	}

	// ファイルから読んだ色。サーバーに知らせるまでは色が届かないので、読んだものをそのまま入れる
	private void loaded(Identifier dimension, Dimension cache, Region region, FarCache.Tiles tiles) {
		if (dimensions.get(dimension) != cache || cache.regions.get(ChunkPos.pack(region.x, region.z)) != region) {
			return;
		}
		for (int index = 0; index < TerrainRegions.COUNT; index++) {
			if (tiles.data[index] != null && region.tiles[index] == null) {
				region.tiles[index] = tiles.data[index];
				region.stamps[index] = tiles.stamps[index];
				region.count++;
			}
		}
		region.loaded = true;
		Texture texture = dimension.equals(textureDimension) ? textures.get(ChunkPos.pack(region.x, region.z)) : null;
		if (texture != null) {
			paint(texture.texture.getPixels(), region, level);
			texture.dirty = true;
		}
	}

	// 離れたリージョンを捨てる（ファイルには残る）。サーバーにも知らせて、戻ってきたときに知らせ直す
	private void prune(Minecraft client, Identifier dimension, Dimension cache, ChunkPos player) {
		int centerX = TerrainRegions.coordinate(player.x());
		int centerZ = TerrainRegions.coordinate(player.z());
		var regions = cache.regions.values().iterator();
		while (regions.hasNext()) {
			Region region = regions.next();
			if (Math.max(Math.abs(region.x - centerX), Math.abs(region.z - centerZ)) <= KEEP_REGIONS) {
				continue;
			}
			save(dimension, cache, region);
			if (region.introduced && dimension.equals(introduced) && ClientPlayNetworking.canSend(TerrainHave.TYPE)) {
				ClientPlayNetworking.send(new TerrainHave(dimension, region.x, region.z, null));
			}
			regions.remove();
			if (dimension.equals(textureDimension)) {
				Texture texture = textures.remove(ChunkPos.pack(region.x, region.z));
				if (texture != null) {
					client.getTextureManager().release(texture.id);
				}
			}
		}
	}

	private void save() {
		for (Map.Entry<Identifier, Dimension> entry : dimensions.entrySet()) {
			for (Region region : entry.getValue().regions.values()) {
				save(entry.getKey(), entry.getValue(), region);
			}
		}
	}

	// 色は後から北の端を決め直すので、書く時点の中身を写す。メモリにあるチャンクから作った色の版はこの接続の間しか通じず、
	// 天井のある次元の色はそのときの足元の高さで変わるので、次は必ず送り直されるようにする
	private void save(Identifier dimension, Dimension cache, Region region) {
		if (!region.unsaved || !region.loaded) {
			return;
		}
		region.unsaved = false;
		FarCache.Tiles tiles = new FarCache.Tiles();
		for (int index = 0; index < TerrainRegions.COUNT; index++) {
			if (region.tiles[index] != null) {
				tiles.data[index] = region.tiles[index].clone();
				tiles.stamps[index] = region.stamps[index] > 0 && !cache.ceiling ? region.stamps[index] : TerrainTile.STALE;
			}
		}
		disk.save(dimension, region.x, region.z, TILE_BYTES, tiles);
	}

	// 北のチャンクの南の端の高さと比べる。北の色が届いていなければ、手元に読み込まれているチャンクから出す
	private static void resolveNorthRow(Minecraft client, Identifier dimension, Dimension cache, int chunkX, int chunkZ, byte[] data) {
		byte[] north = cache.tile(chunkX, chunkZ - 1);
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

	private void repaint(Identifier dimension, Dimension cache, int chunkX, int chunkZ) {
		long key = TerrainRegions.key(chunkX, chunkZ);
		Texture texture = dimension.equals(textureDimension) ? textures.get(key) : null;
		Region region = texture == null ? null : cache.regions.get(key);
		if (region != null) {
			paint(texture.texture.getPixels(), region, Math.floorMod(chunkX, REGION_CHUNKS), Math.floorMod(chunkZ, REGION_CHUNKS), level);
			texture.dirty = true;
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
	private void updateTextures(Minecraft client, Dimension cache, int minX, int minZ, int maxX, int maxZ) {
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
				Region region = cache.regions.get(key);
				if (region == null || region.count == 0 && region.preview == null) {
					continue;
				}
				texture = createTexture(client, region, size);
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

	private Texture createTexture(Minecraft client, Region region, int size) {
		Identifier id = Identifier.fromNamespaceAndPath("minimap", "far/" + region.x + "_" + region.z);
		NativeImage image = new NativeImage(size, size, true);
		paint(image, region, level);
		DynamicTexture texture = new DynamicTexture(id::toString, size, size, false) {
		};
		texture.setPixels(image);
		client.getTextureManager().register(id, texture);
		return new Texture(id, texture);
	}

	private void releaseTextures(Minecraft client) {
		for (Texture texture : textures.values()) {
			client.getTextureManager().release(texture.id);
		}
		textures.clear();
	}

	private static void paint(NativeImage image, Region region, int level) {
		for (int cz = 0; cz < REGION_CHUNKS; cz++) {
			for (int cx = 0; cx < REGION_CHUNKS; cx++) {
				paint(image, region, cx, cz, level);
			}
		}
	}

	// リージョンの中のチャンク 1 つ分を塗る。1 画素に入る列のうち北西の 1 列の色にする。
	// 色が届いていないチャンクは下書きの色、それもなければ透明にして背景の色を見せる
	private static void paint(NativeImage image, Region region, int chunkX, int chunkZ, int level) {
		byte[] tile = region.tiles[chunkX + chunkZ * REGION_CHUNKS];
		byte[] preview = region.preview;
		int texels = 16 >> level;
		for (int tz = 0; tz < texels; tz++) {
			for (int tx = 0; tx < texels; tx++) {
				int x = tx << level;
				int z = tz << level;
				int packed;
				if (tile != null) {
					packed = Byte.toUnsignedInt(tile[z * 16 + x]);
				} else if (preview != null) {
					packed = Byte.toUnsignedInt(preview[(chunkX * 16 + x) / TerrainPreview.STEP + (chunkZ * 16 + z) / TerrainPreview.STEP * TerrainPreview.SIZE]);
				} else {
					packed = 0;
				}
				image.setPixel(chunkX * texels + tx, chunkZ * texels + tz, ARGB[packed]);
			}
		}
	}

	interface RegionConsumer {
		void accept(Identifier texture, int minBlockX, int minBlockZ, int size);
	}

	private static final class Dimension {
		private final Long2ObjectMap<Region> regions = new Long2ObjectOpenHashMap<>();
		// 天井のある次元（色が足元の高さで変わる）
		private boolean ceiling;

		private byte[] tile(int chunkX, int chunkZ) {
			Region region = regions.get(TerrainRegions.key(chunkX, chunkZ));
			return region == null ? null : region.tiles[TerrainRegions.index(chunkX, chunkZ)];
		}
	}

	// リージョン 1 つ分の色と版。リージョンの中の番号（TerrainRegions.index）で並べる
	private static final class Region {
		private final int x;
		private final int z;
		private final byte[][] tiles = new byte[TerrainRegions.COUNT][];
		private final int[] stamps = new int[TerrainRegions.COUNT];
		private int count;
		private byte[] preview;
		// ファイルを読み終えた
		private boolean loaded;
		// サーバーに手持ちの版を知らせた
		private boolean introduced;
		// まだファイルに書いていない変化がある
		private boolean unsaved;

		private Region(int x, int z) {
			this.x = x;
			this.z = z;
		}

		private void put(int index, byte[] data, int stamp) {
			if (tiles[index] == null) {
				count++;
			}
			tiles[index] = data;
			stamps[index] = stamp;
			unsaved = true;
		}

		private boolean remove(int index) {
			if (tiles[index] == null) {
				return false;
			}
			tiles[index] = null;
			stamps[index] = 0;
			count--;
			unsaved = true;
			return true;
		}
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
