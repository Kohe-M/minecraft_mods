package com.omuraisu.minimap;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

// tick ごとの処理の予算。サーバーが予定より遅れていないかを見て、読み始める数・同時に読む数・同時に生成する数・
// ワールドに読み込まれたチャンクから色を作る時間を決める。数えるのはサーバー全体（人数が増えても負担が増えない）
public final class TerrainPace {
	// 1 tick に読み始める数と、同時に読んでいる数。遅れている間は、読み終えた後の送る処理も減らすため少なくする
	private static final int READS_PER_TICK = 1024;
	private static final int READS_PER_TICK_BEHIND = 32;
	private static final int MAX_READING = TerrainWorkers.COUNT * 256;
	// マップ画面を開いていない人の範囲は、開いている人の後に回す。読み始めるのは開いている人が使った残りの予算で、
	// 生成は同時にこれまで（同時に生成する数の上限の方が小さければそちら）。遅れている間は進めない。
	// 値は計測で決めた（docs/mods/minimap.md）。1 秒 150 チャンク前後（開いているときの最大の約半分）を保てる一番小さい値で、
	// CPU を使い切らずクライアントの描画とチャンク構築に余裕を残す
	private static final int BACKGROUND_GENERATING = 16;
	// ワールドに読み込まれたチャンクから色を出すのはメインスレッドなので、1 tick に使う時間を絞る
	private static final long LOADED_BUDGET_NANOS = 2_000_000;
	// 同時に生成する数。遅れていなければ 1 秒ごとに増やし、遅れたら半分にする
	private static final int MIN_GENERATING = 4;
	private static final int MAX_GENERATING = 256;
	private static final int GENERATING_STEP = 8;
	// 直近 20 tick にかかった時間が予定の 1.1 倍を超えるか（tick の合間の処理も含む）、tick がこれより重いか、
	// 直近の GC の後に残ったヒープが上限の 85% を超えたら絞る。vanilla は予定より 1 秒遅れると Can't keep up を出す
	private static final int PACE_TICKS = 20;
	private static final long SLOW_TICK_NANOS = 40_000_000;
	private static final int MEMORY_PERCENT = 85;
	// ヒープのメモリプール。GC の後の使用量（getCollectionUsage）を持たないものは使わない
	private static final List<MemoryPoolMXBean> HEAP_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
			.filter(pool -> pool.getType() == MemoryType.HEAP && pool.getCollectionUsage() != null)
			.toList();

	private final long[] tickTimes = new long[PACE_TICKS];
	private boolean behind;
	private int reading;
	private int generating;
	// そのうち、マップ画面を開いていない人のために始めた数
	private int backgroundGenerating;
	private int generationLimit = 16;
	private int lastThrottle = Integer.MIN_VALUE / 2;
	// 同時に生成する数の上限のせいで、生成を始められなかった
	private boolean limited;

	// tick の始めに、遅れているかを決めて、同時に生成する数を調整する
	void startTick(MinecraftServer server, int now) {
		long time = Util.getNanos();
		long previous = tickTimes[now % PACE_TICKS];
		tickTimes[now % PACE_TICKS] = time;
		long expected = PACE_TICKS * server.tickRateManager().nanosecondsPerTick();
		boolean lowMemory = usedAfterGc() / MEMORY_PERCENT > Runtime.getRuntime().maxMemory() / 100;
		behind = previous != 0 && (time - previous) * 10 > expected * 11 || server.getAverageTickTimeNanos() > SLOW_TICK_NANOS || lowMemory;
		adjustGenerationLimit(now);
	}

	// 直近の GC の後に残ったヒープの量（プールごとの GC の後の使用量の和）。
	// その瞬間の使用量（totalMemory - freeMemory）は、まだ回収していないごみを含み、GC の前には上限近くまで膨らむ。
	// それで見ていたときは、ヒープが 3 GB だと、生きているのが 2 割ほどでもごみだけで 85% を超え、生成が 4 つまで絞られていた
	public static long usedAfterGc() {
		long used = 0;
		for (MemoryPoolMXBean pool : HEAP_POOLS) {
			MemoryUsage usage = pool.getCollectionUsage();
			if (usage != null) {
				used += usage.getUsed();
			}
		}
		return used;
	}

	// 遅れていなければ 1 秒ごとに増やし（上限のせいで生成を始められなかったときだけ）、遅れたら半分にする
	private void adjustGenerationLimit(int now) {
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

	boolean behind() {
		return behind;
	}

	// この tick に、ワールドに読み込まれたチャンクから色を作ってよい時刻（System.nanoTime）
	long loadedDeadline() {
		return System.nanoTime() + LOADED_BUDGET_NANOS;
	}

	// この tick に読み始めてよい数
	int readBudget() {
		return Math.max(0, Math.min(behind ? READS_PER_TICK_BEHIND : READS_PER_TICK, MAX_READING - reading));
	}

	void startReading(int count) {
		reading += count;
	}

	void finishReading(int count) {
		reading -= count;
	}

	boolean canGenerate(boolean background) {
		if (behind || background && backgroundGenerating >= BACKGROUND_GENERATING) {
			return false;
		}
		if (generating >= generationLimit) {
			limited = true;
			return false;
		}
		return true;
	}

	void startGenerating(boolean background) {
		generating++;
		if (background) {
			backgroundGenerating++;
		}
	}

	void finishGenerating(boolean background) {
		generating--;
		if (background) {
			backgroundGenerating--;
		}
	}
}
