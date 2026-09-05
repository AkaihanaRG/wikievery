package com.wikievery;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Client side setup: registers the K key binding that toggles the embedded
 * webview overlay.
 */
public class WikieveryClientMod implements ClientModInitializer {
	public static final String MOD_ID = "wikievery";

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("wikievery", "webview"));

	public static KeyMapping WEBVIEW_KEY;

	@Override
	public void onInitializeClient() {
		WikieveryConfig config = WikieveryConfig.load();
		WebviewOverlay overlay = new WebviewOverlay(config);
		WebviewHudRenderer.register(overlay::getFrameGuiRect);

		WEBVIEW_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.wikievery.webview",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			overlay.tick();

			while (WEBVIEW_KEY.consumeClick()) {
				overlay.toggle();
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> overlay.shutdown());
	}
}
