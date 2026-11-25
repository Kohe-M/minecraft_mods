package com.omuraisu.digger.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.omuraisu.digger.DigPlan;
import com.omuraisu.digger.DigSettings;
import com.omuraisu.digger.Digger;
import com.omuraisu.digger.SettingsPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;

// 壊れるブロックの外周を、狙っている間だけ線で出す。枠線は大きさの設定画面の代わりになり、
// 巻き込むものを壊す前に分かる。対象は tick ごとに計算し直し、描くときは覚えた辺を使う
final class DigPreview {
	// 水色。アルファはバニラの枠線と同じくらい
	private static final int COLOR = 0xD04FD8E0;

	// 前の tick と同じ対象なら辺を作り直さない。鉱脈の外接箱は大きくなることがある
	private static List<BlockPos> shown = List.of();
	private static List<Edge> edges = List.of();
	private static int minX;
	private static int minY;
	private static int minZ;

	private DigPreview() {
	}

	static void clear() {
		shown = List.of();
		edges = List.of();
	}

	static void update(Minecraft client) {
		if (client.player == null || client.level == null || !Digger.isActive()) {
			clear();
			return;
		}
		DigSettings settings = DiggerClient.settings();
		if (!settings.area() && !settings.vein()) {
			clear();
			return;
		}
		// サーバーが受け付けない・ブロックを壊せない・HUD を隠しているときは、バニラの枠線と同じように出さない
		if (!ClientPlayNetworking.canSend(SettingsPayload.TYPE)
				|| !client.player.mayBuild()
				|| client.player.isSpectator()
				|| client.gui.hud.isHidden()) {
			clear();
			return;
		}
		if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			clear();
			return;
		}

		BlockPos target = hit.getBlockPos();
		var targetState = client.level.getBlockState(target);
		if (targetState.isAir()) {
			clear();
			return;
		}

		// スニーク中は範囲採掘の枠を出さない。V を押している間は、奥行きを変えながら確かめられるように出す
		DigSettings planSettings = DiggerClient.areaKey.isDown()
				? settings
				: settings.whenSneaking(client.player.isShiftKeyDown());
		List<BlockPos> plan = DigPlan.compute(
				client.player, client.level, target, targetState, hit.getDirection(), planSettings);
		if (plan.isEmpty()) {
			// 狙った 1 つだけならバニラの枠線に任せる
			clear();
			return;
		}

		List<BlockPos> positions = new ArrayList<>(plan);
		positions.add(target);
		if (positions.equals(shown)) {
			return;
		}
		shown = List.copyOf(positions);
		rebuild(positions);
	}

	private static void rebuild(List<BlockPos> positions) {
		int lowX = Integer.MAX_VALUE;
		int lowY = Integer.MAX_VALUE;
		int lowZ = Integer.MAX_VALUE;
		int highX = Integer.MIN_VALUE;
		int highY = Integer.MIN_VALUE;
		int highZ = Integer.MIN_VALUE;
		for (BlockPos pos : positions) {
			lowX = Math.min(lowX, pos.getX());
			lowY = Math.min(lowY, pos.getY());
			lowZ = Math.min(lowZ, pos.getZ());
			highX = Math.max(highX, pos.getX());
			highY = Math.max(highY, pos.getY());
			highZ = Math.max(highZ, pos.getZ());
		}
		minX = lowX;
		minY = lowY;
		minZ = lowZ;

		int sizeX = highX - lowX + 1;
		int sizeY = highY - lowY + 1;
		int sizeZ = highZ - lowZ + 1;
		BitSetDiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(sizeX, sizeY, sizeZ);
		for (BlockPos pos : positions) {
			shape.fill(pos.getX() - lowX, pos.getY() - lowY, pos.getZ() - lowZ);
		}

		List<Edge> built = new ArrayList<>();
		shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> built.add(new Edge(x1, y1, z1, x2, y2, z2)), true);
		edges = List.copyOf(built);
	}

	static void submit(LevelRenderContext context) {
		if (edges.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (!Digger.isActive() || client.gui.hud.isHidden() || !ClientPlayNetworking.canSend(SettingsPayload.TYPE)) {
			return;
		}

		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		var camera = context.levelState().cameraRenderState.pos;
		poseStack.translate(minX - camera.x, minY - camera.y, minZ - camera.z);

		RenderType renderType = context.gameRenderer().useImprovedTransparency()
				? RenderTypes.linesTranslucentNoDepthWrite()
				: RenderTypes.linesTranslucent();
		float width = context.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		context.submitNodeCollector().submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
			Vector3f normal = new Vector3f();
			for (Edge edge : edges) {
				normal.set(edge.x2 - edge.x1, edge.y2 - edge.y1, edge.z2 - edge.z1).normalize();
				buffer.addVertex(pose, edge.x1, edge.y1, edge.z1)
						.setColor(COLOR)
						.setNormal(pose, normal)
						.setLineWidth(width);
				buffer.addVertex(pose, edge.x2, edge.y2, edge.z2)
						.setColor(COLOR)
						.setNormal(pose, normal)
						.setLineWidth(width);
			}
		});
		poseStack.popPose();
	}

	private record Edge(float x1, float y1, float z1, float x2, float y2, float z2) {
	}
}
