package com.omuraisu.minimap;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

// 地図の地形を作る自前のスレッドと、その結果をメインスレッドに渡す列。
// 自前のスレッドは色を作るまでで、見ている人や次元の状態に触るのは、メインスレッドが drain で結果を動かすときだけ
final class TerrainWorkers {
	// セーブを読んで色にするスレッドの数
	static final int COUNT = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 8);

	private final ExecutorService executor = Executors.newFixedThreadPool(COUNT, new Factory());
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();

	Executor executor() {
		return executor;
	}

	void submit(Runnable task) {
		try {
			executor.execute(task);
		} catch (RejectedExecutionException e) {
			// サーバーを止めている
		}
	}

	// 自前のスレッドから、メインスレッドで動かす結果を渡す
	void complete(Runnable result) {
		results.add(result);
	}

	// メインスレッドで、tick の始めに届いた結果を動かす
	void drain() {
		for (Runnable result = results.poll(); result != null; result = results.poll()) {
			result.run();
		}
	}

	void close() {
		executor.shutdownNow();
	}

	private static final class Factory implements ThreadFactory {
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
