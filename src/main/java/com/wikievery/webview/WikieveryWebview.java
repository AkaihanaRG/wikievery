package com.wikievery.webview;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.sun.jna.Native;

import com.wikievery.win32.Win32;

/**
 * Thin wrapper around the native webview library.
 *
 * <p>On first use the bundled DLLs (webview.dll + WebView2Loader.dll) are
 * extracted from the mod jar into a temp folder, then loaded into the game
 * process. The webview is created as its own top-level native window and is
 * later re-parented into the Minecraft window by {@code WebviewOverlay}, which
 * keeps it at a fixed centered size (the library only auto-fills the parent
 * when created in "embedded" mode, which we deliberately avoid).
 */
public final class WikieveryWebview implements AutoCloseable {
	private static final Object LOAD_LOCK = new Object();
	private static volatile WebviewNative NATIVE;

	private final long pointer;
	private final long childWindow;

	private WikieveryWebview(long pointer, long childWindow) {
		this.pointer = pointer;
		this.childWindow = childWindow;
	}

	/**
	 * Creates the webview as a top-level window with the given fixed size.
	 * The window is hidden and moved off-screen immediately so it never
	 * flashes at the top-left corner of the desktop before being embedded.
	 */
	public static WikieveryWebview create(boolean debug, int width, int height) {
		WebviewNative n = loadNative();

		long pointer = n.webview_create(debug, null);

		if (pointer == 0) {
			throw new IllegalStateException("webview_create returned null, WebView2 runtime missing?");
		}

		n.webview_set_title(pointer, "Wikievery Webview");
		n.webview_set_size(pointer, width, height, WebviewNative.WV_HINT_FIXED);

		long childWindow = n.webview_get_window(pointer);

		Win32.User32Native.INSTANCE.ShowWindow(Win32.hwnd(childWindow), Win32.SW_HIDE);
		Win32.User32Native.INSTANCE.SetWindowPos(Win32.hwnd(childWindow), null, -10000, -10000, width, height,
				Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);

		return new WikieveryWebview(pointer, n.webview_get_window(pointer));
	}

	public void navigate(String url) {
		NATIVE.webview_navigate(pointer, url == null || url.isBlank() ? "about:blank" : url);
	}

	/** Injected into every page before window.onload; must be set before {@link #navigate}. */
	public void setInitScript(String script) {
		NATIVE.webview_init(pointer, script);
	}

	/** Executes JavaScript in the CURRENT page (used to re-apply the page script reliably). */
	public void eval(String script) {
		NATIVE.webview_eval(pointer, script);
	}

	/** Exposes a global JS function; must be bound before {@link #navigate}. */
	public void bind(String name, WebviewNative.BindCallback callback) {
		NATIVE.webview_bind(pointer, name, callback, 0);
	}

	/** Blocks until {@link #terminate()} is called; destroys the webview afterwards. */
	public void runLoop() {
		NATIVE.webview_run(pointer);
		NATIVE.webview_destroy(pointer);
	}

	public void terminate() {
		try {
			NATIVE.webview_terminate(pointer);
		} catch (Throwable ignored) {
		}
	}

	public void dispatch(Runnable action) {
		NATIVE.webview_dispatch(pointer, (pointerArg, arg) -> action.run(), 0);
	}

	public long getChildWindow() {
		return childWindow;
	}

	@Override
	public void close() {
		terminate();
	}

	private static WebviewNative loadNative() {
		synchronized (LOAD_LOCK) {
			if (NATIVE != null) {
				return NATIVE;
			}

			try {
				Path dir = Path.of(System.getProperty("java.io.tmpdir"), "wikievery-natives");
				Files.createDirectories(dir);

				Path loader = dir.resolve("WebView2Loader.dll");
				Path webview = dir.resolve("webview.dll");

				extract("/natives/windows/WebView2Loader.dll", loader);
				extract("/natives/windows/webview.dll", webview);

				// Load the loader first so webview.dll resolves its import at load time.
				System.load(loader.toAbsolutePath().toString());

				NATIVE = Native.load(webview.toAbsolutePath().toString(), WebviewNative.class);
				return NATIVE;
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to extract webview natives", e);
			}
		}
	}

	private static void extract(String resource, Path target) throws IOException {
		if (Files.exists(target) && Files.size(target) > 0) {
			return;
		}

		try (InputStream in = WebviewNative.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Missing native resource: " + resource);
			}

			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
