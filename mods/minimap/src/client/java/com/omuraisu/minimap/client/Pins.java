package com.omuraisu.minimap.client;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
	// 共有に移している途中の、手元のファイル（legacy）のピン。サーバーの一覧に出たものだけを移し終えたとする。
	// 送った変更をサーバーがすべて反映しても出ないもの（上限で断られた）と、確かめる前に切断した分は手元のファイルに残る
	private Path legacy;
	private final List<Pin> migrating = new ArrayList<>();

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
		try {
			pins.addAll(Pin.read(file));
		} catch (IOException e) {
			LOGGER.warn("Failed to read pins from {}", file, e);
		}
	}

	// 共有に移している途中のピンは手元のファイルに残っていて、次に入ったときに移し直す
	public void close() {
		file = null;
		pins.clear();
		shared = false;
		sent = 0;
		legacy = null;
		migrating.clear();
	}

	public boolean isShared() {
		return shared;
	}

	public void receive(PinList list) {
		if (!shared) {
			shared = true;
			startMigration(list);
		} else {
			// サーバーは変更を反映するたびに一覧を送るので、受け付けられたピンはどれかの一覧に出る
			migrating.removeIf(pin -> list.pins().stream().anyMatch(pin::samePlace));
			if (list.acknowledged() < sent) {
				return;
			}
			pins.clear();
			pins.addAll(list.pins());
		}
		if (legacy != null && list.acknowledged() >= sent) {
			finishMigration();
		}
	}

	// サーバーに mod を入れる前に手元に刺していたピンを、共有に移し始める。サーバーに同じ場所のピンがあれば移さない
	private void startMigration(PinList list) {
		List<Pin> local = List.copyOf(pins);
		legacy = file;
		file = null;
		pins.clear();
		pins.addAll(list.pins());
		for (Pin pin : local) {
			if (list.pins().stream().noneMatch(pin::samePlace)) {
				migrating.add(pin);
				place(pin);
			}
		}
	}

	// 送った変更をサーバーがすべて反映した。移せなかったピンだけを手元のファイルに残し、なければファイルを消す
	private void finishMigration() {
		Path file = legacy;
		legacy = null;
		try {
			if (migrating.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				LOGGER.warn("{} pins could not be shared with the server and stay in {}", migrating.size(), file);
				Pin.write(file, migrating);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to update {}", file, e);
		}
		migrating.clear();
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
		try {
			Pin.write(file, pins);
		} catch (IOException e) {
			LOGGER.warn("Failed to save pins to {}", file, e);
		}
	}
}
