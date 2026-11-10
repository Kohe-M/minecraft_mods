package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.logging.LogUtils;
import com.omuraisu.minimap.mixin.ChunkMapInvoker;
import com.omuraisu.minimap.mixin.ChunkTaskDispatcherAccessor;
import com.omuraisu.minimap.mixin.IOWorkerAccessor;
import com.omuraisu.minimap.mixin.RegionFileStorageAccessor;
import com.omuraisu.minimap.mixin.SimpleRegionStorageAccessor;

import org.slf4j.Logger;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// マップ画面に描画距離の外を出すため、地形の色を送る。地図のためにチャンクをワールドへ読み込まない。
// クライアントは見ている範囲（TerrainView）と、リージョンごとに持っている色の版（TerrainHave）を知らせるだけで、
// 何を送るかはサーバーが決める。チャンクの今の版と、クライアントが持っている版が違うものだけを、範囲の中心に近い順に送る。
// - メモリにあるチャンク: 保存してから変わっていなければ、版はセーブにあるものと同じ。変わっていれば、最後に変わった時点。
//   ワールドに読み込まれたものはメインスレッドで、生成の途中のものは自前のスレッドで色にする
// - セーブにあるチャンク: 版はリージョンファイルのヘッダーにある書いた時刻。ヘッダーを読むだけで、リージョン 1 つ分の有無と版が分かる。
//   版が違うチャンクだけ、リージョンファイルを自前のスレッドで直接読んで色にする（vanilla の IO スレッドを使わない）
// - 未生成のチャンク: まず下書き（TerrainPreviewer）をリージョンごとに送って地図を埋め、それから FEATURES まで生成する。
//   同時に生成する数は、サーバーが予定より遅れていなければ増やし、遅れたら減らす。
//   地図だけのチャンクの生成は、vanilla の生成の列の一番後ろに回す（MapPriority）
// マップ画面を開いていない人の範囲（マップ距離）は、開いている人の後に、少しずつ進める
public final class TerrainServer {
	// プレイヤーのいるチャンクからこの距離（チャンク）までしか送らない。どの解像度でも一番引いた地図が収まる
	public static final int RADIUS = 192;
	// セーブを読んで色にするスレッドの数
	private static final int WORKERS = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 8);
	// 1 tick に読み始める数と、同時に読んでいる数（サーバー全体）。遅れている間は、読み終えた後の送る処理も減らすため少なくする
	private static final int READS_PER_TICK = 1024;
	private static final int READS_PER_TICK_BEHIND = 32;
	private static final int MAX_READING = WORKERS * 256;
	// 同じリージョンのチャンクは、この数ずつまとめて 1 つの仕事にする（ヘッダーを読むのが 1 回で済む）
	private static final int READ_BATCH = 64;
	// マップ画面を開いていない人の範囲（マップ距離）は、開いている人の後に回し、遊んでいる間の負担を小さくする。
	// 読み始めるのは 1 tick にこれまで、生成はサーバー全体で同時にこれまで。遅れている間は進めない
	private static final int BACKGROUND_READS_PER_TICK = 32;
	private static final int BACKGROUND_GENERATING = 4;
	// ワールドに読み込まれたチャンクから色を出すのはメインスレッドなので、1 tick に使う時間を絞る
	private static final long LOADED_BUDGET_NANOS = 2_000_000;
	// 範囲を一巡して版を比べる。1 tick に比べるチャンクの数と、一巡し終えてから次に始めるまでの tick。
	// 前の一巡から何も変わっていないリージョンは飛ばすので、送り終えた後の一巡は軽い
	private static final int SCAN_PER_TICK = 8192;
	private static final int PASS_TICKS = 20;
	private static final int BACKGROUND_PASS_TICKS = 40;
	// 一巡の始めに、これより古いヘッダーを読み直す
	private static final int HEADER_TICKS = 20;
	// 生成を始められなかった未生成のチャンクを、次の一巡までの間に空きができたら始められるよう覚えておく数
	private static final int CANDIDATES = 2048;
	// 同時に生成する数。遅れていなければ 1 秒ごとに増やし、遅れたら半分にする
	private static final int MIN_GENERATING = 4;
	private static final int MAX_GENERATING = 256;
	private static final int GENERATING_STEP = 8;
	// 直近 20 tick にかかった時間が予定の 1.1 倍を超えるか（tick の合間の処理も含む）、tick がこれより重いか、
	// ヒープの使っている量が上限の 85% を超えたら絞る。vanilla は予定より 1 秒遅れると Can't keep up を出す
	private static final int PACE_TICKS = 20;
	private static final long SLOW_TICK_NANOS = 40_000_000;
	private static final int MEMORY_PERCENT = 85;
	// 1 人が知らせられるリージョンの数。クライアントは離れたリージョンを捨てて知らせるので、ふつうは 300 に届かない
	private static final int MAX_REGIONS = 4096;
	// 覚えておく下書きの数（1 つ 16 KiB）
	private static final int PREVIEW_CACHE = 256;
	// 天井のある次元は足元の高さから下を映すので、これだけ上下に動いたら送り直す
	private static final int FEET_STEP = 16;
	// メモリから外れたチャンクが書かれる（ヘッダーの版が変わる）のを待つ tick。保存を止めているサーバーでは書かれないので、いつまでも待たない
	private static final int SAVE_WAIT_TICKS = 40;
	private static final int PRUNE_TICKS = 600;
	// 生成し終えてから 1 秒で外れる。登録はしない（保存されない札なので名前は要らない）
	private static final TicketType TICKET = new TicketType(20, TicketType.FLAG_LOADING);

	private static final TerrainTile UNREADY = new TerrainTile(0, 0, null, null, null);

	private static final Logger LOGGER = LogUtils.getLogger();

	private static TerrainServer current;

	private final MinecraftServer server;
	private final Map<UUID, Viewer> viewers = new HashMap<>();
	private final Map<ResourceKey<Level>, LevelState> levels = new HashMap<>();
	private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS, new WorkerFactory());
	// 自前のスレッドの仕事の結果。メインスレッドが tick ごとに受け取る
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
	// メモリにあるチャンクの版に使う時計。tick の終わりに色を作った後で進めるので、その後の変化は必ず新しい版になる
	private int clock = 1;
	private int reading;
	private int generating;
	// そのうち、マップ画面を開いていない人のために始めた数
	private int backgroundGenerating;
	private int generationLimit = 16;
	private int lastThrottle = Integer.MIN_VALUE / 2;
	// 同時に生成する数の上限のせいで、生成を始められなかった
	private boolean limited;
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
		// シングルプレイは途中で切り替えられるので、受け口は作っておいて tick で確かめる
		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			if (Minimap.isActive() || !server.isDedicatedServer()) {
				ServerPlayNetworking.registerReceiver(handler, TerrainView.TYPE, (payload, context) -> {
					if (current != null) {
						current.view(context.player(), payload);
					}
				});
				ServerPlayNetworking.registerReceiver(handler, TerrainHave.TYPE, (payload, context) -> {
					if (current != null) {
						current.have(context.player(), payload);
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
	}

	// 動いているサーバーで、セーブを読んだ数と、そのうち vanilla の経路で読んだ数
	public static int[] readCounts() {
		TerrainServer server = current;
		return server == null ? new int[2] : new int[] {server.reads.get(), server.vanillaReads.get()};
	}

	public static boolean isInRange(ChunkPos center, int x, int z) {
		return Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) <= RADIUS;
	}

	// ワールドに読み込まれているチャンクのブロックが変わった（LevelChunkMixin）。メインスレッドから呼ばれる
	public static void blockChanged(ServerLevel level, BlockPos pos) {
		if (current != null && current.server == level.getServer()) {
			current.touch(level, ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
		}
	}

	// 誰も地図を見ていない次元では何もしない
	private void touch(ServerLevel level, long chunk) {
		LevelState state = levels.get(level.dimension());
		if (state != null) {
			state.touch(chunk, clock);
		}
	}

	private void close() {
		workers.shutdownNow();
		levels.values().forEach(LevelState::close);
	}

	private void view(ServerPlayer player, TerrainView payload) {
		Viewer viewer = viewers.computeIfAbsent(player.getUUID(), id -> new Viewer());
		viewer.enter(payload.dimension());
		viewer.candidates.clear();
		viewer.active = !payload.isEmpty();
		viewer.minX = payload.minX();
		viewer.minZ = payload.minZ();
		viewer.maxX = payload.maxX();
		viewer.maxZ = payload.maxZ();
		viewer.centerX = payload.centerX();
		viewer.centerZ = payload.centerZ();
		viewer.background = payload.background();
		viewer.changed = true;
	}

	private void have(ServerPlayer player, TerrainHave payload) {
		Viewer viewer = viewers.computeIfAbsent(player.getUUID(), id -> new Viewer());
		viewer.enter(payload.dimension());
		long region = ChunkPos.pack(payload.regionX(), payload.regionZ());
		if (payload.forgotten()) {
			viewer.regions.remove(region);
		} else if (viewer.regions.containsKey(region) || viewer.regions.size() < MAX_REGIONS) {
			viewer.regions.put(region, new RegionView(payload.regionX(), payload.regionZ(), payload.stamps()));
		}
		viewer.changed = true;
	}

	private void tick() {
		for (Runnable result = results.poll(); result != null; result = results.poll()) {
			result.run();
		}
		if (!Minimap.isActive()) {
			levels.values().forEach(state -> state.priority.clear());
			return;
		}
		int now = server.getTickCount();
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
			// クライアントが次元を移ったことを知らせてくるまでは、前の次元の範囲のままなので進めない
			if (player != null && viewer.active && player.level().dimension().identifier().equals(viewer.dimension)) {
				(viewer.background ? background : open).add(viewer);
				(viewer.background ? backgroundPlayers : openPlayers).add(player);
			}
		}
		long deadline = System.nanoTime() + LOADED_BUDGET_NANOS;
		int budget = Math.max(0, Math.min(behind ? READS_PER_TICK_BEHIND : READS_PER_TICK, MAX_READING - reading));
		budget -= serve(open, openPlayers, now, deadline, behind, budget);
		if (!behind) {
			serve(background, backgroundPlayers, now, deadline, false, Math.min(budget, BACKGROUND_READS_PER_TICK));
		}
		for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				entry.getValue().flush(player);
			}
		}
		for (LevelState state : levels.values()) {
			// 生成の列で地図だけのチャンクを後ろに回すため、プレイヤーの位置と生成しているチャンクを写す
			state.priority.update(state.level, state.generating);
			if (now % PRUNE_TICKS == 0) {
				state.prune(now);
			}
		}
		clock++;
	}

	// 見ている人が何人いても順に先を譲る。読み始めた数を返す
	private int serve(List<Viewer> viewers, List<ServerPlayer> players, int now, long deadline, boolean behind, int budget) {
		int share = viewers.isEmpty() ? 0 : Math.max(1, (budget + viewers.size() - 1) / viewers.size());
		int started = 0;
		for (int i = 0; i < viewers.size(); i++) {
			int index = (i + now) % viewers.size();
			started += serve(viewers.get(index), players.get(index), now, deadline, behind, Math.max(0, Math.min(share, budget - started)));
		}
		return started;
	}

	// 遅れていなければ 1 秒ごとに増やし（上限のせいで生成を始められなかったときだけ）、遅れたら半分にする
	private void adjustGenerationLimit(boolean behind, int now) {
		if (behind) {
			if (now - lastThrottle >= 20) {
				generationLimit = Math.max(MIN_GENERATING, generationLimit / 2);
				lastThrottle = now;
			}
		} else if (now % 20 == 0 && now - lastThrottle >= 100) {
			if (limited) {
				generationLimit = Math.min(MAX_GENERATING, generationLimit + GENERATING_STEP);
			}
			limited = false;
		}
	}

	private int serve(Viewer viewer, ServerPlayer player, int now, long deadline, boolean behind, int budget) {
		ServerLevel level = player.level();
		LevelState state = state(level);
		ChunkPos center = player.chunkPosition();
		boolean ceiling = level.dimensionType().hasCeiling();
		if (ceiling && Math.abs(player.getBlockY() - viewer.feetY) >= FEET_STEP) {
			viewer.feetY = player.getBlockY();
			viewer.resend();
		}

		// 時間が足りずに待たせていた、ワールドに読み込まれているチャンク
		while (!viewer.backlog.isEmpty() && System.nanoTime() < deadline) {
			long chunk = viewer.backlog.dequeueLong();
			viewer.pending.remove(chunk);
			ChunkAccess loaded = inMemory(level, chunk);
			if (loaded != null) {
				deliver(viewer, tile(loaded, state.stamp(loaded, chunk), ceiling, viewer.feetY));
			}
		}
		// 前の一巡で始められなかった生成を、空きができた分だけ始める
		while (!viewer.candidates.isEmpty() && canGenerate(viewer, behind)) {
			long chunk = viewer.candidates.dequeueLong();
			int x = ChunkPos.getX(chunk);
			int z = ChunkPos.getZ(chunk);
			if (viewer.contains(x, z) && isInRange(center, x, z) && !state.generating.contains(chunk) && inMemory(level, chunk) == null) {
				generate(state, chunk, viewer.background);
			}
		}
		sendPreviews(viewer, state);

		if (viewer.changed || viewer.passDone && now - viewer.passStart >= (viewer.incomplete ? 2 : viewer.background ? BACKGROUND_PASS_TICKS : PASS_TICKS)) {
			startPass(viewer, state, center, now);
		}
		return viewer.passDone ? 0 : scan(viewer, state, player, deadline, behind, budget);
	}

	// 一巡の始めに、範囲にかかるリージョンごとに、前の一巡から変わったかを確かめる。変わっていないリージョンは飛ばす
	private void startPass(Viewer viewer, LevelState state, ChunkPos center, int now) {
		viewer.changed = false;
		viewer.incomplete = false;
		viewer.candidates.clear();
		viewer.passStart = now;
		viewer.ring = 0;
		viewer.step = 0;
		viewer.passMinX = Math.max(viewer.minX, center.x() - RADIUS);
		viewer.passMinZ = Math.max(viewer.minZ, center.z() - RADIUS);
		viewer.passMaxX = Math.min(viewer.maxX, center.x() + RADIUS);
		viewer.passMaxZ = Math.min(viewer.maxZ, center.z() + RADIUS);
		viewer.passRegions = null;
		viewer.passOrder = List.of();
		viewer.passDone = viewer.passMinX > viewer.passMaxX || viewer.passMinZ > viewer.passMaxZ;
		if (viewer.passDone) {
			return;
		}
		viewer.passCenterX = Math.clamp(viewer.centerX, viewer.passMinX, viewer.passMaxX);
		viewer.passCenterZ = Math.clamp(viewer.centerZ, viewer.passMinZ, viewer.passMaxZ);
		viewer.maxRing = Math.max(Math.max(viewer.passCenterX - viewer.passMinX, viewer.passMaxX - viewer.passCenterX),
				Math.max(viewer.passCenterZ - viewer.passMinZ, viewer.passMaxZ - viewer.passCenterZ));
		// 開いていない人は、プレイヤーが読み込んでいるチャンクを飛ばすので、動いたら飛ばすチャンクも変わる
		viewer.signature = viewer.background ? center.pack() : Long.MIN_VALUE;
		viewer.regionMinX = TerrainRegions.coordinate(viewer.passMinX);
		viewer.regionMinZ = TerrainRegions.coordinate(viewer.passMinZ);
		viewer.regionWidth = TerrainRegions.coordinate(viewer.passMaxX) - viewer.regionMinX + 1;
		int height = TerrainRegions.coordinate(viewer.passMaxZ) - viewer.regionMinZ + 1;
		viewer.passRegions = new RegionView[viewer.regionWidth * height];
		List<RegionView> order = new ArrayList<>();
		for (int dz = 0; dz < height; dz++) {
			for (int dx = 0; dx < viewer.regionWidth; dx++) {
				int regionX = viewer.regionMinX + dx;
				int regionZ = viewer.regionMinZ + dz;
				long key = ChunkPos.pack(regionX, regionZ);
				RegionView region = viewer.regions.get(key);
				if (region == null) {
					// まだ知らされていない（クライアントがファイルを読んでいる）
					continue;
				}
				viewer.passRegions[dx + dz * viewer.regionWidth] = region;
				order.add(region);
				region.minX = Math.max(viewer.passMinX, regionX * TerrainRegions.CHUNKS);
				region.minZ = Math.max(viewer.passMinZ, regionZ * TerrainRegions.CHUNKS);
				region.maxX = Math.min(viewer.passMaxX, regionX * TerrainRegions.CHUNKS + TerrainRegions.CHUNKS - 1);
				region.maxZ = Math.min(viewer.passMaxZ, regionZ * TerrainRegions.CHUNKS + TerrainRegions.CHUNKS - 1);
				region.header = state.header(key, now, HEADER_TICKS);
				region.epoch = state.epochs.get(key);
				region.dirty = false;
				region.skip = region.clean && region.header != null && region.cleanHeader == region.header && region.cleanEpoch == region.epoch
						&& region.cleanSignature == viewer.signature && region.cleanMinX <= region.minX && region.cleanMinZ <= region.minZ
						&& region.cleanMaxX >= region.maxX && region.cleanMaxZ >= region.maxZ;
				region.absent = !region.previewed && region.header != null && hasAbsent(region);
				if (region.header == null) {
					viewer.incomplete = true;
				}
			}
		}
		int centerX = viewer.passCenterX;
		int centerZ = viewer.passCenterZ;
		order.sort(Comparator.comparingInt(region -> region.distance(centerX, centerZ)));
		viewer.passOrder = order;
	}

	// 範囲の中に、セーブにないチャンクがあるか（下書きが要るか）
	private static boolean hasAbsent(RegionView region) {
		int[] stamps = region.header.stamps();
		for (int z = region.minZ; z <= region.maxZ; z++) {
			for (int x = region.minX; x <= region.maxX; x++) {
				if (stamps[TerrainRegions.index(x, z)] == 0) {
					return true;
				}
			}
		}
		return false;
	}

	// 未生成のチャンクがあるリージョンの下書きを、中心に近い順に送る。作るのは自前のスレッドで、作ったものは覚えておく
	private void sendPreviews(Viewer viewer, LevelState state) {
		if (state.previewer == null) {
			return;
		}
		for (RegionView region : viewer.passOrder) {
			if (region.previewed || !region.absent) {
				continue;
			}
			long key = ChunkPos.pack(region.x, region.z);
			byte[] colors = state.previews.getAndMoveToLast(key);
			if (colors != null) {
				viewer.previews.add(new TerrainPreview(viewer.dimension, region.x, region.z, colors));
				region.previewed = true;
			} else if (state.previewing.size() < WORKERS && state.previewing.add(key)) {
				TerrainPreviewer previewer = state.previewer;
				submit(() -> {
					byte[] made;
					Throwable failure = null;
					try {
						made = previewer.region(region.x, region.z);
					} catch (RuntimeException | LinkageError e) {
						made = null;
						failure = e;
					}
					byte[] result = made;
					Throwable error = failure;
					results.add(() -> {
						state.previewing.remove(key);
						if (result == null) {
							// ほかの mod が地形の作りを変えているなど。下書きなしで、生成した分だけを出す
							LOGGER.warn("Failed to preview terrain of {}, previews are disabled", state.level.dimension().identifier(), error);
							state.previewer = null;
						} else {
							state.previews.putAndMoveToLast(key, result);
							if (state.previews.size() > PREVIEW_CACHE) {
								state.previews.removeFirst();
							}
						}
					});
				});
			}
		}
	}

	// 中心から外へ四角く回りながら、クライアントの持っている版と今の版を比べる。1 tick に比べる数と読み始める数を超えたら、
	// 続きは次の tick に回す。セーブを読み始めた数を返す
	private int scan(Viewer viewer, LevelState state, ServerPlayer player, long deadline, boolean behind, int budget) {
		ServerLevel level = state.level;
		boolean ceiling = level.dimensionType().hasCeiling();
		Long2ObjectMap<IntArrayList> batches = new Long2ObjectOpenHashMap<>();
		int visited = 0;
		int started = 0;
		scan:
		for (; viewer.ring <= viewer.maxRing; viewer.ring++, viewer.step = 0) {
			int ring = viewer.ring;
			int ringSize = ring == 0 ? 1 : 8 * ring;
			for (; viewer.step < ringSize; viewer.step++) {
				int step = viewer.step;
				int x;
				int z;
				if (step <= 2 * ring) {
					x = viewer.passCenterX - ring + step;
					z = viewer.passCenterZ - ring;
				} else if (step <= 4 * ring + 1) {
					x = viewer.passCenterX - ring + step - (2 * ring + 1);
					z = viewer.passCenterZ + ring;
				} else if (step <= 6 * ring) {
					x = viewer.passCenterX - ring;
					z = viewer.passCenterZ - ring + step - (4 * ring + 1);
				} else {
					x = viewer.passCenterX + ring;
					z = viewer.passCenterZ - ring + step - 6 * ring;
				}
				if (x < viewer.passMinX || x > viewer.passMaxX || z < viewer.passMinZ || z > viewer.passMaxZ) {
					continue;
				}
				RegionView region = viewer.passRegions[TerrainRegions.coordinate(x) - viewer.regionMinX
						+ (TerrainRegions.coordinate(z) - viewer.regionMinZ) * viewer.regionWidth];
				if (region == null || region.skip) {
					continue;
				}
				if (++visited > SCAN_PER_TICK) {
					break scan;
				}
				long chunk = ChunkPos.pack(x, z);
				if (viewer.pending.contains(chunk)) {
					region.dirty = true;
					continue;
				}
				int index = TerrainRegions.index(x, z);
				ChunkAccess loaded = inMemory(level, chunk);
				if (loaded != null) {
					// 開いていない間は、クライアントが手元の地形で描けるチャンクは送らない（開いてから送る）
					if (viewer.background && player.getChunkTrackingView().contains(x, z)) {
						continue;
					}
					if (region.header == null) {
						region.dirty = true;
						continue;
					}
					int stamp = state.stamp(loaded, chunk);
					if (region.known[index] != stamp) {
						region.dirty = true;
						if (loaded instanceof ProtoChunk && !(loaded instanceof ImposterProtoChunk)) {
							sendProto(viewer, loaded, stamp, ceiling);
						} else if (System.nanoTime() < deadline) {
							deliver(viewer, tile(loaded, stamp, ceiling, viewer.feetY));
						} else {
							viewer.pending.add(chunk);
							viewer.backlog.enqueue(chunk);
						}
					}
					continue;
				}
				if (region.header == null) {
					region.dirty = true;
					continue;
				}
				int stamp = region.header.stamps()[index];
				int have = region.known[index];
				if (have < 0 && have != TerrainTile.STALE && state.awaitsSave(chunk, stamp, server.getTickCount())) {
					region.dirty = true;
					continue;
				}
				if (stamp != 0 && state.failed.get(chunk) == stamp) {
					continue;
				}
				// 地物を置き終えていないチャンクは、セーブにあっても未生成と同じに扱う
				if (stamp != 0 && state.unready.get(chunk) != stamp) {
					if (have != stamp) {
						region.dirty = true;
						if (started >= budget) {
							break scan;
						}
						batches.computeIfAbsent(ChunkPos.pack(region.x, region.z), key -> new IntArrayList()).add(index);
						viewer.pending.add(chunk);
						started++;
					}
					continue;
				}
				region.dirty = true;
				if (have != 0) {
					// クライアントは色を持っているが、もうない（別のワールドの色が残っていた、など）
					region.known[index] = 0;
					viewer.removed.add(chunk);
				}
				if (!state.generating.contains(chunk)) {
					if (canGenerate(viewer, behind)) {
						generate(state, chunk, viewer.background);
					} else if (viewer.candidates.size() < CANDIDATES) {
						viewer.candidates.enqueue(chunk);
					}
				}
			}
		}
		if (viewer.ring > viewer.maxRing) {
			viewer.passDone = true;
			for (RegionView region : viewer.passOrder) {
				if (!region.skip) {
					region.clean = !region.dirty && region.header != null;
					region.cleanHeader = region.header;
					region.cleanEpoch = region.epoch;
					region.cleanSignature = viewer.signature;
					region.cleanMinX = region.minX;
					region.cleanMinZ = region.minZ;
					region.cleanMaxX = region.maxX;
					region.cleanMaxZ = region.maxZ;
				}
			}
		}
		for (Long2ObjectMap.Entry<IntArrayList> batch : batches.long2ObjectEntrySet()) {
			int[] indexes = batch.getValue().toIntArray();
			for (int from = 0; from < indexes.length; from += READ_BATCH) {
				read(viewer, state, batch.getLongKey(), Arrays.copyOfRange(indexes, from, Math.min(indexes.length, from + READ_BATCH)), ceiling);
			}
		}
		return started;
	}

	private boolean canGenerate(Viewer viewer, boolean behind) {
		if (behind || viewer.background && backgroundGenerating >= BACKGROUND_GENERATING) {
			return false;
		}
		if (generating >= generationLimit) {
			limited = true;
			return false;
		}
		return true;
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

	private static TerrainTile tile(ChunkAccess chunk, int stamp, boolean ceiling, int feetY) {
		return TerrainColors.tile(chunk.getPos(), TerrainColors.of(chunk), ceiling, feetY).withStamp(stamp);
	}

	// 送る列に入れ、クライアントが持っている版として覚える。色が前に送ったものと同じなら、版だけを送る
	// （メモリにあったチャンクが同じ中身のまま保存された、保存されたチャンクが読み込まれた、など）
	private static void deliver(Viewer viewer, TerrainTile tile) {
		int x = ChunkPos.getX(tile.chunk());
		int z = ChunkPos.getZ(tile.chunk());
		RegionView region = viewer.regions.get(TerrainRegions.key(x, z));
		if (region == null) {
			return;
		}
		int index = TerrainRegions.index(x, z);
		int hash = 31 * (31 * Arrays.hashCode(tile.colors()) + Arrays.hashCode(tile.northEdge())) + Arrays.hashCode(tile.southEdge());
		if (region.sent == null) {
			region.sent = new int[TerrainRegions.COUNT];
		}
		if (region.known[index] != 0 && region.sent[index] == hash && hash != 0) {
			viewer.stampChunks.add(tile.chunk());
			viewer.stampValues.add(tile.stamp());
		} else {
			viewer.tiles.add(tile);
		}
		region.sent[index] = hash;
		region.known[index] = tile.stamp();
	}

	// ワールドに読み込まれていない（tick されない）チャンクは、自前のスレッドで色にする。
	// 色にしている間に変わっても、その変化は新しい版になるか、保存されたときに版が変わるので、後で送り直される
	private void sendProto(Viewer viewer, ChunkAccess chunk, int stamp, boolean ceiling) {
		long key = chunk.getPos().pack();
		int session = viewer.session;
		int feetY = viewer.feetY;
		viewer.pending.add(key);
		submit(() -> {
			TerrainTile made;
			try {
				made = tile(chunk, stamp, ceiling, feetY);
			} catch (RuntimeException e) {
				made = null;
			}
			TerrainTile tile = made;
			results.add(() -> {
				if (viewer.session == session) {
					viewer.pending.remove(key);
					if (tile != null) {
						deliver(viewer, tile);
					}
				}
			});
		});
	}

	// 同じリージョンのチャンクをまとめて読む。読むのも色にするのも自前のスレッド。メインスレッドは結果を送るだけ。
	// ヘッダーを読み直してから読むので、版は読んだ時点のもの。直接は読めなかったチャンクは vanilla の経路（IO スレッド）で読み直す
	private void read(Viewer viewer, LevelState state, long regionKey, int[] indexes, boolean ceiling) {
		ServerLevel level = state.level;
		RegionReader reader = state.reader();
		int regionX = ChunkPos.getX(regionKey);
		int regionZ = ChunkPos.getZ(regionKey);
		int session = viewer.session;
		int feetY = viewer.feetY;
		reading += indexes.length;
		reads.addAndGet(indexes.length);
		submit(() -> {
			RegionReader.Header header = reader.header(regionX, regionZ);
			TerrainTile[] tiles = new TerrainTile[indexes.length];
			// 0: セーブにない、1: 色にできた、2: 地物を置き終えていない、3: 直接は読めなかった
			byte[] outcomes = new byte[indexes.length];
			for (int i = 0; header != null && i < indexes.length; i++) {
				int index = indexes[i];
				if (header.offsets()[index] == 0) {
					continue;
				}
				ChunkPos pos = new ChunkPos(regionX * TerrainRegions.CHUNKS + index % TerrainRegions.CHUNKS,
						regionZ * TerrainRegions.CHUNKS + index / TerrainRegions.CHUNKS);
				outcomes[i] = 3;
				try {
					CompoundTag tag = reader.read(pos, header.offsets()[index]);
					if (tag != null) {
						StoredChunk blocks = StoredChunk.read(level, tag);
						outcomes[i] = 2;
						if (blocks != null) {
							tiles[i] = TerrainColors.tile(pos, blocks, ceiling, feetY).withStamp(header.stamps()[index]);
							outcomes[i] = 1;
						}
					}
				} catch (RuntimeException e) {
					// vanilla の経路で読み直す
				}
			}
			results.add(() -> {
				reading -= indexes.length;
				if (header != null) {
					state.accept(regionKey, header, server.getTickCount());
				}
				if (viewer.session != session) {
					return;
				}
				for (int i = 0; i < indexes.length; i++) {
					int index = indexes[i];
					ChunkPos pos = new ChunkPos(regionX * TerrainRegions.CHUNKS + index % TerrainRegions.CHUNKS,
							regionZ * TerrainRegions.CHUNKS + index / TerrainRegions.CHUNKS);
					long chunk = pos.pack();
					viewer.pending.remove(chunk);
					if (header == null) {
						continue;
					}
					int stamp = header.stamps()[index];
					if (outcomes[i] == 1) {
						// 読んでいる間に読み込まれていたら、セーブより新しいかもしれないので、次の一巡でメモリの方から送る
						if (inMemory(level, chunk) == null) {
							deliver(viewer, tiles[i]);
						}
					} else if (outcomes[i] == 2) {
						state.unready.put(chunk, stamp);
					} else if (outcomes[i] == 3) {
						readByVanilla(viewer, state, pos, stamp, ceiling);
					}
				}
			});
		});
	}

	// 古い版のデータは、読み込むときと同じく新しい形に直る。それでも読めなければ、書き直されるまで読まない
	private void readByVanilla(Viewer viewer, LevelState state, ChunkPos pos, int stamp, boolean ceiling) {
		ServerLevel level = state.level;
		long chunk = pos.pack();
		int session = viewer.session;
		int feetY = viewer.feetY;
		viewer.pending.add(chunk);
		reading++;
		vanillaReads.incrementAndGet();
		// 色にできたら色、地物を置き終えていなければ UNREADY、セーブになければ null
		((ChunkMapInvoker) level.getChunkSource().chunkMap).minimap$readChunk(pos)
				.thenApplyAsync(data -> {
					if (data.isEmpty()) {
						return null;
					}
					StoredChunk blocks = StoredChunk.read(level, data.get());
					return blocks == null ? UNREADY : TerrainColors.tile(pos, blocks, ceiling, feetY).withStamp(stamp);
				}, workers)
				.whenComplete((tile, error) -> results.add(() -> {
					reading--;
					if (error != null) {
						state.failed.put(chunk, stamp);
						if (!failureLogged) {
							failureLogged = true;
							LOGGER.warn("Failed to read terrain of chunk {}", pos, error);
						}
					} else if (tile == UNREADY) {
						state.unready.put(chunk, stamp);
					}
					if (viewer.session != session) {
						return;
					}
					viewer.pending.remove(chunk);
					if (error == null && tile != null && tile != UNREADY && inMemory(level, chunk) == null) {
						deliver(viewer, tile);
					}
				}));
	}

	// 地図に要るのは地形と地物だけなので、光の計算やエンティティの読み込みをする FULL までは進めない（FEATURES のまま保存される）。
	// getChunkFuture だけでは 1 tick で札が外れ、生成に時間がかかるチャンクは途中で捨てられるので、生成し終えるまで外れない札を付ける。
	// getChunkFuture はメインスレッドから呼ぶと生成し終えるまで待つので、自前のスレッドから呼ぶ（メインスレッドに頼むだけになる）。
	// セーブにあったチャンク（ほかの誰かが生成した、地物を置く前で止まっていた）は、vanilla が読んで続きから進める
	private void generate(LevelState state, long chunk, boolean background) {
		generating++;
		if (background) {
			backgroundGenerating++;
		}
		state.generating.add(chunk);
		ChunkPos pos = ChunkPos.unpack(chunk);
		ServerChunkCache source = state.level.getChunkSource();
		source.addTicket(new Ticket(TICKET, ChunkLevel.byStatus(ChunkStatus.FEATURES)), pos);
		CompletableFuture<ChunkAccess> generated;
		try {
			generated = CompletableFuture.supplyAsync(() -> source.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FEATURES, true), workers)
					.thenCompose(future -> future)
					.thenApply(result -> result.orElse(null));
		} catch (RejectedExecutionException e) {
			return;
		}
		generated.whenComplete((result, error) -> results.add(() -> {
			generating--;
			if (background) {
				backgroundGenerating--;
			}
			state.generating.remove(chunk);
			if (result != null && Minimap.isActive()) {
				state.touch(chunk, clock);
				sendGenerated(state, result);
			}
		}));
	}

	// 見ている人に送る
	private void sendGenerated(LevelState state, ChunkAccess chunk) {
		long key = chunk.getPos().pack();
		Identifier dimension = state.level.dimension().identifier();
		boolean ceiling = state.level.dimensionType().hasCeiling();
		for (Viewer viewer : viewers.values()) {
			if (viewer.active && dimension.equals(viewer.dimension) && viewer.contains(chunk.getPos().x(), chunk.getPos().z()) && !viewer.pending.contains(key)) {
				sendProto(viewer, chunk, state.memoryStamp(key), ceiling);
			}
		}
	}

	private void submit(Runnable task) {
		try {
			workers.execute(task);
		} catch (RejectedExecutionException e) {
			// サーバーを止めている
		}
	}

	private LevelState state(ServerLevel level) {
		return levels.computeIfAbsent(level.dimension(), key -> new LevelState(level));
	}

	private final class LevelState {
		private final ServerLevel level;
		private RegionReader reader;
		// 下書きを作れない次元なら null
		private TerrainPreviewer previewer;
		// 作った下書き（古い順）と、作っているリージョン
		private final Long2ObjectLinkedOpenHashMap<byte[]> previews = new Long2ObjectLinkedOpenHashMap<>();
		private final LongSet previewing = new LongOpenHashSet();
		private final Long2ObjectMap<HeaderEntry> headers = new Long2ObjectOpenHashMap<>();
		// 生成しているチャンク
		private final LongSet generating = new LongOpenHashSet();
		// メモリにあるチャンクが最後に変わった時点（clock）。変わっていなければ、初めて見た時点
		private final Long2IntMap changed = new Long2IntOpenHashMap();
		// メモリにあるチャンクが変わるたびに増える、リージョンごとの数
		private final Long2IntOpenHashMap epochs = new Long2IntOpenHashMap();
		// セーブにあるが地物を置き終えていないチャンクと、読めなかったチャンクの、そのときの版。版が変わるまで読み直さない
		private final Long2IntMap unready = new Long2IntOpenHashMap();
		private final Long2IntMap failed = new Long2IntOpenHashMap();
		// メモリから外れたのに気づいた tick と、そのときのヘッダーの版（awaitsSave）
		private final Long2IntMap leftSince = new Long2IntOpenHashMap();
		private final Long2IntMap leftStamp = new Long2IntOpenHashMap();
		private final MapPriority priority = new MapPriority();
		private final MapPriority.Queue queue;

		private LevelState(ServerLevel level) {
			this.level = level;
			this.previewer = TerrainPreviewer.create(level);
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

		// 読んであるヘッダー。まだなら null。maxAge より古ければ自前のスレッドで読み直す（次の一巡から新しいものになる）
		private RegionReader.Header header(long region, int now, int maxAge) {
			HeaderEntry entry = headers.computeIfAbsent(region, key -> new HeaderEntry());
			entry.used = now;
			if (!entry.loading && (entry.header == null || now - entry.loaded >= maxAge)) {
				entry.loading = true;
				RegionReader reader = reader();
				submit(() -> {
					RegionReader.Header header = reader.header(ChunkPos.getX(region), ChunkPos.getZ(region));
					results.add(() -> {
						entry.loading = false;
						if (header != null) {
							accept(region, header, server.getTickCount());
						}
					});
				});
			}
			return entry.header;
		}

		// 中身が同じなら前のものを使い続け、変わっていないリージョンを見分けられるようにする
		private void accept(long region, RegionReader.Header header, int now) {
			HeaderEntry entry = headers.computeIfAbsent(region, key -> new HeaderEntry());
			entry.loaded = now;
			if (entry.header == null || !entry.header.sameAs(header)) {
				entry.header = header;
			}
		}

		// クライアントが持っている色はメモリにあったときのもので、チャンクはもうメモリにない。
		// vanilla が書き終えるまでは、セーブにないか古い中身のままなので、ヘッダーの版が変わるのを少し待つ。
		// 待たないと、古い中身を送ったり、生成し終えたチャンクをもう一度生成しようとしたりする
		private boolean awaitsSave(long chunk, int stamp, int now) {
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
		private int stamp(ChunkAccess loaded, long chunk) {
			HeaderEntry entry = headers.get(TerrainRegions.key(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
			int saved = entry == null || entry.header == null ? 0 : entry.header.stamps()[TerrainRegions.index(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))];
			boolean unsaved = loaded instanceof ImposterProtoChunk imposter ? imposter.getWrapped().isUnsaved() : loaded.isUnsaved();
			return saved != 0 && !unsaved ? saved : memoryStamp(chunk);
		}

		// まだ保存していないチャンクの版。初めて見たチャンクには、そのときの時計を付ける。メモリから外れて戻ってきたチャンクは
		// 間に中身が変わっているかもしれないので、前と同じ版にならないようにするため（外れたチャンクの時計は prune で消える）
		private int memoryStamp(long chunk) {
			int value = changed.get(chunk);
			if (value == 0) {
				value = clock;
				changed.put(chunk, value);
			}
			return -1 - value;
		}

		private void touch(long chunk, int clock) {
			if (changed.put(chunk, clock) != clock) {
				epochs.addTo(TerrainRegions.key(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)), 1);
			}
		}

		private void prune(int now) {
			changed.long2IntEntrySet().removeIf(entry -> inMemory(level, entry.getLongKey()) == null);
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

		private void close() {
			queue.minimap$setPriority(null);
			if (reader != null) {
				reader.close();
			}
		}
	}

	private static final class HeaderEntry {
		private RegionReader.Header header;
		private boolean loading;
		private int loaded;
		private int used;
	}

	// 見ている人が知らせてきたリージョン 1 つ分
	private static final class RegionView {
		private final int x;
		private final int z;
		// クライアントが持っている色の版（TerrainRegions.index の順。0 は持っていない）
		private final int[] known;
		// この接続で送った色のハッシュ（送っていなければ 0）。色が同じで版だけ変わったチャンクを見分ける
		private int[] sent;
		private boolean previewed;

		// いまの一巡で比べる範囲（チャンク）と、始めたときのヘッダー・メモリの変化の数
		private int minX;
		private int minZ;
		private int maxX;
		private int maxZ;
		private RegionReader.Header header;
		private int epoch;
		private boolean skip;
		// 送るもの・読むもの・生成するものがあった
		private boolean dirty;
		// 範囲の中にセーブにないチャンクがあり、下書きが要る
		private boolean absent;

		// 何も送るものがなかった一巡の条件。同じなら次の一巡で飛ばす
		private boolean clean;
		private RegionReader.Header cleanHeader;
		private int cleanEpoch;
		private long cleanSignature;
		private int cleanMinX;
		private int cleanMinZ;
		private int cleanMaxX;
		private int cleanMaxZ;

		private RegionView(int x, int z, int[] known) {
			this.x = x;
			this.z = z;
			this.known = known;
		}

		// リージョンの一番近いチャンクまでの距離
		private int distance(int chunkX, int chunkZ) {
			int nearX = Math.clamp(chunkX, x * TerrainRegions.CHUNKS, x * TerrainRegions.CHUNKS + TerrainRegions.CHUNKS - 1);
			int nearZ = Math.clamp(chunkZ, z * TerrainRegions.CHUNKS, z * TerrainRegions.CHUNKS + TerrainRegions.CHUNKS - 1);
			return Math.max(Math.abs(nearX - chunkX), Math.abs(nearZ - chunkZ));
		}
	}

	private static final class Viewer {
		// 最後に知らせてきた次元と範囲
		private Identifier dimension;
		private boolean active;
		private int minX;
		private int minZ;
		private int maxX;
		private int maxZ;
		private int centerX;
		private int centerZ;
		// マップ画面を開いていない（マップ距離の分を見ている）
		private boolean background;
		// 範囲か知らせてきたリージョンが変わった。次の tick に一巡をやり直す
		private boolean changed;
		// 知らせてきたリージョンを忘れるたびに増やし、忘れる前に始めた仕事の結果を捨てる
		private int session;
		private final Long2ObjectMap<RegionView> regions = new Long2ObjectOpenHashMap<>();
		// 色を作っている途中のチャンク（セーブを読んでいる、メインスレッドの時間を待っている、など）
		private final LongSet pending = new LongOpenHashSet();
		private final LongArrayFIFOQueue backlog = new LongArrayFIFOQueue();
		private final LongArrayFIFOQueue candidates = new LongArrayFIFOQueue();
		private int feetY = Integer.MIN_VALUE / 2;

		// いまの一巡
		private boolean passDone = true;
		// ヘッダーをまだ読めていないリージョンがあった。すぐにやり直す
		private boolean incomplete;
		private int passStart = Integer.MIN_VALUE / 2;
		private int passMinX;
		private int passMinZ;
		private int passMaxX;
		private int passMaxZ;
		private int passCenterX;
		private int passCenterZ;
		private int maxRing;
		private int ring;
		private int step;
		private long signature;
		private int regionMinX;
		private int regionMinZ;
		private int regionWidth;
		private RegionView[] passRegions;
		private List<RegionView> passOrder = List.of();

		// まとめて送るもの
		private final List<TerrainTile> tiles = new ArrayList<>();
		private final LongList stampChunks = new LongArrayList();
		private final IntArrayList stampValues = new IntArrayList();
		private final LongList removed = new LongArrayList();
		private final List<TerrainPreview> previews = new ArrayList<>();

		// 違う次元のことを知らせてきたら、前の次元のことはすべて忘れる（クライアントも知らせ直す）
		private void enter(Identifier newDimension) {
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

		// 持っている色をすべて送り直す
		private void resend() {
			for (RegionView region : regions.values()) {
				Arrays.fill(region.known, 0);
				region.clean = false;
			}
			changed = true;
		}

		private boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		private void flush(ServerPlayer player) {
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
