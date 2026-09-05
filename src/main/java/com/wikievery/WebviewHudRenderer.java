package com.wikievery;

import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * Draws the overlay's in-game UI in the game's own HUD pass (above the game
 * world, below the native browser window):
 * <ul>
 *   <li>the 50% black dim filter over the whole game frame,</li>
 *   <li>the webview frame texture (1104x684, i.e. 72px border around the
 *       960x540 browser) centered on the browser window. The texture is drawn
 *       before the native browser window composites, so the browser sits in
 *       the transparent center of the frame.</li>
 * </ul>
 * This is purely visual: it introduces no windows or input handling, so it
 * cannot affect mouse capture, hotkeys or focus/pause behavior.
 */
public final class WebviewHudRenderer {
	public static final Identifier UI_ID = Identifier.fromNamespaceAndPath("wikievery", "webview_ui");
	private static final Identifier FRAME_TEXTURE =
			Identifier.fromNamespaceAndPath("wikievery", "textures/webview_frame.png");
	private static final int DIM_COLOR = 0x80000000;

	private WebviewHudRenderer() {
	}

	public static void register(Supplier<int[]> frameRectSupplier) {
		HudElementRegistry.addLast(UI_ID, (extractor, deltaTracker) -> {
			int[] rect = frameRectSupplier.get();

			if (rect == null) {
				return;
			}

			extractor.fill(0, 0, extractor.guiWidth(), extractor.guiHeight(), DIM_COLOR);

			int x = rect[0];
			int y = rect[1];
			int width = rect[2];
			int height = rect[3];

			extractor.blit(FRAME_TEXTURE, x, y, x + width, y + height, 0.0F, 1.0F, 0.0F, 1.0F);
		});
	}
}
