package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;

import java.util.ArrayList;
import java.util.List;

import com.omuraisu.minimap.TerrainPace;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.testkit.Scenario;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

// 遠くの地形の負荷: 4K で一番引いた地図で、サーバーが遅れず、ワールドに読み込まない
public class TerrainLoadTest implements Scenario {
	@Override
	public String name() {
		return "load";
	}

	@Override
	public void run(ClientGameTestContext context) {
		watchFullLoads();
		resetConfig(context);
		testMemoryAfterGc();
		testFarTerrainLoad(context);
	}

	// 遅れの判定に使うヒープの量は、直近の GC の後に残った量。その瞬間の使用量と違い、まだ回収していないごみでは増えない
	private static void testMemoryAfterGc() {
		Runtime runtime = Runtime.getRuntime();
		List<byte[]> garbage = new ArrayList<>();
		for (int i = 0; i < 64; i++) {
			garbage.add(new byte[4 << 20]);
		}
		long withGarbage = runtime.totalMemory() - runtime.freeMemory();
		garbage.clear();
		System.gc();
		long afterGc = TerrainPace.usedAfterGc();
		System.out.printf("memory-after-gc: %d MB with 256 MB of garbage, %d MB after GC%n", withGarbage >> 20, afterGc >> 20);
		if (afterGc <= 0 || afterGc > withGarbage - (128 << 20)) {
			throw new AssertionError("heap after GC should leave out the garbage: " + (afterGc >> 20) + " MB after GC, "
					+ (withGarbage >> 20) + " MB with 256 MB of garbage");
		}
	}

	// 4K で一番引いても、サーバーが遅れず、地図のためにチャンクをワールドへ読み込まない。
	// 以前は描画距離の外を 1 つずつ FULL まで読み込み、ヒープを使い切ってサーバーが落ちていた
	private static void testFarTerrainLoad(ClientGameTestContext context) {
		TestWorldSave save;
		int generated;
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(creator -> creator.setSeed("far terrain load"))
				.create()) {
			save = singleplayer.getWorldSave();
			singleplayer.getConnection().waitForChunksRender();
			openFarMap(context);
			watchServer(context, singleplayer, "far-terrain-load", 600);
			generated = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.count(OVERWORLD));
			toggleMap(context);
			if (generated < 1000) {
				throw new AssertionError("far terrain should keep generating but only " + generated + " chunks arrived");
			}
		}
		// 開き直すと、前に届いた色をクライアントのファイルからすぐ出す。メモリにあったチャンクの色は版が残らないので、
		// サーバーがセーブから読み直す。開いていない間の範囲は開く前から読み直し始めるので、入ったときから数える
		int before = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.received());
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			openFarMap(context);
			context.waitTicks(10);
			int shown = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.count(OVERWORLD));
			if (shown < generated) {
				throw new AssertionError("far terrain saved on the client should be shown right after opening: " + shown + " / " + generated);
			}
			watchServer(context, singleplayer, "far-terrain-load-reopened", 200);
			int refreshed = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.received()) - before;
			int[] reads = singleplayer.getServer().computeOnServer(server -> TerrainServer.readCounts());
			System.out.printf("far-terrain-load-reopened: shown %d, refreshed %d, read %d (vanilla %d)%n", shown, refreshed, reads[0], reads[1]);
			// リージョンファイルを自前で読めている（vanilla の IO スレッドに頼っていない）
			if (reads[0] < 100 || reads[1] * 10 > reads[0]) {
				throw new AssertionError("most chunks should be read from the region files directly but " + reads[1] + " / " + reads[0] + " fell back");
			}
			toggleMap(context);
		}
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
	}

	private static void openFarMap(ClientGameTestContext context) {
		context.getInput().resizeWindow(3840, 2160);
		context.waitTicks(3);
		toggleMap(context);
		context.getInput().setCursorPos(1920, 1080);
		scroll(context, -6);
		assertScale(context, 1);
	}

	// ticks の間、サーバーが遅れず、地図のためにチャンクをワールドへ読み込んでいないことを確かめる
	private static void watchServer(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, int ticks) {
		ServerWatch watch = new ServerWatch(singleplayer);
		for (int waited = 0; waited < ticks; waited += 100) {
			context.waitTicks(Math.min(100, ticks - waited));
			watch.sample();
		}
		context.takeScreenshot(name);
		watch.check(context, name);
	}
}
