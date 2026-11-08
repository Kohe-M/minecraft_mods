package com.omuraisu.fullbright.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.fullbright.client.FullbrightClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class FullbrightClientGameTest implements FabricClientGameTest {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("fullbright.txt");
	private static final ResourceKey<Level> NO_AMBIENT = ResourceKey.create(Registries.DIMENSION,
			Identifier.fromNamespaceAndPath("fullbright-gametest", "no_ambient"));

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			BlockPos feet = singleplayer.getServer().computeOnServer(server -> {
				BlockPos pos = server.getPlayerList().getPlayers().getFirst().blockPosition();
				buildDarkRoom(server.overworld(), pos);
				return pos;
			});
			waitForRender(context, singleplayer);
			context.getInput().lookAt(feet.offset(0, 1, -3));
			context.waitTicks(5);

			testToggleAndLevel(context);
			testInactive(context);
			testConfig(context);

			if (FabricLoader.getInstance().isModLoaded("iris")) {
				testShaderPack(context, singleplayer);
			}

			testNoAmbientDimension(context, singleplayer);
		}
	}

	private static void testToggleAndLevel(ClientGameTestContext context) {
		// 前回の実行の設定が残っているので上限に揃える。ホットバーは動かない
		int slot = selectedSlot(context);
		adjust(context, 100);
		assertState(true, FullbrightClient.MAX_LEVEL);
		if (selectedSlot(context) != slot) {
			throw new AssertionError("hotbar slot changed while adjusting");
		}

		tap(context);
		assertState(false, FullbrightClient.MAX_LEVEL);
		double off = brightness(context, "off");

		tap(context);
		assertState(true, FullbrightClient.MAX_LEVEL);
		double max = brightness(context, "level-max");

		adjust(context, -100);
		assertState(true, FullbrightClient.MIN_LEVEL);
		double min = brightness(context, "level-min");

		if (!(off < min && min < max)) {
			throw new AssertionError("brightness should rise off < min < max but was " + off + ", " + min + ", " + max);
		}
		if (max < off * 3) {
			throw new AssertionError("max level should be far brighter than off but was " + max + " and " + off);
		}

		// 上限突破しても設定値には書き込まない（options.txt が読めなくなる）
		double gamma = context.computeOnClient(client -> client.options.gamma().get());
		if (gamma > 1) {
			throw new AssertionError("gamma option was changed to " + gamma);
		}

		// オフの間に調整するとオンになる
		tap(context);
		assertState(false, FullbrightClient.MIN_LEVEL);
		adjust(context, 1);
		assertState(true, FullbrightClient.MIN_LEVEL + 1);

		// 離した後のホイールはホットバーに戻る
		context.getInput().scroll(-1);
		context.waitTick();
		if (selectedSlot(context) == slot) {
			throw new AssertionError("hotbar slot should change when the key is not held");
		}
		assertState(true, FullbrightClient.MIN_LEVEL + 1);
	}

	// Jori で無効にされている間は、明るさもキーもホイールもバニラのまま。戻すと元の明るさに戻る
	private static void testInactive(ClientGameTestContext context) {
		assertState(true, FullbrightClient.MIN_LEVEL + 1);
		double on = brightness(context, "active");

		context.runOnClient(client -> FullbrightClient.setActive(false));
		try {
			double off = brightness(context, "inactive");
			if (off * 2 > on) {
				throw new AssertionError("inactive should look vanilla but was " + off + " against " + on);
			}
			int slot = selectedSlot(context);
			tap(context);
			adjust(context, 1);
			if (FullbrightClient.isEnabled()) {
				throw new AssertionError("key should do nothing while inactive");
			}
			if (selectedSlot(context) == slot) {
				throw new AssertionError("scroll should reach the hotbar while inactive");
			}
		} finally {
			context.runOnClient(client -> FullbrightClient.setActive(true));
		}
		assertState(true, FullbrightClient.MIN_LEVEL + 1);
		double back = brightness(context, "active-again");
		if (Math.abs(back - on) > 1) {
			throw new AssertionError("brightness should come back to " + on + " but was " + back);
		}
	}

	private static void testConfig(ClientGameTestContext context) {
		try {
			adjust(context, 1);
			assertConfig("enabled=true\nlevel=" + (FullbrightClient.MIN_LEVEL + 2) + "\n");
			tap(context);
			assertConfig("enabled=false\nlevel=" + (FullbrightClient.MIN_LEVEL + 2) + "\n");

			FullbrightClient.load();
			assertState(false, FullbrightClient.MIN_LEVEL + 2);

			// 範囲外や壊れた値で落ちない
			Files.writeString(CONFIG, "level=999\n");
			FullbrightClient.load();
			assertState(true, FullbrightClient.MAX_LEVEL);

			Files.writeString(CONFIG, "level=abc\n");
			FullbrightClient.load();
			assertState(true, FullbrightClient.MAX_LEVEL);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ライトマップを見ないシェーダーパックにも届く。uniform の値をそのまま色にして読み取る
	private static void testShaderPack(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.runOnClient(client -> IrisPack.enable());
		waitForRender(context, singleplayer);
		try {
			adjust(context, 100);
			assertUniforms(context, "pack-max", 1, 1);

			adjust(context, 5 - FullbrightClient.MAX_LEVEL);
			assertUniforms(context, "pack-mid", 5f / FullbrightClient.MAX_LEVEL, 1);

			tap(context);
			double gamma = context.computeOnClient(client -> client.options.gamma().get());
			assertUniforms(context, "pack-off", 0, (float) gamma);

			tap(context);
		} finally {
			context.runOnClient(client -> IrisPack.disable());
			waitForRender(context, singleplayer);
		}
	}

	// ambient_light_color がない次元でも暗所が明るくなる
	private static void testNoAmbientDimension(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		BlockPos feet = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(NO_AMBIENT);
			BlockPos pos = new BlockPos(0, 100, 0);
			buildDarkRoom(level, pos);
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), 0, 0, true);
			return pos;
		});
		context.waitTicks(20);
		waitForRender(context, singleplayer);
		context.getInput().lookAt(feet.offset(0, 1, -3));
		context.waitTicks(5);

		adjust(context, 100);
		double on = brightness(context, "no-ambient-on");
		tap(context);
		double off = brightness(context, "no-ambient-off");
		tap(context);

		if (on < 30 || on < off * 3) {
			throw new AssertionError("no ambient dimension should be bright but on was " + on + " and off was " + off);
		}
	}

	// 外から光が入らない部屋を足元に作る
	private static void buildDarkRoom(ServerLevel level, BlockPos feet) {
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-4, -1, -4), feet.offset(4, 4, 4))) {
			level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
		}
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-3, 0, -3), feet.offset(3, 3, 3))) {
			level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		}
	}

	private static void tap(ClientGameTestContext context) {
		context.getInput().pressKey(FullbrightClient.key);
		context.waitTicks(3);
	}

	private static void adjust(ClientGameTestContext context, int steps) {
		context.getInput().holdKey(FullbrightClient.key);
		context.waitTick();
		context.getInput().scroll(steps);
		context.waitTick();
		context.getInput().releaseKey(FullbrightClient.key);
		context.waitTicks(3);
	}

	private static void waitForRender(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	private static void assertState(boolean enabled, int level) {
		if (FullbrightClient.isEnabled() != enabled || FullbrightClient.level() != level) {
			throw new AssertionError("expected " + enabled + "/" + level + " but was " + FullbrightClient.isEnabled() + "/" + FullbrightClient.level());
		}
	}

	private static void assertConfig(String expected) throws IOException {
		String actual = Files.readString(CONFIG);
		if (!actual.equals(expected)) {
			throw new AssertionError("expected config " + expected + " but was " + actual);
		}
	}

	private static void assertUniforms(ClientGameTestContext context, String name, float nightVision, float screenBrightness) {
		context.waitTicks(5);
		int pixel = readImage(context.takeScreenshot(name), image -> image.getPixel(image.getWidth() / 4, image.getHeight() / 4));
		float actualNightVision = ARGB.red(pixel) / 255f;
		float actualBrightness = ARGB.green(pixel) / 255f;
		if (Math.abs(actualNightVision - nightVision) > 0.02f || Math.abs(actualBrightness - screenBrightness) > 0.02f) {
			throw new AssertionError(name + ": expected nightVision " + nightVision + " and screenBrightness " + screenBrightness
					+ " but was " + actualNightVision + " and " + actualBrightness);
		}
	}

	private static int selectedSlot(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.player.getInventory().getSelectedSlot());
	}

	// 画面の左上の平均の明るさ（0〜255）。ホットバーや手を避ける
	private static double brightness(ClientGameTestContext context, String name) {
		context.waitTicks(5);
		return readImage(context.takeScreenshot(name), image -> {
			long sum = 0;
			int count = 0;
			for (int y = image.getHeight() / 8; y < image.getHeight() / 2; y++) {
				for (int x = image.getWidth() / 8; x < image.getWidth() / 2; x++) {
					int pixel = image.getPixel(x, y);
					sum += ARGB.red(pixel) + ARGB.green(pixel) + ARGB.blue(pixel);
					count += 3;
				}
			}
			return (double) sum / count;
		});
	}

	private static <T> T readImage(Path path, ImageReader<T> reader) {
		try (InputStream in = Files.newInputStream(path); NativeImage image = NativeImage.read(in)) {
			return reader.read(image);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private interface ImageReader<T> {
		T read(NativeImage image);
	}

	// Iris がないときに読み込まれないようにクラスを分ける
	private static final class IrisPack {
		private static final String NAME = "fullbright-probe";

		private static final String VERTEX = """
				#version 120
				void main() {
					gl_Position = ftransform();
				}
				""";

		private static final String FRAGMENT = """
				#version 120
				uniform float nightVision;
				uniform float screenBrightness;
				void main() {
					gl_FragData[0] = vec4(nightVision, screenBrightness, 0.0, 1.0);
				}
				""";

		static void enable() {
			try {
				Path shaders = Iris.getShaderpacksDirectory().resolve(NAME).resolve("shaders");
				Files.createDirectories(shaders);
				Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), VERTEX);
				Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), FRAGMENT);

				Iris.getIrisConfig().setShaderPackName(NAME);
				Iris.getIrisConfig().setShadersEnabled(true);
				Iris.getIrisConfig().save();
				Iris.reload();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		static void disable() {
			try {
				Iris.getIrisConfig().setShadersEnabled(false);
				Iris.getIrisConfig().save();
				Iris.reload();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
