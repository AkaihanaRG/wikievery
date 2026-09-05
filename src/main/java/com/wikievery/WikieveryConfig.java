package com.wikievery;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Reads the mod settings from {@code config/wikievery.properties} inside the
 * game directory. The file is created with the default values on first run so
 * the URL / overlay size can be tweaked without recompiling.
 */
public final class WikieveryConfig {
	public static final String DEFAULT_URL = "https://zh.minecraft.wiki/";
	public static final int DEFAULT_WIDTH = 960;
	public static final int DEFAULT_HEIGHT = 540;

	private static final Logger LOGGER = LogUtils.getLogger();

	private final String url;
	private final int width;
	private final int height;
	private final boolean debug;

	private WikieveryConfig(String url, int width, int height, boolean debug) {
		this.url = url;
		this.width = width;
		this.height = height;
		this.debug = debug;
	}

	public static WikieveryConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("wikievery.properties");

		if (!Files.exists(file)) {
			writeDefaults(file);
		}

		Properties props = new Properties();

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (IOException e) {
			LOGGER.warn("[wikievery] Failed to read config {}, using defaults", file, e);
		}

		String url = props.getProperty("url", DEFAULT_URL);
		int width = clamp(parseInt(props, "width", DEFAULT_WIDTH), 320, 3840);
		int height = clamp(parseInt(props, "height", DEFAULT_HEIGHT), 180, 2160);
		boolean debug = Boolean.parseBoolean(props.getProperty("debug", "false"));

		LOGGER.info("[wikievery] Webview config: url={}, size={}x{}, debug={}", url, width, height, debug);
		return new WikieveryConfig(url, width, height, debug);
	}

	public String url() {
		return url;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean debug() {
		return debug;
	}

	private static void writeDefaults(Path file) {
		Properties props = new Properties();
		props.setProperty("url", DEFAULT_URL);
		props.setProperty("width", Integer.toString(DEFAULT_WIDTH));
		props.setProperty("height", Integer.toString(DEFAULT_HEIGHT));
		props.setProperty("debug", "false");

		try {
			Files.createDirectories(file.getParent());

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				props.store(writer, "Wikievery webview settings. url = page to open, width/height = overlay resolution (fixed), debug = enable devtools.");
			}
		} catch (IOException e) {
			LOGGER.warn("[wikievery] Failed to write default config to {}", file, e);
		}
	}

	private static int parseInt(Properties props, String key, int fallback) {
		try {
			return Integer.parseInt(props.getProperty(key, Integer.toString(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
