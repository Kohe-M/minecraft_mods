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

import com.omuraisu.minimap.Pin;
import com.omuraisu.minimap.PinEdit;
import com.omuraisu.minimap.PinList;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

// 今いるワールドのピン。サーバーに mod があればサーバーにいる人で共有し（PinServer）、なければ手元のファイルに保存する
public final class Pins {
	private static final Logger LOGGER = LoggerFactory.getLogger("minimap");

	private final List<Pin> pins = new ArrayList<>();
	// サーバーに mod がないときに保存するファイル
	private Path file;
	private boolean shared;
	// 接続してからサーバーに送った変更の数。届いた一覧がそこまで反映していなければ、手元で先に変えた分が消えないよう捨てる
	private int sent;

	public static Path serverFile(String address) {
		return FabricLoader.getInstance().getConfigDir().resolve("minimap").resolve(encodeAddress(address) + ".txt");
	}

	// サーバーのアドレスをファイル名に使える形にする
	static String encodeAddress(String address) {
		return URLEncoder.encode(address, StandardCharsets.UTF_8).replace("*", "%2A");
	}

	// サーバーから一覧が届くまでは file のピンを出す。null なら一覧を待つ
	public void open(Path file) {
		close();
		this.file = file;
		if (file == null) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file);
		} catch (IOException e) {
			return;
		}
		lines.stream().map(Pin::parse).flatMap(Optional::stream).forEach(pins::add);
	}

	public void close() {
		file = null;
		pins.clear();
		shared = false;
		sent = 0;
	}

	public boolean isShared() {
		return shared;
	}

	public void receive(PinList list) {
		if (!shared) {
			shared = true;
			// サーバーに mod を入れる前に手元に刺していたピンは、共有に移して手元のファイルを消す
			List<Pin> local = List.copyOf(pins);
			Path legacy = file;
			file = null;
			pins.clear();
			pins.addAll(list.pins());
			local.forEach(this::place);
			if (legacy != null) {
				try {
					Files.deleteIfExists(legacy);
				} catch (IOException e) {
					LOGGER.warn("Failed to delete {}", legacy, e);
				}
			}
			return;
		}
		if (list.acknowledged() >= sent) {
			pins.clear();
			pins.addAll(list.pins());
		}
	}

	public List<Pin> in(Identifier dimension) {
		return pins.stream().filter(pin -> pin.dimension().equals(dimension)).toList();
	}

	public void place(Pin pin) {
		if (pins.stream().noneMatch(pin::samePlace)) {
			pins.add(pin);
			changed(PinEdit.Action.PLACE, pin);
		}
	}

	public void rename(Pin pin, String name) {
		int index = pins.indexOf(pin);
		if (index >= 0) {
			Pin renamed = pin.withName(name);
			pins.set(index, renamed);
			changed(PinEdit.Action.RENAME, renamed);
		}
	}

	public void remove(Pin pin) {
		if (pins.remove(pin)) {
			changed(PinEdit.Action.REMOVE, pin);
		}
	}

	// 手元にはすぐ反映し、共有しているならサーバーにも送る
	private void changed(PinEdit.Action action, Pin pin) {
		if (!shared) {
			save();
		} else if (ClientPlayNetworking.canSend(PinEdit.TYPE)) {
			sent++;
			ClientPlayNetworking.send(new PinEdit(action, pin));
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		String text = pins.stream().map(pin -> pin.line() + "\n").collect(Collectors.joining());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			LOGGER.warn("Failed to save pins to {}", file, e);
		}
	}
}
