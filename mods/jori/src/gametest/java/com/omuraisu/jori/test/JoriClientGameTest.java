package com.omuraisu.jori.test;

import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Supplier;

import com.omuraisu.fullbright.client.FullbrightClient;
import com.omuraisu.jori.Feature;
import com.omuraisu.jori.JoriConfig;
import com.omuraisu.jori.client.JoriClient;
import com.omuraisu.jori.client.JoriScreen;
import com.omuraisu.minimap.TerrainView;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.peek.client.ShulkerPreview;
import com.omuraisu.sorter.SortPayload;
import com.omuraisu.testkit.TestServers;
import com.omuraisu.testkit.Widgets;
import com.omuraisu.timber.TogglePayload;
import com.omuraisu.zoom.client.ZoomClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

public class JoriClientGameTest implements FabricClientGameTest {
	private static final int SETTLE_TICKS = 20;

	@Override
	public void runTest(ClientGameTestContext context) {
		// 前回の実行の設定を残さない
		setAll(context, true);

		openFrom(context, TitleScreen::new, "jori-title");
		context.takeScreenshot("jori-screen-title");
		assertEquals("tiles", JoriClient.FEATURES.size(), tiles(context).size());
		Widgets.click(context, "Done");
		assertScreen(context, TitleScreen.class);

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			testToggle(context);
		}

		testConfigFile();
		testDedicatedServer(context);
		setAll(context, true);
	}

	// タイルを押すと切り替わり、設定ファイルに残る。各 mod の働きも止まる
	private static void testToggle(ClientGameTestContext context) {
		openFrom(context, () -> new PauseScreen(true), "jori-pause");
		for (Feature feature : JoriClient.FEATURES) {
			Widgets.click(context, name(feature));
			if (feature.isActive()) {
				throw new AssertionError(feature.id() + " should be inactive after the click");
			}
		}
		assertConfig(false);
		Widgets.hover(context, "Minimap");
		context.takeScreenshot("jori-all-off");

		context.setScreen(() -> null);
		context.waitTick();
		assertWorking(context, false);
		context.takeScreenshot("jori-world-off");

		openFrom(context, () -> new PauseScreen(true), "jori-pause-again");
		for (Feature feature : JoriClient.FEATURES) {
			Widgets.click(context, name(feature));
			if (!feature.isActive()) {
				throw new AssertionError(feature.id() + " should be active after the second click");
			}
		}
		assertConfig(true);
		context.takeScreenshot("jori-all-on");

		// 完了で開いた画面に戻る
		Widgets.click(context, "Done");
		assertScreen(context, PauseScreen.class);
		context.setScreen(() -> null);
		context.waitTick();
		assertWorking(context, true);
	}

	// いくつかの mod で、実際に働いているかを見る。細かい動きは各 mod のテストで確かめている
	private static void assertWorking(ClientGameTestContext context, boolean active) {
		ItemStack box = new ItemStack(Items.SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
		assertEquals("peek preview", active, ShulkerPreview.shows(box));
		assertEquals("fullbright", active, FullbrightClient.isEnabled());

		context.getInput().pressKey(MinimapClient.mapKey);
		context.waitTicks(2);
		assertEquals("map screen opened", active, context.computeOnClient(client -> client.gui.screen() != null));
		context.setScreen(() -> null);

		context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
		context.waitTicks(2);
		long sortButtons = context.computeOnClient(client -> Screens.getWidgets(client.gui.screen()).stream()
				.filter(widget -> widget instanceof ImageButton && widget.getMessage().getString().equals("Sort"))
				.count());
		assertEquals("sort buttons", active ? 1L : 0L, sortButtons);
		context.setScreen(() -> null);

		context.getInput().holdKey(ZoomClient.key);
		context.waitTicks(SETTLE_TICKS);
		assertEquals("zooming", active, ZoomClient.zoom() > 1);
		context.getInput().releaseKey(ZoomClient.key);
		context.waitTicks(SETTLE_TICKS);
	}

	// 書いていない mod は有効。壊れた行は読み飛ばす
	private static void testConfigFile() {
		write("sorter=false\nbroken line\n zoom = false \nxray=yes\n");
		JoriConfig config = JoriConfig.load();
		assertEquals("sorter", false, config.isEnabled("sorter"));
		assertEquals("zoom", false, config.isEnabled("zoom"));
		assertEquals("xray", true, config.isEnabled("xray"));
		assertEquals("minimap", true, config.isEnabled("minimap"));
	}

	// 専用サーバーは起動するときに設定ファイルを読み、無効にした mod は受け口を作らない。
	// テストではクライアントと同じ JVM で動くので、切り替えはクライアントにも及ぶ
	private static void testDedicatedServer(ClientGameTestContext context) {
		write("minimap=false\nsorter=false\ntimber=false\nzoom=false\n");
		try (TestDedicatedServerContext server = TestServers.create(context)) {
			for (Feature feature : Feature.SERVER) {
				assertEquals(feature.id() + " on the server", false, feature.isActive());
			}
			// クライアントだけの mod は専用サーバーでは切り替えない
			assertEquals("zoom", true, ZoomClient.isActive());
			// 知らない mod の行も消さずに残す
			assertEquals("config on the server", "minimap=false\nsorter=false\ntimber=false\nzoom=false\n", read());

			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				assertCanSend(context, false);
				assertEquals("sorter missing", true, context.computeOnClient(client -> JoriClient.isMissingOnServer(client, Feature.SORTER)));
				context.setScreen(() -> new JoriScreen(null));
				context.waitTicks(2);
				Widgets.hover(context, "Sorter");
				context.takeScreenshot("jori-server-missing");
				context.setScreen(() -> null);
			}

			context.runOnClient(client -> Feature.SERVER.forEach(feature -> feature.setActive().accept(true)));
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				assertCanSend(context, true);
				assertEquals("sorter missing", false, context.computeOnClient(client -> JoriClient.isMissingOnServer(client, Feature.SORTER)));
			}
		}
	}

	private static void assertCanSend(ClientGameTestContext context, boolean expected) {
		for (CustomPacketPayload.Type<?> type : List.of(SortPayload.TYPE, TerrainView.TYPE, TogglePayload.TYPE)) {
			assertEquals("can send " + type.id(), expected, context.computeOnClient(client -> ClientPlayNetworking.canSend(type)));
		}
	}

	// 画面にある Jori のボタンを押して一覧を開く。ボタンはほかのボタンと重ならない
	private static void openFrom(ClientGameTestContext context, Supplier<Screen> screen, String screenshot) {
		context.setScreen(screen::get);
		context.waitTicks(3);
		context.runOnClient(client -> {
			List<AbstractWidget> widgets = Screens.getWidgets(client.gui.screen());
			if (widgets.stream().noneMatch(widget -> widget instanceof SpriteIconButton && widget.getMessage().getString().equals("Jori"))) {
				throw new AssertionError("Jori button not found on " + client.gui.screen());
			}
			for (AbstractWidget a : widgets) {
				for (AbstractWidget b : widgets) {
					if (a != b && a.visible && b.visible && a.getRectangle().intersects(b.getRectangle())) {
						throw new AssertionError(a.getMessage().getString() + " overlaps " + b.getMessage().getString());
					}
				}
			}
		});
		context.takeScreenshot(screenshot);
		Widgets.click(context, "Jori");
		assertScreen(context, JoriScreen.class);
	}

	private static List<AbstractWidget> tiles(ClientGameTestContext context) {
		List<String> names = JoriClient.FEATURES.stream().map(JoriClientGameTest::name).toList();
		return context.computeOnClient(client -> Screens.getWidgets(client.gui.screen()).stream()
				.filter(widget -> names.contains(widget.getMessage().getString()))
				.toList());
	}

	private static String name(Feature feature) {
		return FabricLoader.getInstance().getModContainer(feature.id()).orElseThrow().getMetadata().getName();
	}

	private static void assertScreen(ClientGameTestContext context, Class<? extends Screen> expected) {
		Screen screen = context.computeOnClient(client -> client.gui.screen());
		if (!expected.isInstance(screen)) {
			throw new AssertionError("expected " + expected.getSimpleName() + " but was " + screen);
		}
	}

	private static void assertConfig(boolean active) {
		JoriConfig config = JoriConfig.load();
		for (Feature feature : JoriClient.FEATURES) {
			assertEquals("config of " + feature.id(), active, config.isEnabled(feature.id()));
		}
	}

	private static void setAll(ClientGameTestContext context, boolean active) {
		context.runOnClient(client -> {
			for (Feature feature : JoriClient.FEATURES) {
				JoriClient.setActive(feature, active);
			}
		});
	}

	private static void write(String text) {
		try {
			Files.writeString(JoriConfig.FILE, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read() {
		try {
			return Files.readString(JoriConfig.FILE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
