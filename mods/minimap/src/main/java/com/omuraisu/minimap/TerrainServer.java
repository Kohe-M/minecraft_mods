package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.logging.LogUtils;
import com.omuraisu.minimap.mixin.ChunkMapInvoker;

import org.slf4j.Logger;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// マップ画面に描画距離の外を出すため、頼まれたチャンクの地形の色を送り返す。
// 生成済みのチャンクはワールドに読み込まず、セーブから読んで別のスレッドで色にする（札・エンティティ・保存が起きない）。
// 未生成のチャンクは、同時に生成する数を絞り、サーバーが遅れていない間だけ生成する
public final class TerrainServer {
	// プレイヤーのいるチャンクからこの距離（チャンク）までしか送らない。どの解像度でも一番引いた地図が収まる
	public static final int RADIUS = 192;
	// セーブを読み始める数（サーバー全体で 1 tick あたり）。読み込みは IO スレッドでワールドの保存より先に回されるので、
	// 保存を待たせ続けない量にする
	private static final int READS_PER_TICK = 64;
	private static final int MAX_READING = 256;
	// 読み込まれているチャンクから色を出すのはメインスレッドなので、1 tick に使う時間を絞る
	private static final long LOADED_BUDGET_NANOS = 2_000_000;
	// 同時に生成する数。サーバーの tick がこれより遅れている間は新しく始めない
	private static final int MAX_GENERATING = 16;
	private static final long HEALTHY_TICK_NANOS = 30_000_000;
	// 答えたチャンクは、クライアントが次の依頼に反映するまで答え直さない
	private static final int ANSWER_TICKS = 40;
	// 生成し終えてから 1 秒で外れる。登録はしない（保存されない札なので名前は要らない）
	private static final TicketType TICKET = new TicketType(20, TicketType.FLAG_LOADING);

	private static final Logger LOGGER = LogUtils.getLogger();

	private static TerrainServer current;

	private final MinecraftServer server;
	private final Map<UUID, Viewer> viewers = new HashMap<>();
	private final Map<ResourceKey<Level>, LongSet> generatingChunks = new HashMap<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Minimap Terrain");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private int reading;
	// 生成の前にセーブを確かめている分も数える
	private int generating;
	private boolean failureLogged;

	private TerrainServer(MinecraftServer server) {
		this.server = server;
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> current = new TerrainServer(server));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (current != null && current.server == server) {
				current.worker.shutdownNow();
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
	}

	public static boolean isInRange(ChunkPos center, int x, int z) {
		return Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) <= RADIUS;
	}

	private void request(ServerPlayer player, TerrainRequest payload) {
		if (!Minimap.isActive()) {
			return;
		}
		Viewer viewer = viewers.computeIfAbsent(player.getUUID(), id -> new Viewer());
		viewer.read = toArray(payload.read());
		viewer.generate = toArray(payload.generate());
	}

	private void tick() {
		if (!Minimap.isActive()) {
			viewers.values().forEach(Viewer::cancel);
			return;
		}
		int now = server.getTickCount();
		List<Viewer> active = new ArrayList<>();
		List<ServerPlayer> players = new ArrayList<>();
		for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Viewer viewer = entry.getValue();
			if (player == null) {
				continue;
			}
			viewer.flushMissing(player);
			viewer.prune(now);
			if (viewer.read.length > 0 || viewer.generate.length > 0) {
				active.add(viewer);
				players.add(player);
			}
		}
		if (active.isEmpty()) {
			return;
		}

		long deadline = System.nanoTime() + LOADED_BUDGET_NANOS;
		boolean healthy = server.getAverageTickTimeNanos() < HEALTHY_TICK_NANOS;
		int reads = Math.max(0, Math.min(READS_PER_TICK, MAX_READING - reading));
		int share = Math.max(1, (reads + active.size() - 1) / active.size());
		// 見ている人が何人いても順に先を譲る
		for (int i = 0; i < active.size(); i++) {
			int index = (i + now) % active.size();
			reads -= process(active.get(index), players.get(index), now, deadline, healthy, Math.min(share, reads));
		}
	}

	// 頼まれた順に、読み込まれているものはすぐ、セーブにあるものは読んで、未生成のものは生成して送る。
	// 生成は読む方とは別に数え、読む列が長くても止まらないようにする。セーブを読み始めた数を返す（生成の前の確かめは数えない）
	private int process(Viewer viewer, ServerPlayer player, int now, long deadline, boolean healthy, int reads) {
		ServerLevel level = player.level();
		if (viewer.level != level.dimension()) {
			viewer.level = level.dimension();
			viewer.answered.clear();
		}
		ChunkPos center = player.chunkPosition();
		LongSet generatingHere = generatingChunks.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
		for (long chunk : viewer.generate) {
			if (!viewer.isWaiting(chunk, center, now) || generatingHere.contains(chunk)) {
				continue;
			}
			ChunkAccess loaded = inMemory(level, chunk);
			if (loaded != null) {
				if (System.nanoTime() < deadline) {
					sendLoaded(viewer, player, level, loaded, now);
				}
			} else if (healthy && generating < MAX_GENERATING) {
				// 他のプレイヤーが生成したかもしれないので、先にセーブを確かめる
				generating++;
				read(viewer, player, level, chunk, true);
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
					sendLoaded(viewer, player, level, loaded, now);
				}
			} else if (started < reads) {
				read(viewer, player, level, chunk, false);
				started++;
			}
		}
		return started;
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

	private void sendLoaded(Viewer viewer, ServerPlayer player, ServerLevel level, ChunkAccess chunk, int now) {
		ChunkPos pos = chunk.getPos();
		send(viewer, player, TerrainColors.tile(level.dimension().identifier(), pos, TerrainColors.of(chunk),
				level.dimensionType().hasCeiling(), player.getBlockY()), now);
	}

	private void send(Viewer viewer, ServerPlayer player, TerrainTile tile, int now) {
		viewer.answered.put(tile.chunk(), now);
		if (ServerPlayNetworking.canSend(player, TerrainTile.TYPE)) {
			ServerPlayNetworking.send(player, tile);
		}
	}

	// IO スレッドで読み、色にするのは自前のスレッド。メインスレッドは結果を送るだけ
	private void read(Viewer viewer, ServerPlayer player, ServerLevel level, long chunk, boolean generate) {
		ChunkPos pos = ChunkPos.unpack(chunk);
		ResourceKey<Level> dimension = level.dimension();
		boolean ceiling = level.dimensionType().hasCeiling();
		int feetY = player.getBlockY();
		viewer.pending.add(chunk);
		reading++;
		((ChunkMapInvoker) level.getChunkSource().chunkMap).minimap$readChunk(pos)
				.thenApplyAsync(tag -> tag.map(data -> StoredChunk.read(level, data))
						.map(blocks -> TerrainColors.tile(dimension.identifier(), pos, blocks, ceiling, feetY))
						.orElse(null), worker)
				.whenComplete((tile, error) -> server.execute(() -> finishRead(viewer, player.getUUID(), dimension, chunk, tile, error, generate)));
	}

	private void finishRead(Viewer viewer, UUID id, ResourceKey<Level> dimension, long chunk, TerrainTile tile, Throwable error, boolean generate) {
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
			if (error != null) {
				if (!failureLogged) {
					failureLogged = true;
					LOGGER.warn("Failed to read terrain of chunk {}", ChunkPos.unpack(chunk), error);
				}
				viewer.answered.put(chunk, now);
			} else if (inMemory(level, chunk) != null) {
				// 読んでいる間に読み込まれた。セーブより新しいかもしれないので、次の tick にメモリの方から送る
			} else if (tile != null) {
				send(viewer, player, tile, now);
			} else if (generate) {
				if (isInRange(player.chunkPosition(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
					generate(level, chunk);
					generationStarted = true;
				}
			} else {
				viewer.missing.add(chunk);
				viewer.answered.put(chunk, now);
			}
		} finally {
			if (generate && !generationStarted) {
				generating--;
			}
		}
	}

	// 地図に要るのは地形と地物だけなので、光の計算やエンティティの読み込みをする FULL までは進めない（FEATURES のまま保存される）。
	// getChunkFuture だけでは 1 tick で札が外れ、生成に時間がかかるチャンクは途中で捨てられるので、生成し終えるまで外れない札を付ける
	private void generate(ServerLevel level, long chunk) {
		LongSet generatingHere = generatingChunks.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
		generatingHere.add(chunk);
		ChunkPos pos = ChunkPos.unpack(chunk);
		level.getChunkSource().addTicket(new Ticket(TICKET, ChunkLevel.byStatus(ChunkStatus.FEATURES)), pos);
		level.getChunkSource().getChunkFuture(pos.x(), pos.z(), ChunkStatus.FEATURES, true)
				.whenComplete((result, error) -> server.execute(() -> {
					if (current != this) {
						return;
					}
					generating--;
					generatingHere.remove(chunk);
					ChunkAccess generated = result == null ? null : result.orElse(null);
					if (generated != null && Minimap.isActive()) {
						sendGenerated(level, generated);
					}
				}));
	}

	// 頼んでいるプレイヤーに送る
	private void sendGenerated(ServerLevel level, ChunkAccess chunk) {
		long key = chunk.getPos().pack();
		int now = server.getTickCount();
		for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Viewer viewer = entry.getValue();
			if (player != null && player.level() == level && viewer.wants(key)) {
				send(viewer, player, TerrainColors.tile(level.dimension().identifier(), chunk.getPos(), TerrainColors.of(chunk),
						level.dimensionType().hasCeiling(), player.getBlockY()), now);
			}
		}
	}

	private static long[] toArray(List<Long> chunks) {
		long[] array = new long[chunks.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = chunks.get(i);
		}
		return array;
	}

	private static final class Viewer {
		private static final long[] NONE = new long[0];

		private long[] read = NONE;
		private long[] generate = NONE;
		private ResourceKey<Level> level;
		// セーブを読んでいるチャンク
		private final LongSet pending = new LongOpenHashSet();
		// 答えた tick
		private final Long2IntMap answered = new Long2IntOpenHashMap();
		// まとめて送る未生成のチャンク
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

		private void prune(int now) {
			if (now % ANSWER_TICKS == 0) {
				answered.long2IntEntrySet().removeIf(entry -> now - entry.getIntValue() >= ANSWER_TICKS);
			}
		}

		private void flushMissing(ServerPlayer player) {
			if (missing.isEmpty() || level == null) {
				return;
			}
			if (ServerPlayNetworking.canSend(player, TerrainMissing.TYPE)) {
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
			missing.clear();
		}
	}
}
