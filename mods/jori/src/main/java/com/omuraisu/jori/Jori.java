package com.omuraisu.jori;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

// 各 mod は jar ごと中に入っていて、それぞれの初期化はそれぞれが行う。Jori は有効・無効を決めるだけ
public class Jori implements ModInitializer {
	@Override
	public void onInitialize() {
		// 専用サーバーは起動するときに設定ファイルを読む。シングルプレイの内蔵サーバーは、クライアントで切り替えた値をそのまま使う
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			if (!server.isDedicatedServer()) {
				return;
			}
			JoriConfig config = JoriConfig.load();
			config.fill(Feature.SERVER);
			config.save();
			for (Feature feature : Feature.SERVER) {
				feature.apply(config);
			}
		});
	}
}
