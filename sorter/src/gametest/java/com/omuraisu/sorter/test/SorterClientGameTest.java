package com.omuraisu.sorter.test;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class SorterClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			SimpleContainer chest = new SimpleContainer(27);
			chest.setItem(2, new ItemStack(Items.DIAMOND, 3));
			chest.setItem(7, new ItemStack(Items.COBBLESTONE, 20));
			chest.setItem(13, new ItemStack(Items.OAK_LOG, 12));
			chest.setItem(19, new ItemStack(Items.COBBLESTONE, 50));
			chest.setItem(25, new ItemStack(Items.DIRT, 7));

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				Inventory inventory = player.getInventory();
				inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
				inventory.setItem(14, new ItemStack(Items.BREAD, 5));
				inventory.setItem(22, new ItemStack(Items.TORCH, 30));
				inventory.setItem(31, new ItemStack(Items.BREAD, 9));
				player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, chest), Component.literal("Chest")));
			});
			context.waitForScreen(ContainerScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("sorter-chest-before");

			clickButton(context, true, 0);
			clickButton(context, true, 1);
			context.takeScreenshot("sorter-chest-after");

			// カーソルを離したら、押したボタンのハイライトは消える
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(2);
			context.runOnClient(client -> {
				for (AbstractWidget button : Screens.getWidgets(client.gui.screen())) {
					if (button.isHoveredOrFocused()) {
						throw new AssertionError("still highlighted: " + button.getMessage().getString());
					}
				}
			});

			singleplayer.getServer().runOnServer(server -> {
				Inventory inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
				assertStack(chest.getItem(0), new ItemStack(Items.DIRT, 7));
				assertStack(chest.getItem(1), new ItemStack(Items.COBBLESTONE, 64));
				assertStack(chest.getItem(2), new ItemStack(Items.COBBLESTONE, 6));
				assertStack(chest.getItem(3), new ItemStack(Items.OAK_LOG, 12));
				assertStack(chest.getItem(4), new ItemStack(Items.DIAMOND, 3));
				assertStack(inventory.getItem(0), new ItemStack(Items.IRON_PICKAXE));
				assertStack(inventory.getItem(9), new ItemStack(Items.TORCH, 30));
				assertStack(inventory.getItem(10), new ItemStack(Items.BREAD, 14));
			});

			// スロット欄の間が一番狭い画面
			singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().openMenu(
					new SimpleMenuProvider((id, inv, p) -> new ShulkerBoxMenu(id, inv, new SimpleContainer(27)), Component.literal("Shulker Box"))));
			context.waitForScreen(ShulkerBoxScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("sorter-shulker");

			context.setScreen(() -> null);
			context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
			context.waitTicks(2);
			context.takeScreenshot("sorter-inventory");

			// レシピ本を開くと画面が右にずれる
			clickButton(context, false, 0);
			context.takeScreenshot("sorter-inventory-recipe-book");
		}
	}

	private static void assertStack(ItemStack actual, ItemStack expected) {
		if (!ItemStack.matches(actual, expected)) {
			throw new AssertionError("expected " + expected + " but was " + actual);
		}
	}

	private static void clickButton(ClientGameTestContext context, boolean sort, int index) {
		int[] center = context.computeOnClient(client -> {
			List<AbstractWidget> buttons = Screens.getWidgets(client.gui.screen()).stream()
					.filter(widget -> widget instanceof ImageButton && widget.getMessage().getString().equals("Sort") == sort)
					.toList();
			AbstractWidget button = buttons.get(index);
			int scale = client.getWindow().getGuiScale();
			return new int[] {(button.getX() + button.getWidth() / 2) * scale, (button.getY() + button.getHeight() / 2) * scale};
		});
		context.getInput().setCursorPos(center[0], center[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(5);
	}
}
