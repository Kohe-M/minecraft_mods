package com.omuraisu.jori.client;

import com.omuraisu.jori.Feature;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

// Jori に入れた mod をタイルで並べ、押すと有効・無効が切り替わる
public class JoriScreen extends Screen {
	private static final int GAP = 4;
	private static final int MARGIN = 8;
	private static final int MAX_TILE_WIDTH = 140;
	private static final int MIN_TILE_HEIGHT = 24;
	private static final int MAX_TILE_HEIGHT = 40;

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	public JoriScreen(Screen parent) {
		super(Component.literal("Jori"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);

		// 名前が収まる幅で、入るだけの列に並べる。行が増えたらタイルを低くして画面に収める
		int nameWidth = 0;
		for (Feature feature : JoriClient.FEATURES) {
			nameWidth = Math.max(nameWidth, font.width(FeatureTile.name(feature)));
		}
		int count = JoriClient.FEATURES.size();
		int columns = 3;
		int tileWidth = 0;
		for (; columns > 1; columns--) {
			tileWidth = Math.min(MAX_TILE_WIDTH, (width - 2 * MARGIN - (columns - 1) * GAP) / columns);
			if (tileWidth >= FeatureTile.minWidth(nameWidth, MAX_TILE_HEIGHT)) {
				break;
			}
		}
		if (columns == 1) {
			tileWidth = Math.min(MAX_TILE_WIDTH, width - 2 * MARGIN);
		}
		int rows = Math.ceilDiv(count, columns);
		int contentHeight = height - 2 * HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT - MARGIN;
		int tileHeight = Mth.clamp((contentHeight - (rows - 1) * GAP) / rows, MIN_TILE_HEIGHT, MAX_TILE_HEIGHT);

		GridLayout grid = new GridLayout().spacing(GAP);
		GridLayout.RowHelper helper = grid.createRowHelper(columns);
		for (Feature feature : JoriClient.FEATURES) {
			helper.addChild(new FeatureTile(feature, tileWidth, tileHeight));
		}
		layout.addToContents(grid);

		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(Button.BIG_WIDTH).build());
		layout.visitWidgets(this::addRenderableWidget);
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
