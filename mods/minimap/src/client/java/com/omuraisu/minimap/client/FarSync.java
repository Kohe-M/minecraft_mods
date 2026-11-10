package com.omuraisu.minimap.client;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

import com.omuraisu.minimap.TerrainHave;
import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.TerrainTile;
import com.omuraisu.minimap.TerrainView;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

// 覚えているリージョンを、ファイル（FarCache）とサーバーにそろえる。覚え始めたリージョンをファイルから読み、
// 読み終えたらサーバーに手持ちの版を知らせ（TerrainHave）、見ている範囲を知らせる（TerrainView）。捨てるリージョンは書いてから忘れたと知らせる。
// サーバーに何を知らせたかは、送ったパケットだけで決める
final class FarSync {
	// 見ている範囲が動いても、この間隔（tick）より短くは知らせ直さない
	private static final int VIEW_INTERVAL = 5;

	private final Map<Identifier, FarDimension> dimensions;
	// ファイルを読み終えたリージョン。テクスチャに描き直す
	private final BiConsumer<FarDimension, FarRegion> loaded;
	private final FarCache disk = new FarCache();
	// サーバーに手持ちを知らせてある次元と、最後に知らせた範囲
	private Identifier introduced;
	private TerrainView sent;
	private int lastSent;

	FarSync(Map<Identifier, FarDimension> dimensions, BiConsumer<FarDimension, FarRegion> loaded) {
		this.dimensions = dimensions;
		this.loaded = loaded;
	}

	// 接続するたびに、そのワールド・サーバーの保存先を開く。null なら残さない
	void open(Path root) {
		disk.open(root);
	}

	// 切断した。覚えているものを書き、次に接続した相手にはすべて知らせ直す
	void close() {
		save();
		disk.close();
		introduced = null;
		sent = null;
	}

	// 範囲にかかるリージョンを覚え始め（ファイルがあれば読み）、読み終えたものからサーバーに手持ちの版を知らせる。
	// サーバーは知らされたリージョンだけを送るので、ファイルを読み終える前に同じ色が届くことはない
	void request(Minecraft client, FarDimension cache, TerrainView view, int ticks) {
		if (!ClientPlayNetworking.canSend(TerrainView.TYPE)) {
			return;
		}
		if (!cache.id.equals(introduced)) {
			// 違う次元のことを知らせると、サーバーは前の次元の分をすべて忘れる
			for (FarDimension other : dimensions.values()) {
				other.regions.values().forEach(region -> region.introduced = false);
			}
			introduced = cache.id;
			sent = null;
		}
		for (int regionZ = TerrainRegions.coordinate(view.minZ()); regionZ <= TerrainRegions.coordinate(view.maxZ()); regionZ++) {
			for (int regionX = TerrainRegions.coordinate(view.minX()); regionX <= TerrainRegions.coordinate(view.maxX()); regionX++) {
				long key = ChunkPos.pack(regionX, regionZ);
				FarRegion region = cache.regions.get(key);
				if (region == null) {
					FarRegion created = new FarRegion(regionX, regionZ);
					region = created;
					cache.regions.put(key, created);
					disk.load(cache.id, regionX, regionZ, FarRegion.TILE_BYTES).thenAccept(tiles -> client.execute(() -> loaded(cache, created, tiles)));
				}
				if (region.loaded && !region.introduced) {
					region.introduced = true;
					ClientPlayNetworking.send(new TerrainHave(cache.id, regionX, regionZ, region.stamps.clone()));
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
	void cancel() {
		if (sent != null && !sent.isEmpty() && ClientPlayNetworking.canSend(TerrainView.TYPE)) {
			sent = TerrainView.none(sent.dimension());
			ClientPlayNetworking.send(sent);
		}
	}

	// 捨てるリージョン。ファイルに書き、サーバーにも忘れたと知らせて、戻ってきたときに知らせ直す
	void forget(FarDimension cache, FarRegion region) {
		save(cache, region);
		if (region.introduced && cache.id.equals(introduced) && ClientPlayNetworking.canSend(TerrainHave.TYPE)) {
			ClientPlayNetworking.send(new TerrainHave(cache.id, region.x, region.z, null));
		}
	}

	void save() {
		for (FarDimension cache : dimensions.values()) {
			for (FarRegion region : cache.regions.values()) {
				save(cache, region);
			}
		}
	}

	// ファイルから読んだ色。読んでいる間に切断したり捨てたりしたリージョンには入れない
	private void loaded(FarDimension cache, FarRegion region, FarCache.Tiles tiles) {
		if (dimensions.get(cache.id) != cache || cache.regions.get(ChunkPos.pack(region.x, region.z)) != region) {
			return;
		}
		region.load(tiles);
		loaded.accept(cache, region);
	}

	// 色は後から北の端を決め直すので、書く時点の中身を写す。メモリにあるチャンクから作った色の版はこの接続の間しか通じず、
	// 天井のある次元の色はそのときの足元の高さで変わるので、次は必ず送り直されるようにする
	private void save(FarDimension cache, FarRegion region) {
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
		disk.save(cache.id, region.x, region.z, FarRegion.TILE_BYTES, tiles);
	}
}
