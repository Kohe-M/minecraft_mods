package com.omuraisu.jori;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

// config/jori.txt。1 行に「mod ID=true」か「mod ID=false」。書いていない mod は有効。
// 行ごとに読み、「=」のない行は無視する（キーと値の前後の空白も無視する）
public final class JoriConfig {
	public static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("jori.txt");

	private static final Logger LOGGER = LogUtils.getLogger();

	// 知らないキーも消さずに書き戻す（専用サーバーとクライアントで書く mod が違う）
	private final Map<String, Boolean> enabled = new TreeMap<>();

	private JoriConfig() {
	}

	public static JoriConfig load() {
		JoriConfig config = new JoriConfig();
		List<String> lines;
		try {
			lines = Files.readAllLines(FILE);
		} catch (IOException e) {
			return config;
		}
		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator > 0) {
				config.enabled.put(line.substring(0, separator).strip(), !line.substring(separator + 1).strip().equals("false"));
			}
		}
		return config;
	}

	public boolean isEnabled(String id) {
		return enabled.getOrDefault(id, true);
	}

	public void set(String id, boolean value) {
		enabled.put(id, value);
	}

	// 書いていない mod を有効として書き足す。どの mod を切り替えられるかがファイルを見て分かるように
	public void fill(List<Feature> features) {
		for (Feature feature : features) {
			enabled.putIfAbsent(feature.id(), true);
		}
	}

	// 書けなくても切り替えはこの起動の間は効くが、黙って捨てずにログに出す
	public void save() {
		StringBuilder text = new StringBuilder();
		enabled.forEach((id, value) -> text.append(id).append('=').append(value).append('\n'));
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, text);
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}: {}", FILE, e.toString());
		}
	}
}
