package com.omuraisu.jori;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.omuraisu.minimap.Minimap;
import com.omuraisu.sorter.Sorter;
import com.omuraisu.timber.Timber;

// Jori に入れた mod の 1 つ。id は mod ID と同じで、設定ファイルのキーと各 mod のアイコン・名前にも使う
public record Feature(String id, BooleanSupplier active, Consumer<Boolean> setActive) {
	public static final Feature MINIMAP = new Feature("minimap", Minimap::isActive, Minimap::setActive);
	public static final Feature SORTER = new Feature("sorter", Sorter::isActive, Sorter::setActive);
	public static final Feature TIMBER = new Feature("timber", Timber::isActive, Timber::setActive);

	// サーバーでも動く mod。専用サーバーではこれだけを設定ファイルで切り替える
	public static final List<Feature> SERVER = List.of(MINIMAP, SORTER, TIMBER);

	public boolean isActive() {
		return active.getAsBoolean();
	}

	public void apply(JoriConfig config) {
		setActive.accept(config.isEnabled(id));
	}
}
