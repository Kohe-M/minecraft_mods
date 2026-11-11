package com.omuraisu.minimap;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

// 地図の地形を作る自前のスレッドと、その結果をメインスレッドに渡す列。
// 自前のスレッドは色を作るまでで、見ている人や次元の状態に触るのは、メインスレッドが drain で後始末を動かすときだけ。
// 仕事は「自前のスレッドでする仕事」と「メインスレッドで動かす後始末」を組で受け取る。仕事が何で失敗しても
// （RuntimeException でない Error も）後始末は必ず列に入るので、読み込みの予算や待っているチャンクの印を戻し忘れない
public final class TerrainWorkers {
	// セーブを読んで色にするスレッドの数
	static final int COUNT = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 8);

	private final ExecutorService executor = Executors.newFixedThreadPool(COUNT, new Factory());
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();

	// CompletableFuture の非同期の段を動かす。結果は complete(future, finish) で受け取る
	Executor executor() {
		return executor;
	}

	// work を自前のスレッドで動かし、メインスレッドで finish に結果と失敗（なければ null）を渡す。
	// work が投げたら結果は null。止めた後で動かせなかったときも、失敗として finish を列に入れる
	public <T> void run(Supplier<T> work, BiConsumer<? super T, Throwable> finish) {
		try {
			executor.execute(() -> {
				T result = null;
				Throwable failure = null;
				try {
					result = work.get();
				} catch (Throwable e) {
					failure = e;
				}
				finish(result, failure, finish);
			});
		} catch (RejectedExecutionException e) {
			finish(null, e, finish);
		}
	}

	// future が終わったら（失敗しても）、メインスレッドで finish に結果と失敗を渡す
	<T> void complete(CompletableFuture<T> future, BiConsumer<? super T, Throwable> finish) {
		future.whenComplete((result, failure) -> finish(result, failure, finish));
	}

	private <T> void finish(T result, Throwable failure, BiConsumer<? super T, Throwable> finish) {
		results.add(() -> finish.accept(result, failure));
	}

	// メインスレッドで、tick の始めに届いた後始末を動かす
	public void drain() {
		for (Runnable result = results.poll(); result != null; result = results.poll()) {
			result.run();
		}
	}

	public void close() {
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
