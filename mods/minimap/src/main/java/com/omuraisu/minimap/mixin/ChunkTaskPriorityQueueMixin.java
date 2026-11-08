package com.omuraisu.minimap.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.omuraisu.minimap.MapPriority;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.world.level.ChunkPos;

// 生成の列で、地図のためだけのチャンクの仕事を一番後ろの段に入れる（MapPriority）。
// vanilla は段をチャンクのレベルと同じにし、レベルが変わると古いレベルの段から仕事を探して移す。
// ここでは段がレベルと違うことがあるので、レベルの段と一番後ろの段の両方を探す。1 つのチャンクの仕事はいつも 1 つの段にまとめる。
// 列の操作は vanilla と同じく、生成の列のスレッドから 1 つずつ呼ばれる
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class ChunkTaskPriorityQueueMixin implements MapPriority.Queue {
	@Shadow
	@Final
	private List<Long2ObjectLinkedOpenHashMap<List<Runnable>>> queuesPerPriority;

	@Shadow
	private volatile int topPriorityQueueIndex;

	@Unique
	private volatile MapPriority minimap$priority;

	@Shadow
	public abstract boolean hasWork();

	@Override
	public void minimap$setPriority(MapPriority priority) {
		minimap$priority = priority;
	}

	@Override
	public MapPriority minimap$priority() {
		return minimap$priority;
	}

	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void minimap$submit(Runnable task, long chunk, int level, CallbackInfo ci) {
		MapPriority priority = minimap$priority;
		if (priority == null) {
			return;
		}
		int bucket = priority.bucket(chunk, level);
		// 前の仕事が別の段にあれば、順番を保って今の段へ移す
		int other = bucket == level ? MapPriority.LAST : level;
		List<Runnable> earlier = other == bucket ? null : queuesPerPriority.get(other).remove(chunk);
		List<Runnable> tasks = queuesPerPriority.get(bucket).computeIfAbsent(chunk, key -> new ArrayList<>());
		if (earlier != null) {
			tasks.addAll(earlier);
			minimap$skipEmpty();
		}
		tasks.add(task);
		topPriorityQueueIndex = Math.min(topPriorityQueueIndex, bucket);
		ci.cancel();
	}

	@Inject(method = "resortChunkTasks", at = @At("HEAD"), cancellable = true)
	private void minimap$resort(int oldLevel, ChunkPos pos, int newLevel, CallbackInfo ci) {
		MapPriority priority = minimap$priority;
		if (priority == null || oldLevel >= ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT) {
			return;
		}
		long chunk = pos.pack();
		List<Runnable> tasks = queuesPerPriority.get(oldLevel).remove(chunk);
		if (tasks == null && oldLevel != MapPriority.LAST) {
			tasks = queuesPerPriority.get(MapPriority.LAST).remove(chunk);
		}
		minimap$skipEmpty();
		if (tasks != null && !tasks.isEmpty()) {
			int bucket = priority.bucket(chunk, newLevel);
			queuesPerPriority.get(bucket).computeIfAbsent(chunk, key -> new ArrayList<>()).addAll(tasks);
			topPriorityQueueIndex = Math.min(topPriorityQueueIndex, bucket);
		}
		ci.cancel();
	}

	// 先頭の段が空になったら、次の空でない段まで進める
	@Unique
	private void minimap$skipEmpty() {
		while (hasWork() && queuesPerPriority.get(topPriorityQueueIndex).isEmpty()) {
			topPriorityQueueIndex++;
		}
	}
}
