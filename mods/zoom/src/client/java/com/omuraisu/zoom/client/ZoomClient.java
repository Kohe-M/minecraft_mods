package com.omuraisu.zoom.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public class ZoomClient implements ClientModInitializer {
	// 押した直後の倍率
	public static final float DEFAULT_LEVEL = 4;
	private static final float MIN_LEVEL = 1.5f;
	private static final float MAX_LEVEL = 50;
	// ホイール1段で倍率をこの比だけ変える
	private static final float SCROLL_STEP = 1.25f;
	// 1 tick で目標の倍率までの比をこれだけ詰める（対数で補間する）
	private static final float SMOOTHING = 0.5f;

	public static KeyMapping key;

	private static float level = DEFAULT_LEVEL;
	private static float zoom = 1;
	private static float oldZoom = 1;

	@Override
	public void onInitializeClient() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.zoom.zoom",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_C,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath("zoom", "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(ZoomClient::tick);
	}

	private static void tick(Minecraft client) {
		boolean active = isActive(client);
		if (!active) {
			level = DEFAULT_LEVEL;
		}

		float target = active ? level : 1;
		oldZoom = zoom;
		zoom *= (float) Math.pow(target / zoom, SMOOTHING);
		if (Math.abs(zoom / target - 1) < 0.001f) {
			zoom = target;
		}
	}

	private static boolean isActive(Minecraft client) {
		return key.isDown() && client.gui.screen() == null;
	}

	public static float level() {
		return level;
	}

	public static float zoom() {
		return zoom;
	}

	public static float zoom(float partialTick) {
		return Mth.lerp(partialTick, oldZoom, zoom);
	}

	// 倍率を変えたらホットバーには回さない
	public static boolean scroll(Minecraft client, double amount) {
		if (!isActive(client)) {
			return false;
		}
		level = Mth.clamp(level * (float) Math.pow(SCROLL_STEP, amount), MIN_LEVEL, MAX_LEVEL);
		return true;
	}

	// 中心付近の見かけの大きさが zoom 倍になる FOV
	public static float apply(float fov, float zoom) {
		double half = Math.toRadians(fov) / 2;
		return (float) Math.toDegrees(2 * Math.atan(Math.tan(half) / zoom));
	}
}
