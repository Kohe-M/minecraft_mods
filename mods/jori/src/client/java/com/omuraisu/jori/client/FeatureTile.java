package com.omuraisu.jori.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.omuraisu.jori.Feature;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

// 1 つの mod のタイル。アイコン・名前・スイッチを並べ、押すと切り替わる。説明とキーはツールチップに出す
final class FeatureTile extends AbstractButton {
	private static final int PADDING = 4;
	private static final int ICON_GAP = 6;
	private static final int SWITCH_WIDTH = 16;
	private static final int SWITCH_HEIGHT = 8;
	private static final int SWITCH_MARGIN = 6;

	private static final int BACKGROUND = 0xA0101318;
	private static final int HOVER_BACKGROUND = 0xC0283040;
	private static final int BORDER = 0x40FFFFFF;
	private static final int HOVER_BORDER = 0xFFFFFFFF;
	// 無効のアイコンに重ねて暗くする
	private static final int DIM = 0xB0101318;
	private static final int NAME = 0xFFFFFFFF;
	private static final int DIM_NAME = 0xFF7A808A;
	private static final int TRACK_ON = 0xFF3FAE4F;
	private static final int TRACK_OFF = 0xFF3A3F48;
	private static final int KNOB_ON = 0xFFFFFFFF;
	private static final int KNOB_OFF = 0xFF8A9099;

	private final Feature feature;
	private final Identifier icon;
	private Component tooltip;

	FeatureTile(Feature feature, int width, int height) {
		super(0, 0, width, height, name(feature));
		this.feature = feature;
		this.icon = Identifier.fromNamespaceAndPath(feature.id(), "icon.png");
	}

	static Component name(Feature feature) {
		return Component.literal(FabricLoader.getInstance().getModContainer(feature.id())
				.map(mod -> mod.getMetadata().getName())
				.orElse(feature.id()));
	}

	// 名前が切れずに収まる幅
	static int minWidth(int nameWidth, int height) {
		return PADDING + (height - 2 * PADDING) + ICON_GAP + nameWidth + ICON_GAP + SWITCH_WIDTH + SWITCH_MARGIN;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		JoriClient.setActive(feature, !feature.isActive());
	}

	// マウスで押した後に枠が残らないようにする。キーボードで選んだときは残る
	@Override
	public boolean shouldTakeFocusAfterInteraction() {
		return false;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		Minecraft client = Minecraft.getInstance();
		boolean on = feature.isActive();
		boolean hot = isHoveredOrFocused();

		graphics.fill(getX(), getY(), getRight(), getBottom(), hot ? HOVER_BACKGROUND : BACKGROUND);
		graphics.outline(getX(), getY(), width, height, hot ? HOVER_BORDER : BORDER);

		int size = height - 2 * PADDING;
		int iconX = getX() + PADDING;
		int iconY = getY() + PADDING;
		// アイコンは 128 ピクセルなので、縮めても崩れないよう線形補間で描く
		AbstractTexture texture = client.getTextureManager().getTexture(icon);
		graphics.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), iconX, iconY, iconX + size, iconY + size, 0, 1, 0, 1);
		if (!on) {
			graphics.fill(iconX, iconY, iconX + size, iconY + size, DIM);
		}

		int textX = iconX + size + ICON_GAP;
		graphics.text(client.font, getMessage(), textX, getY() + (height - client.font.lineHeight) / 2 + 1, on ? NAME : DIM_NAME);

		int switchX = getRight() - SWITCH_MARGIN - SWITCH_WIDTH;
		int switchY = getY() + (height - SWITCH_HEIGHT) / 2;
		graphics.fill(switchX, switchY, switchX + SWITCH_WIDTH, switchY + SWITCH_HEIGHT, on ? TRACK_ON : TRACK_OFF);
		int knobX = on ? switchX + SWITCH_WIDTH - SWITCH_HEIGHT : switchX;
		graphics.fill(knobX, switchY, knobX + SWITCH_HEIGHT, switchY + SWITCH_HEIGHT, on ? KNOB_ON : KNOB_OFF);

		// キーの割り当てやサーバーの状態で変わるので、変わったときだけ作り直す
		Component text = tooltipText(client);
		if (!text.equals(tooltip)) {
			tooltip = text;
			setTooltip(Tooltip.create(text));
		}
	}

	private Component tooltipText(Minecraft client) {
		MutableComponent text = Component.translatable("jori.description." + feature.id());
		for (KeyMapping key : client.options.keyMappings) {
			if (key.getCategory().id().getNamespace().equals(feature.id())) {
				text.append("\n").append(key.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.YELLOW))
						.append(" ").append(Component.translatable(key.getName()).withStyle(ChatFormatting.GRAY));
			}
		}
		if (JoriClient.isMissingOnServer(client, feature)) {
			text.append("\n").append(Component.translatable("jori.missing_on_server").withStyle(ChatFormatting.RED));
		}
		return text;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, CommonComponents.optionStatus(getMessage(), feature.isActive()));
		if (tooltip != null) {
			output.add(NarratedElementType.HINT, tooltip);
		}
	}
}
