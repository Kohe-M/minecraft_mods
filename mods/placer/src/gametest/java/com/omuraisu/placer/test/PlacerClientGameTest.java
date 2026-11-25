package com.omuraisu.placer.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import com.omuraisu.placer.client.PlacerClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

public class PlacerClientGameTest implements FabricClientGameTest {
	private static final int FLOOR_Y = 100;
	private static final int FEET_Y = FLOOR_Y + 1;
	private static final int AREA = 12;
	// 止めたまま押し続けて、バニラならいくつも置かれる長さ
	private static final int HOLD_TICKS = 30;

	private ClientGameTestContext context;
	private TestSingleplayerContext singleplayer;

	@Override
	public void runTest(ClientGameTestContext context) {
		this.context = context;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			this.singleplayer = singleplayer;
			singleplayer.getConnection().waitForChunksRender();

			testToggleKey();
			testConfig();
			testHoldPlacesOne();
			testVanillaWhenDisabled();
			testVanillaWhenInactive();
			testSweep();
			testWall();
			testWallStepBack();
			testJumpBeside();
			testBridge();
			testBridgeAfterFloor();
			testTower();
			testSlab();
			testScaffolding();
			testPressAgain();
			testShortPress();
			testWalkBack();
			testWalkSideways();
			testWalkForward();
			testWideBridge();
			testWallWalkBack();
		}
	}

	private void testToggleKey() {
		context.runOnClient(client -> {
			for (KeyMapping other : client.options.keyMappings) {
				if (other != PlacerClient.toggle && other.same(PlacerClient.toggle)) {
					throw new AssertionError("toggle key conflicts with " + other.getName());
				}
			}
		});

		setEnabled(true);
		pressToggle();
		assertEnabled(false);
		assertConfig("enabled=false");
		pressToggle();
		assertEnabled(true);
		assertConfig("enabled=true");
	}

	// config/placer.txt は行ごとに読む。知らない行が混ざっていても enabled を読め、書いていなければ有効
	private void testConfig() {
		try {
			Files.writeString(PlacerClient.CONFIG, "# memo\nfuture_option=3\n enabled = false \nbroken line\n");
			context.runOnClient(client -> PlacerClient.load());
			assertEnabled(false);
			Files.writeString(PlacerClient.CONFIG, "future_option=3\n");
			context.runOnClient(client -> PlacerClient.load());
			assertEnabled(true);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// 切り替えると、知らない行は残さずに書き直す
		pressToggle();
		assertConfig("enabled=false");
		pressToggle();
		assertConfig("enabled=true");
	}

	// 止めたまま押し続けても、上にも手前にも増えない
	private void testHoldPlacesOne() {
		setUp(Items.OAK_PLANKS);
		aim(onFloor(0, -3));
		holdUse();
		context.waitTicks(HOLD_TICKS);
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, Set.of(new BlockPos(0, FEET_Y, -3)));
	}

	// 上と同じ操作で、無効ならバニラどおり増える
	// Jori で無効にされている間は、J キーも効かずバニラのまま。戻すと J キーで決めた状態に戻る
	private void testVanillaWhenInactive() {
		setEnabled(true);
		context.runOnClient(client -> PlacerClient.setActive(false));
		try {
			pressToggle();
			assertEnabled(false);
			assertConfig("enabled=true");

			setUp(Items.OAK_PLANKS);
			aim(onFloor(0, -3));
			holdUse();
			context.waitTicks(HOLD_TICKS);
			releaseUse();
			int count = placed(Blocks.OAK_PLANKS).size();
			if (count < 2) {
				throw new AssertionError("vanilla should place several blocks while inactive but placed " + count);
			}
		} finally {
			context.runOnClient(client -> PlacerClient.setActive(true));
		}
		assertEnabled(true);
	}

	private void testVanillaWhenDisabled() {
		setEnabled(false);
		setUp(Items.OAK_PLANKS);
		aim(onFloor(0, -3));
		holdUse();
		context.waitTicks(HOLD_TICKS);
		releaseUse();
		context.takeScreenshot("placer-disabled");
		int count = placed(Blocks.OAK_PLANKS).size();
		if (count < 2) {
			throw new AssertionError("vanilla should place several blocks but placed " + count);
		}
		setEnabled(true);
	}

	// 1 tick に 1 マス動かしても隙間なく置け、自分のブロックの上面を見ても積まれない
	private void testSweep() {
		setUp(Items.OAK_PLANKS);
		aim(new Vec3(-1.5, FEET_Y, -1.25));
		holdUse();
		// 最初の連続設置までの待ち
		context.waitTicks(5);

		Set<BlockPos> expected = new HashSet<>();
		expected.add(new BlockPos(-2, FEET_Y, -2));
		for (int x = -1; x <= 2; x++) {
			aim(new Vec3(x + 0.5, FEET_Y, -1.25));
			context.waitTick();
			expected.add(new BlockPos(x, FEET_Y, -2));
		}
		assertPlaced(Blocks.OAK_PLANKS, expected);

		aim(new Vec3(0.5, FEET_Y + 1, -1.5));
		context.waitTicks(10);
		releaseUse();
		context.takeScreenshot("placer-sweep");
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	// 壁に張るときは手前へ伸びず、壁の面に沿ってだけ増える
	private void testWall() {
		setUp(Items.OAK_PLANKS);
		singleplayer.getServer().runOnServer(server -> fill(server.overworld(),
				new BlockPos(-3, FEET_Y, -3), new BlockPos(3, FEET_Y + 3, -3), Blocks.STONE));
		context.waitTicks(2);

		aim(new Vec3(0.5, FEET_Y + 1.5, -2));
		holdUse();
		context.waitTicks(HOLD_TICKS);
		Set<BlockPos> expected = new HashSet<>();
		expected.add(new BlockPos(0, FEET_Y + 1, -2));
		assertPlaced(Blocks.OAK_PLANKS, expected);

		aim(new Vec3(1.5, FEET_Y + 1.5, -2));
		context.waitTicks(5);
		expected.add(new BlockPos(1, FEET_Y + 1, -2));
		assertPlaced(Blocks.OAK_PLANKS, expected);

		aim(new Vec3(0.5, FEET_Y + 2.5, -2));
		context.waitTicks(5);
		releaseUse();
		expected.add(new BlockPos(0, FEET_Y + 2, -2));
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	// 壁に張りながら下がっても、置いたブロックの手前へは増えない
	private void testWallStepBack() {
		setUp(Items.OAK_PLANKS);
		singleplayer.getServer().runOnServer(server -> fill(server.overworld(),
				new BlockPos(-3, FEET_Y, -2), new BlockPos(3, FEET_Y + 3, -2), Blocks.STONE));
		context.waitTicks(2);

		BlockPos placed = new BlockPos(0, FEET_Y + 2, -1);
		aim(new Vec3(0.5, FEET_Y + 2.5, -1));
		holdUse();
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(placed));

		context.getInput().lookAt(180, -90);
		context.getInput().holdKey(options -> options.keyDown);
		context.waitTicks(5);
		context.getInput().releaseKey(options -> options.keyDown);
		context.waitTicks(5);
		aim(new Vec3(0.5, FEET_Y + 2.5, 0));
		context.waitTicks(10);
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, Set.of(placed));
	}

	// 置いたブロックの横で跳んで、その上面を見ていても積まれない（置いた床へ飛び乗るとき）
	private void testJumpBeside() {
		setUp(Items.OAK_PLANKS);
		BlockPos placed = new BlockPos(0, FEET_Y, -1);
		aim(onFloor(0, -1));
		holdUse();
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(placed));

		// 跳んで足が 0.1 ブロックより上にある間、視線がこのブロックの上面に当たる
		context.getInput().lookAt(180, 56);
		context.getInput().holdKey(options -> options.keyJump);
		context.waitTicks(30);
		releaseUse();
		context.getInput().releaseKey(options -> options.keyJump);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(placed));
	}

	// スニークで後ろへ歩きながらの橋渡し。自分のブロックの面に続けて置ける
	private void testBridge() {
		setUp(Items.OAK_PLANKS);
		removeFloorBehind();
		holdUse();
		bridgeBackward("placer-bridge");
		assertBridge(placed(Blocks.OAK_PLANKS));
	}

	// 床を敷いている長押しのまま端まで下がっても、そこから橋を架けられる
	private void testBridgeAfterFloor() {
		setUp(Items.OAK_PLANKS);
		removeFloorBehind();
		BlockPos floor = new BlockPos(-2, FEET_Y, -2);
		aim(onFloor(-2, -2));
		holdUse();
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(floor));

		bridgeBackward("placer-bridge-after-floor");
		Set<BlockPos> placed = placed(Blocks.OAK_PLANKS);
		if (!placed.remove(floor)) {
			throw new AssertionError("floor should stay but was " + placed);
		}
		assertBridge(placed);
	}

	private void removeFloorBehind() {
		singleplayer.getServer().runOnServer(server -> fill(server.overworld(),
				new BlockPos(-AREA, FLOOR_Y, 1), new BlockPos(AREA, FLOOR_Y, AREA), Blocks.AIR));
		context.waitTicks(2);
	}

	// 使うキーは押したまま呼ぶ
	private void bridgeBackward(String screenshot) {
		context.getInput().lookAt(180, 81);
		context.getInput().holdKey(options -> options.keyShift);
		context.getInput().holdKey(options -> options.keyDown);
		context.waitTicks(100);
		releaseUse();
		context.getInput().releaseKey(options -> options.keyDown);
		context.takeScreenshot(screenshot);
		context.getInput().releaseKey(options -> options.keyShift);
	}

	private static void assertBridge(Set<BlockPos> placed) {
		if (placed.size() < 4) {
			throw new AssertionError("bridge should grow but was " + placed);
		}
		for (int z = 1; z <= placed.size(); z++) {
			if (!placed.contains(new BlockPos(0, FLOOR_Y, z))) {
				throw new AssertionError("bridge should be one straight line but was " + placed);
			}
		}
	}

	// 真下を見てジャンプしながらの塔
	private void testTower() {
		setUp(Items.OAK_PLANKS);
		context.getInput().lookAt(0, 90);
		context.getInput().holdKey(options -> options.keyJump);
		holdUse();
		context.waitTicks(80);
		releaseUse();
		context.getInput().releaseKey(options -> options.keyJump);

		Set<BlockPos> placed = placed(Blocks.OAK_PLANKS);
		if (placed.size() < 3) {
			throw new AssertionError("tower should grow but was " + placed);
		}
		for (int i = 0; i < placed.size(); i++) {
			if (!placed.contains(new BlockPos(0, FEET_Y + i, 0))) {
				throw new AssertionError("tower should be one column but was " + placed);
			}
		}
	}

	// 下付きのハーフブロックに重ねて、フルブロックにしない
	private void testSlab() {
		setUp(Items.OAK_SLAB);
		aim(onFloor(0, -3));
		holdUse();
		context.waitTicks(10);
		aim(new Vec3(0.5, FEET_Y + 0.5, -2.5));
		context.waitTicks(10);
		releaseUse();

		BlockPos slab = new BlockPos(0, FEET_Y, -3);
		assertPlaced(Blocks.OAK_SLAB, Set.of(slab));
		SlabType type = singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(slab).getValue(SlabBlock.TYPE));
		if (type != SlabType.BOTTOM) {
			throw new AssertionError("slab should stay at the bottom but was " + type);
		}
	}

	// 足場は上へ伸ばして使うのでバニラのまま
	private void testScaffolding() {
		setUp(Items.SCAFFOLDING);
		aim(onFloor(0, -3));
		holdUse();
		context.waitTicks(HOLD_TICKS);
		releaseUse();
		int count = placed(Blocks.SCAFFOLDING).size();
		if (count < 2) {
			throw new AssertionError("scaffolding should grow but placed " + count);
		}
	}

	// 押し直せば、自分のブロックの上にも置ける
	private void testPressAgain() {
		setUp(Items.OAK_PLANKS);
		aim(onFloor(0, -3));
		holdUse();
		context.waitTicks(5);
		releaseUse();

		aim(new Vec3(0.5, FEET_Y + 1, -2.5));
		context.getInput().pressKey(options -> options.keyUse);
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(new BlockPos(0, FEET_Y, -3), new BlockPos(0, FEET_Y + 1, -3)));
	}

	// 軽く押す間に狙いが動いても 2 個置かれない
	private void testShortPress() {
		setUp(Items.OAK_PLANKS);
		aim(onFloor(0, -3));
		holdUse();
		context.waitTick();
		aim(onFloor(1, -3));
		context.waitTick();
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, Set.of(new BlockPos(0, FEET_Y, -3)));
	}

	// 置いたブロックの南面に続けて、後ろへ歩いた分だけ一直線に伸びる
	private void testWalkBack() {
		setUp(Items.OAK_PLANKS);
		putStone(0, 102, -4);
		// 目（102.62）と同じ高さのブロックなので、水平に南面へ当たる
		context.getInput().lookAt(180, 0);
		holdUse();
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(new BlockPos(0, 102, -3)));

		// 約 4 ブロック下がる。1 ブロックごとの置ける境目から離して止める
		context.getInput().holdKey(options -> options.keyDown);
		while (playerZ() < 4.1) {
			context.waitTick();
		}
		context.getInput().releaseKey(options -> options.keyDown);
		context.waitTicks(5);

		// 0.5 から歩いた距離を四捨五入した数だけ、-2 から南へ伸びる
		Set<BlockPos> expected = new HashSet<>();
		for (int k = 0; k <= (int) Math.floor(playerZ()); k++) {
			expected.add(new BlockPos(0, 102, -3 + k));
		}
		assertPlaced(Blocks.OAK_PLANKS, expected);

		// 止めたまま押し続けても増えない
		context.waitTicks(HOLD_TICKS);
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	// 置いたブロックの東面に続けて、横へ歩いた分だけ一直線に伸びる
	private void testWalkSideways() {
		setUp(Items.OAK_PLANKS);
		putStone(-2, 102, -2);
		// 石の東面の中央（目と同じ高さ）を狙う。東面に (-1,102,-2) が置ける
		aim(new Vec3(-1, 102.62, -1.5));
		holdUse();
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(new BlockPos(-1, 102, -2)));

		// 後ろと右を同時に押すと、向き (0.99, 0.14) でほぼ東へ歩く
		context.getInput().holdKey(options -> options.keyDown);
		context.getInput().holdKey(options -> options.keyRight);
		while (playerX() < 3.5) {
			context.waitTick();
		}
		context.getInput().releaseKey(options -> options.keyRight);
		context.getInput().releaseKey(options -> options.keyDown);
		context.waitTicks(5);

		// 東へ 3.44 で (2,102,-2)、4.56 で (3,102,-2) が射線に乗る。3.5 から 4.5 の間に止める
		Set<BlockPos> expected = new HashSet<>();
		for (int x = -1; x <= 2; x++) {
			expected.add(new BlockPos(x, 102, -2));
		}
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	// 少し下がっただけでも、前へ歩いても、視線が自分のブロックに当たっている間は増えない
	private void testWalkForward() {
		setUp(Items.OAK_PLANKS);
		putStone(0, 102, -4);
		context.getInput().lookAt(180, 0);
		holdUse();
		context.waitTicks(5);
		Set<BlockPos> expected = Set.of(new BlockPos(0, 102, -3));
		assertPlaced(Blocks.OAK_PLANKS, expected);

		// 続きになるのは 0.5 ブロック歩いてから。行き過ぎないようスニークで 0.3 ほど下がる（目の高さ 102.27 でも南面に当たる）
		context.getInput().holdKey(options -> options.keyShift);
		context.getInput().holdKey(options -> options.keyDown);
		while (playerZ() < 0.75) {
			context.waitTick();
		}
		context.getInput().releaseKey(options -> options.keyDown);
		context.waitTicks(10);
		context.getInput().releaseKey(options -> options.keyShift);
		if (playerZ() >= 1) {
			throw new AssertionError("should step back less than 0.5 but was at z=" + playerZ());
		}
		assertPlaced(Blocks.OAK_PLANKS, expected);

		// 自分のブロックの南面を見たまま 1 ブロック前へ歩く
		context.getInput().holdKey(options -> options.keyUp);
		while (playerZ() > -0.5) {
			context.waitTick();
		}
		context.getInput().releaseKey(options -> options.keyUp);
		context.waitTicks(5);
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	// スニークで後ろへ歩きながら幅 3 の橋を架ける。足元のマスは横にも置ける
	private void testWideBridge() {
		setUp(Items.OAK_PLANKS);
		removeFloorBehind();
		// 押した瞬間は足のマスを見ていて、体と重なるので何も置けない
		holdUse();
		context.getInput().lookAt(180, 81);
		context.getInput().holdKey(options -> options.keyShift);

		for (int n = 1; n <= 3; n++) {
			// 真っ直ぐ後ろへ下がり、橋の端 z=n を目が 0.2 ほど越えるまで歩く
			context.getInput().lookAt(180, 81);
			context.getInput().holdKey(options -> options.keyDown);
			while (eyeZ() < n + 0.2) {
				context.waitTick();
			}
			context.getInput().releaseKey(options -> options.keyDown);
			context.waitTicks(2);

			// スニークのまま足元の横を順に狙い、2〜3 tick ずつ待つ
			aim(new Vec3(0.5, FLOOR_Y + 0.5, n));
			context.waitTicks(3);
			aim(new Vec3(1.5, FLOOR_Y + 0.5, n));
			context.waitTicks(3);
			aim(new Vec3(-0.5, FLOOR_Y + 0.5, n));
			context.waitTicks(3);
		}

		releaseUse();
		context.getInput().releaseKey(options -> options.keyShift);

		Set<BlockPos> expected = new HashSet<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = 1; z <= 3; z++) {
				expected.add(new BlockPos(x, FLOOR_Y, z));
			}
		}
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	// 壁に張って後ろへ歩くと、足と同じ段なら床に沿って伸びる
	private void testWallWalkBack() {
		setUp(Items.OAK_PLANKS);
		singleplayer.getServer().runOnServer(server -> fill(server.overworld(),
				new BlockPos(-3, FEET_Y, -3), new BlockPos(3, FEET_Y + 3, -3), Blocks.STONE));
		context.waitTicks(2);

		// 壁の南面の、足と同じ段（Y=101）を狙う
		aim(new Vec3(0.5, FEET_Y + 0.3, -2));
		holdUse();
		context.waitTicks(5);
		assertPlaced(Blocks.OAK_PLANKS, Set.of(new BlockPos(0, FEET_Y, -2)));

		// 約 3 ブロック下がる。足と同じ段なので、壁を張っていても置ける
		context.getInput().holdKey(options -> options.keyDown);
		while (playerZ() < 3.1) {
			context.waitTick();
		}
		context.getInput().releaseKey(options -> options.keyDown);
		context.waitTicks(5);

		Set<BlockPos> expected = new HashSet<>();
		for (int k = 0; k <= (int) Math.floor(playerZ()); k++) {
			expected.add(new BlockPos(0, FEET_Y, -2 + k));
		}
		releaseUse();
		assertPlaced(Blocks.OAK_PLANKS, expected);
	}

	private void putStone(int x, int y, int z) {
		singleplayer.getServer().runOnServer(server -> server.overworld()
				.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState()));
		context.waitTicks(2);
	}

	private double playerX() {
		return context.computeOnClient(client -> client.player.position().x);
	}

	private double playerZ() {
		return context.computeOnClient(client -> client.player.position().z);
	}

	private double eyeZ() {
		return context.computeOnClient(client -> client.player.getEyePosition().z);
	}

	private void setUp(ItemLike held) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			fill(level, new BlockPos(-AREA, FLOOR_Y, -AREA), new BlockPos(AREA, FLOOR_Y, AREA), Blocks.STONE);
			fill(level, new BlockPos(-AREA, FEET_Y, -AREA), new BlockPos(AREA, FLOOR_Y + AREA, AREA), Blocks.AIR);
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.SURVIVAL);
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(held, 64));
			player.teleportTo(level, 0.5, FEET_Y, 0.5, Set.of(), 180, 0, true);
		});
		context.waitTicks(10);
		singleplayer.getConnection().waitForChunksRender();
	}

	private static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			level.setBlockAndUpdate(pos, block.defaultBlockState());
		}
	}

	private static Vec3 onFloor(int x, int z) {
		return new Vec3(x + 0.5, FEET_Y, z + 0.5);
	}

	private void aim(Vec3 point) {
		Vec3 eye = context.computeOnClient(client -> client.player.getEyePosition());
		Vec3 to = point.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
		context.getInput().lookAt(yaw, pitch);
	}

	private void holdUse() {
		context.getInput().holdKey(options -> options.keyUse);
	}

	private void releaseUse() {
		context.getInput().releaseKey(options -> options.keyUse);
	}

	private void pressToggle() {
		context.getInput().pressKey(PlacerClient.toggle);
		context.waitTick();
	}

	private void setEnabled(boolean enabled) {
		if (PlacerClient.enabled() != enabled) {
			pressToggle();
		}
		assertEnabled(enabled);
	}

	private static void assertEnabled(boolean expected) {
		if (PlacerClient.enabled() != expected) {
			throw new AssertionError("enabled should be " + expected);
		}
	}

	private static void assertConfig(String expected) {
		try {
			String actual = Files.readString(PlacerClient.CONFIG).strip();
			if (!actual.equals(expected)) {
				throw new AssertionError("config should be " + expected + " but was " + actual);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Set<BlockPos> placed(Block block) {
		// サーバーの処理が追いつくのを待つ
		context.waitTicks(2);
		return singleplayer.getServer().computeOnServer(server -> {
			Set<BlockPos> found = new HashSet<>();
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-AREA, FLOOR_Y, -AREA), new BlockPos(AREA, FLOOR_Y + AREA, AREA))) {
				if (server.overworld().getBlockState(pos).is(block)) {
					found.add(pos.immutable());
				}
			}
			return found;
		});
	}

	private void assertPlaced(Block block, Set<BlockPos> expected) {
		Set<BlockPos> actual = placed(block);
		if (!actual.equals(expected)) {
			throw new AssertionError("expected " + expected + " but was " + actual);
		}
	}
}
