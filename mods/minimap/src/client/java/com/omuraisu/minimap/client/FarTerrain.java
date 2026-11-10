package com.omuraisu.minimap.client;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.omuraisu.minimap.TerrainColors;
import com.omuraisu.minimap.TerrainPreview;
import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.TerrainRemoved;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainStamps;
import com.omuraisu.minimap.TerrainTile;
import com.omuraisu.minimap.TerrainTiles;
import com.omuraisu.minimap.TerrainView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

// マップ画面で描画距離の外に出す地形。サーバーから届いたチャンクの色を 32 × 32 チャンク（リージョン）ごとに覚え、
// 開いている間はリージョンごとのテクスチャにして描く。読み込み済みのチャンクは MapTerrain に任せる。
// サーバーには見ている範囲（TerrainView）と、リージョンごとに持っている色の版（TerrainHave）を知らせるだけで、
// 何を送るかはサーバーが決める。届いた色は版と一緒にファイルにも残し（FarCache）、次に入ったときは版の違うチャンクだけが届く。
// まだ生成していない場所には、サーバーが見積もった下書き（TerrainPreview）を描き、本物の色が届いたチャンクから置き換える。
// 開いていない間も、プレイヤーの周りのマップ距離の分を知らせておく
//
// 分け方: 次元ごとのリージョンは FarDimension、リージョン 1 つ分の色・版・下書きは FarRegion、
// ファイルとサーバーへの知らせは FarSync、テクスチャは FarTextures。ここは届いたパケットを覚え、どのリージョンを覚えておくかを決める
public final class FarTerrain {
	private static final int REGION_CHUNKS = TerrainRegions.CHUNKS;
	private static final int REGION_BLOCKS = TerrainRegions.BLOCKS;
	// 開いていない間に、離れたリージョンを捨てる間隔と、届いた色をファイルに書く間隔
	private static final int BACKGROUND_PRUNE_INTERVAL = 200;
	private static final int BACKGROUND_SAVE_INTERVAL = 1200;
	// 覚えておく範囲。読み込める範囲より少し広くして、行き来しても読み直さずに済むようにする
	private static final int KEEP_REGIONS = (TerrainServer.RADIUS + REGION_CHUNKS) / REGION_CHUNKS + 1;

	private final Map<Identifier, FarDimension> dimensions = new HashMap<>();
	// 開いている間だけ持つ
	private final FarTextures textures = new FarTextures();
	private final FarSync sync = new FarSync(dimensions, textures::repaintRegion);
	private int ticks;
	private int received;

	// 届いた色。北の端の行は北のチャンクと比べた後の明るさ。テストから確かめる
	public byte[] colors(Identifier dimension, int chunkX, int chunkZ) {
		FarDimension cache = dimensions.get(dimension);
		byte[] tile = cache == null ? null : cache.tile(chunkX, chunkZ);
		return tile == null ? null : Arrays.copyOf(tile, 16 * 16);
	}

	// 届いた色の版。持っていなければ 0。テストから確かめる
	public int stamp(Identifier dimension, int chunkX, int chunkZ) {
		FarDimension cache = dimensions.get(dimension);
		FarRegion region = cache == null ? null : cache.region(chunkX, chunkZ);
		return region == null ? 0 : region.stamps[TerrainRegions.index(chunkX, chunkZ)];
	}

	public int received() {
		return received;
	}

	// 覚えているチャンクの数。テストから確かめる
	public int count(Identifier dimension) {
		FarDimension cache = dimensions.get(dimension);
		int count = 0;
		if (cache != null) {
			for (FarRegion region : cache.regions.values()) {
				count += region.count;
			}
		}
		return count;
	}

	// 下書きの色（TerrainColors の packed id）。届いていなければ 0。テストから確かめる
	public int preview(Identifier dimension, int blockX, int blockZ) {
		FarDimension cache = dimensions.get(dimension);
		FarRegion region = cache == null ? null : cache.region(blockX >> 4, blockZ >> 4);
		return region == null ? 0 : region.preview(Math.floorMod(blockX, REGION_BLOCKS), Math.floorMod(blockZ, REGION_BLOCKS));
	}

	// 北の端の行は、北のチャンクの色が届いていればその高さと比べて明るさを決める。南のチャンクが先に届いていれば、その北の端も決め直す。
	// テクスチャへの反映は tick でまとめて送る
	void receive(Minecraft client, TerrainTiles payload) {
		FarDimension cache = dimensions.get(payload.dimension());
		if (cache == null) {
			return;
		}
		for (TerrainTile tile : payload.tiles()) {
			int chunkX = ChunkPos.getX(tile.chunk());
			int chunkZ = ChunkPos.getZ(tile.chunk());
			FarRegion region = cache.region(chunkX, chunkZ);
			// 捨てたリージョンの色が遅れて届いた。もう一度覚え始めるときに知らせ直すので、サーバーも送り直す
			if (region == null || !region.introduced) {
				continue;
			}
			byte[] data = FarRegion.tile(tile);
			region.put(TerrainRegions.index(chunkX, chunkZ), data, tile.stamp());
			received++;

			resolveNorthRow(client, cache, chunkX, chunkZ, data);
			textures.repaint(cache, chunkX, chunkZ);
			byte[] south = cache.tile(chunkX, chunkZ + 1);
			if (south != null) {
				resolveNorthRow(client, cache, chunkX, chunkZ + 1, south);
				textures.repaint(cache, chunkX, chunkZ + 1);
			}
		}
	}

	// 色は前に届いたものと同じで、版だけが変わったチャンク
	void stamps(TerrainStamps payload) {
		FarDimension cache = dimensions.get(payload.dimension());
		if (cache == null) {
			return;
		}
		for (int i = 0; i < payload.chunks().length; i++) {
			int chunkX = ChunkPos.getX(payload.chunks()[i]);
			int chunkZ = ChunkPos.getZ(payload.chunks()[i]);
			FarRegion region = cache.region(chunkX, chunkZ);
			int index = TerrainRegions.index(chunkX, chunkZ);
			if (region != null && region.introduced && region.tiles[index] != null) {
				region.stamps[index] = payload.stamps()[i];
				region.unsaved = true;
			}
		}
	}

	// サーバーにもうないチャンク（別のワールドの色がファイルに残っていた、など）
	void removed(TerrainRemoved payload) {
		FarDimension cache = dimensions.get(payload.dimension());
		if (cache == null) {
			return;
		}
		for (long chunk : payload.chunks()) {
			int chunkX = ChunkPos.getX(chunk);
			int chunkZ = ChunkPos.getZ(chunk);
			FarRegion region = cache.region(chunkX, chunkZ);
			if (region != null && region.remove(TerrainRegions.index(chunkX, chunkZ))) {
				textures.repaint(cache, chunkX, chunkZ);
			}
		}
	}

	// まだ生成していない場所の下書き。色の届いていないチャンクに描く
	void preview(TerrainPreview payload) {
		FarDimension cache = dimensions.get(payload.dimension());
		FarRegion region = cache == null ? null : cache.regions.get(ChunkPos.pack(payload.regionX(), payload.regionZ()));
		if (region == null) {
			return;
		}
		region.preview = payload.colors();
		textures.repaintPreview(cache, region);
	}

	// 接続するたびに、そのワールド・サーバーの保存先を開く。null なら残さない
	void openCache(Path root) {
		sync.open(root);
	}

	void clear() {
		sync.close();
		dimensions.clear();
	}

	// 開いたときに、離れたリージョンを捨てる（ファイルには残る）
	void open(Minecraft client, Identifier dimension, ChunkPos player) {
		prune(client, dimension(dimension), player);
	}

	// マップ画面を開いていない間、毎 tick 呼ぶ。プレイヤーの周り distance チャンク（マップ距離）を見ている範囲としてサーバーに知らせ、
	// 開いたときにすぐ出せるようにする。distance が 0 なら知らせない
	void tickBackground(Minecraft client, Identifier dimension, ChunkPos player, int distance) {
		ticks++;
		if (ticks % BACKGROUND_SAVE_INTERVAL == 0) {
			sync.save();
		}
		FarDimension cache = dimension(dimension);
		cache.ceiling = client.level.dimensionType().hasCeiling();
		if (ticks % BACKGROUND_PRUNE_INTERVAL == 0) {
			prune(client, cache, player);
		}
		if (distance <= 0) {
			sync.cancel();
			return;
		}
		int radius = Math.min(distance, TerrainServer.RADIUS);
		sync.request(client, cache, new TerrainView(dimension, player.x() - radius, player.z() - radius, player.x() + radius, player.z() + radius,
				player.x(), player.z(), true), ticks);
	}

	// マップ画面から毎 tick 呼ぶ。範囲はチャンクの座標で、center に近いものから届く。scale は 1 ブロックの実ピクセル。
	// 手元で描けるチャンクも範囲に入れる。手元のテクスチャの端は丸めで 1 画素ずれることがあり、下に何もないとすき間が見えるため
	void tick(Minecraft client, Identifier dimension, ChunkPos player, double scale,
			int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ) {
		ticks++;
		textures.prepare(client, dimension, scale);
		minX = Math.max(minX, player.x() - TerrainServer.RADIUS);
		minZ = Math.max(minZ, player.z() - TerrainServer.RADIUS);
		maxX = Math.min(maxX, player.x() + TerrainServer.RADIUS);
		maxZ = Math.min(maxZ, player.z() + TerrainServer.RADIUS);
		if (minX > maxX || minZ > maxZ) {
			sync.cancel();
			return;
		}
		FarDimension cache = dimension(dimension);
		cache.ceiling = client.level.dimensionType().hasCeiling();
		sync.request(client, cache, new TerrainView(dimension, minX, minZ, maxX, maxZ, Math.clamp(centerX, minX, maxX), Math.clamp(centerZ, minZ, maxZ), false), ticks);
		textures.update(client, cache, minX, minZ, maxX, maxZ);
	}

	void close(Minecraft client) {
		sync.cancel();
		textures.close(client);
		sync.save();
	}

	void forEachRegion(FarTextures.RegionConsumer consumer) {
		textures.forEach(consumer);
	}

	private FarDimension dimension(Identifier dimension) {
		return dimensions.computeIfAbsent(dimension, FarDimension::new);
	}

	// 離れたリージョンを捨てる（ファイルには残る）。サーバーにも知らせて、戻ってきたときに知らせ直す
	private void prune(Minecraft client, FarDimension cache, ChunkPos player) {
		int centerX = TerrainRegions.coordinate(player.x());
		int centerZ = TerrainRegions.coordinate(player.z());
		var regions = cache.regions.values().iterator();
		while (regions.hasNext()) {
			FarRegion region = regions.next();
			if (Math.max(Math.abs(region.x - centerX), Math.abs(region.z - centerZ)) <= KEEP_REGIONS) {
				continue;
			}
			sync.forget(cache, region);
			regions.remove();
			textures.remove(client, cache, region);
		}
	}

	// 北のチャンクの南の端の高さと比べる。北の色が届いていなければ、手元に読み込まれているチャンクから出す
	private static void resolveNorthRow(Minecraft client, FarDimension cache, int chunkX, int chunkZ, byte[] data) {
		byte[] north = cache.tile(chunkX, chunkZ - 1);
		int[] northY;
		if (north != null) {
			northY = new int[16];
			for (int dx = 0; dx < 16; dx++) {
				northY[dx] = FarRegion.edge(north, FarRegion.SOUTH_EDGE, dx);
			}
		} else {
			northY = localSouthEdge(client, cache.id, chunkX, chunkZ - 1);
		}
		for (int dx = 0; dx < 16; dx++) {
			int packed = Byte.toUnsignedInt(data[dx]);
			data[dx] = (byte) TerrainColors.relief(packed, FarRegion.edge(data, FarRegion.NORTH_EDGE, dx), northY == null ? TerrainColors.UNKNOWN_Y : northY[dx]);
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
}
