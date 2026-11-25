package com.omuraisu.placer.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
	// 置いたブロックに続けて置くには、そのマスの基準から水平にこれだけ動いていること
	private static final double MIN_STEP = 0.5;

	// この長押しで置いた（試した）マス
	private static final Set<BlockPos> tried = new HashSet<>();
	// この長押しで置けたマスと、そのときの基準の位置。続きはマスごとの基準からの動きで見る
	private static final Map<BlockPos, Vec3> bases = new HashMap<>();
	// 最初に置いた面の向き。まだ置いていなければ null で、バニラのまま動かす
	private static Direction face;
	// 置いてよい層の、face の軸の座標。最初に置いたマスで決め、長押しの間は動かさない
	private static int layer;
	// 最初に置いた面が本物の壁の側面か。長押しの間は動かさない
	private static boolean wall;
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
		bases.clear();
		face = null;
		wall = false;
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
		// 足元のマスは乗るために置くものなので、層にも当たったブロックにもよらず置く（橋渡し・塔）
		if (underFeet(player, target)) {
			return true;
		}
		// 後ろや横へ歩きながら列を伸ばすとき。歩いた向きにしか増えないので、層にも当たったブロックにもよらず置く
		if (continuation(player, target) != null) {
			return true;
		}
		// 自分のブロックに視線が当たっている間は置かない。上や手前へ増えないように
		return !tried.contains(hit.getBlockPos()) && target.get(face.getAxis()) == layer;
	}

	// target が、置いたマスの歩いた向きの隣なら、その向き。違えば null
	private static Direction continuation(LocalPlayer player, BlockPos target) {
		Direction walked = null;
		double moved = MIN_STEP;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			Vec3 base = bases.get(target.relative(d.getOpposite()));
			// 壁に張りながら下がっても、足より高い段では手前へ増やさない
			if (base == null || wall && d.getAxis() == face.getAxis() && target.getY() > player.getBlockY()) {
				continue;
			}
			double distance = player.position().subtract(base).dot(d.getUnitVec3());
			// 複数の向きが続きになるときは、いちばん動いた向きを続きとみなす
			if (distance >= moved) {
				walked = d;
				moved = distance;
			}
		}
		return walked;
	}

	// 足の下 1 ブロック以内で、体と水平に重なるマス。体と重なるマスは getPlacementState で落ちている
	private static boolean underFeet(LocalPlayer player, BlockPos target) {
		AABB box = player.getBoundingBox();
		return new AABB(box.minX, box.minY - 1, box.minZ, box.maxX, box.minY, box.maxZ).intersects(new AABB(target));
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
		// 壁かどうかは置く前の状態で見る
		boolean firstWall = face == null && isWall(player, hit, side);

		InteractionResult result = use.get();

		if (player.level().getBlockState(target) != before) {
			if (face == null) {
				face = side;
				layer = target.get(face.getAxis());
				wall = firstWall;
			}
			remember(player, target);
		}
		if (face != null) {
			tried.add(target);
		}
		return result;
	}

	// 置いたマスの基準。続きなら、歩いた距離を四捨五入した数より多く増えないように先へずらす
	private static void remember(LocalPlayer player, BlockPos target) {
		Vec3 base = player.position();
		Direction walked = continuation(player, target);
		if (walked != null) {
			double moved = base.subtract(bases.get(target.relative(walked.getOpposite()))).dot(walked.getUnitVec3());
			base = base.add(walked.getUnitVec3().scale(Math.max(0, 1 - moved)));
		}
		bases.put(target, base);
	}

	// 最初に置いた面が本物の壁の側面か。草などの置き換えで side を UP にしたときは壁ではない
	private static boolean isWall(LocalPlayer player, BlockHitResult hit, Direction side) {
		if (side.getAxis().isVertical()) {
			return false;
		}
		BlockPos pos = hit.getBlockPos();
		return solid(player, pos.relative(side.getClockWise()))
				|| solid(player, pos.relative(side.getCounterClockWise()));
	}

	private static boolean solid(LocalPlayer player, BlockPos pos) {
		return !player.level().getBlockState(pos).getCollisionShape(player.level(), pos).isEmpty();
	}
}
