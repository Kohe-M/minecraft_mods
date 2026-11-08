package com.omuraisu.jori.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.omuraisu.fullbright.client.FullbrightClient;
import com.omuraisu.jori.Feature;
import com.omuraisu.jori.JoriConfig;
import com.omuraisu.peek.client.PeekClient;
import com.omuraisu.placer.client.PlacerClient;
import com.omuraisu.refill.client.RefillClient;
import com.omuraisu.sorter.SortPayload;
import com.omuraisu.timber.TogglePayload;
import com.omuraisu.xray.client.XrayClient;
import com.omuraisu.zoom.client.ZoomClient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class JoriClient implements ClientModInitializer {
	// 一覧に並べる順
	public static final List<Feature> FEATURES = List.of(
			new Feature("fullbright", FullbrightClient::isActive, FullbrightClient::setActive),
			Feature.MINIMAP,
			new Feature("peek", PeekClient::isActive, PeekClient::setActive),
			new Feature("placer", PlacerClient::isActive, PlacerClient::setActive),
			new Feature("refill", RefillClient::isActive, RefillClient::setActive),
			Feature.SORTER,
			Feature.TIMBER,
			new Feature("xray", XrayClient::isActive, XrayClient::setActive),
			new Feature("zoom", ZoomClient::isActive, ZoomClient::setActive));

	// サーバーにも入っていないと働かない mod。サーバーが受け付けるかはこのパケットで分かる
	private static final Map<String, CustomPacketPayload.Type<?>> SERVER_PAYLOADS = Map.of(
			"sorter", SortPayload.TYPE,
			"timber", TogglePayload.TYPE);

	private static final Identifier BUTTON_SPRITE =Identifier.fromNamespaceAndPath("jori", "button");
	private static final int BUTTON_SIZE = 20;
	private static final int BUTTON_GAP = 4;

	private static JoriConfig config;

	@Override
	public void onInitializeClient() {
		config = JoriConfig.load();
		for (Feature feature : FEATURES) {
			feature.apply(config);
		}

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen || screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				addButton(screen);
			}
		});
	}

	public static void setActive(Feature feature, boolean active) {
		feature.setActive().accept(active);
		config.set(feature.id(), active);
		config.save();
	}

	// いまのサーバーに入っていないため働かないとき true。シングルプレイと、ワールドの外では false
	public static boolean isMissingOnServer(Minecraft client, Feature feature) {
		CustomPacketPayload.Type<?> payload = SERVER_PAYLOADS.get(feature.id());
		return payload != null && client.getConnection() != null && !ClientPlayNetworking.canSend(payload);
	}

	// 「設定」ボタンのすぐ上にあるアイコンの列（フレンド・言語など）の右端に足し、列を中央にそろえ直す。
	// 列が見つからなければ「設定」ボタンの左に置く
	private static void addButton(Screen screen) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		AbstractWidget options = widgets.stream().filter(JoriClient::isOptionsButton).findFirst().orElse(null);
		if (options == null) {
			return;
		}
		SpriteIconButton button = SpriteIconButton.builder(Component.literal("Jori"), pressed -> Minecraft.getInstance().gui.setScreen(new JoriScreen(screen)), true)
				.width(BUTTON_SIZE)
				.sprite(BUTTON_SPRITE, 16, 16)
				.withTootip()
				.build();

		List<AbstractWidget> row = iconRowAbove(widgets, options);
		if (row.isEmpty()) {
			button.setPosition(options.getX() - BUTTON_GAP - BUTTON_SIZE, options.getY() + (options.getHeight() - BUTTON_SIZE) / 2);
		} else {
			int gap = row.size() > 1 ? row.get(1).getX() - row.get(0).getRight() : BUTTON_GAP;
			int center = (row.getFirst().getX() + row.getLast().getRight()) / 2;
			int y = row.getFirst().getY();
			row.add(button);
			int x = center - (row.size() * BUTTON_SIZE + (row.size() - 1) * gap) / 2;
			for (AbstractWidget widget : row) {
				widget.setPosition(x, y);
				x += BUTTON_SIZE + gap;
			}
		}
		widgets.add(button);
	}

	private static List<AbstractWidget> iconRowAbove(List<AbstractWidget> widgets, AbstractWidget options) {
		int y = Integer.MIN_VALUE;
		for (AbstractWidget widget : widgets) {
			if (isIcon(widget) && widget.getBottom() <= options.getY() && options.getY() - widget.getBottom() <= BUTTON_SIZE) {
				y = Math.max(y, widget.getY());
			}
		}
		List<AbstractWidget> row = new ArrayList<>();
		for (AbstractWidget widget : widgets) {
			if (isIcon(widget) && widget.getY() == y) {
				row.add(widget);
			}
		}
		row.sort(Comparator.comparingInt(AbstractWidget::getX));
		return row;
	}

	private static boolean isIcon(AbstractWidget widget) {
		return widget.visible && widget.getWidth() == BUTTON_SIZE && widget.getHeight() == BUTTON_SIZE;
	}

	private static boolean isOptionsButton(AbstractWidget widget) {
		return widget.getMessage().getContents() instanceof TranslatableContents contents && contents.getKey().equals("menu.options");
	}
}
