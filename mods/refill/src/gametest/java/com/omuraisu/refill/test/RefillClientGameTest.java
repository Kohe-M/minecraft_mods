package com.omuraisu.refill.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import com.omuraisu.refill.client.RefillClient;
import com.omuraisu.testkit.TestServers;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

public class RefillClientGameTest implements FabricClientGameTest {
	private static final int HAND = 0;
	private static final int OFFHAND = Inventory.SLOT_OFFHAND;
	private static final int OTHER_HOTBAR = 3;
	private static final int MAIN = 20;
	private static final int EAT_TICKS = 40;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			Scene scene = new Scene(context, singleplayer.getServer());

			refillsPlacedBlock(scene);
			context.takeScreenshot("refill-placed");
			takesSmallestStack(scene);
			keepsPlacingWhileHeld(scene);
			leavesUnfinishedStack(scene);
			refillsOffhand(scene);
			refillsThrownItem(scene);
			refillsEatenFood(scene);
			keepsEmptyContainerAfterStew(scene);
			refillsBoneMeal(scene);
			refillsFedAnimal(scene);
			leavesHandEmptyWithoutSameItem(scene);
			doesNotReplaceBrokenTool(scene);
			doesNotRefillAfterDrop(scene);
			doesNotRefillAfterSwapHands(scene);
			doesNotRefillAfterInventoryMove(scene);
			doesNotRefillAfterSlotChange(scene);
			doesNotRefillWhileInactive(scene);
		}

		testDedicatedServer(context);
	}

	// Jori で無効にされている間は補充しない。戻せばまた補充する
	private static void doesNotRefillWhileInactive(Scene scene) {
		scene.context.runOnClient(client -> RefillClient.setActive(false));
		try {
			scene.start(at(HAND, Items.COBBLESTONE, 1), at(MAIN, Items.COBBLESTONE, 5));
			scene.use();
			scene.assertInventory(at(MAIN, Items.COBBLESTONE, 5));
		} finally {
			scene.context.runOnClient(client -> RefillClient.setActive(true));
		}
		refillsPlacedBlock(scene);
	}

	private static void refillsPlacedBlock(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 1), at(OTHER_HOTBAR, Items.COBBLESTONE, 7), at(12, Items.DIRT, 9), at(MAIN, Items.COBBLESTONE, 5));
		scene.use();
		// 手が空になる tick を挟まない
		scene.assertHandNow(Items.COBBLESTONE, 5);
		scene.assertInventory(at(HAND, Items.COBBLESTONE, 5), at(OTHER_HOTBAR, Items.COBBLESTONE, 7), at(12, Items.DIRT, 9));
		scene.assertPlaced(Blocks.COBBLESTONE, 1);
	}

	private static void takesSmallestStack(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 1), at(10, Items.COBBLESTONE, 64), at(11, Items.COBBLESTONE, 3), at(12, Items.COBBLESTONE, 32));
		scene.use();
		scene.assertInventory(at(HAND, Items.COBBLESTONE, 3), at(10, Items.COBBLESTONE, 64), at(12, Items.COBBLESTONE, 32));
	}

	private static void keepsPlacingWhileHeld(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 2), at(MAIN, Items.COBBLESTONE, 2));
		scene.hold(30);
		scene.assertInventory();
		scene.assertPlaced(Blocks.COBBLESTONE, 4);
	}

	private static void leavesUnfinishedStack(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 2), at(MAIN, Items.COBBLESTONE, 5));
		scene.use();
		scene.assertInventory(at(HAND, Items.COBBLESTONE, 1), at(MAIN, Items.COBBLESTONE, 5));
	}

	private static void refillsOffhand(Scene scene) {
		scene.start(at(OFFHAND, Items.TORCH, 1), at(MAIN, Items.TORCH, 4));
		scene.use();
		scene.assertInventory(at(OFFHAND, Items.TORCH, 4));
		scene.assertPlaced(Blocks.TORCH, 1);
	}

	private static void refillsThrownItem(Scene scene) {
		scene.start(at(HAND, Items.SNOWBALL, 1), at(MAIN, Items.SNOWBALL, 3));
		scene.use();
		scene.assertInventory(at(HAND, Items.SNOWBALL, 3));
	}

	private static void refillsEatenFood(Scene scene) {
		scene.start(at(HAND, Items.BREAD, 1), at(MAIN, Items.BREAD, 2));
		scene.hold(EAT_TICKS);
		scene.assertInventory(at(HAND, Items.BREAD, 2));
	}

	// 手に器が残るので、空にならない
	private static void keepsEmptyContainerAfterStew(Scene scene) {
		scene.start(at(HAND, Items.MUSHROOM_STEW, 1), at(MAIN, Items.MUSHROOM_STEW, 1));
		scene.hold(EAT_TICKS);
		scene.assertInventory(at(HAND, Items.BOWL, 1), at(MAIN, Items.MUSHROOM_STEW, 1));
	}

	// サーバーだけが減らす
	private static void refillsBoneMeal(Scene scene) {
		scene.start(at(HAND, Items.BONE_MEAL, 1), at(MAIN, Items.BONE_MEAL, 2));
		scene.use();
		scene.assertInventory(at(HAND, Items.BONE_MEAL, 2));
	}

	// エンティティへの操作で、サーバーだけが減らす
	private static void refillsFedAnimal(Scene scene) {
		scene.start(at(HAND, Items.WHEAT, 1), at(MAIN, Items.WHEAT, 2));
		scene.summonCowInFront();
		scene.use();
		scene.assertInventory(at(HAND, Items.WHEAT, 2));
	}

	// ホットバーの他のスロットや、名前を付けた別物からは取らない
	private static void leavesHandEmptyWithoutSameItem(Scene scene) {
		ItemStack named = new ItemStack(Items.COBBLESTONE, 5);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
		scene.start(at(HAND, Items.COBBLESTONE, 1), at(OTHER_HOTBAR, Items.COBBLESTONE, 7), at(MAIN, named), at(MAIN + 1, Items.DIRT, 5));
		scene.use();
		scene.assertInventory(at(OTHER_HOTBAR, Items.COBBLESTONE, 7), at(MAIN, named), at(MAIN + 1, Items.DIRT, 5));
	}

	private static void doesNotReplaceBrokenTool(Scene scene) {
		ItemStack worn = new ItemStack(Items.FLINT_AND_STEEL);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		scene.start(at(HAND, worn), at(MAIN, Items.FLINT_AND_STEEL, 1));
		scene.use();
		scene.assertInventory(at(MAIN, Items.FLINT_AND_STEEL, 1));
	}

	private static void doesNotRefillAfterDrop(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 2), at(MAIN, Items.COBBLESTONE, 5));
		scene.useAndSync();
		scene.press(options -> options.keyDrop);
		scene.assertInventory(at(MAIN, Items.COBBLESTONE, 5));

		// Ctrl + Q でまとめて捨てる
		scene.start(at(HAND, Items.COBBLESTONE, 3), at(MAIN, Items.COBBLESTONE, 5));
		scene.useAndSync();
		scene.context.getInput().holdControl();
		scene.press(options -> options.keyDrop);
		scene.context.getInput().releaseControl();
		scene.assertInventory(at(MAIN, Items.COBBLESTONE, 5));
	}

	private static void doesNotRefillAfterSwapHands(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 2), at(MAIN, Items.COBBLESTONE, 5));
		scene.useAndSync();
		scene.press(options -> options.keySwapOffhand);
		scene.assertInventory(at(OFFHAND, Items.COBBLESTONE, 1), at(MAIN, Items.COBBLESTONE, 5));
	}

	private static void doesNotRefillAfterInventoryMove(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 2), at(MAIN, Items.COBBLESTONE, 5));
		scene.useAndSync();
		scene.context.runOnClient(client -> {
			client.gui.setScreen(new InventoryScreen(client.player));
			client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, InventoryMenu.USE_ROW_SLOT_START + HAND, 0, ContainerInput.QUICK_MOVE, client.player);
		});
		scene.context.waitTicks(2);
		scene.context.setScreen(() -> null);
		scene.assertInventory(at(MAIN, Items.COBBLESTONE, 6));
	}

	private static void doesNotRefillAfterSlotChange(Scene scene) {
		scene.start(at(HAND, Items.COBBLESTONE, 2), at(MAIN, Items.COBBLESTONE, 5));
		scene.useAndSync();
		scene.press(options -> options.keyHotbarSlots[HAND + 1]);
		scene.assertInventory(at(HAND, Items.COBBLESTONE, 1), at(MAIN, Items.COBBLESTONE, 5));
		scene.press(options -> options.keyHotbarSlots[HAND]);
	}

	// サーバーに mod が無くても動く
	private static void testDedicatedServer(ClientGameTestContext context) {
		Properties properties = new Properties();
		properties.setProperty("spawn-protection", "0");
		try (TestDedicatedServerContext server = TestServers.create(context, properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			Scene scene = new Scene(context, server);

			refillsPlacedBlock(scene);
			refillsOffhand(scene);
			refillsBoneMeal(scene);
			doesNotRefillAfterDrop(scene);
			doesNotRefillAfterSwapHands(scene);
		}
	}

	private static InSlot at(int index, Item item, int count) {
		return new InSlot(index, new ItemStack(item, count));
	}

	private static InSlot at(int index, ItemStack stack) {
		return new InSlot(index, stack);
	}

	private record InSlot(int index, ItemStack stack) {
	}

	// 整地した場所に立ち、3 ブロックほど先の地面を見て、インベントリの中身を決めて始める場面
	private static final class Scene {
		private static final int RADIUS = 8;
		private static final int CLEAR_HEIGHT = 6;
		private static final float LOOK_DOWN = 30;
		private static final int SYNC_TICKS = 5;

		private final ClientGameTestContext context;
		private final TestServerContext server;
		private final BlockPos feet;

		Scene(ClientGameTestContext context, TestServerContext server) {
			this.context = context;
			this.server = server;
			feet = server.computeOnServer(minecraft -> {
				ServerPlayer player = player(minecraft);
				player.setGameMode(GameType.SURVIVAL);
				BlockPos pos = player.blockPosition();
				return new BlockPos(pos.getX(), minecraft.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()), pos.getZ());
			});
		}

		void start(InSlot... slots) {
			// 画面を開くと直前の場面で使った記憶が消える
			context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
			context.waitTick();
			server.runOnServer(minecraft -> {
				ServerLevel level = minecraft.overworld();
				for (Entity entity : level.getEntities((Entity) null, area(), entity -> !(entity instanceof Player))) {
					entity.discard();
				}
				for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-RADIUS, -1, -RADIUS), feet.offset(RADIUS, CLEAR_HEIGHT, RADIUS))) {
					level.setBlockAndUpdate(pos, pos.getY() < feet.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
				}
				ServerPlayer player = player(minecraft);
				player.getFoodData().setFoodLevel(10);
				player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, Set.of(), 0, LOOK_DOWN, true);
				Inventory inventory = player.getInventory();
				inventory.clearContent();
				for (InSlot slot : slots) {
					inventory.setItem(slot.index(), slot.stack().copy());
				}
			});
			context.waitTicks(SYNC_TICKS);
			context.setScreen(() -> null);
			context.waitTick();
		}

		void summonCowInFront() {
			server.runCommand("summon minecraft:cow %d %d %d {NoAI:1b}".formatted(feet.getX(), feet.getY(), feet.getZ() + 2));
			context.waitTicks(SYNC_TICKS);
		}

		void use() {
			press(options -> options.keyUse);
		}

		// 置いた分の応答より先に Q を押すと、バニラでも手の個数がサーバーとずれる
		void useAndSync() {
			use();
			context.waitTicks(SYNC_TICKS);
		}

		void hold(int ticks) {
			context.getInput().holdKeyFor((Options options) -> options.keyUse, ticks);
		}

		void press(Function<Options, KeyMapping> key) {
			context.getInput().pressKey(key);
		}

		void assertHandNow(Item item, int count) {
			ItemStack hand = context.computeOnClient(client -> client.player.getMainHandItem().copy());
			if (!hand.is(item) || hand.getCount() != count) {
				throw new AssertionError("hand: expected " + count + " " + item + " but was " + hand);
			}
		}

		// クライアントとサーバーの両方で、書いたもの以外のスロットは空
		void assertInventory(InSlot... expected) {
			context.waitTicks(SYNC_TICKS);
			List<ItemStack> wanted = new ArrayList<>();
			for (int i = 0; i <= Inventory.SLOT_OFFHAND; i++) {
				wanted.add(ItemStack.EMPTY);
			}
			for (InSlot slot : expected) {
				wanted.set(slot.index(), slot.stack());
			}
			assertStacks("client", wanted, context.computeOnClient(client -> stacks(client.player.getInventory())));
			assertStacks("server", wanted, server.computeOnServer(minecraft -> stacks(player(minecraft).getInventory())));
		}

		void assertPlaced(Block block, int count) {
			int placed = server.computeOnServer(minecraft -> {
				int found = 0;
				for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-RADIUS, 0, -RADIUS), feet.offset(RADIUS, CLEAR_HEIGHT, RADIUS))) {
					if (minecraft.overworld().getBlockState(pos).is(block)) {
						found++;
					}
				}
				return found;
			});
			if (placed != count) {
				throw new AssertionError("placed " + block + ": expected " + count + " but was " + placed);
			}
		}

		private AABB area() {
			return new AABB(feet).inflate(RADIUS * 2);
		}

		private static void assertStacks(String side, List<ItemStack> expected, List<ItemStack> actual) {
			for (int i = 0; i < expected.size(); i++) {
				if (!ItemStack.matches(expected.get(i), actual.get(i))) {
					throw new AssertionError(side + " slot " + i + ": expected " + expected.get(i) + " but was " + actual.get(i) + ", all: " + actual.stream().filter(stack -> !stack.isEmpty()).toList());
				}
			}
		}

		private static List<ItemStack> stacks(Inventory inventory) {
			List<ItemStack> stacks = new ArrayList<>();
			for (int i = 0; i <= Inventory.SLOT_OFFHAND; i++) {
				stacks.add(inventory.getItem(i).copy());
			}
			return stacks;
		}

		private static ServerPlayer player(MinecraftServer server) {
			return server.getPlayerList().getPlayers().getFirst();
		}
	}
}
