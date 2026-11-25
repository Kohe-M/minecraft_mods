package com.omuraisu.minimap.client;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.Pin;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

// ピンの位置に立つ薄い光の柱と、その名前。
// ビーコンのレンダータイプで描くので、シェーダーパックでもビーコンと同じ扱いになる
final class PinBeams {
	private static final RenderType RENDER_TYPE = RenderTypes.beaconBeam(BeaconRenderer.BEAM_LOCATION, true);

	private static final int ALPHA = 0x70;
	private static final float RADIUS = 0.25f;
	// この距離までは同じ太さ。遠くでは太くして、細くなりすぎないようにする（ビーコンと同じ）
	private static final float CONSTANT_RADIUS_DISTANCE = 96;
	// 霧に消されないよう、描画距離のこの割合より遠いピンは方角を保って手前に描く
	private static final double MAX_DISTANCE_RATIO = 0.75;
	private static final int V_REPEAT_BLOCKS = 8;
	private static final int LABEL_GAP = 4;
	// これより離れると名前を出さない（ブロック）
	private static final int LABEL_MAX_DISTANCE = 32;

	// フェードの後に描くときだけ使う
	private static StagedVertexBuffer staged;

	static void submit(LevelRenderContext context) {
		if (drawsAfterFade()) {
			return;
		}
		PoseStack poseStack = context.poseStack();
		for (Beam beam : beams(context)) {
			poseStack.pushPose();
			poseStack.translate(beam.x, 0, beam.z);
			context.submitNodeCollector().submitCustomGeometry(poseStack, RENDER_TYPE, beam::write);
			poseStack.popPose();
		}
	}

	// Distant Horizons のフェード（LevelRenderer.executeOutline）の後。FeatureRenderDispatcher の PreparedFrame は使用中なので、
	// 自前のバッファに書いて描く。カメラの回転は ModelViewStack に乗っているので、PoseStack にはカメラからの平行移動だけを積む
	static void drawAfterFade(LevelRenderContext context) {
		if (!drawsAfterFade()) {
			return;
		}
		List<Beam> beams = beams(context);
		if (beams.isEmpty()) {
			return;
		}

		if (staged == null) {
			staged = new StagedVertexBuffer(() -> "minimap pin beams", 1536);
		}
		try {
			StagedVertexBuffer.Draw draw = staged.appendDraw(RENDER_TYPE.format(), RENDER_TYPE.primitiveTopology());
			VertexConsumer consumer = staged.getVertexBuilder(draw);
			PoseStack poseStack = new PoseStack();
			for (Beam beam : beams) {
				poseStack.pushPose();
				poseStack.translate(beam.x, 0, beam.z);
				beam.write(poseStack.last(), consumer);
				poseStack.popPose();
			}
			staged.upload();

			StagedVertexBuffer.ExecuteInfo info = staged.getExecuteInfo(draw);
			if (info == null) {
				return;
			}
			PreparedRenderType prepared = RENDER_TYPE.prepare();
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			try (RenderPass renderPass = RenderSystem.getDevice()
					.createCommandEncoder()
					.createRenderPass(() -> "minimap pin beams", main.getColorTextureView(), Optional.empty(),
							main.getDepthTextureView(), OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(renderPass);
				prepared.drawFromBuffer(info, renderPass);
			}
		} finally {
			staged.endFrame();
		}
	}

	// Distant Horizons は、バニラの画素を奥行きに応じて LOD の色に混ぜる（フェード）。柱は深度を書かない半透明なので、
	// 柱の後ろの地形の奥行きで混ざり、後ろが遠いところで柱が消える。シェーダーパック使用中はフェードが掛からない
	private static boolean drawsAfterFade() {
		return FabricLoader.getInstance().isModLoaded("distanthorizons") && !ShaderPacks.inUse();
	}

	// 名前はカメラの高さで柱に添える
	static void extractLabels(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (!Minimap.isActive() || client.level == null) {
			return;
		}
		Vec3 camera = client.gameRenderer.mainCamera().position();
		Vec3 forward = new Vec3(client.gameRenderer.mainCamera().forwardVector());
		for (Pin pin : MinimapClient.PINS.in(client.level.dimension().identifier())) {
			if (pin.name().isEmpty() || !isLabelInRange(pin, camera)) {
				continue;
			}
			Vec3 offset = offsetFromCamera(client, pin, camera);
			// カメラの後ろの点も投影すると画面の中に写ってしまう
			if (offset.dot(forward) <= 0) {
				continue;
			}
			Vec3 projected = client.gameRenderer.projectPointToScreen(camera.add(offset));
			if (Math.abs(projected.x) > 1 || Math.abs(projected.y) > 1) {
				continue;
			}
			int x = (int) ((projected.x + 1) / 2 * graphics.guiWidth());
			int y = (int) ((1 - projected.y) / 2 * graphics.guiHeight());
			MapIcons.label(graphics, client.font, pin.name(), x, y - MapIcons.LABEL_HEIGHT - LABEL_GAP);
		}
	}

	// 柱は手前に寄せて描くことがあるので、実際の位置で測る
	private static boolean isLabelInRange(Pin pin, Vec3 camera) {
		double dx = pin.x() + 0.5 - camera.x;
		double dz = pin.z() + 0.5 - camera.z;
		return dx * dx + dz * dz <= LABEL_MAX_DISTANCE * LABEL_MAX_DISTANCE;
	}

	// カメラからピンの柱までの水平の差
	private static Vec3 offsetFromCamera(Minecraft client, Pin pin, Vec3 camera) {
		double dx = pin.x() + 0.5 - camera.x;
		double dz = pin.z() + 0.5 - camera.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		double max = client.options.getEffectiveRenderDistance() * 16 * MAX_DISTANCE_RATIO;
		double scale = distance > max ? max / distance : 1;
		return new Vec3(dx * scale, 0, dz * scale);
	}

	// 立っている柱。隠している間は空
	private static List<Beam> beams(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (!Minimap.isActive() || level == null || client.gui.hud.isHidden()) {
			return List.of();
		}

		Vec3 camera = context.levelState().cameraRenderState.pos;
		float bottom = (float) (level.getMinY() - camera.y);
		float top = (float) (level.getMaxY() + 1 - camera.y);
		return MinimapClient.PINS.in(level.dimension().identifier()).stream().map(pin -> {
			Vec3 offset = offsetFromCamera(client, pin, camera);
			float radius = RADIUS * Math.max(1, (float) offset.length() / CONSTANT_RADIUS_DISTANCE);
			return new Beam(offset.x, offset.z, radius, bottom, top, ARGB.color(ALPHA, pin.color()));
		}).toList();
	}

	// 位置と高さはカメラからの相対
	private record Beam(double x, double z, float radius, float bottom, float top, int color) {
		void write(PoseStack.Pose pose, VertexConsumer consumer) {
			float[][] corners = {{-radius, radius}, {radius, radius}, {radius, -radius}, {-radius, -radius}};
			float height = (top - bottom) / V_REPEAT_BLOCKS;
			for (int i = 0; i < corners.length; i++) {
				float[] from = corners[i];
				float[] to = corners[(i + 1) % corners.length];
				vertex(pose, consumer, from[0], bottom, from[1], 0, height, color);
				vertex(pose, consumer, to[0], bottom, to[1], 1, height, color);
				vertex(pose, consumer, to[0], top, to[1], 1, 0, color);
				vertex(pose, consumer, from[0], top, from[1], 0, 0, color);
			}
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float z, float u, float v, int color) {
		consumer.addVertex(pose, x, y, z)
				.setColor(color)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(pose, 0, 1, 0);
	}

	private PinBeams() {
	}
}
