package com.omuraisu.minimap;

import java.util.Optional;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

// マップのピン。ファイルには 1 行に「次元 x z 名前」で書く。名前はなくてもよい
public record Pin(Identifier dimension, int x, int z, String name) {
	public static final int NAME_MAX_LENGTH = 32;
	public static final StreamCodec<RegistryFriendlyByteBuf, Pin> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, Pin::dimension,
			ByteBufCodecs.VAR_INT, Pin::x,
			ByteBufCodecs.VAR_INT, Pin::z,
			ByteBufCodecs.stringUtf8(NAME_MAX_LENGTH), Pin::name,
			Pin::new);

	// 地形に紛れない色。抜いても他のピンの色が変わらず、誰の画面でも同じになるよう座標から決める
	private static final int[] COLORS = {0xFFE5484D, 0xFF3E8BFF, 0xFFFFD23F, 0xFFD14FE0, 0xFF38D6D6, 0xFFFF8A2B};

	public Pin(Identifier dimension, int x, int z) {
		this(dimension, x, z, "");
	}

	public int color() {
		return COLORS[Math.floorMod(31 * x + z, COLORS.length)];
	}

	public boolean samePlace(Pin other) {
		return dimension.equals(other.dimension) && x == other.x && z == other.z;
	}

	// 1 行に 1 本で保存するので、改行などの制御文字は空白にする
	public Pin withName(String name) {
		String clean = name.codePoints()
				.map(c -> Character.isISOControl(c) ? ' ' : c)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString()
				.strip();
		if (clean.length() > NAME_MAX_LENGTH) {
			clean = clean.substring(0, NAME_MAX_LENGTH).strip();
		}
		return new Pin(dimension, x, z, clean);
	}

	public String line() {
		return (dimension + " " + x + " " + z + " " + name).strip();
	}

	public static Optional<Pin> parse(String line) {
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
}
