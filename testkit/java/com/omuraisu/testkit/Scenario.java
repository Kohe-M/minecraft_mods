package com.omuraisu.testkit;

import java.util.Arrays;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// クライアントのゲームテストを、ほかと独立して走らせられる単位（シナリオ）に分ける。
// シナリオごとに gametest の fabric.mod.json の fabric-client-gametest に並べ、ワールドもシナリオの中で作る。
// -Pscenarios=hud,map（python tools/dev.py test <mod> --scenario hud）で、名前の合うシナリオだけを走らせる。指定がなければ全部
public interface Scenario extends FabricClientGameTest {
	String PROPERTY = "testkit.scenarios";

	String name();

	void run(ClientGameTestContext context);

	@Override
	default void runTest(ClientGameTestContext context) {
		if (selected(name())) {
			System.out.printf("scenario %s: start%n", name());
			run(context);
			System.out.printf("scenario %s: done%n", name());
		} else {
			System.out.printf("scenario %s: skipped (%s=%s)%n", name(), PROPERTY, System.getProperty(PROPERTY));
		}
	}

	static boolean selected(String name) {
		String filter = System.getProperty(PROPERTY, "").strip();
		return filter.isEmpty() || Arrays.stream(filter.split(",")).map(String::strip).anyMatch(name::equals);
	}
}
