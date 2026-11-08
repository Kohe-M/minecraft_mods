package com.omuraisu.minimap;

import java.util.List;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// 地図のためだけに生成しているチャンクを、vanilla の生成の列（ChunkTaskPriorityQueue）の一番後ろに回す。
// vanilla は列をチャンクのレベル（札の強さ）の順に処理し、地物の配置などは 1 本の列で順に動かす。地図の札は FEATURES までなので、
// 描画距離のすぐ外の下ごしらえ（縁のチャンクを仕上げるのに要る隣）と同じ帯に並び、プレイヤーの周りが出るのを遅らせていた。
// 列の中の順番だけを変え、どの段階まで生成するか（レベル）は変えない。ディメンションごとに 1 つ
public final class MapPriority {
	// 列の一番後ろ（ワールドから外れていくチャンクと同じ）
	public static final int LAST = ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT - 1;
	// これより強いチャンクは誰かがワールドに読み込もうとしているので動かさない
	private static final int FULL_LEVEL = ChunkLevel.byStatus(FullChunkStatus.FULL);
	// 地図の札が生成を及ぼす範囲（チャンク）
	private static final int TICKET_RADIUS = ChunkLevel.MAX_LEVEL - ChunkLevel.byStatus(ChunkStatus.FEATURES);

	// 生成の列のスレッドから読む。地図のために生成していなければ null
	private volatile Snapshot snapshot;

	// 生成の列が持つ。null なら vanilla のまま
	public interface Queue {
		void minimap$setPriority(MapPriority priority);

		MapPriority minimap$priority();
	}

	// メインスレッドから毎 tick 呼ぶ。プレイヤーの位置と生成しているチャンクを写す
	public void update(ServerLevel level, LongSet generating) {
		if (generating.isEmpty()) {
			snapshot = null;
			return;
		}
		List<ServerPlayer> players = level.players();
		long[] positions = new long[players.size()];
		for (int i = 0; i < positions.length; i++) {
			positions[i] = players.get(i).chunkPosition().pack();
		}
		var list = level.getServer().getPlayerList();
		// プレイヤーの札で読み込むチャンクと、それを仕上げるのに要る隣
		int playerRadius = Math.max(list.getViewDistance(), list.getSimulationDistance()) + 1 + ChunkLevel.RADIUS_AROUND_FULL_CHUNK;
		snapshot = new Snapshot(positions, playerRadius, generating.toLongArray());
	}

	void clear() {
		snapshot = null;
	}

	// 地図のために生成しているチャンクがあるか。テストから確かめる
	public boolean hasTargets() {
		return snapshot != null;
	}

	// 生成の列のスレッドから呼ぶ。チャンクの仕事を入れる列の段（小さいほど先）
	public int bucket(long chunk, int level) {
		Snapshot current = snapshot;
		return current != null && level > FULL_LEVEL && current.isMapOnly(chunk) ? LAST : level;
	}

	private record Snapshot(long[] players, int playerRadius, long[] targets) {
		// どのプレイヤーの札も届かず、地図の札だけが届くチャンク
		private boolean isMapOnly(long chunk) {
			int x = ChunkPos.getX(chunk);
			int z = ChunkPos.getZ(chunk);
			for (long player : players) {
				if (distance(player, x, z) <= playerRadius) {
					return false;
				}
			}
			for (long target : targets) {
				if (distance(target, x, z) <= TICKET_RADIUS) {
					return true;
				}
			}
			return false;
		}

		private static int distance(long chunk, int x, int z) {
			return Math.max(Math.abs(ChunkPos.getX(chunk) - x), Math.abs(ChunkPos.getZ(chunk) - z));
		}
	}
}
