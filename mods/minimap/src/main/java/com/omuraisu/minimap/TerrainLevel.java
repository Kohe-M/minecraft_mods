package com.omuraisu.minimap;

import com.omuraisu.minimap.mixin.ChunkMapInvoker;
import com.omuraisu.minimap.mixin.ChunkTaskDispatcherAccessor;
import com.omuraisu.minimap.mixin.IOWorkerAccessor;
import com.omuraisu.minimap.mixin.RegionFileStorageAccessor;
import com.omuraisu.minimap.mixin.SimpleRegionStorageAccessor;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// 次元ごとの、地図の地形の状態。チャンクの版（リージョンファイルのヘッダー・メモリの変化）、下書き、生成しているチャンク、生成の順番。
// メインスレッドでだけ触る（自前のスレッドの仕事は TerrainWorkers.run の後始末で結果を受け取る）
final class TerrainLevel {
	// メモリから外れたチャンクが書かれる（ヘッダーの版が変わる）のを待つ tick。保存を止めているサーバーでは書かれないので、いつまでも待たない
	private static final int SAVE_WAIT_TICKS = 40;
	// これより使っていないヘッダーを捨てる。捨てる間隔も同じ
	static final int PRUNE_TICKS = 600;
	// 覚えておく下書きの数（1 つ 16 KiB）
	private static final int PREVIEW_CACHE = 256;

	final ServerLevel level;
	private final TerrainWorkers workers;
	private RegionReader reader;
	// 下書きを作れない次元なら null
	TerrainPreviewer previewer;
	// 作った下書き（古い順）と、作っているリージョン
	private final Long2ObjectLinkedOpenHashMap<byte[]> previews = new Long2ObjectLinkedOpenHashMap<>();
	final LongSet previewing = new LongOpenHashSet();
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
	// メモリから外れたのに気づいた tick と、そのときのヘッダーの版（awaitsSave）
	private final Long2IntMap leftSince = new Long2IntOpenHashMap();
	private final Long2IntMap leftStamp = new Long2IntOpenHashMap();
	final MapPriority priority = new MapPriority();
	private final MapPriority.Queue queue;

	TerrainLevel(ServerLevel level, TerrainWorkers workers) {
		this.level = level;
		this.workers = workers;
		this.previewer = TerrainPreviewer.create(level);
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

	// 作ってある下書き。あれば新しいものとして扱う
	byte[] preview(long region) {
		return previews.getAndMoveToLast(region);
	}

	void rememberPreview(long region, byte[] colors) {
		previews.putAndMoveToLast(region, colors);
		if (previews.size() > PREVIEW_CACHE) {
			previews.removeFirst();
		}
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
	// vanilla が書き終えるまでは、セーブにないか古い中身のままなので、ヘッダーの版が変わるのを少し待つ。
	// 待たないと、古い中身を送ったり、生成し終えたチャンクをもう一度生成しようとしたりする
	boolean awaitsSave(long chunk, int stamp, int now) {
		if (!leftSince.containsKey(chunk)) {
			leftSince.put(chunk, now);
			leftStamp.put(chunk, stamp);
			return true;
		}
		if (leftStamp.get(chunk) == stamp && now - leftSince.get(chunk) < SAVE_WAIT_TICKS) {
			return true;
		}
		leftSince.remove(chunk);
		leftStamp.remove(chunk);
		return false;
	}

	// メモリにあるチャンクの版。保存してから変わっていなければ、セーブにあるものと同じ中身なので、その版にする
	// （読み込まれただけのチャンクを送り直さずに済む）。保存を始めてからヘッダーが書き換わるまでは古い版のままだが、
	// 書き換わったときに版が変わり、色が同じなら版だけを送り直す
	int stamp(ChunkAccess loaded, long chunk, int clock) {
		HeaderEntry entry = headers.get(TerrainRegions.key(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
		int saved = entry == null || entry.header == null ? 0 : entry.header.stamps()[TerrainRegions.index(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))];
		boolean unsaved = loaded instanceof ImposterProtoChunk imposter ? imposter.getWrapped().isUnsaved() : loaded.isUnsaved();
		return saved != 0 && !unsaved ? saved : memoryStamp(chunk, clock);
	}

	// まだ保存していないチャンクの版。初めて見たチャンクには、そのときの時計を付ける。メモリから外れて戻ってきたチャンクは
	// 間に中身が変わっているかもしれないので、前と同じ版にならないようにするため（外れたチャンクの時計は prune で消える）
	int memoryStamp(long chunk, int clock) {
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
		headers.long2ObjectEntrySet().removeIf(entry -> !entry.getValue().loading && now - entry.getValue().used >= PRUNE_TICKS);
		if (unready.size() > 1 << 18) {
			unready.clear();
		}
		if (failed.size() > 1 << 18) {
			failed.clear();
		}
		// 待っている間に範囲から外れたチャンクは、戻ってきたときに待ち直す
		leftSince.long2IntEntrySet().removeIf(entry -> {
			if (now - entry.getIntValue() < 2 * SAVE_WAIT_TICKS) {
				return false;
			}
			leftStamp.remove(entry.getLongKey());
			return true;
		});
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
