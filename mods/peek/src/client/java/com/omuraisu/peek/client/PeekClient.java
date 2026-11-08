package com.omuraisu.peek.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;

public class PeekClient implements ClientModInitializer {
	// Jori の一覧で無効にされている間は false。ツールチップはバニラのまま
	private static volatile boolean active = true;

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	@Override
	public void onInitializeClient() {
		ClientTooltipComponentCallback.EVENT.register(component -> component instanceof ShulkerPreview preview ? new ClientShulkerPreview(preview) : null);
	}
}
