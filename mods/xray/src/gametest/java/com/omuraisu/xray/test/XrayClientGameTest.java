package com.omuraisu.xray.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntPredicate;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.testkit.Asserts;
import com.omuraisu.testkit.Screenshots;
import com.omuraisu.xray.client.ModeWheelScreen;
import com.omuraisu.xray.client.XrayClient;
import com.omuraisu.xray.client.XrayMode;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public class XrayClientGameTest implements FabricClientGameTest {
	// ModeWheelScreen と同じ外周の半径
	private static final int RADIUS = 54;
	private static final int FAR = 72;
	// 鶏をふつうに描く 32 ブロックより遠く、クライアントが知っている範囲（描画距離）より近く
	private static final int FAR_ENTITY = 48;

	private record Scene(BlockPos center, BlockPos chest, BlockPos farChest, int sheep, Vec3 sheepBody, int chicken, Vec3 chickenBody) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		testConfig(context);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			Scene scene = singleplayer.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				BlockPos feet = server.getPlayerList().getPlayers().getFirst().blockPosition();
				BlockPos origin = feet.offset(4, -2, -6);
				for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(10, 8, 12))) {
					level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
				}
				place(level, origin.offset(3, 3, 6), Blocks.DIAMOND_ORE);
				place(level, origin.offset(6, 1, 1), Blocks.DEEPSLATE_DIAMOND_ORE);
				place(level, origin.offset(5, 5, 3), Blocks.GOLD_ORE);
				place(level, origin.offset(4, 2, 9), Blocks.IRON_ORE);
				place(level, origin.offset(6, 6, 8), Blocks.COAL_ORE);
				place(level, origin.offset(2, 1, 4), Blocks.REDSTONE_ORE);
				place(level, origin.offset(5, 4, 10), Blocks.EMERALD_ORE);
				place(level, origin.offset(7, 2, 2), Blocks.DEEPSLATE_LAPIS_ORE);
				place(level, origin.offset(3, 5, 7), Blocks.WATER);
				// 石に埋めて光が届かないようにする。手前に鉱石がない位置
				BlockPos chest = origin.offset(5, 2, 4);
				place(level, chest, Blocks.CHEST);
				// ブロックエンティティをふつうに描く 64 ブロックより遠く、描画距離（5 チャンク）より近く
				BlockPos farChest = feet.offset(FAR, 1, 0);
				place(level, farChest, Blocks.CHEST);
				// 石の中の空洞に赤い羊。光が届かず真っ暗。手前に鉱石もチェストもない位置
				BlockPos cave = origin.offset(6, 2, 10);
				place(level, cave, Blocks.AIR);
				place(level, cave.above(), Blocks.AIR);
				Sheep sheep = spawn(level, EntityTypes.SHEEP, Vec3.atBottomCenterOf(cave));
				sheep.setColor(DyeColor.RED);
				level.addFreshEntity(sheep);
				Mob chicken = spawn(level, EntityTypes.CHICKEN, Vec3.atBottomCenterOf(feet.offset(FAR_ENTITY, 3, 2)));
				chicken.setNoGravity(true);
				level.addFreshEntity(chicken);
				// 羊の毛の胴体は足元から 0.64〜1.23 の高さ
				return new Scene(origin.offset(4, 3, 6), chest, farChest,
						sheep.getId(), sheep.position().add(0, 0.94, 0), chicken.getId(), chicken.getBoundingBox().getCenter());
			});
			BlockPos center = scene.center();
			BlockPos chest = scene.chest();
			BlockPos farChest = scene.farChest();
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().lookAt(center);
			context.waitTicks(5);
			context.takeScreenshot("xray-off");

			// 長押しでホイール、動かさずに離すと中央の全鉱石
			openWheel(context);
			moveCursor(context, 0, 0);
			context.takeScreenshot("wheel-all");
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTick();
			assertState(context, true, XrayMode.ALL);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-all");

			tap(context);
			assertState(context, false, XrayMode.ALL);

			// ホイールが開くより短い長押しは単押しと同じ
			context.getInput().holdKey(XrayClient.toggle);
			context.waitTicks(XrayClient.HOLD_TICKS - 2);
			if (context.computeOnClient(client -> client.gui.screen()) != null) {
				throw new AssertionError("wheel should not open before " + XrayClient.HOLD_TICKS + " ticks");
			}
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTicks(2);
			assertState(context, true, XrayMode.ALL);
			tap(context);
			assertState(context, false, XrayMode.ALL);

			// 真上のダイヤを選んで離す
			openWheel(context);
			moveCursor(context, 0, -RADIUS);
			context.takeScreenshot("wheel-diamond");
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTick();
			assertState(context, true, XrayMode.DIAMOND);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-diamond");

			// 単押しは選んだモードのままオン/オフ
			tap(context);
			assertState(context, false, XrayMode.DIAMOND);
			tap(context);
			assertState(context, true, XrayMode.DIAMOND);

			// クリックでも確定する。その後キーを離しても切り替わらない
			int goldIndex = XrayMode.RING.indexOf(XrayMode.GOLD);
			double angle = -Math.PI / 2 + goldIndex * 2 * Math.PI / XrayMode.RING.size();
			openWheel(context);
			moveCursor(context, (int) Math.round(Math.cos(angle) * RADIUS), (int) Math.round(Math.sin(angle) * RADIUS));
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTick();
			assertState(context, true, XrayMode.GOLD);
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTicks(2);
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-gold");

			tap(context);
			assertState(context, false, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("xray-off-again");

			// Jori で無効にされている間は透過せず、キーも効かない。戻すと透過し直す
			tap(context);
			assertState(context, true, XrayMode.GOLD);
			context.runOnClient(client -> XrayClient.setActive(false));
			try {
				assertState(context, false, XrayMode.GOLD);
				waitForRender(context, singleplayer);
				context.takeScreenshot("xray-inactive");
				tap(context);
				assertState(context, false, XrayMode.GOLD);
				context.getInput().holdKey(XrayClient.toggle);
				context.waitTicks(10);
				if (context.computeOnClient(client -> client.gui.screen()) != null) {
					throw new AssertionError("wheel should not open while inactive");
				}
				context.getInput().releaseKey(XrayClient.toggle);
				context.waitTick();
			} finally {
				context.runOnClient(client -> XrayClient.setActive(true));
			}
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("xray-active-again");
			tap(context);
			assertState(context, false, XrayMode.GOLD);

			// 普通のチェストは、どの鉱石を選んでいても最大の明るさで見せる。64 ブロックより遠くても描く
			context.getInput().lookAt(chest);
			waitForRender(context, singleplayer);
			assertChestShown(context, "chest-off", false);
			assertChestLight(context, chest, false);
			assertChestLight(context, farChest, false);
			tap(context);
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			assertChestShown(context, "chest-gold", true);
			assertChestLight(context, chest, true);
			assertChestLight(context, farChest, true);
			tap(context);
			assertState(context, false, XrayMode.GOLD);

			// 鉱石のモードでは、エンティティは本物の明るさのまま
			lookAt(context, scene.sheepBody());
			waitForRender(context, singleplayer);
			assertSheepShown(context, "sheep-off", false);
			assertEntityLight(context, scene.sheep(), false);
			tap(context);
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			assertEntityLight(context, scene.sheep(), false);
			tap(context);
			assertState(context, false, XrayMode.GOLD);

			// エンティティとチェストのモードは、鉱石を出さず、エンティティを最大の明るさで描く
			int entitiesIndex = XrayMode.RING.indexOf(XrayMode.ENTITIES);
			double entitiesAngle = -Math.PI / 2 + entitiesIndex * 2 * Math.PI / XrayMode.RING.size();
			openWheel(context);
			moveCursor(context, (int) Math.round(Math.cos(entitiesAngle) * RADIUS), (int) Math.round(Math.sin(entitiesAngle) * RADIUS));
			context.takeScreenshot("wheel-entities");
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTick();
			assertState(context, true, XrayMode.ENTITIES);
			waitForRender(context, singleplayer);
			assertSheepShown(context, "sheep-entities", true);
			assertEntityLight(context, scene.sheep(), true);
			assertChestLight(context, chest, true);
			assertChestLight(context, farChest, true);
			context.runOnClient(client -> {
				if (XrayClient.isVisible(Blocks.DIAMOND_ORE.defaultBlockState()) || !XrayClient.isVisible(Blocks.CHEST.defaultBlockState())) {
					throw new AssertionError("only chests should be visible in the entity mode");
				}
			});
			context.getInput().lookAt(center);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-entities");

			// 大きさで決まる距離より遠いエンティティも描く
			lookAt(context, scene.chickenBody());
			waitForRender(context, singleplayer);
			assertEntityRendered(context, scene.chicken(), true);
			tap(context);
			assertState(context, false, XrayMode.ENTITIES);
			waitForRender(context, singleplayer);
			assertEntityRendered(context, scene.chicken(), false);
		}
	}

	// config/xray.txt は行ごとに読む。知らない行が混ざっていてもモードを読め、書いていない・知らないモードなら全鉱石
	private static void testConfig(ClientGameTestContext context) {
		try {
			Files.writeString(XrayClient.CONFIG, "# memo\nfuture_option=3\n mode = diamond \nbroken line\n");
			context.runOnClient(client -> XrayClient.load());
			Asserts.assertEquals("mode among unknown lines", XrayMode.DIAMOND, XrayClient.mode());
			Files.writeString(XrayClient.CONFIG, "future_option=3\n");
			context.runOnClient(client -> XrayClient.load());
			Asserts.assertEquals("mode without the line", XrayMode.ALL, XrayClient.mode());
			Files.writeString(XrayClient.CONFIG, "mode=nothing\n");
			context.runOnClient(client -> XrayClient.load());
			Asserts.assertEquals("unknown mode", XrayMode.ALL, XrayClient.mode());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// 動かず、ダメージも受けないモブを作る。ワールドに足すのは呼んだ側
	private static <T extends Mob> T spawn(ServerLevel level, EntityType<T> type, Vec3 pos) {
		T mob = type.create(level, EntitySpawnReason.COMMAND);
		// 南を向かせて、プレイヤー（西）から横腹が見えるようにする
		mob.snapTo(pos, 0, 0);
		mob.setYHeadRot(0);
		mob.setYBodyRot(0);
		mob.setNoAi(true);
		mob.setPermanentlyInvulnerable(true);
		return mob;
	}

	private static void lookAt(ClientGameTestContext context, Vec3 target) {
		Vec3 delta = target.subtract(context.computeOnClient(client -> client.player.getEyePosition()));
		float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
		context.getInput().lookAt(yaw, pitch);
	}

	// 画面の中央（羊の胴体を見ている）に、明るい赤い毛が映っているか
	private static void assertSheepShown(ClientGameTestContext context, String name, boolean shown) {
		// 羊の胴体は 11 ブロック先で 0.6 ブロックの高さなので、中央を少し狭く見る。
		// 石は灰色、空は青か橙、光の届かない羊は黒に近い
		double red = centerRatio(context, name, 6, pixel -> ARGB.red(pixel) > 60
				&& ARGB.red(pixel) > ARGB.green(pixel) * 2 && ARGB.red(pixel) > ARGB.blue(pixel) * 2);
		if ((red > 0.5) != shown) {
			throw new AssertionError(name + ": sheep should be " + (shown ? "shown" : "hidden") + " but " + Math.round(red * 100) + "% of the center was red wool");
		}
	}

	private static void assertEntityLight(ClientGameTestContext context, int id, boolean bright) {
		int light = context.computeOnClient(client -> client.getEntityRenderDispatcher().getPackedLightCoords(entity(client, id), 0));
		if (bright) {
			Asserts.assertEquals("light of the entity " + id, LightCoordsUtil.FULL_BRIGHT, light);
		} else if (light == LightCoordsUtil.FULL_BRIGHT) {
			throw new AssertionError("buried entity " + id + " should be dark but was full bright");
		}
	}

	// 見ている先にあるエンティティを、いまのカメラで描くかどうか
	private static void assertEntityRendered(ClientGameTestContext context, int id, boolean rendered) {
		boolean actual = context.computeOnClient(client -> {
			Camera camera = client.gameRenderer.mainCamera();
			Vec3 pos = camera.position();
			return client.getEntityRenderDispatcher().shouldRender(entity(client, id), camera.getCullFrustum(), pos.x, pos.y, pos.z, 0);
		});
		Asserts.assertEquals("far entity " + id + " rendered", rendered, actual);
	}

	private static Entity entity(Minecraft client, int id) {
		Entity entity = client.level.getEntity(id);
		if (entity == null) {
			throw new AssertionError("no entity " + id + " on the client");
		}
		return entity;
	}

	// 画面の中央（チェストを見ている）に、明るい木の色が映っているか
	private static void assertChestShown(ClientGameTestContext context, String name, boolean shown) {
		// チェストは 9 ブロック先で 30 画素ほど。黒い縁と継ぎ目を除けば、ほとんどが木の色。
		// 石は灰色、空は青か橙で白っぽく、光の届かないチェストは黒に近い
		double wood = centerRatio(context, name, 8, pixel -> ARGB.red(pixel) > 55 && ARGB.red(pixel) > ARGB.blue(pixel) * 5 / 2);
		if ((wood > 0.5) != shown) {
			throw new AssertionError(name + ": chest should be " + (shown ? "shown" : "hidden") + " but " + Math.round(wood * 100) + "% of the center was wood");
		}
	}

	// 画面の中央から上下左右に half 画素の範囲で、色が当てはまる画素の割合
	private static double centerRatio(ClientGameTestContext context, String name, int half, IntPredicate color) {
		// 十字の照準が重ならないよう、F1 と同じく HUD を隠して撮る
		context.runOnClient(client -> client.gui.hud.toggle());
		Path path;
		try {
			path = context.takeScreenshot(name);
		} finally {
			context.runOnClient(client -> client.gui.hud.toggle());
		}
		return Screenshots.read(path, image -> {
			int count = 0;
			int total = 0;
			for (int y = image.getHeight() / 2 - half; y <= image.getHeight() / 2 + half; y++) {
				for (int x = image.getWidth() / 2 - half; x <= image.getWidth() / 2 + half; x++) {
					if (color.test(image.getPixel(x, y))) {
						count++;
					}
					total++;
				}
			}
			return (double) count / total;
		});
	}

	// 透過中だけ、チェストを描画距離いっぱいまで最大の明るさで描く
	private static void assertChestLight(ClientGameTestContext context, BlockPos pos, boolean enabled) {
		Integer light = context.computeOnClient(client -> {
			BlockEntity blockEntity = client.level.getBlockEntity(pos);
			if (blockEntity == null) {
				throw new AssertionError("no chest at " + pos + " on the client");
			}
			BlockEntityRenderState state = client.getBlockEntityRenderDispatcher().tryExtractRenderState(blockEntity, 0, null, false);
			return state == null ? null : state.lightCoords;
		});
		boolean far = !pos.closerThan(context.computeOnClient(client -> client.player.blockPosition()), 64);
		if (enabled) {
			Asserts.assertEquals("light of the chest at " + pos, LightCoordsUtil.FULL_BRIGHT, light);
		} else if (far) {
			Asserts.assertEquals("render state of the far chest at " + pos, null, light);
		} else if (light == null || light == LightCoordsUtil.FULL_BRIGHT) {
			throw new AssertionError("buried chest at " + pos + " should be dark but was " + light);
		}
	}

	private static void openWheel(ClientGameTestContext context) {
		context.getInput().holdKey(XrayClient.toggle);
		context.waitForScreen(ModeWheelScreen.class);
		context.waitTick();
	}

	private static void tap(ClientGameTestContext context) {
		context.getInput().pressKey(XrayClient.toggle);
		context.waitTicks(2);
	}

	// ホイールの中心からのずれ（GUI 座標）にカーソルを置く
	private static void moveCursor(ClientGameTestContext context, int dx, int dy) {
		double[] pos = context.computeOnClient(client -> {
			int scale = client.getWindow().getGuiScale();
			return new double[] {(client.gui.screen().width / 2 + dx) * scale, (client.gui.screen().height / 2 + dy) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.waitTicks(2);
	}

	private static void waitForRender(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.waitTick();
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	private static void assertState(ClientGameTestContext context, boolean enabled, XrayMode mode) {
		if (XrayClient.isEnabled() != enabled || XrayClient.mode() != mode) {
			throw new AssertionError("expected " + enabled + "/" + mode + " but was " + XrayClient.isEnabled() + "/" + XrayClient.mode());
		}
		if (context.computeOnClient(client -> client.gui.screen()) != null) {
			throw new AssertionError("wheel should be closed");
		}
	}

	private static void place(ServerLevel level, BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState());
	}
}
