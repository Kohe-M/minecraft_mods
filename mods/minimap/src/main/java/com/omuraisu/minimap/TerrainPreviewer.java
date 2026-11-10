package com.omuraisu.minimap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.omuraisu.minimap.mixin.MaterialRuleContextInvoker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.FindTopSurfaceFunction;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

// まだ生成していない地形の下書き（TerrainPreview）を、チャンクを生成せずにワールドのシードから作る。どのスレッドから同時に呼んでもよい。
// 生成（1 秒に数百チャンク）を待たずに地図を埋めるためのもので、リージョン 1 つ（1024 チャンク）が 20 ms ほどで出る。
// 4 ブロックに 1 列だけ、次のように見積もる:
// - 高さ: 地表で 0 になる「深さ」の関数から（地形のうねりを決める 2 次元のノイズだけで決まる）。
//   16 ブロックおきの列だけ本物の地形の密度から地表を探し、その差で直す。洞窟や細かい凹凸は入らない
// - バイオーム: 地表の気候（深さ 0）から
// - 地表のブロック: ワールドの地表の規則（material rule）をその 1 ブロックにだけ当てる。草・砂・雪・テラコッタの縞などはこれで決まる
// - 規則では決まらない地物は、バイオームから足す: 雪が積もる寒さなら雪、凍る寒さの水面は氷、木の多いバイオームは葉の色
// 本物とは細部が違うので、生成し終えたチャンクは本物の色（TerrainTile）で上書きされる
final class TerrainPreviewer {
	private static final int SIZE = TerrainPreview.SIZE;
	private static final int STEP = TerrainPreview.STEP;
	// 北の端の行の起伏を決めるため、ひとつ北の行の高さも見積もる
	private static final int ROWS = SIZE + 1;
	// 木 1 本が覆うおおよその広さ（ブロック）。チャンクあたりの本数から、葉が地面を覆う割合を出す
	private static final double CANOPY_BLOCKS = 32;
	private static final int COUNT_SAMPLES = 32;
	// 本物の地形から地表を探す列の間隔（ブロック）と、リージョンの 1 辺の列の数（両端を含む）
	private static final int EXACT_STEP = 16;
	private static final int EXACT_SIZE = TerrainRegions.BLOCKS / EXACT_STEP + 1;
	// 1 回に密度を出す高さの幅（ブロック）。見積もりの少し下から上を見る
	private static final int EXACT_WINDOW = 64;
	private static final int EXACT_BELOW = 16;

	private final NoiseBasedChunkGenerator generator;
	private final NoiseGeneratorSettings settings;
	private final RandomState randomState;
	private final LevelHeightAccessor heights;
	// 帯水層が使う「おおよその地表の高さ」。深さから高さを出せないワールドで使う
	private final DensityFunction surfaceFunction;
	// それが「上から 8 ブロックおきに探して、初めて地面になる高さ」なら、その中の密度の関数。1 ブロック刻みに詰め直すのに使う
	private final FindTopSurfaceFunction topSurface;
	// 深さを比べる 2 つの高さ
	private final int lowY;
	private final int highY;
	private final BiomeSource biomeSource;
	private final int seaLevel;
	private final MapColor fluidColor;
	private final boolean water;
	private final Map<Holder<Biome>, Canopy> canopies = new ConcurrentHashMap<>();

	private TerrainPreviewer(ServerLevel level, NoiseBasedChunkGenerator generator, DensityFunction surfaceFunction) {
		this.generator = generator;
		this.settings = generator.generatorSettings().value();
		this.randomState = level.getChunkSource().randomState();
		this.heights = LevelHeightAccessor.create(level.getMinY(), level.getHeight());
		this.surfaceFunction = surfaceFunction;
		DensityFunction unwrapped = surfaceFunction;
		while (unwrapped instanceof DensityFunctions.HolderHolder holder) {
			unwrapped = holder.function().value();
		}
		this.topSurface = unwrapped instanceof FindTopSurfaceFunction top ? top : null;
		this.lowY = level.getMinY() + level.getHeight() / 4;
		this.highY = level.getMinY() + level.getHeight() / 2;
		this.biomeSource = generator.getBiomeSource();
		this.seaLevel = generator.getSeaLevel();
		BlockState fluid = settings.defaultFluid();
		this.fluidColor = fluid.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		this.water = fluid.getFluidState().is(Fluids.WATER);
	}

	// 下書きを作れないワールド（平らなワールド、ネザーやエンドのように地表の高さの関数がない次元）なら null
	static TerrainPreviewer create(ServerLevel level) {
		if (level.dimensionType().hasCeiling() || !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
			return null;
		}
		return generator.generatorSettings().value().aquifers()
				.map(aquifers -> new TerrainPreviewer(level, generator, aquifers.surfaceLevel()))
				.orElse(null);
	}

	// リージョン 1 つ分の色（TerrainPreview.colors）
	byte[] region(int regionX, int regionZ) {
		int minX = regionX * TerrainRegions.BLOCKS;
		int minZ = regionZ * TerrainRegions.BLOCKS;
		DensityVolume volume = new DensityVolume(SIZE, 1, ROWS, minX, lowY, minZ - STEP, STEP, 1, STEP);
		// 同じ範囲で何度も使う 2 次元のノイズ（大陸・浸食など）を、高さと気候で使い回す
		SamplerContext context = SamplerContext.builder().enableCaches().build();
		DensitySamplerSet samplers = randomState.samplersWithContext(context);
		int[] height = heights(samplers, volume, minX, minZ);
		correct(height, minX, minZ);
		Holder<Biome>[] biomes = biomes(context, volume, height, minX, minZ);

		int[] current = new int[1];
		DensityFunction preliminary = randomState.surfaceSystem().preliminarySurfaceFunction();
		// 規則が「おおよその地表より上か」を確かめるときの高さを、1 列ずつ計算し直さずに見積もった高さから答える
		DensitySamplerSet ruleSamplers = new DensitySamplerSet() {
			@Override
			public DensitySampler.Bound get(DensityFunction function) {
				return samplers.get(function);
			}

			@Override
			public float sampleValue(DensityFunction function, int blockX, int blockY, int blockZ) {
				return function == preliminary ? height[current[0]] : samplers.sampleValue(function, blockX, blockY, blockZ);
			}
		};
		// 規則がまとめて計算する範囲は、見積もる列に重ならない場所にして使わせない
		MaterialRuleContext ruleContext = MaterialRuleContextInvoker.minimap$create(randomState.surfaceSystem(), randomState,
				new DensityVolume(1, 1, 1, minX - 16, 0, minZ - 16), ruleSamplers, pos -> biomes[current[0]],
				new WorldGenerationContext(generator, heights), null);
		MaterialRuleContextInvoker invoker = (MaterialRuleContextInvoker) (Object) ruleContext;
		RuleEvaluator rule = settings.materialRule().value().compile(ruleContext);

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		byte[] colors = new byte[SIZE * SIZE];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				int i = x + (z + 1) * SIZE;
				current[0] = i;
				int blockX = minX + x * STEP;
				int blockZ = minZ + z * STEP;
				int y = height[i];
				Biome biome = biomes[i].value();
				int random = random(blockX, blockZ);
				if (y < seaLevel - 1) {
					colors[x + z * SIZE] = fluid(biome, pos.set(blockX, seaLevel - 1, blockZ), seaLevel - 1 - y);
					continue;
				}
				int gradientX = (height[x < SIZE - 1 ? i + 1 : i] - height[x > 0 ? i - 1 : i]) / STEP;
				int gradientZ = (height[z < SIZE - 1 ? i + SIZE : i] - height[i - SIZE]) / STEP;
				invoker.minimap$updateXZ(blockX, blockZ, gradientX, gradientZ);
				invoker.minimap$updateY(1, 64, Integer.MIN_VALUE, y);
				BlockState state = rule.tryApply(blockX, y, blockZ);
				if (state == null) {
					state = settings.defaultBlock();
				}
				MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				if (color == MapColor.NONE) {
					color = settings.defaultBlock().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				}
				MapColor.Brightness brightness;
				if (!state.getFluidState().isEmpty()) {
					// 沼の水たまりなど、規則が置く浅い水
					brightness = MapColor.Brightness.HIGH;
				} else {
					// 本物は 1 ブロック北と比べる。4 ブロック北との差から、その割合で段差があるものとして散らす
					int step = y - height[i - SIZE];
					int threshold = (random & 3) + 1;
					brightness = step >= threshold ? MapColor.Brightness.HIGH : -step >= threshold ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
					if (biome.hasPrecipitation() && biome.coldEnoughToSnow(pos.set(blockX, y + 1, blockZ), seaLevel)) {
						color = MapColor.SNOW;
					} else if (state.is(BlockTags.SUBSTRATE_OVERWORLD)) {
						Canopy canopy = canopies.computeIfAbsent(biomes[i], TerrainPreviewer::canopy);
						if ((random >>> 8 & 0xFF) < canopy.coverage) {
							color = canopy.colors[(random >>> 16 & 0xFF) % canopy.colors.length];
							// 木の高さはまちまちなので、明るさも散らす
							brightness = MapColor.Brightness.byId((random >>> 24 & 0x7F) % 3);
						}
					}
				}
				colors[x + z * SIZE] = color.getPackedId(brightness);
			}
		}
		return colors;
	}

	// 4 ブロックおきの列の、一番上の地面のブロックの高さ。北から 1 行ずつ、ROWS 行。
	// 地形の密度は「深さ × 傾き + 3 次元のノイズ」で、深さは高さに比例して減り、地表で 0 になる。ノイズを 0 と見れば、深さが 0 になる高さが地表。
	// 帯水層の「おおよその地表の高さ」は、水が地表に漏れないよう 10 ブロックほど低く見積もってあり、浅い陸が海になるので使わない
	private int[] heights(DensitySamplerSet samplers, DensityVolume volume, int minX, int minZ) {
		DensityFunction depth = settings.noiseRouter().depth();
		float slope = slope(samplers, minX, minZ);
		if (!(slope < -1e-6f)) {
			return surfaceHeights(samplers, volume, minX, minZ);
		}
		DensityBuffer depths = sample(samplers.get(depth), volume);
		int[] height = new int[volume.size()];
		for (int i = 0; i < height.length; i++) {
			height[i] = fromDepth(depths.get(i), slope);
		}
		return height;
	}

	// 深さが高さ 1 ブロックでどれだけ変わるか。深さが高さで変わらないワールドでは 0
	private float slope(DensitySamplerSet samplers, int x, int z) {
		DensityFunction depth = settings.noiseRouter().depth();
		return (samplers.sampleValue(depth, x, highY, z) - samplers.sampleValue(depth, x, lowY, z)) / (highY - lowY);
	}

	// lowY での深さから、深さが 0 になる高さのすぐ下のブロック
	private int fromDepth(float depth, float slope) {
		return Math.clamp((long) Math.ceil(lowY - depth / slope) - 1, heights.getMinY(), heights.getMaxY());
	}

	// 見積もった高さを、本物の地形に寄せる。地形の密度に足される 3 次元のノイズは、地面を削るより盛る方に強く効くので、
	// 深さだけの見積もりは低めに出て、川や海が広くなる。16 ブロックおきの列だけ本物の密度から地表を探し、
	// 見積もりとの差をその間でなめらかにつないで、すべての列に足す（密度を全部の列で出すと、リージョン 1 つに 0.5 秒かかる）
	private void correct(int[] height, int minX, int minZ) {
		// リージョンごとの 2 次元のノイズの使い回しを、1 列ずつの計算で捨てさせないよう、別に持つ
		DensitySamplerSet samplers = randomState.samplersWithContext(SamplerContext.builder().enableCaches().build());
		DensitySampler.Bound density = samplers.get(settings.noiseRouter().finalDensity());
		DensitySampler.Bound depth = samplers.get(settings.noiseRouter().depth());
		DensitySampler.Bound surface = samplers.get(surfaceFunction);
		float slope = slope(samplers, minX, minZ);
		float[] difference = new float[EXACT_SIZE * EXACT_SIZE];
		for (int gz = 0; gz < EXACT_SIZE; gz++) {
			for (int gx = 0; gx < EXACT_SIZE; gx++) {
				int x = minX + gx * EXACT_STEP;
				int z = minZ + gz * EXACT_STEP;
				int estimate = slope < -1e-6f ? fromDepth(depth.sampleValue(x, lowY, z), slope) : (int) Math.floor(surface.sampleValue(x, 0, z));
				difference[gx + gz * EXACT_SIZE] = exactHeight(density, x, z, estimate) - estimate;
			}
		}
		int cells = EXACT_STEP / STEP;
		for (int row = 0; row < ROWS; row++) {
			// 一番北の行（リージョンのひとつ北）は、リージョンの北の端の差を使う
			int z = Math.max(0, row - 1);
			int gz = z / cells;
			float fz = (float) (z % cells) / cells;
			for (int x = 0; x < SIZE; x++) {
				int gx = x / cells;
				float fx = (float) (x % cells) / cells;
				float north = difference[gx + gz * EXACT_SIZE] * (1 - fx) + difference[gx + 1 + gz * EXACT_SIZE] * fx;
				float south = difference[gx + (gz + 1) * EXACT_SIZE] * (1 - fx) + difference[gx + 1 + (gz + 1) * EXACT_SIZE] * fx;
				int i = x + row * SIZE;
				height[i] = Math.clamp(height[i] + Math.round(north * (1 - fz) + south * fz), heights.getMinY(), heights.getMaxY());
			}
		}
	}

	// 本物の地形の密度から出した、(x, z) の列の一番上の地面のブロックの高さ。見積もりの近くから探し、なければ上下にずらす
	private int exactHeight(DensitySampler.Bound density, int x, int z, int estimate) {
		int minY = heights.getMinY();
		int maxY = heights.getMaxY();
		int from = Math.clamp(estimate - EXACT_BELOW, minY, Math.max(minY, maxY + 1 - EXACT_WINDOW));
		for (int attempt = 0; attempt < 8; attempt++) {
			int size = Math.min(EXACT_WINDOW, maxY + 1 - from);
			try (ScopedDensityBuffer buffer = density.sampleVolume(new DensityVolume(1, size, 1, x, from, z))) {
				if (buffer.get(size - 1) > 0 && from + size <= maxY) {
					// 幅の一番上まで地面。もっと上にある
					from += size - 1;
					continue;
				}
				for (int y = size - 1; y >= 0; y--) {
					if (buffer.get(y) > 0) {
						return from + y;
					}
				}
			}
			if (from <= minY) {
				return minY - 1;
			}
			from = Math.max(minY, from - EXACT_WINDOW + 1);
		}
		return estimate;
	}

	// 深さが高さで変わらないワールド（データパックで地形の関数を作り替えている、など）
	private int[] surfaceHeights(DensitySamplerSet samplers, DensityVolume volume, int minX, int minZ) {
		DensityBuffer surface = sample(samplers.get(surfaceFunction), volume);
		int[] height = new int[volume.size()];
		DensitySampler.Bound density = topSurface == null ? null : samplers.get(topSurface.density());
		for (int z = 0; z < ROWS; z++) {
			for (int x = 0; x < SIZE; x++) {
				int i = x + z * SIZE;
				int low = (int) Math.floor(surface.get(i));
				if (density != null && low > topSurface.lowerBound()) {
					// low は地面、low + cellHeight は地面でないと分かっているので、その間を詰める
					int high = low + topSurface.cellHeight();
					while (high - low > 1) {
						int middle = (low + high) >>> 1;
						if (density.sampleValue(minX + x * STEP, middle, minZ + (z - 1) * STEP) > 0) {
							low = middle;
						} else {
							high = middle;
						}
					}
				}
				height[i] = low;
			}
		}
		return height;
	}

	// 地表のバイオーム。北から 1 行ずつ、ROWS 行（一番北の行は使わないので空のまま）
	@SuppressWarnings("unchecked")
	private Holder<Biome>[] biomes(SamplerContext context, DensityVolume volume, int[] height, int minX, int minZ) {
		Holder<Biome>[] biomes = new Holder[volume.size()];
		Climate.Sampler climate = randomState.createClimateSampler(context);
		if (biomeSource instanceof MultiNoiseBiomeSource multiNoise) {
			// 深さだけは高さで変わるので、範囲ごとまとめては出せない。地表の深さは 0（洞窟のバイオームは 0.2 から）
			DensityBuffer temperature = sample(climate.temperature(), volume);
			DensityBuffer humidity = sample(climate.humidity(), volume);
			DensityBuffer continentalness = sample(climate.continentalness(), volume);
			DensityBuffer erosion = sample(climate.erosion(), volume);
			DensityBuffer weirdness = sample(climate.weirdness(), volume);
			for (int i = SIZE; i < biomes.length; i++) {
				biomes[i] = multiNoise.getNoiseBiome(Climate.target(temperature.get(i), humidity.get(i), continentalness.get(i), erosion.get(i), 0, weirdness.get(i)));
			}
		} else {
			BiomeResolver resolver = biomeSource.createResolver(climate);
			for (int z = 1; z < ROWS; z++) {
				for (int x = 0; x < SIZE; x++) {
					int i = x + z * SIZE;
					biomes[i] = resolver.getNoiseBiome(QuartPos.fromBlock(minX + x * STEP), QuartPos.fromBlock(height[i]), QuartPos.fromBlock(minZ + (z - 1) * STEP));
				}
			}
		}
		return biomes;
	}

	private static DensityBuffer sample(DensitySampler.Bound sampler, DensityVolume volume) {
		DensityBuffer buffer = DensityBuffer.createUnpooled(volume.size());
		sampler.sampleVolume(buffer, volume);
		return buffer;
	}

	// 海や川。深さで濃さを変え（TerrainColors と同じ区切り）、凍る寒さなら氷にする
	private byte fluid(Biome biome, BlockPos surface, int depth) {
		if (water && !biome.warmEnoughToRain(surface, seaLevel)) {
			return MapColor.ICE.getPackedId(MapColor.Brightness.NORMAL);
		}
		return fluidColor.getPackedId(depth <= 3 ? MapColor.Brightness.HIGH : depth <= 8 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW);
	}

	// バイオームの木が地面を覆う割合と葉の色。バイオームに置く地物のうち木と大きなキノコを数え、
	// チャンクあたりの本数は置き方（何本置くか、何回に 1 回置くか）から見積もる。データパックや mod のバイオームでもそのまま出る
	private static Canopy canopy(Holder<Biome> biome) {
		List<MapColor> colors = new ArrayList<>();
		double trees = 0;
		RandomSource random = RandomSource.create(0);
		try {
			for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
				for (Holder<PlacedFeature> placed : step) {
					List<MapColor> leaves = new ArrayList<>();
					List<MapColor> caps = new ArrayList<>();
					placed.value().getFeatures().forEach(feature -> {
						if (feature.value() instanceof TreeFeature tree) {
							add(leaves, tree.foliageProvider(), random);
						} else if (feature.value() instanceof AbstractHugeMushroomFeature mushroom) {
							add(caps, mushroom.capProvider(), random);
						}
					});
					// 木に混ざってたまに生えるキノコ（暗い森）は数えない。どれが何割かは分からないので、木と同じ割合で塗ってしまう
					List<MapColor> found = leaves.isEmpty() ? caps : leaves;
					if (!found.isEmpty()) {
						trees += count(placed.value().placement(), random);
						colors.addAll(found);
					}
				}
			}
		} catch (RuntimeException e) {
			// 読めた分だけで決める
		}
		if (colors.isEmpty() || trees <= 0) {
			return Canopy.NONE;
		}
		return new Canopy(colors.toArray(MapColor[]::new), (int) Math.round(256 * (1 - Math.exp(-trees * CANOPY_BLOCKS / 256))));
	}

	// ワールドを見て決める色は、ここでは決められないので数えない
	private static void add(List<MapColor> colors, Holder<BlockStateProvider> provider, RandomSource random) {
		try {
			MapColor color = provider.value().getState(null, random, BlockPos.ZERO).getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (color != MapColor.NONE) {
				colors.add(color);
			}
		} catch (RuntimeException e) {
			// 数えない
		}
	}

	// チャンクに置く数の平均。場所で決まる絞り込み（高さ、バイオーム、水の深さなど）は数えない
	private static double count(List<PlacementModifier> placement, RandomSource random) {
		double count = 1;
		for (PlacementModifier modifier : placement) {
			if (modifier instanceof RepeatingPlacement repeating) {
				int total = 0;
				for (int i = 0; i < COUNT_SAMPLES; i++) {
					total += repeating.count(random, new BlockPos(i * 977, 64, i * 1311));
				}
				count *= (double) total / COUNT_SAMPLES;
			} else if (modifier instanceof RarityFilter rarity) {
				count /= Math.max(1, rarity.chance());
			}
		}
		return count;
	}

	// 列ごとに決まった乱数（同じリージョンは何度作っても同じ色になる）
	private static int random(int blockX, int blockZ) {
		long hash = blockX * 0x9E3779B97F4A7C15L ^ blockZ * 0xC2B2AE3D27D4EB4FL;
		hash ^= hash >>> 29;
		hash *= 0xBF58476D1CE4E5B9L;
		return (int) (hash >>> 32) & Integer.MAX_VALUE;
	}

	// 葉が地面を覆う割合（256 分のいくつ）と、木の種類ごとの葉の色
	private record Canopy(MapColor[] colors, int coverage) {
		private static final Canopy NONE = new Canopy(new MapColor[0], 0);
	}
}
