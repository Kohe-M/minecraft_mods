package com.omuraisu.testkit;

import java.util.Arrays;
import java.util.Locale;

import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// クライアントのゲームテストを、ほかと独立して走らせられる単位（シナリオ）に分ける。
// シナリオごとに gametest の fabric.mod.json の fabric-client-gametest に並べ、ワールドもシナリオの中で作る。
// -Pscenarios=hud,map（python tools/dev.py test <mod> --scenario hud）で、名前の合うシナリオだけを走らせる。指定がなければ全部。
// 始まりと終わりと、かかった時間をゲームのログ（logs/latest.log）に出す。dev.py test がこれを拾って、記録と結果の行に載せる
public interface Scenario extends FabricClientGameTest {
	String PROPERTY = "testkit.scenarios";

	String name();

	void run(ClientGameTestContext context);

	@Override
	default void runTest(ClientGameTestContext context) {
		// System.out はゲームのログに入らないので、ロガーに出す
		var logger = LoggerFactory.getLogger("testkit");
		if (!selected(name())) {
			logger.info("scenario {}: skipped ({}={})", name(), PROPERTY, System.getProperty(PROPERTY));
			return;
		}
		logger.info("scenario {}: start", name());
		long start = System.nanoTime();
		boolean done = false;
		try {
			run(context);
			done = true;
		} finally {
			// dev.py は「scenario <名前>: done in <秒> s」（失敗したら failed）を読む
			logger.info("scenario {}: {} in {} s", name(), done ? "done" : "failed",
					String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9));
		}
	}

	static boolean selected(String name) {
		String filter = System.getProperty(PROPERTY, "").strip();
		return filter.isEmpty() || Arrays.stream(filter.split(",")).map(String::strip).anyMatch(name::equals);
	}
}
