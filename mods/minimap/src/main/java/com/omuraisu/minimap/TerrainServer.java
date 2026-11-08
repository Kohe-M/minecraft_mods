package com.omuraisu.minimap;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// マップ画面に描画距離の外を出すため、頼まれたチャンクを読み込んで（なければ生成して）地形の色を送り返す。
// 読み込みは非同期で、プレイヤーごとに同時に読み込む数を絞る
public final class TerrainServer {
	// プレイヤーのいるチャンクからこの距離（チャンク）までしか読み込まない。どの解像度でも一番引いた地図が収まる
	public static final int RADIUS = 192;
	// 1 つの読み込みは数 tick かかるので、まとめて頼んで待ち時間を重ねる
	private static final int MAX_LOADING = 256;
	// 読み込み終えてから 1 秒で外れる。登録はしない（保存されない札なので名前は要らない）
	private static final TicketType TICKET = new TicketType(20, TicketType.FLAG_LOADING);

	private static final Map<UUID, Queue> QUEUES = new HashMap<>();

	private TerrainServer() {
	}

	static void register() {
		ServerPlayNetworking.registerGlobalReceiver(TerrainRequest.TYPE, (payload, context) -> request(context.player(), payload.chunks()));
		ServerTickEvents.END_SERVER_TICK.register(TerrainServer::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> QUEUES.remove(handler.player.getUUID()));
	}

	public static boolean isInRange(ChunkPos center, int x, int z) {
		return Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) <= RADIUS;
	}

	private static void request(ServerPlayer player, List<Long> chunks) {
		Queue queue = QUEUES.computeIfAbsent(player.getUUID(), id -> new Queue());
		queue.waiting.clear();
		queue.waiting.addAll(chunks);
	}

	private static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Queue queue = QUEUES.get(player.getUUID());
			if (queue == null) {
				continue;
			}
			while (queue.loading.size() < MAX_LOADING && !queue.waiting.isEmpty()) {
				long chunk = queue.waiting.poll();
				if (!queue.loading.contains(chunk) && isInRange(player.chunkPosition(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
					load(player, queue, chunk);
				}
			}
		}
	}

	// 北の端の起伏を出すため、ひとつ北のチャンクも読み込む
	private static void load(ServerPlayer player, Queue queue, long chunk) {
		ServerLevel level = player.level();
		int x = ChunkPos.getX(chunk);
		int z = ChunkPos.getZ(chunk);
		Identifier dimension = level.dimension().identifier();
		boolean ceiling = level.dimensionType().hasCeiling();
		int feetY = player.getBlockY();

		queue.loading.add(chunk);
		CompletableFuture<ChunkResult<ChunkAccess>> self = loadChunk(level, x, z);
		CompletableFuture<ChunkResult<ChunkAccess>> north = loadChunk(level, x, z - 1);
		// 読み込み中に書き換えられないよう、色はメインスレッドで読む
		self.thenCombine(north, (a, b) -> new ChunkAccess[] {a.orElse(null), b.orElse(null)})
				.whenCompleteAsync((chunks, error) -> {
					queue.loading.remove(chunk);
					if (chunks == null || chunks[0] == null || player.hasDisconnected() || !ServerPlayNetworking.canSend(player, TerrainTile.TYPE)) {
						return;
					}
					ServerPlayNetworking.send(player, new TerrainTile(dimension, chunk, TerrainColors.chunk(chunks[0], chunks[1], ceiling, feetY)));
				}, level.getServer());
	}

	// getChunkFuture だけでは 1 tick で札が外れ、生成に時間がかかるチャンクは途中で捨てられる。
	// 読み込み終えるまで外れない札を付ける。tick はさせない
	private static CompletableFuture<ChunkResult<ChunkAccess>> loadChunk(ServerLevel level, int x, int z) {
		level.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(x, z), 0);
		return level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true);
	}

	private static final class Queue {
		private final ArrayDeque<Long> waiting = new ArrayDeque<>();
		private final LongSet loading = new LongOpenHashSet();
	}
}
