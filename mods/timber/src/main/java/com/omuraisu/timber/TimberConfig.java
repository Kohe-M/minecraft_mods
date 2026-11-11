package com.omuraisu.timber;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

// config/timber.txt に 1 行 1 つ「キー=値」。いまの項目は enabled（G キーで切り替える、自分の分のオン/オフ）だけ。
// 知らない行は無視するので、項目を足しても前の値を読める。書くのはクライアントだけだが、
// サーバーのゲームテストから読み方を確かめられるよう main に置く
public final class TimberConfig {
	public static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("timber.txt");

	private static final Logger LOGGER = LogUtils.getLogger();

	private TimberConfig() {
	}

	// 読めないとき・書いていないときはオン
	public static boolean loadEnabled(Path file) {
		List<String> lines;
		try {
			lines = Files.readAllLines(file);
		} catch (IOException e) {
			return true;
		}
		boolean enabled = true;
		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator > 0 && line.substring(0, separator).strip().equals("enabled")) {
				enabled = !line.substring(separator + 1).strip().equals("false");
			}
		}
		return enabled;
	}

	// 書けなくても遊ぶのには困らないが、黙って捨てずにログに出す
	public static void saveEnabled(Path file, boolean enabled) {
		try {
			Files.writeString(file, "enabled=" + enabled + "\n");
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}: {}", file, e.toString());
		}
	}
}
