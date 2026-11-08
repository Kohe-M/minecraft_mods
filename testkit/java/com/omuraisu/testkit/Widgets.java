package com.omuraisu.testkit;

import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;

// いまの画面のボタンなどを、表示している文字で探して押す。押すのは実際のマウスの入力
public final class Widgets {
	private Widgets() {
	}

	public static void click(ClientGameTestContext context, String message) {
		click(context, widget -> widget.getMessage().getString().equals(message), message);
	}

	public static void click(ClientGameTestContext context, Predicate<AbstractWidget> filter, String description) {
		hover(context, filter, description);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	public static void hover(ClientGameTestContext context, String message) {
		hover(context, widget -> widget.getMessage().getString().equals(message), message);
	}

	// カーソルを真ん中に置く。ツールチップを出すときにも使う
	public static void hover(ClientGameTestContext context, Predicate<AbstractWidget> filter, String description) {
		int[] center = context.computeOnClient(client -> {
			AbstractWidget widget = Screens.getWidgets(client.gui.screen()).stream()
					.filter(filter)
					.findFirst()
					.orElseThrow(() -> new AssertionError(description + " not found on " + client.gui.screen()));
			int scale = client.getWindow().getGuiScale();
			return new int[] {(widget.getX() + widget.getWidth() / 2) * scale, (widget.getY() + widget.getHeight() / 2) * scale};
		});
		context.getInput().setCursorPos(center[0], center[1]);
		context.waitTicks(2);
	}
}
