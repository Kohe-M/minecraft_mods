package com.omuraisu.minimap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

// サーバーにいる人で共有するピン。ワールドの minimap_pins.txt に保存し、変わるたびに全員へ一覧を送る
public final class PinServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("minimap");
	private static PinServer current;

	private final MinecraftServer server;
	private final Path file;
	private final List<Pin> pins = new ArrayList<>();
	// 一覧を送っている人と、その人から届いた変更の数。サーバーのスレッドでだけ触る
	private final Map<UUID, Integer> sharing = new HashMap<>();

	private PinServer(MinecraftServer server) {
		this.server = server;
		this.file = server.getWorldPath(LevelResource.ROOT).resolve("minimap_pins.txt");
		try {
			for (Pin pin : Pin.read(file)) {
				apply(new PinEdit(PinEdit.Action.PLACE, pin));
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to read pins from {}", file, e);
		}
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> current = new PinServer(server));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (current != null && current.server == server) {
				current = null;
			}
		});
		// 専用サーバーで無効にしているときは受け口を作らず一覧も送らない。クライアントは mod のないサーバーと同じく手元に保存する。
		// シングルプレイは途中で切り替えられるので、いつも共有する（無効の間はクライアントがピンを操作しない）
		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			if (isShared(server)) {
				ServerPlayNetworking.registerReceiver(handler, PinEdit.TYPE, (payload, context) -> edit(context.player(), payload));
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (current != null && current.server == server && isShared(server) && ServerPlayNetworking.canSend(handler.player, PinList.TYPE)) {
				current.sharing.put(handler.player.getUUID(), 0);
				current.send(handler.player);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (current != null && current.server == server) {
				current.sharing.remove(handler.player.getUUID());
			}
		});
	}

	private static boolean isShared(MinecraftServer server) {
		return Minimap.isActive() || !server.isDedicatedServer();
	}

	// player の変更として反映し、全員に送る。一覧を送っていない人（FakePlayer など）の変更も反映する。サーバーのスレッドで呼ぶ
	public static void edit(ServerPlayer player, PinEdit edit) {
		PinServer pinServer = current;
		if (pinServer == null || pinServer.server != player.level().getServer()) {
			return;
		}
		pinServer.sharing.computeIfPresent(player.getUUID(), (id, count) -> count + 1);
		if (pinServer.apply(edit)) {
			pinServer.save();
		}
		pinServer.broadcast();
	}

	private boolean apply(PinEdit edit) {
		Pin pin = edit.pin().withName(edit.pin().name());
		switch (edit.action()) {
			case PLACE -> {
				if (pins.size() < PinList.MAX_PINS && pins.stream().noneMatch(pin::samePlace)) {
					return pins.add(pin);
				}
			}
			case RENAME -> {
				for (int i = 0; i < pins.size(); i++) {
					if (pins.get(i).samePlace(pin)) {
						return !pin.equals(pins.set(i, pin));
					}
				}
			}
			case REMOVE -> {
				return pins.removeIf(pin::samePlace);
			}
		}
		return false;
	}

	private void broadcast() {
		for (UUID id : sharing.keySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				send(player);
			}
		}
	}

	private void send(ServerPlayer player) {
		ServerPlayNetworking.send(player, new PinList(List.copyOf(pins), sharing.getOrDefault(player.getUUID(), 0)));
	}

	private void save() {
		try {
			Pin.write(file, pins);
		} catch (IOException e) {
			LOGGER.warn("Failed to save pins to {}", file, e);
		}
	}
}
