package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.logging.LogUtils;
import com.omuraisu.minimap.mixin.ChunkMapInvoker;
import com.omuraisu.minimap.mixin.ChunkTaskDispatcherAccessor;
import com.omuraisu.minimap.mixin.IOWorkerAccessor;
import com.omuraisu.minimap.mixin.RegionFileStorageAccessor;
import com.omuraisu.minimap.mixin.SimpleRegionStorageAccessor;

import org.slf4j.Logger;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// マップ画面に描画距離の外を出すため、頼まれたチャンクの地形の色を送り返す。地図のためにチャンクをワールドへ読み込まない。
// - メモリにあるチャンク: メインスレッドで色にする
// - セーブにあるチャンク: リージョンファイルを自前のスレッドで直接読み、色にする（vanilla の IO スレッドを使わない）
// - 未生成のチャンク: FEATURES まで生成する。同時に生成する数は、サーバーが予定より遅れていなければ増やし、遅れたら減らす。
//   地図だけのチャンクの生成は、vanilla の生成の列の一番後ろに回す（MapPriority）
// マップ画面を開いていない人の依頼（マップ距離）は、開いている人の依頼の後に、少しずつ進める
public final class TerrainServer {
	// プレイヤーのいるチャンクからこの距離（チャンク）までしか送らない。どの解像度でも一番引いた地図が収まる
	public static final int RADIUS = 192;
	// セーブを読んで色にするスレッドの数
	private static final int WORKERS = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 8);
	// 1 tick に読み始める数と、同時に読んでいる数（サーバー全体）。遅れている間は、読み終えた後の送る処理も減らすため少なくする
	private static final int READS_PER_TICK = 512;
	private static final int READS_PER_TICK_BEHIND = 32;
	private static final int MAX_READING = WORKERS * 128;
	// マップ画面を開いていない人の依頼（マップ距離）は、開いている人の後に回し、遊んでいる間の負担を小さくする。
	// 読み始めるのは 1 tick にこれまで、生成はサーバー全体で同時にこれまで。遅れている間は進めない
	private static final int BACKGROUND_READS_PER_TICK = 32;
	private static final int BACKGROUND_GENERATING = 4;
	// メモリにあるチャンクから色を出すのはメインスレッドなので、1 tick に使う時間を絞る
	private static final long LOADED_BUDGET_NANOS = 2_000_000;
	// 同時に生成する数。遅れていなければ 1 秒ごとに増やし、遅れたら半分にする
	private static final int MIN_GENERATING = 4;
	private static final int MAX_GENERATING = 256;
	private static final int GENERATING_STEP = 8;
	// 直近 20 tick にかかった時間が予定の 1.1 倍を超えるか（tick の合間の処理も含む）、tick がこれより重いか、
	// ヒープの使っている量が上限の 85% を超えたら絞る。vanilla は予定より 1 秒遅れると Can't keep up を出す
	private static final int PACE_TICKS = 20;
	private static final long SLOW_TICK_NANOS = 40_000_000;
	private static final int MEMORY_PERCENT = 85;
	// 答えたチャンクは、クライアントが次の依頼に反映するまで答え直さない
	private static final int ANSWER_TICKS = 40;
	// ワールドから外れてすぐのチャンクは、書く前のデータが vanilla の IO スレッドにあるので、そちらから読む
	private static final int UNLOAD_TICKS = 600;
	// 未生成と分かってからこの間は、生成の前にセーブを確かめ直さない
	private static final int MISSING_TICKS = 1200;
	// 生成し終えてから 1 秒で外れる。登録はしない（保存されない札なので名前は要らない）
	private static final TicketType TICKET = new TicketType(20, TicketType.FLAG_LOADING);

	private static final Logger LOGGER = LogUtils.getLogger();

	private static TerrainServer current;

	private final MinecraftServer server;
	private final Map<UUID, Viewer> viewers = new HashMap<>();
	private final Map<ResourceKey<Level>, LevelState> levels = new HashMap<>();
	private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS, new WorkerFactory());
	private int reading;
	// 生成の前にセーブを確かめている分も数える
	private int generating;
	// そのうち、マップ画面を開いていない人のために始めた数
	private int backgroundGenerating;
	private int generationLimit = 16;
	private int lastThrottle = Integer.MIN_VALUE / 2;
	private final long[] tickTimes = new long[PACE_TICKS];
	private boolean failureLogged;
	// セーブを読んだ数のうち、vanilla の経路で読んだ数。テストから確かめる
	private final AtomicInteger reads = new AtomicInteger();
	private final AtomicInteger vanillaReads = new AtomicInteger();

	private TerrainServer(MinecraftServer server) {
		this.server = server;
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> current = new TerrainServer(server));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (current != null && current.server == server) {
				current.close();
				current = null;
			}
		});
		// 専用サーバーで無効にしているときは受け口を作らず、クライアントには手元のチャンクだけを出させる。
		// シングルプレイは途中で切り替えられるので、受け口は作っておいて届いた時点で確かめる
		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			if (Minimap.isActive() || !server.isDedicatedServer()) {
				ServerPlayNetworking.registerReceiver(handler, TerrainRequest.TYPE, (payload, context) -> {
					if (current != null) {
						current.request(context.player(), payload);
					}
				});
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (current != null && current.server == server) {
				current.tick();
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (current != null) {
				current.viewers.remove(handler.player.getUUID());
			}
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (current != null && current.server == level.getServer()) {
				current.state(level).unloaded.put(chunk.getPos().pack(), level.getServer().getTickCount());
			}
		});
	}

	// 動いているサーバーで、セーブを読んだ数と、そのうち vanilla の経路で読んだ数
	public static int[] readCounts() {
		TerrainServer server = current;
		return server == null ? new int[2] : new int[] {server.reads.get(), server.vanillaReads.get()};
	}

	public static boolean isInRange(ChunkPos center, int x, int z) {
		return Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) <= RADIUS;
	}

	private void close() {
		workers.shutdownNow();
		levels.values().forEach(LevelState::close);
	}

	private void request(ServerPlayer player, TerrainRequest payload) {
		if (!Minimap.isActive()) {
			return;
		}
		Viewer viewer = viewers.computeIfAbsent(player.getUUID(), id -> new Viewer());
		viewer.read = toArray(payload.read());
		viewer.generate = toArray(payload.generate());
		viewer.background = payload.background();
	}

	private void tick() {
		if (!Minimap.isActive()) {
			viewers.values().forEach(Viewer::cancel);
			levels.values().forEach(state -> state.priority.clear());
			return;
		}
		int now = server.getTickCount();
		for (LevelState state : levels.values()) {
			state.prune(now);
		}
		long time = Util.getNanos();
		long previous = tickTimes[now % PACE_TICKS];
		tickTimes[now % PACE_TICKS] = time;
		long expected = PACE_TICKS * server.tickRateManager().nanosecondsPerTick();
		Runtime runtime = Runtime.getRuntime();
		boolean lowMemory = (runtime.totalMemory() - runtime.freeMemory()) / MEMORY_PERCENT > runtime.maxMemory() / 100;
		boolean behind = previous != 0 && (time - previous) * 10 > expected * 11 || server.getAverageTickTimeNanos() > SLOW_TICK_NANOS || lowMemory;
		adjustGenerationLimit(behind, now);

		List<Viewer> open = new ArrayList<>();
		List<ServerPlayer> openPlayers = new ArrayList<>();
		List<Viewer> background = new ArrayList<>();
		List<ServerPlayer> backgroundPlayers = new ArrayList<>();
		for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Viewer viewer = entry.getValue();
			if (player == null) {
				continue;
			}
			viewer.prune(now);
			if (viewer.read.length > 0 || viewer.generate.length > 0) {
				(viewer.background ? background : open).add(viewer);
				(viewer.background ? backgroundPlayers : openPlayers).add(player);
			}
		}
		long deadline = System.nanoTime() + LOADED_BUDGET_NANOS;
		int reads = Math.max(0, Math.min(behind ? READS_PER_TICK_BEHIND : READS_PER_TICK, MAX_READING - reading));
		reads -= serve(open, openPlayers, now, deadline, behind, reads);
		if (!behind) {
			serve(background, backgroundPlayers, now, deadline, false, Math.min(reads, BACKGROUND_READS_PER_TICK));
		}
		for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				entry.getValue().flush(player);
			}
		}
		// 生成の列で地図だけのチャンクを後ろに回すため、プレイヤーの位置と生成しているチャンクを写す
		for (LevelState state : levels.values()) {
			state.priority.update(state.level, state.generating);
		}
	}

	// 見ている人が何人いても順に先を譲る。読み始めた数を返す
	private int serve(List<Viewer> viewers, List<ServerPlayer> players, int now, long deadline, boolean behind, int reads) {
		int share = viewers.isEmpty() ? 0 : Math.max(1, (reads + viewers.size() - 1) / viewers.size());
		int started = 0;
		for (int i = 0; i < viewers.size(); i++) {
			int index = (i + now) % viewers.size();
			started += process(viewers.get(index), players.get(index), now, deadline, behind, Math.min(share, reads - started));
		}
		return started;
	}

	// 遅れていなければ 1 秒ごとに増やし（生成する数が上限に届いているときだけ）、遅れたら半分にする
	private void adjustGenerationLimit(boolean behind, int now) {
		if (behind) {
			if (now - lastThrottle >= 20) {
				generationLimit = Math.max(MIN_GENERATING, generationLimit / 2);
				lastThrottle = now;
			}
		} else if (now % 20 == 0 && generating >= generationLimit && now - lastThrottle >= 100) {
			generationLimit = Math.min(MAX_GENERATING, generationLimit + GENERATING_STEP);
		}
	}

	// 頼まれた順に、メモリにあるものはすぐ、セーブにあるものは読んで、未生成のものは生成して送る。
	// 生成は読む方とは別に数え、読む列が長くても止まらないようにする。セーブを読み始めた数を返す（生成の前の確かめは数えない）
	private int process(Viewer viewer, ServerPlayer player, int now, long deadline, boolean behind, int reads) {
		ServerLevel level = player.level();
		if (viewer.level != level.dimension()) {
			viewer.changeLevel(level.dimension());
		}
		LevelState state = state(level);
		ChunkPos center = player.chunkPosition();
		for (long chunk : viewer.generate) {
			if (!viewer.isWaiting(chunk, center, now) || state.generating.contains(chunk)) {
				continue;
			}
			ChunkAccess loaded = inMemory(level, chunk);
			if (loaded != null) {
				if (System.nanoTime() < deadline) {
					sendLoaded(viewer, player, loaded, now);
				}
			} else if (!behind && generating < generationLimit && (!viewer.background || backgroundGenerating < BACKGROUND_GENERATING)) {
				boolean background = viewer.background;
				startGeneration(background);
				if (state.isKnownMissing(chunk, now)) {
					generate(state, chunk, background);
				} else {
					// 他のプレイヤーが生成したかもしれないので、先にセーブを確かめる
					read(viewer, player, state, chunk, true, background);
				}
			}
		}
		int started = 0;
		for (long chunk : viewer.read) {
			if (!viewer.isWaiting(chunk, center, now)) {
				continue;
			}
			ChunkAccess loaded = inMemory(level, chunk);
			if (loaded != null) {
				if (System.nanoTime() < deadline) {
					sendLoaded(viewer, player, loaded, now);
				}
			} else if (started < reads) {
				read(viewer, player, state, chunk, false, false);
				started++;
			}
		}
		return started;
	}

	// 生成の前にセーブを確かめている間も数える。background はマップ画面を開いていない人のために始めたか
	private void startGeneration(boolean background) {
		generating++;
		if (background) {
			backgroundGenerating++;
		}
	}

	private void endGeneration(boolean background) {
		generating--;
		if (background) {
			backgroundGenerating--;
		}
	}

	// メモリにあるチャンク。ワールドから外れて保存を待っている間も、セーブより新しいのでこちらを使う。
	// 生成の途中のチャンクも、地物を置き終えていれば使う
	private static ChunkAccess inMemory(ServerLevel level, long chunk) {
		ChunkMapInvoker chunkMap = (ChunkMapInvoker) level.getChunkSource().chunkMap;
		ChunkHolder holder = chunkMap.minimap$getVisibleChunkIfPresent(chunk);
		if (holder == null) {
			holder = chunkMap.minimap$pendingUnloads().get(chunk);
		}
		ChunkAccess access = holder == null ? null : holder.getLatestChunk();
		return access != null && access.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES) ? access : null;
	}

	private void sendLoaded(Viewer viewer, ServerPlayer player, ChunkAccess chunk, int now) {
		ServerLevel level = player.level();
		viewer.queue(TerrainColors.tile(chunk.getPos(), TerrainColors.of(chunk), level.dimensionType().hasCeiling(), player.getBlockY()), now);
	}

	// 読むのも色にするのも自前のスレッド。メインスレッドは結果を送るだけ。
	// ワールドから外れてすぐのチャンクと、直接は読めなかったチャンクは vanilla の経路（IO スレッド）で読む
	private void read(Viewer viewer, ServerPlayer player, LevelState state, long chunk, boolean generate, boolean background) {
		ServerLevel level = state.level;
		ChunkPos pos = ChunkPos.unpack(chunk);
		ResourceKey<Level> dimension = level.dimension();
		boolean ceiling = level.dimensionType().hasCeiling();
		int feetY = player.getBlockY();
		UUID id = player.getUUID();
		viewer.pending.add(chunk);
		reading++;
		reads.incrementAndGet();
		CompletableFuture<CompoundTag> tag;
		if (state.isRecentlyUnloaded(chunk, server.getTickCount())) {
			tag = readByVanilla(level, pos);
		} else {
			RegionReader reader = state.reader();
			tag = CompletableFuture.supplyAsync(() -> reader.read(pos), workers)
					.thenCompose(read -> read != null ? CompletableFuture.completedFuture(read) : readByVanilla(level, pos));
		}
		tag.thenApplyAsync(data -> data == RegionReader.ABSENT ? null
						: Optional.ofNullable(StoredChunk.read(level, data)).map(blocks -> TerrainColors.tile(pos, blocks, ceiling, feetY)).orElse(null), workers)
				.whenComplete((tile, error) -> server.execute(() -> finishRead(viewer, id, dimension, chunk, tile, error, generate, background)));
	}

	// 古い版のデータは読み込むときと同じく新しい形に直る
	private CompletableFuture<CompoundTag> readByVanilla(ServerLevel level, ChunkPos pos) {
		vanillaReads.incrementAndGet();
		return ((ChunkMapInvoker) level.getChunkSource().chunkMap).minimap$readChunk(pos).thenApply(tag -> tag.orElse(RegionReader.ABSENT));
	}

	private void finishRead(Viewer viewer, UUID id, ResourceKey<Level> dimension, long chunk, TerrainTile tile, Throwable error,
			boolean generate, boolean background) {
		if (current != this) {
			return;
		}
		reading--;
		viewer.pending.remove(chunk);
		boolean generationStarted = false;
		try {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (viewers.get(id) != viewer || player == null || player.level().dimension() != dimension || !Minimap.isActive()) {
				return;
			}
			int now = server.getTickCount();
			ServerLevel level = player.level();
			LevelState state = state(level);
			if (error != null) {
				if (!failureLogged) {
					failureLogged = true;
					LOGGER.warn("Failed to read terrain of chunk {}", ChunkPos.unpack(chunk), error);
				}
				viewer.answered.put(chunk, now);
			} else if (inMemory(level, chunk) != null) {
				// 読んでいる間に読み込まれた。セーブより新しいかもしれないので、次の tick にメモリの方から送る
			} else if (tile != null) {
				viewer.queue(tile, now);
			} else {
				state.missing.put(chunk, now);
				if (generate) {
					if (isInRange(player.chunkPosition(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
						generate(state, chunk, background);
						generationStarted = true;
					}
				} else {
					viewer.missing.add(chunk);
					viewer.answered.put(chunk, now);
				}
			}
		} finally {
			if (generate && !generationStarted) {
				endGeneration(background);
			}
		}
	}

	// 地図に要るのは地形と地物だけなので、光の計算やエンティティの読み込みをする FULL までは進めない（FEATURES のまま保存される）。
	// getChunkFuture だけでは 1 tick で札が外れ、生成に時間がかかるチャンクは途中で捨てられるので、生成し終えるまで外れない札を付ける。
	// getChunkFuture はメインスレッドから呼ぶと生成し終えるまで待つので、自前のスレッドから呼ぶ（メインスレッドに頼むだけになる）
	private void generate(LevelState state, long chunk, boolean background) {
		state.generating.add(chunk);
		ChunkPos pos = ChunkPos.unpack(chunk);
		ServerChunkCache source = state.level.getChunkSource();
		source.addTicket(new Ticket(TICKET, ChunkLevel.byStatus(ChunkStatus.FEATURES)), pos);
		CompletableFuture.supplyAsync(() -> source.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FEATURES, true), workers)
				.thenCompose(future -> future)
				.whenComplete((result, error) -> server.execute(() -> {
					if (current != this) {
						return;
					}
					endGeneration(background);
					state.generating.remove(chunk);
					ChunkAccess generated = result == null ? null : result.orElse(null);
					if (generated != null && Minimap.isActive()) {
						state.missing.remove(chunk);
						sendGenerated(state.level, generated);
					}
				}));
	}

	// 頼んでいるプレイヤーに送る。色にするのは自前のスレッド
	private void sendGenerated(ServerLevel level, ChunkAccess chunk) {
		long key = chunk.getPos().pack();
		boolean ceiling = level.dimensionType().hasCeiling();
		for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Viewer viewer = entry.getValue();
			if (player == null || player.level() != level || !viewer.wants(key)) {
				continue;
			}
			int feetY = player.getBlockY();
			UUID id = entry.getKey();
			CompletableFuture.supplyAsync(() -> TerrainColors.tile(chunk.getPos(), TerrainColors.of(chunk), ceiling, feetY), workers)
					.whenComplete((tile, error) -> server.execute(() -> {
						if (current == this && tile != null && viewers.get(id) == viewer && viewer.level == level.dimension()) {
							viewer.queue(tile, server.getTickCount());
						}
					}));
		}
	}

	private LevelState state(ServerLevel level) {
		return levels.computeIfAbsent(level.dimension(), key -> new LevelState(level));
	}

	private static long[] toArray(List<Long> chunks) {
		long[] array = new long[chunks.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = chunks.get(i);
		}
		return array;
	}

	private static final class LevelState {
		private final ServerLevel level;
		private RegionReader reader;
		// 生成しているチャンク
		private final LongSet generating = new LongOpenHashSet();
		// ワールドから外れた tick
		private final Long2IntMap unloaded = new Long2IntOpenHashMap();
		// 未生成と分かった tick
		private final Long2IntMap missing = new Long2IntOpenHashMap();
		private final MapPriority priority = new MapPriority();
		private final MapPriority.Queue queue;

		private LevelState(ServerLevel level) {
			this.level = level;
			var dispatcher = ((ChunkMapInvoker) level.getChunkSource().chunkMap).minimap$worldgenTaskDispatcher();
			this.queue = (MapPriority.Queue) ((ChunkTaskDispatcherAccessor) dispatcher).minimap$queue();
			queue.minimap$setPriority(priority);
		}

		private RegionReader reader() {
			if (reader == null) {
				var worker = ((SimpleRegionStorageAccessor) level.getChunkSource().chunkMap).minimap$worker();
				var storage = ((IOWorkerAccessor) worker).minimap$storage();
				reader = new RegionReader(((RegionFileStorageAccessor) (Object) storage).minimap$folder());
			}
			return reader;
		}

		private boolean isRecentlyUnloaded(long chunk, int now) {
			return unloaded.containsKey(chunk) && now - unloaded.get(chunk) < UNLOAD_TICKS;
		}

		private boolean isKnownMissing(long chunk, int now) {
			return missing.containsKey(chunk) && now - missing.get(chunk) < MISSING_TICKS;
		}

		private void prune(int now) {
			if (now % 200 == 0) {
				unloaded.long2IntEntrySet().removeIf(entry -> now - entry.getIntValue() >= UNLOAD_TICKS);
				missing.long2IntEntrySet().removeIf(entry -> now - entry.getIntValue() >= MISSING_TICKS);
			}
		}

		private void close() {
			queue.minimap$setPriority(null);
			if (reader != null) {
				reader.close();
			}
		}
	}

	private static final class Viewer {
		private static final long[] NONE = new long[0];

		private long[] read = NONE;
		private long[] generate = NONE;
		// マップ画面を開いていない（マップ距離の分を頼んでいる）
		private boolean background;
		private ResourceKey<Level> level;
		// セーブを読んでいるチャンク
		private final LongSet pending = new LongOpenHashSet();
		// 答えた tick
		private final Long2IntMap answered = new Long2IntOpenHashMap();
		// まとめて送る色と未生成のチャンク
		private final List<TerrainTile> tiles = new ArrayList<>();
		private final LongList missing = new LongArrayList();

		private boolean wants(long chunk) {
			for (long wanted : generate) {
				if (wanted == chunk) {
					return true;
				}
			}
			for (long wanted : read) {
				if (wanted == chunk) {
					return true;
				}
			}
			return false;
		}

		private boolean isWaiting(long chunk, ChunkPos center, int now) {
			if (pending.contains(chunk) || !isInRange(center, ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
				return false;
			}
			return !answered.containsKey(chunk) || now - answered.get(chunk) >= ANSWER_TICKS;
		}

		private void queue(TerrainTile tile, int now) {
			answered.put(tile.chunk(), now);
			tiles.add(tile);
		}

		private void changeLevel(ResourceKey<Level> newLevel) {
			level = newLevel;
			answered.clear();
			tiles.clear();
			missing.clear();
		}

		private void prune(int now) {
			if (now % ANSWER_TICKS == 0) {
				answered.long2IntEntrySet().removeIf(entry -> now - entry.getIntValue() >= ANSWER_TICKS);
			}
		}

		private void flush(ServerPlayer player) {
			if (level != null && !tiles.isEmpty() && ServerPlayNetworking.canSend(player, TerrainTiles.TYPE)) {
				for (int from = 0; from < tiles.size(); from += TerrainTiles.MAX_TILES) {
					ServerPlayNetworking.send(player, new TerrainTiles(level.identifier(),
							new ArrayList<>(tiles.subList(from, Math.min(tiles.size(), from + TerrainTiles.MAX_TILES)))));
				}
			}
			tiles.clear();
			if (level != null && !missing.isEmpty() && ServerPlayNetworking.canSend(player, TerrainMissing.TYPE)) {
				for (int from = 0; from < missing.size(); from += TerrainRequest.MAX_READ) {
					List<Long> chunks = new ArrayList<>(missing.subList(from, Math.min(missing.size(), from + TerrainRequest.MAX_READ)));
					ServerPlayNetworking.send(player, new TerrainMissing(level.identifier(), chunks));
				}
			}
			missing.clear();
		}

		private void cancel() {
			read = NONE;
			generate = NONE;
			tiles.clear();
			missing.clear();
		}
	}

	private static final class WorkerFactory implements ThreadFactory {
		private final AtomicInteger count = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "Minimap Terrain #" + count.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		}
	}
}
