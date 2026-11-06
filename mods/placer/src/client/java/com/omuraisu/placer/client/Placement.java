package com.omuraisu.placer.client;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.omuraisu.placer.client.mixin.BlockItemInvoker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ScaffoldingBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// 1回の長押しの間の連続設置。押した瞬間の設置には手を出さない
public final class Placement {
	// 押してからこの間は待つ。軽く押しただけで2個置かれないように、バニラの最初の連続設置に合わせる
	private static final int FIRST_REPEAT_TICKS = 4;
	// 自分が置いたブロックの面に続けて置くには、その面の向きへこれだけ動いていること（橋渡し・ジャンプでの塔）
	private static final double MIN_RETREAT = 0.5;
	// そのときの置き先は、プレイヤーからこの距離以内
	private static final double RETREAT_REACH = 1;

	// この長押しで置いた（試した）マス
	private static final Set<BlockPos> tried = new HashSet<>();
	// 最初に置いた面の向き。まだ置いていなければ null で、バニラのまま動かす
	private static Direction face;
	// 置いてよい層の、face の軸の座標
	private static int layer;
	private static Vec3 lastPlacedFrom;
	private static int heldTicks;

	private Placement() {
	}

	public static void press() {
		reset();
	}

	public static void tick(Minecraft client) {
		if (client.options.keyUse.isDown()) {
			heldTicks++;
		} else {
			reset();
		}
	}

	private static void reset() {
		tried.clear();
		face = null;
		heldTicks = 0;
	}

	// 4 tick を待たず、新しいマスに来たらすぐ置く
	public static boolean skipsDelay(Minecraft client) {
		if (heldTicks < FIRST_REPEAT_TICKS || !client.options.keyUse.isDown()) {
			return false;
		}
		BlockPlaceContext context = repeatContext(client);
		return context != null && accepts(client.player, context, (BlockHitResult) client.hitResult);
	}

	public static boolean allowsRepeat(Minecraft client) {
		BlockPlaceContext context = repeatContext(client);
		return context == null || accepts(client.player, context, (BlockHitResult) client.hitResult);
	}

	// 補正の対象でなければ null
	private static BlockPlaceContext repeatContext(Minecraft client) {
		if (!PlacerClient.enabled() || face == null || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			BlockPlaceContext context = context(client.player, hand, hit);
			if (context != null) {
				return context;
			}
		}
		return null;
	}

	// 足場は上へ伸ばして使うものなので対象にしない
	private static BlockPlaceContext context(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (stack.getItem() instanceof BlockItem && !(stack.getItem() instanceof ScaffoldingBlockItem)) {
			return new BlockPlaceContext(player, hand, stack, hit);
		}
		return null;
	}

	private static boolean accepts(LocalPlayer player, BlockPlaceContext context, BlockHitResult hit) {
		BlockPos target = context.getClickedPos();
		if (tried.contains(target) || !context.canPlace()) {
			return false;
		}
		// 置けないマスは試さずに次の tick でまた見る（塔はジャンプで体が抜けるのを待つ）
		if (((BlockItemInvoker) context.getItemInHand().getItem()).placer$getPlacementState(context) == null) {
			return false;
		}
		int offset = (target.get(face.getAxis()) - layer) * face.getAxisDirection().getStep();
		if (!tried.contains(hit.getBlockPos())) {
			return offset == 0;
		}
		return (offset == 0 || offset == 1) && retreated(player, hit.getDirection(), target);
	}

	private static boolean retreated(LocalPlayer player, Direction side, BlockPos target) {
		double moved = player.position().subtract(lastPlacedFrom).dot(side.getUnitVec3());
		return moved >= MIN_RETREAT && player.getBoundingBox().inflate(RETREAT_REACH).intersects(new AABB(target));
	}

	public static InteractionResult recordUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, Supplier<InteractionResult> use) {
		BlockPlaceContext context = PlacerClient.enabled() ? context(player, hand, hit) : null;
		if (context == null) {
			return use.get();
		}

		BlockPos target = context.getClickedPos();
		BlockState before = player.level().getBlockState(target);
		// 草などを置き換えたときの当たった面は向きに意味がない。たいてい地面の上にある
		Direction side = context.replacingClickedOnBlock() ? Direction.UP : hit.getDirection();

		InteractionResult result = use.get();

		if (player.level().getBlockState(target) != before) {
			if (face == null) {
				face = side;
			}
			layer = target.get(face.getAxis());
			lastPlacedFrom = player.position();
		}
		if (face != null) {
			tried.add(target);
		}
		return result;
	}
}
