package com.omuraisu.testkit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeBufferRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

// Distant Horizons（DH）が入っているときに、LOD（遠景）がいま描かれているかを見る。
// LOD のバッファを 1 つ描くたびに 1 足し、待つ前と比べる。
// isLoaded() は DH のクラスに触れないので、DH のない起動でもこのクラスを読み込める。
public final class Lods {
	private Lods() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("distanthorizons");
	}

	// LOD が描かれるまで待つ。DH の生成を待つので 60 秒ほど見る
	public static void waitForRendering(ClientGameTestContext context) {
		requireLoaded();
		Dh.install();
		int start = Dh.rendered.get();
		// 1 回 10 tick（0.5 秒）ずつ、最大 60 秒
		for (int i = 0; i < 120 && Dh.rendered.get() == start; i++) {
			context.waitTicks(10);
		}
		if (Dh.rendered.get() == start) {
			throw new AssertionError("LODs should be rendered but no buffer was drawn in 60 s");
		}
	}

	// いまから ticks の間、LOD が 1 つも描かれないことを確かめる
	public static void assertNotRendering(ClientGameTestContext context, int ticks) {
		requireLoaded();
		Dh.install();
		int start = Dh.rendered.get();
		context.waitTicks(ticks);
		int drawn = Dh.rendered.get() - start;
		if (drawn != 0) {
			throw new AssertionError("LODs should not be rendered but " + drawn + " buffers were drawn in " + ticks + " ticks");
		}
	}

	// DH の雲はバニラの設定では消えず、動いて画素の比較が揺れる
	public static void disableClouds(ClientGameTestContext context) {
		if (isLoaded()) {
			Dh.withGraphics(context, graphics -> graphics.genericRendering().cloudRenderingEnabled().setValue(false, "gametest"));
		}
	}

	public static void restoreClouds(ClientGameTestContext context) {
		if (isLoaded()) {
			Dh.withGraphics(context, graphics -> graphics.genericRendering().cloudRenderingEnabled().clearValue());
		}
	}

	// バニラの画素を LOD の色に混ぜるフェードを、この距離（チャンクの割合）から掛ける。小さくすると近くで掛かる
	public static void setOverdrawPreventionRadius(ClientGameTestContext context, float radius) {
		if (isLoaded()) {
			Dh.withGraphics(context, graphics -> graphics.overdrawPreventionRadius().setValue(radius, "gametest"));
		}
	}

	public static void clearOverdrawPreventionRadius(ClientGameTestContext context) {
		if (isLoaded()) {
			Dh.withGraphics(context, graphics -> graphics.overdrawPreventionRadius().clearValue());
		}
	}

	private static void requireLoaded() {
		if (!isLoaded()) {
			throw new AssertionError("Distant Horizons is not installed");
		}
	}

	// DH のクラスを参照するのはここだけ。isLoaded() が true のときだけ読み込まれる
	private static final class Dh {
		private static final AtomicInteger rendered = new AtomicInteger();
		private static boolean installed;

		private static void install() {
			if (installed) {
				return;
			}
			installed = true;
			DhApi.events.bind(DhApiBeforeBufferRenderEvent.class, new DhApiBeforeBufferRenderEvent() {
				@Override
				public void beforeRender(DhApiEventParam<DhApiBeforeBufferRenderEvent.EventParam> event) {
					rendered.incrementAndGet();
				}
			});
		}

		// DH の設定は初期化が終わるまで null。ワールドに入った後でも、準備ができるまで待つ
		private static void withGraphics(ClientGameTestContext context, Consumer<IDhApiGraphicsConfig> action) {
			for (int i = 0; i < 200 && DhApi.Delayed.configs == null; i++) {
				context.waitTicks(5);
			}
			IDhApiConfig configs = DhApi.Delayed.configs;
			if (configs == null) {
				throw new AssertionError("Distant Horizons configs should be ready");
			}
			action.accept(configs.graphics());
		}
	}
}
