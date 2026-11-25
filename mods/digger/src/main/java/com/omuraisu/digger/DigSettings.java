package com.omuraisu.digger;

import net.minecraft.util.Mth;

// プレイヤーごとの範囲採掘と鉱石一括の設定。サーバーが受け取った値も、設定ファイルの値も、ここで決まりの範囲に収める。
// 範囲採掘の幅と高さは 1〜9（実際に選べるのは AreaSize の段階）、奥行きは 1〜5
public record DigSettings(boolean area, int width, int height, int depth, boolean vein) {
	public static final int MIN_SIZE = 1;
	public static final int MAX_SIZE = 9;
	public static final int MIN_DEPTH = 1;
	public static final int MAX_DEPTH = 5;

	// 両方オフ。送ってこない人と Jori で無効にしたときにサーバーへ伝える値
	public static final DigSettings OFF = new DigSettings(false, 3, 3, 1, false);

	// 設定ファイルに何も書いていないとき。範囲採掘はオフ、鉱石一括はオン
	public static final DigSettings DEFAULT = new DigSettings(false, 3, 3, 1, true);

	public DigSettings {
		width = Mth.clamp(width, MIN_SIZE, MAX_SIZE);
		height = Mth.clamp(height, MIN_SIZE, MAX_SIZE);
		depth = Mth.clamp(depth, MIN_DEPTH, MAX_DEPTH);
	}

	public DigSettings withArea(boolean value) {
		return new DigSettings(value, width, height, depth, vein);
	}

	// スニーク中は範囲採掘をせず 1 つだけ掘る。鉱石一括はそのまま働く。
	// 覚えている設定・設定ファイル・サーバーへ送る値は変えず、計画を立てるときだけ絞る
	public DigSettings whenSneaking(boolean sneaking) {
		return sneaking ? withArea(false) : this;
	}

	public DigSettings withVein(boolean value) {
		return new DigSettings(area, width, height, depth, value);
	}

	public DigSettings withSize(int newWidth, int newHeight) {
		return new DigSettings(area, newWidth, newHeight, depth, vein);
	}

	public DigSettings withDepth(int newDepth) {
		return new DigSettings(area, width, height, newDepth, vein);
	}
}
