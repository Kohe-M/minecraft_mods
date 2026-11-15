package com.omuraisu.minimap;

import com.omuraisu.minimap.mixin.ChunkMapInvoker;
import com.omuraisu.minimap.mixin.ChunkTaskDispatcherAccessor;
import com.omuraisu.minimap.mixin.IOWorkerAccessor;
import com.omuraisu.minimap.mixin.RegionFileStorageAccessor;
import com.omuraisu.minimap.mixin.SimpleRegionStorageAccessor;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// 次元ごとの、地図の地形の状態。チャンクの版（リージョンファイルのヘッダー・メモリの変化）、生成しているチャンク、生成の順番。
// メインスレッドでだけ触る（自前のスレッドの仕事は TerrainWorkers.run の後始末で結果を受け取る）
final class TerrainLevel {
	// メモリから外れたチャンクが書かれる（ヘッダーの版が変わる）のを待つ tick。保存を止めているサーバーでは書かれないので、いつまでも待たない。
	// 生成が続いて IO スレッドが混んでいると、書き終えるまでに数秒かかる
	private static final int SAVE_WAIT_TICKS = 600;
	// メモリの版を作った時刻を、メモリから外れた後もこれだけ（秒）覚えておく
	private static final int SEEN_SECONDS = 120;
	// これより使っていないヘッダーを捨てる。捨てる間隔も同じ
	static final int PRUNE_TICKS = 600;

	final ServerLevel level;
	private final TerrainWorkers workers;
	private RegionReader reader;
	private final Long2ObjectMap<HeaderEntry> headers = new Long2ObjectOpenHashMap<>();
	// 生成しているチャンク
	final LongSet generating = new LongOpenHashSet();
	// メモリにあるチャンクが最後に変わった時点（clock）。変わっていなければ、初めて見た時点
	private final Long2IntMap changed = new Long2IntOpenHashMap();
	// メモリにあるチャンクが変わるたびに増える、リージョンごとの数
	private final Long2IntOpenHashMap epochs = new Long2IntOpenHashMap();
	// セーブにあるが地物を置き終えていないチャンクと、読めなかったチャンクの、そのときの版。版が変わるまで読み直さない
	final Long2IntMap unready = new Long2IntOpenHashMap();
	final Long2IntMap failed = new Long2IntOpenHashMap();
	// vanilla が書く中身のあるチャンクを、メモリで最後に見た時刻（秒。リージョンファイルのヘッダーの書いた時刻と同じ時計）。
	// メモリから外れた後、ヘッダーの書いた時刻と比べて、その中身が書かれたかを見る（awaitsSave）
	private final Long2IntMap memorySeen = new Long2IntOpenHashMap();
	// メモリから外れたのに気づいた tick（awaitsSave）
	private final Long2IntMap leftSince = new Long2IntOpenHashMap();
	final MapPriority priority = new MapPriority();
	private final MapPriority.Queue queue;

	TerrainLevel(ServerLevel level, TerrainWorkers workers) {
		this.level = level;
		this.workers = workers;
		var dispatcher = ((ChunkMapInvoker) level.getChunkSource().chunkMap).minimap$worldgenTaskDispatcher();
		this.queue = (MapPriority.Queue) ((ChunkTaskDispatcherAccessor) dispatcher).minimap$queue();
		queue.minimap$setPriority(priority);
	}

	// メモリにあるチャンク。ワールドから外れて保存を待っている間も、セーブより新しいのでこちらを使う。
	// 生成の途中のチャンクも、地物を置き終えていれば使う
	static ChunkAccess inMemory(ServerLevel level, long chunk) {
		ChunkMapInvoker chunkMap = (ChunkMapInvoker) level.getChunkSource().chunkMap;
		ChunkHolder holder = chunkMap.minimap$getVisibleChunkIfPresent(chunk);
		if (holder == null) {
			holder = chunkMap.minimap$pendingUnloads().get(chunk);
		}
		ChunkAccess access = holder == null ? null : holder.getLatestChunk();
		return access != null && access.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES) ? access : null;
	}

	ChunkAccess inMemory(long chunk) {
		return inMemory(level, chunk);
	}

	RegionReader reader() {
		if (reader == null) {
			var worker = ((SimpleRegionStorageAccessor) level.getChunkSource().chunkMap).minimap$worker();
			var storage = ((IOWorkerAccessor) worker).minimap$storage();
			reader = new RegionReader(((RegionFileStorageAccessor) (Object) storage).minimap$folder());
		}
		return reader;
	}

	// 読んであるヘッダー。まだなら null。maxAge より古ければ自前のスレッドで読み直す（次の一巡から新しいものになる）
	RegionReader.Header header(long region, int now, int maxAge) {
		HeaderEntry entry = headers.computeIfAbsent(region, key -> new HeaderEntry());
		entry.used = now;
		if (!entry.loading && (entry.header == null || now - entry.loaded >= maxAge)) {
			entry.loading = true;
			RegionReader reader = reader();
			// 読めなくても（何で失敗しても）読んでいる印は外す。外さないと、そのリージョンのヘッダーを二度と読み直さない
			workers.run(() -> reader.header(ChunkPos.getX(region), ChunkPos.getZ(region)), (header, failure) -> {
				entry.loading = false;
				if (header != null) {
					accept(region, header, level.getServer().getTickCount());
				}
			});
		}
		return entry.header;
	}

	// 中身が同じなら前のものを使い続け、変わっていないリージョンを見分けられるようにする
	void accept(long region, RegionReader.Header header, int now) {
		HeaderEntry entry = headers.computeIfAbsent(region, key -> new HeaderEntry());
		entry.loaded = now;
		if (entry.header == null || !entry.header.sameAs(header)) {
			entry.header = header;
		}
	}

	// リージョンの中でメモリにあるチャンクが変わった数。一巡で変わっていないリージョンを飛ばすのに使う
	int epoch(long region) {
		return epochs.get(region);
	}

	// クライアントが持っている色はメモリにあったときのもので、チャンクはもうメモリにない。
	// vanilla が書く中身があったなら、書き終えるまでは、セーブにないか古い中身のままなので、ヘッダーの書いた時刻が
	// 最後にメモリで見た時刻以降になるまで待つ。待たないと、古い中身を送ったり、生成し終えたチャンクをもう一度生成しようとしたりする。
	// 書く中身を見ていなければ（読み込まれただけ・書かれない ImposterProtoChunk・時刻を覚えておく時間を過ぎた）、待っても書かれないので待たない。
	// 保存を止めているサーバーでは書かれないので、SAVE_WAIT_TICKS で待つのをやめる
	boolean awaitsSave(long chunk, int stamp, int now) {
		int seen = memorySeen.get(chunk);
		if (seen == 0 || stamp >= seen) {
			leftSince.remove(chunk);
			return false;
		}
		if (!leftSince.containsKey(chunk)) {
			leftSince.put(chunk, now);
			return true;
		}
		if (now - leftSince.get(chunk) < SAVE_WAIT_TICKS) {
			return true;
		}
		leftSince.remove(chunk);
		memorySeen.remove(chunk);
		return false;
	}

	// メモリにあるチャンクの版。保存してから変わっていなければ、セーブにあるものと同じ中身なので、その版にする
	// （読み込まれただけのチャンクを送り直さずに済む）。保存を始めてからヘッダーが書き換わるまでは古い版のままだが、
	// 書き換わったときに版が変わり、色が同じなら版だけを送り直す
	int stamp(ChunkAccess loaded, long chunk, int clock) {
		HeaderEntry entry = headers.get(TerrainRegions.key(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
		int saved = entry == null || entry.header == null ? 0 : entry.header.stamps()[TerrainRegions.index(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))];
		boolean unsaved = loaded instanceof ImposterProtoChunk imposter ? imposter.getWrapped().isUnsaved() : loaded.isUnsaved();
		if (saved != 0 && !unsaved) {
			return saved;
		}
		// vanilla がメモリから外すときに書くのは、チャンクそのものが書いていない中身を持つときだけ。ImposterProtoChunk は中身が
		// 変わっていても書かないので、書かれるのを待たない。ヘッダーをまだ読めていないだけなら、書いていない中身はない
		return loaded.isUnsaved() ? memoryStamp(chunk, clock) : clockStamp(chunk, clock);
	}

	// vanilla が書く中身のあるチャンクの版。メモリから外れた後に書かれるのを待てるよう、見た時刻を覚える
	int memoryStamp(long chunk, int clock) {
		memorySeen.put(chunk, seconds());
		return clockStamp(chunk, clock);
	}

	// メモリにあるチャンクの版。初めて見たチャンクには、そのときの時計を付ける。メモリから外れて戻ってきたチャンクは
	// 間に中身が変わっているかもしれないので、前と同じ版にならないようにするため（外れたチャンクの時計は prune で消える）
	private int clockStamp(long chunk, int clock) {
		int value = changed.get(chunk);
		if (value == 0) {
			value = clock;
			changed.put(chunk, value);
		}
		return -1 - value;
	}

	void touch(long chunk, int clock) {
		if (changed.put(chunk, clock) != clock) {
			epochs.addTo(TerrainRegions.key(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)), 1);
		}
	}

	void prune(int now) {
		changed.long2IntEntrySet().removeIf(entry -> inMemory(entry.getLongKey()) == null);
		int seconds = seconds();
		memorySeen.long2IntEntrySet().removeIf(entry -> seconds - entry.getIntValue() > SEEN_SECONDS && inMemory(entry.getLongKey()) == null);
		headers.long2ObjectEntrySet().removeIf(entry -> !entry.getValue().loading && now - entry.getValue().used >= PRUNE_TICKS);
		if (unready.size() > 1 << 18) {
			unready.clear();
		}
		if (failed.size() > 1 << 18) {
			failed.clear();
		}
		// 待っている間に範囲から外れたチャンクは、戻ってきたときに待ち直す
		leftSince.long2IntEntrySet().removeIf(entry -> now - entry.getIntValue() >= 2 * SAVE_WAIT_TICKS);
	}

	// リージョンファイルのヘッダーに書かれる時刻と同じ時計（RegionFile.getTimestamp）
	private static int seconds() {
		return (int) (Util.getEpochMillis() / 1000);
	}

	void close() {
		queue.minimap$setPriority(null);
		if (reader != null) {
			reader.close();
		}
	}

	private static final class HeaderEntry {
		private RegionReader.Header header;
		private boolean loading;
		private int loaded;
		private int used;
	}
}
