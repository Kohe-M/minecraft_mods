package com.omuraisu.digger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// 範囲採掘と鉱石一括で壊す位置を決める。壊す道具は findTool で、壊す直前に 1 つずつ選ぶ。
// サーバーは壊した後、クライアントは枠線のために毎 tick 呼ぶので、ワールドを書き換えず同じ計算をする。
// 狙ったブロックは呼んだ側がもう壊している（クライアントではまだ残っている）ため、結果には含めない。
// 並びは鉱石、それ以外の順に、それぞれ狙ったブロックに近い順
public final class DigPlan {
	private static final int MAX_VEIN = 256;
	// 石を掘るだけで黒曜石や古代の残骸が取れないようにする上限。狙ったブロックがもっと硬ければそちらに合わせる
	private static final float BASE_HARDNESS_LIMIT = 5.0F;

	private DigPlan() {
	}

	public static List<BlockPos> compute(
			Player player, Level level, BlockPos target, BlockState targetState, Direction face, DigSettings settings) {
		if (!settings.area() && !settings.vein()) {
			return List.of();
		}

		ItemStack hand = player.getMainHandItem();
		boolean handheldTool = hand.is(ItemTags.PICKAXES) || hand.is(ItemTags.SHOVELS);
		float targetHardness = targetState.getDestroySpeed(level, target);

		// 鉱石一括は、手に持った道具で掘れる鉱石を狙ったときだけ始まる。
		// 木のつるはしでダイヤモンド鉱石を狙っても、取り直せないものを巻き込まない
		boolean veinStarted = settings.vein() && isOre(targetState) && canMine(hand, targetState);

		Set<BlockPos> positions = new LinkedHashSet<>();

		// 鉱石一括が始まるときは、範囲採掘もオンでも鉱脈だけを掘る。
		// つながった鉱石がなく 1 つだけのときも、まわりは掘らない
		boolean areaStarted = settings.area()
				&& !veinStarted
				&& handheldTool
				&& isAreaBreakable(player, level, target, targetState, targetHardness);
		if (areaStarted) {
			// 奥行きの層ごとに、壊すブロックが 1 つもない層で止める。
			// 空洞や部屋の向こうの、見えていない壁まで壊さないため
			for (List<BlockPos> layer : areaLayers(player, target, face, settings)) {
				boolean any = false;
				for (BlockPos pos : layer) {
					if (pos.equals(target)) {
						// 狙ったブロックはもう壊れている。この層にあったものとして扱う
						any = true;
						continue;
					}
					BlockState state = level.getBlockState(pos);
					if (isAreaBreakable(player, level, pos, state, targetHardness)) {
						positions.add(pos);
						any = true;
					}
				}
				if (!any) {
					break;
				}
			}
		}

		if (settings.vein()) {
			// 範囲に入った鉱石も、範囲の外へ続く鉱脈もまとめて掘る。1 回の採掘で掘る鉱石は、
			// 狙ったブロックと範囲に入った鉱石を含めて近い順に MAX_VEIN 個まで
			boolean targetIsOre = isOre(targetState);
			// 狙ったブロックが鉱石で、鉱脈を始めない（道具が合わない）ときも、その 1 個は合計に数える
			int reserved = targetIsOre && !veinStarted ? 1 : 0;
			List<BlockPos> initialOres = new ArrayList<>();
			if (veinStarted) {
				initialOres.add(target);
			}
			for (BlockPos pos : positions) {
				if (isOre(level.getBlockState(pos))) {
					initialOres.add(pos);
				}
			}
			initialOres = initialOres.stream().distinct().sorted(nearest(target)).toList();
			int maxInitial = MAX_VEIN - reserved;
			if (initialOres.size() > maxInitial) {
				Set<BlockPos> kept = new HashSet<>(initialOres.subList(0, maxInitial));
				positions.removeIf(pos -> isOre(level.getBlockState(pos)) && !kept.contains(pos));
				initialOres = List.copyOf(initialOres.subList(0, maxInitial));
			}

			// 見つけ終わった鉱石は出発点にしない。訪問済みは種類を問わず共有し、
			// 最初から計画に入っている鉱石（範囲の鉱石と狙ったブロック）は最初から訪問済みにする
			Set<BlockPos> visited = new HashSet<>(initialOres);
			if (targetIsOre) {
				visited.add(target);
			}
			Set<BlockPos> veinOres = new LinkedHashSet<>();
			for (BlockPos pos : initialOres) {
				if (!pos.equals(target)) {
					veinOres.add(pos);
				}
			}
			int maxVeinOres = MAX_VEIN - (targetIsOre ? 1 : 0);
			if (veinOres.size() < maxVeinOres) {
				for (BlockPos seed : initialOres) {
					if (veinOres.size() >= maxVeinOres) {
						break;
					}
					BlockState seedState = seed.equals(target) ? targetState : level.getBlockState(seed);
					collectVein(player, level, seed, seedState, visited, veinOres, maxVeinOres);
				}
			}
			positions.addAll(veinOres);
		}

		// 範囲採掘で鉱脈も巻き込むとき、道具の耐久が途中で足りなくなっても鉱石は掘れるよう、鉱石を先にする
		List<BlockPos> ores = new ArrayList<>();
		List<BlockPos> others = new ArrayList<>();
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (!pos.equals(target) && !state.isAir() && findTool(player, state) >= 0) {
				(isOre(state) ? ores : others).add(pos.immutable());
			}
		}
		ores.sort(nearest(target));
		others.sort(nearest(target));
		ores.addAll(others);
		return ores;
	}

	public static boolean isOre(BlockState state) {
		return state.is(ConventionalBlockTags.ORES);
	}

	// 掘れるとは、道具として速くなる（getDestroySpeed > 1）ことで、正しい道具が要るブロックなら
	// その道具でなければならない。素手や剣で石を掘るようには扱わない
	public static boolean canMine(ItemStack stack, BlockState state) {
		if (stack.isEmpty() || stack.getDestroySpeed(state) <= 1.0F) {
			return false;
		}
		return !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
	}

	// 残りが 2 以下の道具は使わない。狙ったブロックの分はイベントの後に減るので、その 1 と最後の 1 を残す。
	// クリエイティブでは減らないので見ない
	public static boolean hasDurability(Player player, ItemStack stack) {
		return player.getAbilities().instabuild
				|| !stack.isDamageableItem()
				|| stack.getMaxDamage() - stack.getDamageValue() > 2;
	}

	// 手に持った道具で掘れるならそのスロット。掘れないならホットバーの左から、掘れるつるはし・シャベルを探す。
	// 手に持った道具で掘れるのに耐久が足りないときは、別の道具に替えずに残す（-1）。
	// 使えるシャベルがなければ、土や砂利は手に持ったつるはしで壊す。雪のように正しい道具が要るものは何も落ちないので残す
	public static int findTool(Player player, BlockState state) {
		Inventory inventory = player.getInventory();
		ItemStack hand = player.getMainHandItem();
		if (canMine(hand, state)) {
			return hasDurability(player, hand) ? inventory.getSelectedSlot() : -1;
		}
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if ((stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS))
					&& canMine(stack, state)
					&& hasDurability(player, stack)) {
				return slot;
			}
		}
		if (hand.is(ItemTags.PICKAXES)
				&& state.is(BlockTags.MINEABLE_WITH_SHOVEL)
				&& !state.requiresCorrectToolForDrops()
				&& hasDurability(player, hand)) {
			return inventory.getSelectedSlot();
		}
		return -1;
	}

	private static boolean isAreaBreakable(
			Player player, Level level, BlockPos pos, BlockState state, float targetHardness) {
		if (state.isAir() || state.hasBlockEntity()) {
			return false;
		}
		// レール・ランタン・ドア・階段・ハーフなどを残す。ソウルサンドと泥は当たり判定が低いが見た目は 1 マス。
		// 積もった雪は見た目が 1 マスでないが、雪原の整地で残ると困るので例外的に壊す
		if (!state.is(BlockTags.SNOW) && !Block.isShapeFullBlock(state.getShape(level, pos))) {
			return false;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F || hardness > Math.max(BASE_HARDNESS_LIMIT, targetHardness)) {
			return false;
		}
		if (state.is(Blocks.BUDDING_AMETHYST)) {
			return false;
		}
		// 溶岩が流れ出さないよう、溶岩に接するブロックは壁として残す
		if (touchingLava(level, pos)) {
			return false;
		}
		if (!isAllowed(player, level, pos)) {
			return false;
		}
		return findTool(player, state) >= 0;
	}

	// ServerPlayerGameMode.destroyBlock はスポーン保護とワールドボーダーを確かめないので自分で見る。
	// ワールドボーダーはクライアントも同じ値を持っているので枠線にも効くが、スポーン保護はサーバーだけが知る
	private static boolean isAllowed(Player player, Level level, BlockPos pos) {
		return level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(player, pos);
	}

	private static boolean touchingLava(Level level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (level.getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	private static void collectVein(
			Player player,
			Level level,
			BlockPos origin,
			BlockState originState,
			Set<BlockPos> visited,
			Set<BlockPos> found,
			int maxOres) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin);

		while (!queue.isEmpty() && found.size() < maxOres) {
			BlockPos current = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						BlockPos next = current.offset(dx, dy, dz);
						BlockState state = level.getBlockState(next);
						if (!isOre(state) || !sameOre(originState, state)) {
							continue;
						}
						// ほかの出発点から見つけた鉱石は、もう探さない
						if (!visited.add(next)) {
							continue;
						}
						// 溶岩に接する鉱石や掘れない鉱石は残すが、その向こうのつながりは探し続ける
						if (!touchingLava(level, next) && isAllowed(player, level, next) && findTool(player, state) >= 0) {
							found.add(next.immutable());
							if (found.size() >= maxOres) {
								return;
							}
						}
						queue.add(next);
					}
				}
			}
		}
	}

	// 同じ種類は、同じブロックか、c:ores/ で始まる同じタグを持つもの。石の鉄鉱石と深層岩の鉄鉱石はひとつながりになる。
	// c:ores 自体（すべての鉱石が持つ）は種類とみなさない
	private static boolean sameOre(BlockState first, BlockState second) {
		if (first.getBlock() == second.getBlock()) {
			return true;
		}
		Set<Identifier> tags = oreTags(first);
		return !tags.isEmpty() && oreTags(second).stream().anyMatch(tags::contains);
	}

	private static Set<Identifier> oreTags(BlockState state) {
		Set<Identifier> tags = new HashSet<>();
		state.tags()
				.map(TagKey::location)
				.filter(id -> id.getNamespace().equals("c") && id.getPath().startsWith("ores/"))
				.forEach(tags::add);
		return tags;
	}

	// 範囲採掘の箱を、面の手前から奥へ 1 層ずつ返す
	private static List<List<BlockPos>> areaLayers(
			Player player, BlockPos target, Direction face, DigSettings settings) {
		int width = settings.width();
		int height = settings.height();
		int depth = settings.depth();
		int tx = target.getX();
		int ty = target.getY();
		int tz = target.getZ();
		int minX;
		int maxX;
		int minY;
		int maxY;
		int minZ;
		int maxZ;

		if (face.getAxis().isHorizontal()) {
			int halfWidth = (width - 1) / 2;
			// 横の面では幅を左右（面に沿う水平の軸）へ、高さを足元の段から上へ取る
			// 奥行きは面の奥（面の法線と反対）へ
			if (face.getAxis() == Direction.Axis.Z) {
				minX = tx - halfWidth;
				maxX = minX + width - 1;
				int end = tz - face.getStepZ() * (depth - 1);
				minZ = Math.min(tz, end);
				maxZ = Math.max(tz, end);
			} else {
				minZ = tz - halfWidth;
				maxZ = minZ + width - 1;
				int end = tx - face.getStepX() * (depth - 1);
				minX = Math.min(tx, end);
				maxX = Math.max(tx, end);
			}
			int feet = Mth.floor(player.getY() + 0.2);
			int bottom = Mth.clamp(feet, ty - (height - 1), ty);
			minY = bottom;
			maxY = bottom + height - 1;
		} else {
			// 上と下の面では、狙った段を幅 × 高さの水平な 1 枚で取る。
			// 幅はプレイヤーの左右、高さは向いている水平の方角。1×2 は狙ったブロックとその 1 つ向こう
			Direction forward = player.getDirection();
			boolean alongX = forward.getAxis() == Direction.Axis.X;
			int sign = alongX ? forward.getStepX() : forward.getStepZ();
			int halfHeight = (height - 1) / 2;
			int heightCenter = alongX ? tx : tz;
			int startHeight = heightCenter - sign * halfHeight;
			int endHeight = startHeight + sign * (height - 1);
			int halfWidth = (width - 1) / 2;
			int widthCenter = alongX ? tz : tx;
			int startWidth = widthCenter - halfWidth;
			int endWidth = startWidth + width - 1;
			if (alongX) {
				minX = Math.min(startHeight, endHeight);
				maxX = Math.max(startHeight, endHeight);
				minZ = startWidth;
				maxZ = endWidth;
			} else {
				minZ = Math.min(startHeight, endHeight);
				maxZ = Math.max(startHeight, endHeight);
				minX = startWidth;
				maxX = endWidth;
			}

			int endY = ty - face.getStepY() * (depth - 1);
			minY = Math.min(ty, endY);
			maxY = Math.max(ty, endY);
		}

		// 奥行きの軸に沿って、狙ったブロックの層を 0 として手前から並べる
		Direction.Axis depthAxis = face.getAxis();
		int depthStart = target.get(depthAxis);
		int step = switch (depthAxis) {
			case X -> face.getStepX();
			case Y -> face.getStepY();
			case Z -> face.getStepZ();
		};
		TreeMap<Integer, List<BlockPos>> layers = new TreeMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
			int distance = (depthStart - pos.get(depthAxis)) * step;
			layers.computeIfAbsent(distance, key -> new ArrayList<>()).add(pos.immutable());
		}
		return new ArrayList<>(layers.values());
	}

	private static Comparator<BlockPos> nearest(BlockPos target) {
		return Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(target))
				.thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getZ)
				.thenComparingInt(BlockPos::getX);
	}
}
