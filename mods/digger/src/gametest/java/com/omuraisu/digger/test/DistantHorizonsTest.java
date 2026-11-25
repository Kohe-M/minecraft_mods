package com.omuraisu.digger.test;

import com.omuraisu.digger.DigSettings;
import com.omuraisu.digger.Digger;
import com.omuraisu.digger.DiggerConfig;
import com.omuraisu.digger.client.DiggerClient;
import com.omuraisu.testkit.Lods;
import com.omuraisu.testkit.Scenario;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

// Distant Horizons と併用していても、バニラの線の経路で枠線が出る
public class DistantHorizonsTest implements Scenario {
	@Override
	public String name() {
		return "distant-horizons";
	}

	@Override
	public void run(ClientGameTestContext context) {
		if (!Lods.isLoaded()) {
			return;
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestScene scene = TestScene.build(context, singleplayer);
			context.getInput().lookAt(scene.areaTarget);
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			Lods.waitForRendering(context);
			Lods.disableClouds(context);
			// LOD の生成と色が落ち着くまで待つ
			context.waitTicks(60);

			set(context, new DigSettings(true, 3, 3, 1, false));
			context.waitTicks(5);
			int on = OutlinePixels.count(context.takeScreenshot("digger-dh-outline-on"));

			set(context, new DigSettings(false, 3, 3, 1, false));
			context.waitTicks(5);
			int off = OutlinePixels.count(context.takeScreenshot("digger-dh-outline-off"));

			if (on < 50 || off > 20) {
				throw new AssertionError("outline should be visible with Distant Horizons but was " + on + " on and " + off + " off");
			}
		} finally {
			Lods.restoreClouds(context);
			context.runOnClient(client -> Digger.setActive(true));
			set(context, DigSettings.DEFAULT);
		}
	}

	private static void set(ClientGameTestContext context, DigSettings settings) {
		DiggerConfig.save(DiggerClient.CONFIG, settings);
		context.runOnClient(client -> DiggerClient.load());
		context.waitTicks(3);
	}
}
