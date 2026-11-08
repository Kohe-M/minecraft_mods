package com.omuraisu.peek.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

import com.omuraisu.peek.client.ClientShulkerPreview;
import com.omuraisu.peek.client.PeekClient;
import com.omuraisu.peek.client.ShulkerPreview;
import com.omuraisu.peek.test.mixin.AbstractContainerScreenAccessor;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.GameType;

public class PeekClientGameTest implements FabricClientGameTest {
	private static final int MAIN = 13;
	private static final int HOTBAR = 4;
	private static final int CHEST_SLOT = 13;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			Scene scene = new Scene(context, singleplayer.getServer());

			showsContentsInInventory(scene, "peek-inventory");
			showsContentsInChest(scene);
			showsContentsInCreative(scene);
			showsDyedBox(scene);
			showsBoxWithOneItem(scene);
			hidesEmptyBox(scene);
			hidesWhenTooltipHidesContents(scene);
			keepsVanillaLinesForOtherContainers(scene);
			keepsVanillaWhenInactive(scene);
		}

		testDedicatedServer(context);
	}

	// 開いたときと同じ位置にアイコンが並び、バニラの「名前 x個」の行は消える
	private static void showsContentsInInventory(Scene scene, String screenshot) {
		List<ItemStack> contents = variedContents();
		scene.give(MAIN, box(Items.SHULKER_BOX, contents));
		scene.openInventory();
		List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(MAIN));
		scene.context.takeScreenshot(screenshot);
		assertPreview(tooltip, contents);
		assertEquals("tooltip size", 2, tooltip.size());
		assertNoContentLines(scene, MAIN);
		scene.close();
	}

	private static void showsContentsInChest(Scene scene) {
		List<ItemStack> contents = variedContents();
		ItemStack box = box(Items.SHULKER_BOX, contents);
		scene.give(MAIN, ItemStack.EMPTY);
		scene.server.runOnServer(server -> {
			SimpleContainer chest = new SimpleContainer(27);
			chest.setItem(CHEST_SLOT, box.copy());
			player(server).openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.threeRows(id, inventory, chest), Component.literal("Chest")));
		});
		scene.context.waitTicks(Scene.SYNC_TICKS);
		assertScreen(scene, ContainerScreen.class);
		List<ClientTooltipComponent> tooltip = scene.hover(slot -> !(slot.container instanceof Inventory) && slot.getContainerSlot() == CHEST_SLOT);
		scene.context.takeScreenshot("peek-chest");
		assertPreview(tooltip, contents);
		scene.close();
	}

	private static void showsContentsInCreative(Scene scene) {
		List<ItemStack> contents = variedContents();
		scene.setGameMode(GameType.CREATIVE);
		scene.give(HOTBAR, box(Items.SHULKER_BOX, contents));
		scene.context.runOnClient(client -> client.gui.setScreen(new CreativeModeInventoryScreen(client.player, client.player.connection.enabledFeatures(), false)));
		scene.context.waitTicks(2);
		assertScreen(scene, CreativeModeInventoryScreen.class);
		List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(HOTBAR));
		scene.context.takeScreenshot("peek-creative");
		assertPreview(tooltip, contents);
		scene.close();
		scene.setGameMode(GameType.SURVIVAL);
	}

	// 色付きのシュルカーボックスも同じ
	private static void showsDyedBox(Scene scene) {
		List<ItemStack> contents = slots(at(0, Items.DIAMOND, 64), at(26, Items.EMERALD, 5));
		for (Item dyed : Items.DYED_SHULKER_BOX.asList()) {
			if (!ShulkerPreview.shows(box(dyed, contents))) {
				throw new AssertionError("preview should be shown for " + dyed);
			}
		}
		scene.give(MAIN, box(Items.DYED_SHULKER_BOX.red(), contents));
		scene.openInventory();
		assertPreview(scene.hover(inventorySlot(MAIN)), contents);
		scene.close();
	}

	// 1 個だけでも 3 段すべてを出して、入っている位置が分かるようにする
	private static void showsBoxWithOneItem(Scene scene) {
		List<ItemStack> contents = slots(at(22, Items.TORCH, 1));
		scene.give(MAIN, box(Items.DYED_SHULKER_BOX.blue(), contents));
		scene.openInventory();
		List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(MAIN));
		scene.context.takeScreenshot("peek-one-item");
		assertPreview(tooltip, contents);
		scene.close();
	}

	private static void hidesEmptyBox(Scene scene) {
		scene.give(MAIN, new ItemStack(Items.SHULKER_BOX));
		scene.openInventory();
		List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(MAIN));
		assertNoPreview(tooltip);
		assertEquals("tooltip size of an empty box", 1, tooltip.size());
		scene.close();
	}

	// コマンドなどで中身の表示を隠した箱は、アイコンも出さない
	private static void hidesWhenTooltipHidesContents(Scene scene) {
		ItemStack box = box(Items.SHULKER_BOX, slots(at(0, Items.DIAMOND, 64)));
		box.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.CONTAINER, true));
		scene.give(MAIN, box);
		scene.openInventory();
		List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(MAIN));
		assertNoPreview(tooltip);
		assertEquals("tooltip size of a box hiding its contents", 1, tooltip.size());
		scene.close();
	}

	// シュルカーボックス以外の入れ物はバニラのまま
	// Jori で無効にされている間は、シュルカーボックスもバニラの文字の行のまま
	private static void keepsVanillaWhenInactive(Scene scene) {
		scene.give(MAIN, box(Items.SHULKER_BOX, slots(at(0, Items.DIAMOND, 64), at(1, Items.EMERALD, 5))));
		scene.context.runOnClient(client -> PeekClient.setActive(false));
		try {
			scene.openInventory();
			List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(MAIN));
			scene.context.takeScreenshot("peek-inactive");
			assertNoPreview(tooltip);
			assertEquals("content lines while inactive", 2, contentLines(scene, MAIN));
			scene.close();
		} finally {
			scene.context.runOnClient(client -> PeekClient.setActive(true));
		}
	}

	private static void keepsVanillaLinesForOtherContainers(Scene scene) {
		scene.give(MAIN, box(Items.CHEST, slots(at(0, Items.DIAMOND, 64), at(1, Items.EMERALD, 5))));
		scene.openInventory();
		List<ClientTooltipComponent> tooltip = scene.hover(inventorySlot(MAIN));
		assertNoPreview(tooltip);
		assertEquals("content lines of a chest", 2, contentLines(scene, MAIN));
		scene.close();
	}

	// サーバーに mod が無くても、届いた中身から出せる
	private static void testDedicatedServer(ClientGameTestContext context) {
		Properties properties = new Properties();
		try {
			// テスト用のサーバーを起動するため、テスト環境の Minecraft EULA に同意する
			Files.writeString(Path.of("eula.txt"), "eula=true\n");
			// 普段のサーバーと重ならないよう空いているポートを使う
			try (ServerSocket socket = new ServerSocket(0)) {
				properties.setProperty("server-port", String.valueOf(socket.getLocalPort()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			Scene scene = new Scene(context, server);
			showsContentsInInventory(scene, "peek-dedicated");
		}
	}

	private static List<ItemStack> variedContents() {
		ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE);
		worn.setDamageValue(worn.getMaxDamage() * 2 / 3);
		return slots(
				at(0, Items.DIAMOND, 64), at(1, Items.COBBLESTONE, 37), at(3, worn), at(4, Items.ENDER_PEARL, 16), at(8, Items.TORCH, 1),
				at(9, Items.OAK_LOG, 64), at(12, Items.GOLDEN_APPLE, 3), at(13, Items.POTION, 1), at(17, Items.BUNDLE, 1),
				at(18, Items.REDSTONE, 64), at(20, Items.GLASS, 12), at(24, Items.BREAD, 9), at(26, Items.EMERALD, 5));
	}

	private static ItemStack box(Item item, List<ItemStack> contents) {
		ItemStack box = new ItemStack(item);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
		return box;
	}

	private static List<ItemStack> slots(InSlot... items) {
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < 27; i++) {
			slots.add(ItemStack.EMPTY);
		}
		for (InSlot item : items) {
			slots.set(item.index(), item.stack());
		}
		return slots;
	}

	private static InSlot at(int index, Item item, int count) {
		return new InSlot(index, new ItemStack(item, count));
	}

	private static InSlot at(int index, ItemStack stack) {
		return new InSlot(index, stack);
	}

	private record InSlot(int index, ItemStack stack) {
	}

	private static Predicate<Slot> inventorySlot(int index) {
		return slot -> slot.container instanceof Inventory && slot.getContainerSlot() == index;
	}

	// 名前のすぐ下にアイコンの段が来る
	private static void assertPreview(List<ClientTooltipComponent> tooltip, List<ItemStack> expected) {
		if (tooltip == null) {
			throw new AssertionError("no tooltip was rendered");
		}
		if (tooltip.size() < 2 || !(tooltip.get(1) instanceof ClientShulkerPreview preview)) {
			throw new AssertionError("preview should follow the name but tooltip was " + tooltip);
		}
		if (tooltip.stream().filter(ClientShulkerPreview.class::isInstance).count() != 1) {
			throw new AssertionError("preview should be shown once but tooltip was " + tooltip);
		}
		List<ItemStack> actual = preview.preview().slots();
		assertEquals("preview slots", expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			if (!ItemStack.matches(expected.get(i), actual.get(i))) {
				throw new AssertionError("preview slot " + i + ": expected " + expected.get(i) + " but was " + actual.get(i));
			}
		}
	}

	private static void assertNoPreview(List<ClientTooltipComponent> tooltip) {
		if (tooltip == null) {
			throw new AssertionError("no tooltip was rendered");
		}
		if (tooltip.stream().anyMatch(ClientShulkerPreview.class::isInstance)) {
			throw new AssertionError("preview should not be shown but tooltip was " + tooltip);
		}
	}

	private static void assertNoContentLines(Scene scene, int index) {
		assertEquals("vanilla content lines", 0, contentLines(scene, index));
	}

	// バニラが中身を文字で並べる行の数
	private static int contentLines(Scene scene, int index) {
		return scene.context.computeOnClient(client -> {
			int count = 0;
			for (Component line : Screen.getTooltipFromItem(client, client.player.getInventory().getItem(index))) {
				if (line.getContents() instanceof TranslatableContents translatable && translatable.getKey().startsWith("item.container.")) {
					count++;
				}
			}
			return count;
		});
	}

	private static void assertScreen(Scene scene, Class<? extends Screen> type) {
		String actual = scene.context.computeOnClient(client -> String.valueOf(client.gui.screen()));
		if (!scene.context.computeOnClient(client -> type.isInstance(client.gui.screen()))) {
			throw new AssertionError("screen: expected " + type.getSimpleName() + " but was " + actual);
		}
	}

	private static void assertEquals(String what, int expected, int actual) {
		if (expected != actual) {
			throw new AssertionError(what + ": expected " + expected + " but was " + actual);
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static final class Scene {
		static final int SYNC_TICKS = 5;
		private static final int HOVER_TICKS = 3;

		final ClientGameTestContext context;
		final TestServerContext server;

		Scene(ClientGameTestContext context, TestServerContext server) {
			this.context = context;
			this.server = server;
			setGameMode(GameType.SURVIVAL);
		}

		void setGameMode(GameType mode) {
			server.runOnServer(minecraft -> player(minecraft).setGameMode(mode));
			context.waitTicks(SYNC_TICKS);
		}

		void give(int index, ItemStack stack) {
			server.runOnServer(minecraft -> {
				Inventory inventory = player(minecraft).getInventory();
				inventory.clearContent();
				inventory.setItem(index, stack.copy());
			});
			context.waitTicks(SYNC_TICKS);
		}

		void openInventory() {
			context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
			context.waitTicks(2);
			assertScreen(this, InventoryScreen.class);
		}

		// スロットの真ん中にカーソルを置いて、描かれたツールチップを返す
		List<ClientTooltipComponent> hover(Predicate<Slot> which) {
			context.getInput().setCursorPos(1, 1);
			context.waitTick();
			context.runOnClient(client -> RenderedTooltip.last = null);
			double[] cursor = context.computeOnClient(client -> {
				AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.gui.screen();
				AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
				Slot slot = screen.getMenu().slots.stream().filter(which).findFirst().orElseThrow(() -> new AssertionError("slot not found in " + screen));
				double scale = client.getWindow().getGuiScale();
				return new double[] {(bounds.peekTest$leftPos() + slot.x + 8) * scale, (bounds.peekTest$topPos() + slot.y + 8) * scale};
			});
			context.getInput().setCursorPos(cursor[0], cursor[1]);
			context.waitTicks(HOVER_TICKS);
			return context.computeOnClient(client -> RenderedTooltip.last);
		}

		// サーバーにも閉じたことを伝えないと、チェストを開いたままの扱いになる
		void close() {
			context.getInput().setCursorPos(1, 1);
			context.runOnClient(client -> client.player.closeContainer());
			context.waitTicks(SYNC_TICKS);
		}
	}
}
