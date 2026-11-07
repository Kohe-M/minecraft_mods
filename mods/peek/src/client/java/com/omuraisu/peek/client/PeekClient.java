package com.omuraisu.peek.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;

public class PeekClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTooltipComponentCallback.EVENT.register(component -> component instanceof ShulkerPreview preview ? new ClientShulkerPreview(preview) : null);
	}
}
