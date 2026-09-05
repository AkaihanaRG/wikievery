package com.wikievery;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sun.jna.WString;

import com.wikievery.win32.Win32;
import com.wikievery.win32.Win32.HWND;
import com.wikievery.win32.Win32.Kernel32Native;
import com.wikievery.win32.Win32.MSG;
import com.wikievery.win32.Win32.User32Native;
import com.wikievery.win32.Win32.WNDCLASSEX;
import com.wikievery.win32.Win32.WindowProc;

/**
 * An invisible native child window covering the whole game client area while
 * the webview overlay is open. It swallows every mouse event that would
 * otherwise reach the game (so left/right clicking the dimmed area does
 * nothing), while staying below the browser window so the page stays fully
 * clickable. It never activates (WS_EX_NOACTIVATE), so the keyboard focus
 * stays with the game and K/ESC keep working.
 *
 * <p>It lives on its own thread with its own Win32 message pump so the
 * intercepted mouse messages are consumed and discarded safely.
 */
public final class InputShieldWindow {
	private static final Logger LOGGER = LogUtils.getLogger();

	public interface Listener {
		void onHotkey();

		/** @return the window handle wheel events should be forwarded to, or 0 to swallow them */
		long routeWheel();
	}

	private static final String CLASS_NAME = "WikieveryInputShield";
	private static final int HOTKEY_ID_K = 0x574B; // "WK"
	private static final int HOTKEY_ID_ESC = 0x5745; // "WE"

	private static volatile boolean classRegistered;
	private static final Object REGISTER_LOCK = new Object();
	private static WindowProc WINDOW_PROC; // must be strongly referenced

	private static final AtomicReference<Listener> LISTENER = new AtomicReference<>();

	private final AtomicBoolean running = new AtomicBoolean();
	private volatile HWND hwnd;
	private volatile boolean hotkeyRegistered;

	private InputShieldWindow() {
	}

	public static InputShieldWindow create(long parentHwnd, Listener listener) {
		registerClass();
		LISTENER.set(listener);

		InputShieldWindow shield = new InputShieldWindow();
		shield.running.set(true);

		Thread thread = new Thread(() -> runLoop(parentHwnd, shield), "Wikievery-InputShield");
		thread.setDaemon(true);
		thread.start();
		return shield;
	}

	private static void runLoop(long parentHwndValue, InputShieldWindow shield) {
		HWND parent = Win32.hwnd(parentHwndValue);
		HWND hwnd = User32Native.INSTANCE.CreateWindowExW(
				Win32.WS_EX_NOACTIVATE | Win32.WS_EX_LAYERED,
				new WString(CLASS_NAME),
				null,
				Win32.WS_CHILD | Win32.WS_CLIPSIBLINGS,
				0, 0, 1, 1,
				parent, null, null, null);

		if (hwnd == null) {
			LOGGER.error("[wikievery] CreateWindowExW failed for input shield, error={}", Kernel32Native.INSTANCE.GetLastError());
			return;
		}

		// Nearly invisible (alpha 1/255) but fully hit-testable: this keeps
		// the shield visually transparent while it still swallows every click.
		User32Native.INSTANCE.SetLayeredWindowAttributes(hwnd, 0, 1, Win32.LWA_ALPHA);

		shield.hwnd = hwnd;

		try {
			MSG msg = new MSG();

			while (shield.running.get() && User32Native.INSTANCE.GetMessageW(msg, null, 0, 0) > 0) {
				User32Native.INSTANCE.TranslateMessage(msg);
				User32Native.INSTANCE.DispatchMessageW(msg);
			}
		} finally {
			User32Native.INSTANCE.UnregisterHotKey(hwnd, HOTKEY_ID_K);
			User32Native.INSTANCE.UnregisterHotKey(hwnd, HOTKEY_ID_ESC);
			shield.hwnd = null;
		}
	}

	/** Can be called from any thread; the shield grabs keyboard focus on its own thread. */
	public void requestFocus() {
		HWND window = hwnd;

		if (window != null) {
			User32Native.INSTANCE.PostMessageW(window, Win32.WM_USER + 1, 0, 0);
		}
	}

	/**
	 * Registers/unregisters the global K + ESC hotkeys. Registering consumes
	 * the keys system-wide, so this is only enabled while the overlay is open
	 * and no game screen (e.g. chat) is open. The actual registration runs on
	 * the shield thread (see WM_USER+2/+3).
	 */
	public void setHotkeyEnabled(boolean enabled) {
		if (enabled == hotkeyRegistered) {
			return;
		}

		HWND window = hwnd;

		if (window == null) {
			return;
		}

		if (enabled) {
			User32Native.INSTANCE.PostMessageW(window, Win32.WM_USER + 2, 0, 0);
			hotkeyRegistered = true;
		} else {
			User32Native.INSTANCE.PostMessageW(window, Win32.WM_USER + 3, 0, 0);
			hotkeyRegistered = false;
		}
	}

	private static void registerClass() {
		if (classRegistered) {
			return;
		}

		synchronized (REGISTER_LOCK) {
			if (classRegistered) {
				return;
			}

			WINDOW_PROC = (hwndValue, uMsg, wParam, lParam) -> {
				HWND hwnd = Win32.hwnd(hwndValue);

				try {
					switch (uMsg) {
						case Win32.WM_HOTKEY -> {
							Listener listener = LISTENER.get();

							if (listener != null) {
								listener.onHotkey();
							}

							return 0;
						}
						case Win32.WM_KEYDOWN -> {
							// The shield holds the keyboard focus while the
							// overlay is open: K or ESC close it (fallback for
							// the global hotkeys), every other key is swallowed.
							if (wParam == Win32.VK_K || wParam == Win32.VK_ESCAPE) {
								Listener listener = LISTENER.get();

								if (listener != null) {
									listener.onHotkey();
								}
							}

							return 0;
						}
						case Win32.WM_MOUSEWHEEL -> {
							Listener listener = LISTENER.get();
							long target = listener != null ? listener.routeWheel() : 0;

							if (target != 0) {
								// Forward the wheel to the WebView2 input window so
								// the page still scrolls when the cursor is over it.
								User32Native.INSTANCE.PostMessageW(Win32.hwnd(target), Win32.WM_MOUSEWHEEL, wParam, lParam);
							}

							return 0;
						}
						case Win32.WM_USER + 1 -> {
							// Focus request from the overlay: the shield holds the
							// keyboard focus while the overlay is open, so the game
							// receives neither keys nor wheel input.
							User32Native.INSTANCE.SetFocus(hwnd);
							return 0;
						}
						case Win32.WM_USER + 2 -> {
							// Hotkey registration must happen on the thread that
							// owns the window, otherwise RegisterHotKey fails
							// with ERROR_INVALID_WINDOW_HANDLE (1408).
							if (!User32Native.INSTANCE.RegisterHotKey(hwnd, HOTKEY_ID_K, Win32.MOD_NOREPEAT, Win32.VK_K)) {
								LOGGER.warn("[wikievery] RegisterHotKey(K) failed, error={}", Kernel32Native.INSTANCE.GetLastError());
							}

							if (!User32Native.INSTANCE.RegisterHotKey(hwnd, HOTKEY_ID_ESC, Win32.MOD_NOREPEAT, Win32.VK_ESCAPE)) {
								LOGGER.warn("[wikievery] RegisterHotKey(ESC) failed, error={}", Kernel32Native.INSTANCE.GetLastError());
							}

							return 0;
						}
						case Win32.WM_USER + 3 -> {
							User32Native.INSTANCE.UnregisterHotKey(hwnd, HOTKEY_ID_K);
							User32Native.INSTANCE.UnregisterHotKey(hwnd, HOTKEY_ID_ESC);
							return 0;
						}
						case Win32.WM_MOUSEACTIVATE -> {
							return Win32.MA_NOACTIVATE;
						}
						case Win32.WM_PAINT -> {
							return 0;
						}
						case Win32.WM_CLOSE -> {
							User32Native.INSTANCE.DestroyWindow(hwnd);
							return 0;
						}
						case Win32.WM_DESTROY -> {
							User32Native.INSTANCE.PostQuitMessage(0);
							return 0;
						}
						case Win32.WM_ERASEBKGND -> {
							return 1;
						}
						default -> {
						}
					}
				} catch (Throwable t) {
					LOGGER.warn("[wikievery] Input shield WndProc error", t);
				}

				return User32Native.INSTANCE.DefWindowProcW(hwnd, uMsg, wParam, lParam);
			};

			WNDCLASSEX wc = new WNDCLASSEX();
			wc.cbSize = wc.size();
			wc.style = 0;
			wc.lpfnWndProc = WINDOW_PROC;
			wc.hInstance = Kernel32Native.INSTANCE.GetModuleHandleW(null);
			wc.hCursor = null;
			wc.hbrBackground = null;
			wc.lpszMenuName = null;
			wc.lpszClassName = new WString(CLASS_NAME);
			wc.hIconSm = null;

			short atom = User32Native.INSTANCE.RegisterClassExW(wc);

			if (atom == 0) {
				LOGGER.warn("[wikievery] RegisterClassExW failed for input shield, error={}", Kernel32Native.INSTANCE.GetLastError());
			}

			classRegistered = true;
		}
	}

	/** Covers the whole client area. Call BEFORE raising the browser above it. */
	public void positionAndShow(int width, int height) {
		HWND window = hwnd;

		if (window == null) {
			return;
		}

		User32Native.INSTANCE.SetWindowPos(window, null, 0, 0, width, height,
				Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
	}

	public void hide() {
		HWND window = hwnd;

		if (window != null) {
			User32Native.INSTANCE.ShowWindow(window, Win32.SW_HIDE);
		}
	}

	/** Safe from any thread; posts WM_CLOSE so destruction happens on the shield thread. */
	public void destroy() {
		running.set(false);
		HWND window = hwnd;

		if (window != null) {
			User32Native.INSTANCE.PostMessageW(window, Win32.WM_CLOSE, 0, 0);
		}
	}
}
