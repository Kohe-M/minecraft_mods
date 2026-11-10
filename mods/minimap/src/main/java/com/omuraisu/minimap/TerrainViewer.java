package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

// 地図を見ている人 1 人の状態。知らせてきた次元と範囲、リージョンごとに持っている色の版、いまの一巡、まとめて送るもの。
// メインスレッドでだけ触る。自前のスレッドで色を作る仕事は、始めるときに snapshot を取り、結果を受け取ったら accepts で採るかを決める
final class TerrainViewer {
	// 最後に知らせてきた次元と範囲
	Identifier dimension;
	boolean active;
	int minX;
	int minZ;
	int maxX;
	int maxZ;
	int centerX;
	int centerZ;
	// マップ画面を開いていない（マップ距離の分を見ている）
	boolean background;
	// 範囲か知らせてきたリージョンが変わった。次の tick に一巡をやり直す
	boolean changed;
	// 次元を移って、知らせてきたリージョンを忘れるたびに増やす。前の次元で始めた仕事の結果を捨てる
	private int session;
	final Long2ObjectMap<RegionView> regions = new Long2ObjectOpenHashMap<>();
	// 色を作っている途中のチャンク（セーブを読んでいる、メインスレッドの時間を待っている、など）
	final LongSet pending = new LongOpenHashSet();
	final LongArrayFIFOQueue backlog = new LongArrayFIFOQueue();
	final LongArrayFIFOQueue candidates = new LongArrayFIFOQueue();
	// 天井のある次元で、色を作るときの足元の高さ
	int feetY = Integer.MIN_VALUE / 2;

	// いまの一巡
	boolean passDone = true;
	// ヘッダーをまだ読めていないリージョンがあった。すぐにやり直す
	boolean incomplete;
	int passStart = Integer.MIN_VALUE / 2;
	int passMinX;
	int passMinZ;
	int passMaxX;
	int passMaxZ;
	int passCenterX;
	int passCenterZ;
	int maxRing;
	int ring;
	int step;
	long signature;
	int regionMinX;
	int regionMinZ;
	int regionWidth;
	RegionView[] passRegions;
	List<RegionView> passOrder = List.of();

	// まとめて送るもの
	private final List<TerrainTile> tiles = new ArrayList<>();
	private final LongList stampChunks = new LongArrayList();
	private final IntArrayList stampValues = new IntArrayList();
	final LongList removed = new LongArrayList();
	final List<TerrainPreview> previews = new ArrayList<>();

	// 自前のスレッドで色を作り始めたときの状態
	record Snapshot(int session, int feetY) {
	}

	Snapshot snapshot() {
		return new Snapshot(session, feetY);
	}

	// 結果が届いたときに、待っているチャンクとして外すか。次元を移った後なら、待っているチャンクは enter で空にしてある
	boolean sameSession(Snapshot snapshot) {
		return session == snapshot.session();
	}

	// 結果の色を、クライアントに送って持っている版として覚えてよいか。次元を移った後なら捨てる
	boolean accepts(Snapshot snapshot) {
		return session == snapshot.session();
	}

	// 違う次元のことを知らせてきたら、前の次元のことはすべて忘れる（クライアントも知らせ直す）
	void enter(Identifier newDimension) {
		if (newDimension.equals(dimension)) {
			return;
		}
		dimension = newDimension;
		session++;
		active = false;
		regions.clear();
		pending.clear();
		backlog.clear();
		candidates.clear();
		tiles.clear();
		stampChunks.clear();
		stampValues.clear();
		removed.clear();
		previews.clear();
		passDone = true;
		passRegions = null;
		passOrder = List.of();
	}

	// 天井のある次元で足元の高さが変わった。持っている色をすべて送り直す
	void moveFeet(int newFeetY) {
		feetY = newFeetY;
		for (RegionView region : regions.values()) {
			Arrays.fill(region.known, 0);
			region.clean = false;
		}
		changed = true;
	}

	boolean contains(int x, int z) {
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
	}

	// 送る列に入れ、クライアントが持っている版として覚える。色が前に送ったものと同じなら、版だけを送る
	// （メモリにあったチャンクが同じ中身のまま保存された、保存されたチャンクが読み込まれた、など）
	void deliver(TerrainTile tile) {
		int x = ChunkPos.getX(tile.chunk());
		int z = ChunkPos.getZ(tile.chunk());
		RegionView region = regions.get(TerrainRegions.key(x, z));
		if (region == null) {
			return;
		}
		int index = TerrainRegions.index(x, z);
		int hash = 31 * (31 * Arrays.hashCode(tile.colors()) + Arrays.hashCode(tile.northEdge())) + Arrays.hashCode(tile.southEdge());
		if (region.sent == null) {
			region.sent = new int[TerrainRegions.COUNT];
		}
		if (region.known[index] != 0 && region.sent[index] == hash && hash != 0) {
			stampChunks.add(tile.chunk());
			stampValues.add(tile.stamp());
		} else {
			tiles.add(tile);
		}
		region.sent[index] = hash;
		region.known[index] = tile.stamp();
	}

	// この tick にまとめたものを送る
	void flush(ServerPlayer player) {
		if (!tiles.isEmpty() && ServerPlayNetworking.canSend(player, TerrainTiles.TYPE)) {
			for (int from = 0; from < tiles.size(); from += TerrainTiles.MAX_TILES) {
				ServerPlayNetworking.send(player, new TerrainTiles(dimension,
						new ArrayList<>(tiles.subList(from, Math.min(tiles.size(), from + TerrainTiles.MAX_TILES)))));
			}
		}
		tiles.clear();
		if (!stampChunks.isEmpty() && ServerPlayNetworking.canSend(player, TerrainStamps.TYPE)) {
			for (int from = 0; from < stampChunks.size(); from += TerrainStamps.MAX_CHUNKS) {
				int to = Math.min(stampChunks.size(), from + TerrainStamps.MAX_CHUNKS);
				ServerPlayNetworking.send(player, new TerrainStamps(dimension, stampChunks.subList(from, to).toLongArray(), stampValues.subList(from, to).toIntArray()));
			}
		}
		stampChunks.clear();
		stampValues.clear();
		if (!removed.isEmpty() && ServerPlayNetworking.canSend(player, TerrainRemoved.TYPE)) {
			for (int from = 0; from < removed.size(); from += TerrainRemoved.MAX_CHUNKS) {
				ServerPlayNetworking.send(player, new TerrainRemoved(dimension,
						removed.subList(from, Math.min(removed.size(), from + TerrainRemoved.MAX_CHUNKS)).toLongArray()));
			}
		}
		removed.clear();
		if (!previews.isEmpty() && ServerPlayNetworking.canSend(player, TerrainPreview.TYPE)) {
			previews.forEach(preview -> ServerPlayNetworking.send(player, preview));
		}
		previews.clear();
	}

	// 見ている人が知らせてきたリージョン 1 つ分
	static final class RegionView {
		final int x;
		final int z;
		// クライアントが持っている色の版（TerrainRegions.index の順。0 は持っていない）
		final int[] known;
		// この接続で送った色のハッシュ（送っていなければ 0）。色が同じで版だけ変わったチャンクを見分ける
		private int[] sent;
		boolean previewed;

		// いまの一巡で比べる範囲（チャンク）と、始めたときのヘッダー・メモリの変化の数
		int minX;
		int minZ;
		int maxX;
		int maxZ;
		RegionReader.Header header;
		int epoch;
		boolean skip;
		// 送るもの・読むもの・生成するものがあった
		boolean dirty;
		// 範囲の中にセーブにないチャンクがあり、下書きが要る
		boolean absent;

		// 何も送るものがなかった一巡の条件。同じなら次の一巡で飛ばす
		boolean clean;
		RegionReader.Header cleanHeader;
		int cleanEpoch;
		long cleanSignature;
		int cleanMinX;
		int cleanMinZ;
		int cleanMaxX;
		int cleanMaxZ;

		RegionView(int x, int z, int[] known) {
			this.x = x;
			this.z = z;
			this.known = known;
		}

		// リージョンの一番近いチャンクまでの距離
		int distance(int chunkX, int chunkZ) {
			int nearX = Math.clamp(chunkX, x * TerrainRegions.CHUNKS, x * TerrainRegions.CHUNKS + TerrainRegions.CHUNKS - 1);
			int nearZ = Math.clamp(chunkZ, z * TerrainRegions.CHUNKS, z * TerrainRegions.CHUNKS + TerrainRegions.CHUNKS - 1);
			return Math.max(Math.abs(nearX - chunkX), Math.abs(nearZ - chunkZ));
		}
	}
}
