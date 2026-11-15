package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import com.omuraisu.minimap.TerrainViewer.RegionView;
import com.omuraisu.minimap.mixin.ChunkMapInvoker;
import com.omuraisu.minimap.mixin.ServerWaypointManagerAccessor;

import org.slf4j.Logger;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.waypoints.WaypointTransmitter;

// マップ画面に描画距離の外を出すため、地形の色を送る。地図のためにチャンクをワールドへ読み込まない。
// クライアントは見ている範囲（TerrainView）と、リージョンごとに持っている色の版（TerrainHave）を知らせるだけで、
// 何を送るかはサーバーが決める。チャンクの今の版と、クライアントが持っている版が違うものだけを、範囲の中心に近い順に送る。
// - メモリにあるチャンク: 保存してから変わっていなければ、版はセーブにあるものと同じ。変わっていれば、最後に変わった時点。
//   ワールドに読み込まれたものはメインスレッドで、生成の途中のものは自前のスレッドで色にする
// - セーブにあるチャンク: 版はリージョンファイルのヘッダーにある書いた時刻。ヘッダーを読むだけで、リージョン 1 つ分の有無と版が分かる。
//   版が違うチャンクだけ、リージョンファイルを自前のスレッドで直接読んで色にする（vanilla の IO スレッドを使わない）
// - 未生成のチャンク: FEATURES まで生成してから送る。同時に生成する数は、サーバーが予定より遅れていなければ増やし、遅れたら減らす。
//   地図だけのチャンクの生成は、vanilla の生成の列の一番後ろに回す（MapPriority）
// マップ画面を開いていない人の範囲（一番引いた地図に入る範囲）は、開いている人の後に、残りの予算で進める
//
// 分け方: 見ている人ごとの範囲と送信状態は TerrainViewer、次元ごとの版・生成は TerrainLevel、
// tick ごとの処理の予算は TerrainPace、自前のスレッドは TerrainWorkers。ここは一巡で比べ、読み込み・生成を始めて結果を渡す
public final class TerrainServer {
	// プレイヤーのいるチャンクからこの距離（チャンク）までしか送らない。どの解像度でも一番引いた地図が収まる
	public static final int RADIUS = 192;
	// 同じリージョンのチャンクは、この数ずつまとめて 1 つの仕事にする（ヘッダーを読むのが 1 回で済む）
	private static final int READ_BATCH = 64;
	// 範囲を一巡して版を比べる。1 tick に比べるチャンクの数と、一巡し終えてから次に始めるまでの tick。
	// 前の一巡から何も変わっていないリージョンは飛ばすので、送り終えた後の一巡は軽い
	private static final int SCAN_PER_TICK = 8192;
	private static final int PASS_TICKS = 20;
	private static final int BACKGROUND_PASS_TICKS = 40;
	private static final int PLAYER_POSITION_TICKS = 5;
	// 一巡の始めに、これより古いヘッダーを読み直す
	private static final int HEADER_TICKS = 20;
	// 生成を始められなかった未生成のチャンクを、次の一巡までの間に空きができたら始められるよう覚えておく数
	private static final int CANDIDATES = 2048;
	// 1 人が知らせられるリージョンの数。クライアントは離れたリージョンを捨てて知らせるので、ふつうは 300 に届かない
	private static final int MAX_REGIONS = 4096;
	// 天井のある次元は足元の高さから下を映すので、これだけ上下に動いたら送り直す
	private static final int FEET_STEP = 16;
	// 生成し終えてから 1 秒で外れる。登録はしない（保存されない札なので名前は要らない）
	private static final TicketType TICKET = new TicketType(20, TicketType.FLAG_LOADING);

	private static final TerrainTile UNREADY = new TerrainTile(0, 0, null, null, null);

	private static final Logger LOGGER = LogUtils.getLogger();

	private static TerrainServer current;

	private final MinecraftServer server;
	private final Map<UUID, TerrainViewer> viewers = new HashMap<>();
	private final Map<ResourceKey<Level>, TerrainLevel> levels = new HashMap<>();
	private final TerrainWorkers workers = new TerrainWorkers();
	private final TerrainPace pace = new TerrainPace();
	// メモリにあるチャンクの版に使う時計。tick の終わりに色を作った後で進めるので、その後の変化は必ず新しい版になる
	private int clock = 1;
	private boolean failureLogged;
	// セーブを読んだ数のうち、vanilla の経路で読んだ数・生成を始めた数・一巡で版を比べたチャンクの数。テストから確かめる
	private final AtomicInteger reads = new AtomicInteger();
	private final AtomicInteger vanillaReads = new AtomicInteger();
	private final AtomicInteger generations = new AtomicInteger();
	private final AtomicLong compared = new AtomicLong();

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

	// 動いているサーバーで、地図のために生成を始めた数
	public static int generationCount() {
		TerrainServer server = current;
		return server == null ? 0 : server.generations.get();
	}

	// 動いているサーバーで、一巡で版を比べたチャンクの数（飛ばしたリージョンのチャンクは数えない）
	public static long comparedCount() {
		TerrainServer server = current;
		return server == null ? 0 : server.compared.get();
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
		TerrainLevel state = levels.get(level.dimension());
		if (state != null) {
			state.touch(chunk, clock);
		}
	}

	private void close() {
		workers.close();
		levels.values().forEach(TerrainLevel::close);
	}

	private void view(ServerPlayer player, TerrainView payload) {
		TerrainViewer viewer = viewers.computeIfAbsent(player.getUUID(), id -> new TerrainViewer());
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
		TerrainViewer viewer = viewers.computeIfAbsent(player.getUUID(), id -> new TerrainViewer());
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
		workers.drain();
		if (!Minimap.isActive()) {
			levels.values().forEach(state -> state.priority.clear());
			return;
		}
		int now = server.getTickCount();
		pace.startTick(server, now);

		List<TerrainViewer> open = new ArrayList<>();
		List<ServerPlayer> openPlayers = new ArrayList<>();
		List<TerrainViewer> background = new ArrayList<>();
		List<ServerPlayer> backgroundPlayers = new ArrayList<>();
		for (Map.Entry<UUID, TerrainViewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			TerrainViewer viewer = entry.getValue();
			// クライアントが次元を移ったことを知らせてくるまでは、前の次元の範囲のままなので進めない
			if (player != null && viewer.active && player.level().dimension().identifier().equals(viewer.dimension)) {
				(viewer.background ? background : open).add(viewer);
				(viewer.background ? backgroundPlayers : openPlayers).add(player);
				if (!viewer.background && now % PLAYER_POSITION_TICKS == 0 && ServerPlayNetworking.canSend(player, MapPlayerPositions.TYPE)) {
					sendPlayerPositions(viewer, player);
				}
			}
		}
		// 開いていない人には、開いている人が使った残りの予算を回す。遅れている間は進めない
		long deadline = pace.loadedDeadline();
		int budget = pace.readBudget();
		budget -= serve(open, openPlayers, now, deadline, budget);
		if (!pace.behind()) {
			serve(background, backgroundPlayers, now, deadline, budget);
		}
		for (Map.Entry<UUID, TerrainViewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				entry.getValue().flush(player);
			}
		}
		for (TerrainLevel state : levels.values()) {
			// 生成の列で地図だけのチャンクを後ろに回すため、プレイヤーの位置と生成しているチャンクを写す
			state.priority.update(state.level, state.generating);
			if (now % TerrainLevel.PRUNE_TICKS == 0) {
				state.prune(now);
			}
		}
		clock++;
	}

	// バニラがロケーターバーに出す相手のうち、地図が見ている範囲にいる人だけ座標を補う
	private static void sendPlayerPositions(TerrainViewer viewer, ServerPlayer player) {
		ServerLevel level = player.level();
		Map<UUID, MapPlayerPositions.Position> positions = new HashMap<>();
		if (player.isReceivingWaypoints() && level.getGameRules().get(GameRules.LOCATOR_BAR)) {
			var connections = ((ServerWaypointManagerAccessor) level.getWaypointManager()).minimap$connections();
			for (WaypointTransmitter transmitter : connections.row(player).keySet()) {
				if (transmitter instanceof ServerPlayer other && other.level() == level && !other.isRemoved()
						&& other.isTransmittingWaypoint() && !other.isInvisibleTo(player)
						&& !WaypointTransmitter.doesSourceIgnoreReceiver(other, player)) {
					ChunkPos chunk = other.chunkPosition();
					if (viewer.contains(chunk.x(), chunk.z()) && isInRange(player.chunkPosition(), chunk.x(), chunk.z())) {
						positions.put(other.getUUID(), new MapPlayerPositions.Position(other.getX(), other.getZ()));
					}
				}
			}
		}
		ServerPlayNetworking.send(player, new MapPlayerPositions(viewer.dimension, positions));
	}

	// 見ている人が何人いても順に先を譲る。読み始めた数を返す
	private int serve(List<TerrainViewer> viewers, List<ServerPlayer> players, int now, long deadline, int budget) {
		int share = viewers.isEmpty() ? 0 : Math.max(1, (budget + viewers.size() - 1) / viewers.size());
		int started = 0;
		for (int i = 0; i < viewers.size(); i++) {
			int index = (i + now) % viewers.size();
			started += serve(viewers.get(index), players.get(index), now, deadline, Math.max(0, Math.min(share, budget - started)));
		}
		return started;
	}

	private int serve(TerrainViewer viewer, ServerPlayer player, int now, long deadline, int budget) {
		ServerLevel level = player.level();
		TerrainLevel state = state(level);
		ChunkPos center = player.chunkPosition();
		boolean ceiling = level.dimensionType().hasCeiling();
		if (ceiling && Math.abs(player.getBlockY() - viewer.feetY) >= FEET_STEP) {
			viewer.moveFeet(player.getBlockY());
		}

		// 時間が足りずに待たせていた、ワールドに読み込まれているチャンク
		while (!viewer.backlog.isEmpty() && System.nanoTime() < deadline) {
			long chunk = viewer.backlog.dequeueLong();
			viewer.pending.remove(chunk);
			ChunkAccess loaded = state.inMemory(chunk);
			if (loaded != null) {
				viewer.deliver(tile(loaded, state.stamp(loaded, chunk, clock), ceiling, viewer.feetY));
			}
		}
		// 前の一巡で始められなかった生成を、空きができた分だけ始める
		while (!viewer.candidates.isEmpty() && pace.canGenerate(viewer.background)) {
			long chunk = viewer.candidates.dequeueLong();
			int x = ChunkPos.getX(chunk);
			int z = ChunkPos.getZ(chunk);
			if (viewer.contains(x, z) && isInRange(center, x, z) && !state.generating.contains(chunk) && state.inMemory(chunk) == null) {
				generate(state, chunk, viewer.background);
			}
		}

		if (viewer.changed || viewer.passDone && now - viewer.passStart >= (viewer.incomplete ? 2 : viewer.background ? BACKGROUND_PASS_TICKS : PASS_TICKS)) {
			startPass(viewer, state, player, now);
		}
		return viewer.passDone ? 0 : scan(viewer, state, deadline, budget);
	}

	// 一巡の始めに、範囲にかかるリージョンごとに、前の一巡から変わったかを確かめる。変わっていないリージョンは飛ばす
	private void startPass(TerrainViewer viewer, TerrainLevel state, ServerPlayer player, int now) {
		ChunkPos center = player.chunkPosition();
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
		// 開いていない人は、プレイヤーが読み込んでいるチャンク（追跡範囲）を飛ばす。飛ばすのは一巡の始めの追跡範囲で、
		// プレイヤーが動いて追跡範囲が変わったら、かかっていた・いまかかっているリージョンだけを比べ直す
		viewer.tracking = viewer.background ? player.getChunkTrackingView() : null;
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
				region.epoch = state.epoch(key);
				region.dirty = false;
				region.tracked = overlaps(viewer.tracking, region);
				region.skip = region.clean && region.header != null && region.cleanHeader == region.header && region.cleanEpoch == region.epoch
						&& (!region.cleanTracked && !region.tracked || Objects.equals(region.cleanTracking, viewer.tracking))
						&& region.cleanMinX <= region.minX && region.cleanMinZ <= region.minZ
						&& region.cleanMaxX >= region.maxX && region.cleanMaxZ >= region.maxZ;
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

	// リージョンのいまの一巡の範囲が、追跡範囲（null ならない）にかかるか。追跡範囲は中心から描画距離 + 1 の四角に収まる
	private static boolean overlaps(ChunkTrackingView tracking, RegionView region) {
		if (tracking == null || tracking == ChunkTrackingView.EMPTY) {
			return false;
		}
		if (tracking instanceof ChunkTrackingView.Positioned positioned) {
			int reach = positioned.viewDistance() + 1;
			ChunkPos center = positioned.center();
			return region.minX <= center.x() + reach && region.maxX >= center.x() - reach
					&& region.minZ <= center.z() + reach && region.maxZ >= center.z() - reach;
		}
		// 知らない形の追跡範囲は、どこにでもかかるとみなす
		return true;
	}

	// 中心から外へ四角く回りながら、クライアントの持っている版と今の版を比べる。1 tick に比べる数と読み始める数を超えたら、
	// 続きは次の tick に回す。セーブを読み始めた数を返す
	private int scan(TerrainViewer viewer, TerrainLevel state, long deadline, int budget) {
		ServerLevel level = state.level;
		boolean ceiling = level.dimensionType().hasCeiling();
		Long2ObjectMap<IntArrayList> batches = new Long2ObjectOpenHashMap<>();
		ChunkTrackingView tracking = viewer.tracking;
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
					visited--;
					break scan;
				}
				long chunk = ChunkPos.pack(x, z);
				if (viewer.pending.contains(chunk)) {
					region.dirty = true;
					continue;
				}
				int index = TerrainRegions.index(x, z);
				ChunkAccess loaded = state.inMemory(chunk);
				if (loaded != null) {
					// 開いていない間は、クライアントが手元の地形で描けるチャンクは送らない（開いてから送る）
					if (tracking != null && tracking.contains(x, z)) {
						continue;
					}
					if (region.header == null) {
						region.dirty = true;
						continue;
					}
					int stamp = state.stamp(loaded, chunk, clock);
					if (region.known[index] != stamp) {
						region.dirty = true;
						if (loaded instanceof ProtoChunk && !(loaded instanceof ImposterProtoChunk)) {
							sendProto(viewer, loaded, stamp, ceiling);
						} else if (System.nanoTime() < deadline) {
							viewer.deliver(tile(loaded, stamp, ceiling, viewer.feetY));
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
				// クライアントは色を持っているが、もうない（別のワールドの色が残っていた、など）。この接続でメモリから送った色（負の版）は、
				// 書かれるのを待ちきれなかっただけなので消さない。消すと、読み込み直すまで地図に穴があく
				if (have > 0 || have == TerrainTile.STALE) {
					region.known[index] = 0;
					viewer.removed.add(chunk);
				}
				if (!state.generating.contains(chunk)) {
					if (pace.canGenerate(viewer.background)) {
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
					region.cleanTracked = region.tracked;
					region.cleanTracking = viewer.tracking;
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
		compared.addAndGet(visited);
		return started;
	}

	private static TerrainTile tile(ChunkAccess chunk, int stamp, boolean ceiling, int feetY) {
		return TerrainColors.tile(chunk.getPos(), TerrainColors.of(chunk), ceiling, feetY).withStamp(stamp);
	}

	// ワールドに読み込まれていない（tick されない）チャンクは、自前のスレッドで色にする。
	// 色にしている間に変わっても、その変化は新しい版になるか、保存されたときに版が変わるので、後で送り直される
	private void sendProto(TerrainViewer viewer, ChunkAccess chunk, int stamp, boolean ceiling) {
		long key = chunk.getPos().pack();
		TerrainViewer.Snapshot snapshot = viewer.snapshot();
		viewer.pending.add(key);
		workers.run(() -> tile(chunk, stamp, ceiling, snapshot.feetY()), (tile, failure) -> {
			// 生成の途中のチャンクを別のスレッドが書き換えている間に読むと、RuntimeException になることがある。
			// それは送らずに次の一巡に任せる。それ以外の失敗は思わぬものなのでログに出す
			if (failure != null && !(failure instanceof RuntimeException)) {
				logFailure("Failed to color terrain of chunk " + chunk.getPos(), failure);
			}
			if (viewer.sameSession(snapshot)) {
				viewer.pending.remove(key);
			}
			if (tile != null && viewer.accepts(snapshot)) {
				viewer.deliver(tile);
			}
		});
	}

	// 自前のスレッドの思わぬ失敗。同じ失敗でログが埋まらないよう、最初の 1 回だけ出す
	private void logFailure(String message, Throwable failure) {
		if (!failureLogged) {
			failureLogged = true;
			LOGGER.warn(message, failure);
		}
	}

	// 同じリージョンのチャンクをまとめて読む。読むのも色にするのも自前のスレッド。メインスレッドは結果を送るだけ。
	// ヘッダーを読み直してから読むので、版は読んだ時点のもの。直接は読めなかったチャンクは vanilla の経路（IO スレッド）で読み直す
	private void read(TerrainViewer viewer, TerrainLevel state, long regionKey, int[] indexes, boolean ceiling) {
		ServerLevel level = state.level;
		RegionReader reader = state.reader();
		int regionX = ChunkPos.getX(regionKey);
		int regionZ = ChunkPos.getZ(regionKey);
		TerrainViewer.Snapshot snapshot = viewer.snapshot();
		pace.startReading(indexes.length);
		reads.addAndGet(indexes.length);
		workers.run(() -> {
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
							tiles[i] = TerrainColors.tile(pos, blocks, ceiling, snapshot.feetY()).withStamp(header.stamps()[index]);
							outcomes[i] = 1;
						}
					}
				} catch (RuntimeException e) {
					// vanilla の経路で読み直す
				}
			}
			return new ReadBatch(header, tiles, outcomes);
		}, (batch, failure) -> {
			pace.finishReading(indexes.length);
			// まとめて失敗したら（RuntimeException でない Error など）、待っている印だけ外し、次の一巡で読み直す
			if (failure != null) {
				logFailure("Failed to read terrain of region " + regionX + ", " + regionZ, failure);
			}
			RegionReader.Header header = batch == null ? null : batch.header();
			if (header != null) {
				state.accept(regionKey, header, server.getTickCount());
			}
			if (!viewer.sameSession(snapshot)) {
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
				if (batch.outcomes()[i] == 1) {
					// 読んでいる間に読み込まれていたら、セーブより新しいかもしれないので、次の一巡でメモリの方から送る
					if (viewer.accepts(snapshot) && state.inMemory(chunk) == null) {
						viewer.deliver(batch.tiles()[i]);
					}
				} else if (batch.outcomes()[i] == 2) {
					state.unready.put(chunk, stamp);
				} else if (batch.outcomes()[i] == 3) {
					readByVanilla(viewer, state, pos, stamp, ceiling);
				}
			}
		});
	}

	// 自前のスレッドで読んだリージョンのヘッダー（読めなければ null）と、チャンクごとの色と結果
	private record ReadBatch(RegionReader.Header header, TerrainTile[] tiles, byte[] outcomes) {
	}

	// 古い版のデータは、読み込むときと同じく新しい形に直る。それでも読めなければ、書き直されるまで読まない
	private void readByVanilla(TerrainViewer viewer, TerrainLevel state, ChunkPos pos, int stamp, boolean ceiling) {
		ServerLevel level = state.level;
		long chunk = pos.pack();
		TerrainViewer.Snapshot snapshot = viewer.snapshot();
		viewer.pending.add(chunk);
		pace.startReading(1);
		vanillaReads.incrementAndGet();
		// 色にできたら色、地物を置き終えていなければ UNREADY、セーブになければ null。
		// 頼むところで投げても、失敗として同じ後始末を通す
		CompletableFuture<TerrainTile> read;
		try {
			read = ((ChunkMapInvoker) level.getChunkSource().chunkMap).minimap$readChunk(pos)
					.thenApplyAsync(data -> {
						if (data.isEmpty()) {
							return null;
						}
						StoredChunk blocks = StoredChunk.read(level, data.get());
						return blocks == null ? UNREADY : TerrainColors.tile(pos, blocks, ceiling, snapshot.feetY()).withStamp(stamp);
					}, workers.executor());
		} catch (RuntimeException e) {
			read = CompletableFuture.failedFuture(e);
		}
		workers.complete(read, (tile, error) -> {
			pace.finishReading(1);
			if (error != null) {
				state.failed.put(chunk, stamp);
				logFailure("Failed to read terrain of chunk " + pos, error);
			} else if (tile == UNREADY) {
				state.unready.put(chunk, stamp);
			}
			if (!viewer.sameSession(snapshot)) {
				return;
			}
			viewer.pending.remove(chunk);
			if (error == null && tile != null && tile != UNREADY && viewer.accepts(snapshot) && state.inMemory(chunk) == null) {
				viewer.deliver(tile);
			}
		});
	}

	// 地図に要るのは地形と地物だけなので、光の計算やエンティティの読み込みをする FULL までは進めない（FEATURES のまま保存される）。
	// getChunkFuture だけでは 1 tick で札が外れ、生成に時間がかかるチャンクは途中で捨てられるので、生成し終えるまで外れない札を付ける。
	// getChunkFuture はメインスレッドから呼ぶと生成し終えるまで待つので、自前のスレッドから呼ぶ（メインスレッドに頼むだけになる）。
	// セーブにあったチャンク（ほかの誰かが生成した、地物を置く前で止まっていた）は、vanilla が読んで続きから進める
	private void generate(TerrainLevel state, long chunk, boolean background) {
		pace.startGenerating(background);
		generations.incrementAndGet();
		state.generating.add(chunk);
		ChunkPos pos = ChunkPos.unpack(chunk);
		ServerChunkCache source = state.level.getChunkSource();
		source.addTicket(new Ticket(TICKET, ChunkLevel.byStatus(ChunkStatus.FEATURES)), pos);
		CompletableFuture<ChunkAccess> generated;
		try {
			generated = CompletableFuture.supplyAsync(() -> source.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FEATURES, true), workers.executor())
					.thenCompose(future -> future)
					.thenApply(result -> result.orElse(null));
		} catch (RejectedExecutionException e) {
			generated = CompletableFuture.failedFuture(e);
		}
		workers.complete(generated, (result, error) -> {
			pace.finishGenerating(background);
			state.generating.remove(chunk);
			if (result != null && Minimap.isActive()) {
				state.touch(chunk, clock);
				sendGenerated(state, result);
			}
		});
	}

	// 見ている人に送る
	private void sendGenerated(TerrainLevel state, ChunkAccess chunk) {
		long key = chunk.getPos().pack();
		Identifier dimension = state.level.dimension().identifier();
		boolean ceiling = state.level.dimensionType().hasCeiling();
		for (TerrainViewer viewer : viewers.values()) {
			if (viewer.active && dimension.equals(viewer.dimension) && viewer.contains(chunk.getPos().x(), chunk.getPos().z()) && !viewer.pending.contains(key)) {
				sendProto(viewer, chunk, state.memoryStamp(key, clock), ceiling);
			}
		}
	}

	private TerrainLevel state(ServerLevel level) {
		return levels.computeIfAbsent(level.dimension(), key -> new TerrainLevel(level, workers));
	}
}
