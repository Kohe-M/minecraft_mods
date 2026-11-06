package com.omuraisu.sorter;

import java.util.Arrays;

// グループをどのスロットから置くかを決める。空きに余裕があるほど行頭にそろえて、見ただけで区切りが分かるようにする
public final class SortLayout {
	private static final double SPLIT_ROWS = 10;
	private static final double SHARE_ROW_RELATED = 2;
	private static final double SHARE_ROW_UNRELATED = 5;
	private static final double BLANK_SLOT = 0.5;

	private SortLayout() {
	}

	// sizes はグループごとのスタック数、bands は関連の深さの区分。各グループの先頭スロットを返す
	public static int[] place(int[] sizes, int[] bands, int width, int capacity) {
		int groups = sizes.length;
		int[] remaining = new int[groups + 1];
		for (int i = groups - 1; i >= 0; i--) {
			remaining[i] = remaining[i + 1] + sizes[i];
		}

		// best[i][pos] = グループ i 以降を pos より後に置くときの最小コスト
		double[][] best = new double[groups + 1][capacity + 1];
		boolean[][] skip = new boolean[groups + 1][capacity + 1];
		for (int i = groups - 1; i >= 0; i--) {
			Arrays.fill(best[i], Double.POSITIVE_INFINITY);
			for (int pos = 0; pos <= capacity - remaining[i]; pos++) {
				double here = startCost(i, pos, pos, sizes, bands, width) + best[i + 1][pos + sizes[i]];
				int nextRow = Math.ceilDiv(pos, width) * width;
				double row = Double.POSITIVE_INFINITY;
				if (nextRow != pos && nextRow <= capacity - remaining[i]) {
					row = startCost(i, pos, nextRow, sizes, bands, width) + best[i + 1][nextRow + sizes[i]];
				}
				skip[i][pos] = row < here;
				best[i][pos] = Math.min(here, row);
			}
		}

		int[] starts = new int[groups];
		int pos = 0;
		for (int i = 0; i < groups; i++) {
			starts[i] = skip[i][pos] ? Math.ceilDiv(pos, width) * width : pos;
			pos = starts[i] + sizes[i];
		}
		return starts;
	}

	private static double startCost(int group, int pos, int start, int[] sizes, int[] bands, int width) {
		double cost = (start - pos) * BLANK_SLOT;
		if (group > 0 && start % width != 0) {
			cost += bands[group] == bands[group - 1] ? SHARE_ROW_RELATED : SHARE_ROW_UNRELATED;
		}
		int rows = (start + sizes[group] - 1) / width - start / width + 1;
		if (rows > Math.ceilDiv(sizes[group], width)) {
			cost += SPLIT_ROWS;
		}
		return cost;
	}
}
