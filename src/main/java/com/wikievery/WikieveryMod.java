package com.wikievery;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

public class WikieveryMod implements ModInitializer {
	public static final String MOD_ID = "wikievery";
	private static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public void onInitialize() {
		LOGGER.info("[{}] framework loaded successfully. Ready for development.", MOD_ID);
	}
}
