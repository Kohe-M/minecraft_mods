package com.omuraisu.minimap.client;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

// 今いるワールドのピン。1 行に「次元 x z」で保存する
public final class Pins {
	private static final Logger LOGGER = LoggerFactory.getLogger("minimap");

	public record Pin(Identifier dimension, int x, int z) {
		// 地形に紛れない色。抜いても他のピンの色が変わらないよう座標から決める
		private static final int[] COLORS = {0xFFE5484D, 0xFF3E8BFF, 0xFFFFD23F, 0xFFD14FE0, 0xFF38D6D6, 0xFFFF8A2B};

		public static Pin at(Player player) {
			return new Pin(player.level().dimension().identifier(), player.getBlockX(), player.getBlockZ());
		}

		public int color() {
			return COLORS[Math.floorMod(31 * x + z, COLORS.length)];
		}

		private long distanceSquared(Pin other) {
			long dx = x - other.x;
			long dz = z - other.z;
			return dx * dx + dz * dz;
		}
	}

	private final List<Pin> pins = new ArrayList<>();
	private Path file;

	public static Path serverFile(String address) {
		return FabricLoader.getInstance().getConfigDir().resolve("minimap")
				.resolve(URLEncoder.encode(address, StandardCharsets.UTF_8).replace("*", "%2A") + ".txt");
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
		if (!pins.contains(pin)) {
			pins.add(pin);
			save();
		}
	}

	// 同じ次元で origin に一番近いピンを抜く。なければ false
	public boolean removeNearest(Pin origin) {
		Optional<Pin> nearest = in(origin.dimension()).stream().min(Comparator.comparingLong(origin::distanceSquared));
		nearest.ifPresent(pin -> {
			pins.remove(pin);
			save();
		});
		return nearest.isPresent();
	}

	private static Optional<Pin> parse(String line) {
		String[] parts = line.strip().split(" ");
		if (parts.length != 3) {
			return Optional.empty();
		}
		Identifier dimension = Identifier.tryParse(parts[0]);
		if (dimension == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(new Pin(dimension, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		String text = pins.stream()
				.map(pin -> pin.dimension() + " " + pin.x() + " " + pin.z() + "\n")
				.collect(Collectors.joining());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			LOGGER.warn("Failed to save pins to {}", file, e);
		}
	}
}
