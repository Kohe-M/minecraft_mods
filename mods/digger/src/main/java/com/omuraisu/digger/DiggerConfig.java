package com.omuraisu.digger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

// config/digger.txt に 1 行 1 つ「キー=値」（area・size・depth・vein）。
// 行ごとに最初の = で分け、キーと値の前後の空白と知らない行は無視する。項目を足しても前の版の値を読める。
// 値が決まりの外ならその項目だけ既定に戻す。書くのはクライアントだけだが、サーバーのゲームテストから
// 読み方を確かめられるよう main に置く
public final class DiggerConfig {
	public static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("digger.txt");

	private static final Logger LOGGER = LogUtils.getLogger();

	private DiggerConfig() {
	}

	public static DigSettings load(Path file) {
		boolean area = DigSettings.DEFAULT.area();
		AreaSize size = AreaSize.THREE_BY_THREE;
		int depth = DigSettings.DEFAULT.depth();
		boolean vein = DigSettings.DEFAULT.vein();

		List<String> lines;
		try {
			lines = Files.readAllLines(file);
		} catch (IOException e) {
			return DigSettings.DEFAULT;
		}

		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator <= 0) {
				continue;
			}
			String key = line.substring(0, separator).strip();
			String value = line.substring(separator + 1).strip();
			switch (key) {
				case "area" -> {
					if (value.equals("true")) {
						area = true;
					} else if (value.equals("false")) {
						area = false;
					} else {
						area = DigSettings.DEFAULT.area();
					}
				}
				case "size" -> {
					AreaSize parsed = AreaSize.byId(value);
					size = parsed != null ? parsed : AreaSize.THREE_BY_THREE;
				}
				case "depth" -> {
					try {
						int parsed = Integer.parseInt(value);
						depth = parsed >= DigSettings.MIN_DEPTH && parsed <= DigSettings.MAX_DEPTH ? parsed : DigSettings.DEFAULT.depth();
					} catch (NumberFormatException ignored) {
						depth = DigSettings.DEFAULT.depth();
					}
				}
				case "vein" -> {
					if (value.equals("true")) {
						vein = true;
					} else if (value.equals("false")) {
						vein = false;
					} else {
						vein = DigSettings.DEFAULT.vein();
					}
				}
				default -> {
					// 知らない項目は読まない。保存するとこのファイルは 4 項目だけを書き直すので消える
				}
			}
		}

		return new DigSettings(area, size.width(), size.height(), depth, vein);
	}

	// 書けなくても遊ぶのには困らないが、黙って捨てずにログに出す
	public static void save(Path file, DigSettings settings) {
		String text = "area=" + settings.area() + "\n"
				+ "size=" + settings.width() + "x" + settings.height() + "\n"
				+ "depth=" + settings.depth() + "\n"
				+ "vein=" + settings.vein() + "\n";
		try {
			Files.writeString(file, text);
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}: {}", file, e.toString());
		}
	}
}
