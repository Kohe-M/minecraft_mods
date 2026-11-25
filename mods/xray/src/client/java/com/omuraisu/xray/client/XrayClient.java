package com.omuraisu.xray.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class XrayClient implements ClientModInitializer {
	public static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("xray.txt");

	private static final Logger LOGGER = LogUtils.getLogger();

	// これ以上押し続けるとホイールを開く（0.4 秒）。短いと、単押しのつもりでもホイールが開いてしまう
	public static final int HOLD_TICKS = 8;

	// チャンク構築スレッドから読まれる
	private static volatile boolean enabled;
	private static volatile XrayMode mode = XrayMode.ALL;
	// Jori の一覧で無効にされている間は false。キーも効かず、ブロックはそのまま描く
	private static volatile boolean active = true;

	private static int heldTicks;
	// Distant Horizons の API が合わず、LOD を隠せない
	private static boolean lodsFailed;

	public static KeyMapping toggle;

	@Override
	public void onInitializeClient() {
		load();

		toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.xray.toggle",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_X,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath("xray", "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(XrayClient::tick);
	}

	private static void tick(Minecraft client) {
		Screen screen = client.gui.screen();
		if (!active) {
			while (toggle.consumeClick()) {
				// 無効の間に押した分は、戻したときに効かないよう捨てる
			}
			heldTicks = 0;
			return;
		}
		if (screen != null) {
			// ホイールを閉じた後もキーを離すまでは長押し扱いのままにする
			if (!(screen instanceof ModeWheelScreen)) {
				heldTicks = 0;
			}
			return;
		}

		// 1 tick より短い単押しは isDown に現れないので consumeClick でも拾う
		boolean clicked = false;
		while (toggle.consumeClick()) {
			clicked = true;
		}

		if (toggle.isDown()) {
			if (++heldTicks == HOLD_TICKS && client.player != null) {
				// 直前の ON/OFF 表示がホイールに重なるので消す
				client.gui.hud.setOverlayMessage(Component.empty(), false);
				client.gui.setScreen(new ModeWheelScreen());
			}
		} else {
			if (heldTicks > 0 ? heldTicks < HOLD_TICKS : clicked) {
				setEnabled(client, !enabled);
			}
			heldTicks = 0;
		}
	}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		if (active == value) {
			return;
		}
		active = value;
		// 透過中なら、描き直して元のブロックを出す（または透過し直す）
		Minecraft client = Minecraft.getInstance();
		if (enabled && client.level != null) {
			client.levelExtractor.allChanged();
		}
		// ワールドに入る前でも呼ばれるので、level の有無によらず DH の状態を合わせる
		updateLods();
	}

	public static boolean isEnabled() {
		return active && enabled;
	}

	public static XrayMode mode() {
		return mode;
	}

	public static boolean showsEntities() {
		return isEnabled() && mode == XrayMode.ENTITIES;
	}

	// 宝箱を探せるように、普通のチェストはどの鉱石を選んでいても見せる
	public static boolean isVisible(BlockState state) {
		return state.is(Blocks.CHEST) || mode.matches(state);
	}

	public static void select(Minecraft client, XrayMode selected) {
		mode = selected;
		save();
		setEnabled(client, true);
	}

	private static void setEnabled(Minecraft client, boolean value) {
		enabled = value;
		// F3+A と同じ全チャンク再構築。Sodium もこれで作り直す
		client.levelExtractor.allChanged();
		updateLods();
		if (client.player != null) {
			client.player.sendOverlayMessage(enabled
					? Component.translatable("message.xray.enabled", mode.displayName())
					: Component.translatable("message.xray.disabled"));
		}
	}

	// 透視中は Distant Horizons の LOD も隠す
	private static void updateLods() {
		if (lodsFailed || !FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			return;
		}
		try {
			DistantHorizonsLods.setHidden(isEnabled());
		} catch (LinkageError e) {
			// DH の API が変わっていても xray は落とさない
			lodsFailed = true;
			LOGGER.warn("Distant Horizons API is not compatible; LODs stay visible while xray is on: {}", e.toString());
		}
	}

	// config/xray.txt に 1 行 1 つ「キー=値」。いまの項目は mode だけ。知らない行は無視するので、項目を足しても前の値を読める。
	// 読めないとき・書いていないとき・知らないモードは全鉱石
	public static void load() {
		mode = XrayMode.ALL;
		List<String> lines;
		try {
			lines = Files.readAllLines(CONFIG);
		} catch (IOException e) {
			return;
		}
		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator > 0 && line.substring(0, separator).strip().equals("mode")) {
				mode = XrayMode.byId(line.substring(separator + 1).strip());
			}
		}
	}

	// 書けなくても遊ぶのには困らないが、黙って捨てずにログに出す
	private static void save() {
		try {
			Files.writeString(CONFIG, "mode=" + mode.id + "\n");
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}: {}", CONFIG, e.toString());
		}
	}
}
