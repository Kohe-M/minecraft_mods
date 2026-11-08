package com.omuraisu.minimap.client;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

// 今いるワールドのピン。1 行に「次元 x z 名前」で保存する。名前はなくてもよい
public final class Pins {
	private static final Logger LOGGER = LoggerFactory.getLogger("minimap");

	public record Pin(Identifier dimension, int x, int z, String name) {
		// 地形に紛れない色。抜いても他のピンの色が変わらないよう座標から決める
		private static final int[] COLORS = {0xFFE5484D, 0xFF3E8BFF, 0xFFFFD23F, 0xFFD14FE0, 0xFF38D6D6, 0xFFFF8A2B};

		public Pin(Identifier dimension, int x, int z) {
			this(dimension, x, z, "");
		}

		public int color() {
			return COLORS[Math.floorMod(31 * x + z, COLORS.length)];
		}

		private boolean samePlace(Pin other) {
			return dimension.equals(other.dimension) && x == other.x && z == other.z;
		}
	}

	private final List<Pin> pins = new ArrayList<>();
	private Path file;

	public static Path serverFile(String address) {
		return FabricLoader.getInstance().getConfigDir().resolve("minimap").resolve(encodeAddress(address) + ".txt");
	}

	// サーバーのアドレスをファイル名に使える形にする
	static String encodeAddress(String address) {
		return URLEncoder.encode(address, StandardCharsets.UTF_8).replace("*", "%2A");
	}

	public void open(Path file) {
		this.file = file;
		pins.clear();
		if (file == null) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file);
		} catch (IOException e) {
			return;
		}
		lines.stream().map(Pins::parse).flatMap(Optional::stream).forEach(pins::add);
	}

	public void close() {
		file = null;
		pins.clear();
	}

	public List<Pin> in(Identifier dimension) {
		return pins.stream().filter(pin -> pin.dimension().equals(dimension)).toList();
	}

	public void place(Pin pin) {
		if (pins.stream().noneMatch(pin::samePlace)) {
			pins.add(pin);
			save();
		}
	}

	public void rename(Pin pin, String name) {
		int index = pins.indexOf(pin);
		if (index >= 0) {
			pins.set(index, new Pin(pin.dimension(), pin.x(), pin.z(), name.strip()));
			save();
		}
	}

	public void remove(Pin pin) {
		if (pins.remove(pin)) {
			save();
		}
	}

	private static Optional<Pin> parse(String line) {
		String[] parts = line.strip().split(" ", 4);
		if (parts.length < 3) {
			return Optional.empty();
		}
		Identifier dimension = Identifier.tryParse(parts[0]);
		if (dimension == null) {
			return Optional.empty();
		}
		try {
			String name = parts.length == 4 ? parts[3].strip() : "";
			return Optional.of(new Pin(dimension, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), name));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		String text = pins.stream()
				.map(pin -> (pin.dimension() + " " + pin.x() + " " + pin.z() + " " + pin.name()).strip() + "\n")
				.collect(Collectors.joining());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			LOGGER.warn("Failed to save pins to {}", file, e);
		}
	}
}
