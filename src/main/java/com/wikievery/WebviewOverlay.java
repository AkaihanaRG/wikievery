package com.wikievery;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sun.jna.Pointer;

import net.minecraft.client.Minecraft;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFWNativeWin32;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import com.wikievery.webview.WikieveryWebview;
import com.wikievery.win32.Win32;
import com.wikievery.win32.Win32.HWND;
import com.wikievery.win32.Win32.Kernel32Native;
import com.wikievery.win32.Win32.RECT;
import com.wikievery.win32.Win32.User32Native;
import com.wikievery.win32.Win32.WindowEnumProc;

import com.sun.jna.ptr.IntByReference;

/**
 * Manages the in-game webview overlay.
 *
 * <p>Layering (game -> dim filter -> browser):
 * <ul>
 *   <li>The 50% black filter is drawn by {@link WebviewHudRenderer} inside the
 *       game's own HUD pass (visible as soon as K is pressed).</li>
 *   <li>The browser is a native WebView2 window re-parented into the game
 *       window, centered at a fixed size. It is PRE-CREATED while the game
 *       boots (so the library's brief "tiny window at the top-left" flash
 *       happens during the loading screen and is never seen in-game) and is
 *       simply shown/hidden on K.</li>
 *   <li>The red close button is a DOM element injected into the page itself
 *       (always above page content), wired to a bound JS function.</li>
 * </ul>
 *
 * <p>While the overlay is open: mouse capture is released (the game stops
 * warping the cursor / turning the camera), "pause on lost focus" is
 * temporarily disabled and the game window keeps the keyboard focus unless an
 * editable field inside the page has focus, so K/ESC keep working.
 */
public final class WebviewOverlay implements InputShieldWindow.Listener {
	private static final Logger LOGGER = LogUtils.getLogger();

	private enum State {
		CLOSED, CREATING, OPEN, HIDDEN
	}

	// Frame texture geometry: 72px border around the 960x540 browser window.
	private static final int FRAME_BORDER = 72;
	private static final int FRAME_WIDTH = 1104;
	private static final int FRAME_HEIGHT = 684;

	private final WikieveryConfig config;

	private volatile State state = State.CLOSED;
	private WikieveryWebview webview;
	private InputShieldWindow inputShield;
	private volatile long parentHwnd;
	private volatile boolean editableFocus;
	private volatile boolean pendingShow;
	private boolean prewarmStarted;
	private boolean mouseSuppressed;
	private boolean pauseOptionSuppressed;
	private boolean pauseOptionOriginal;
	private long pauseRestoreDeadline;
	private long closeGraceDeadline;
	private boolean lastLButtonDown;
	private int lastClientWidth = -1;
	private int lastClientHeight = -1;
	private boolean hotkeyActive;

	public WebviewOverlay(WikieveryConfig config) {
		this.config = config;
	}

	/** True while the dim filter should be visible (loading or open). */
	public boolean isDimActive() {
		return state == State.CREATING || state == State.OPEN;
	}

	public void toggle() {
		switch (state) {
			case CLOSED -> {
				pendingShow = true;

				if (webview != null) {
					show();
				} else {
					long parent = minecraftWindowHwnd();

					if (parent == 0) {
						LOGGER.warn("[wikievery] Could not resolve the Minecraft window handle, skipping webview open.");
						return;
					}

					suppressPauseOnFocusLoss();
					state = State.CREATING;
					spawnCreationThread(parent);
				}
			}
			case CREATING -> pendingShow = true;
			case OPEN -> {
				pendingShow = false;
				hide();
			}
			case HIDDEN -> show();
		}
	}

	private void spawnCreationThread(long parent) {
		Thread thread = new Thread(() -> createAndRun(parent), "Wikievery-Webview");
		thread.setDaemon(true);
		thread.start();
	}

	private void createAndRun(long parent) {
		try {
			webview = WikieveryWebview.create(config.debug(), config.width(), config.height());
			embedChild(webview.getChildWindow(), parent);

			parentHwnd = parent;
			inputShield = InputShieldWindow.create(parent, this);

			// The WebView2 controller is created asynchronously: wait a moment
			// so the first navigation is not swallowed by the race.
			Thread.sleep(800);

			webview.navigate(config.url());

			hideBrowser();
			state = State.HIDDEN;
			LOGGER.info("[wikievery] Webview prewarmed ({}x{})", config.width(), config.height());

			// The webview's own top-level window briefly activated while it
			// was created: reclaim activation & keyboard focus for the game
			// window right away (otherwise the game stays unfocused for the
			// whole session, and pauseIfInactive keeps re-opening the pause
			// screen whenever the player has not clicked the window).
			User32Native.INSTANCE.SetForegroundWindow(Win32.hwnd(parent));
			focusGame(parent);

			if (pendingShow) {
				show();
			}

			webview.runLoop(); // blocks until terminate()
		} catch (Throwable t) {
			LOGGER.error("[wikievery] Failed to create webview overlay", t);
			state = State.CLOSED;
			prewarmStarted = false;
			restorePauseOnFocusLoss();
		}
	}

	/**
	 * Client tick: prewarms the webview at boot, keeps the browser centered,
	 * releases/re-grabs the mouse, maintains the pause suppression and keeps
	 * the keyboard focus on the game.
	 */
	public void tick() {
		if (webview == null && state == State.CLOSED) {
			if (!prewarmStarted) {
				prewarmStarted = true;

				long parent = minecraftWindowHwnd();

				if (parent != 0) {
					suppressPauseOnFocusLoss();
					state = State.CREATING;
					spawnCreationThread(parent);
				} else {
					prewarmStarted = false;
				}
			}
		}

		boolean active = state == State.OPEN;
		Minecraft mc = Minecraft.getInstance();

		if (mc != null && mc.mouseHandler != null) {
			if (active) {
				// Keep the mouse released while the overlay is open: clicking
				// the game window re-focuses it, and Minecraft re-grabs the
				// mouse on focus, so re-release every tick.
				if (mc.mouseHandler.isMouseGrabbed()) {
					mc.mouseHandler.releaseMouse();
				}

				mouseSuppressed = true;
			} else if (mouseSuppressed) {
				mc.mouseHandler.grabMouse();
				mouseSuppressed = false;
			}
		}

		boolean needPauseSuppress = state == State.CREATING || state == State.OPEN;

		if (needPauseSuppress && !pauseOptionSuppressed) {
			suppressPauseOnFocusLoss();
		} else if (!needPauseSuppress && pauseOptionSuppressed) {
			restorePauseOnFocusLoss();
		}

		updateHotkey(mc);

		if (state == State.OPEN && webview != null) {
			// While the overlay is open the game must never stay paused:
			// close any (silent or visible) pause screen immediately.
			if (mc != null && mc.gui != null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
				LOGGER.info("[wikievery] Closing pause screen while overlay is open");
				mc.gui.screen().onClose();
			}

			long parent = minecraftWindowHwnd();

			if (parent != 0) {
				RECT rect = new RECT();

				if (User32Native.INSTANCE.GetClientRect(Win32.hwnd(parent), rect)) {
					int width = rect.right - rect.left;
					int height = rect.bottom - rect.top;

					if (width != lastClientWidth || height != lastClientHeight) {
						lastClientWidth = width;
						lastClientHeight = height;
						positionWindows(parent);
					}
				}

				// Focus policy: while the user is interacting with the page
				// (keyboard focus inside the browser) we must not steal focus
				// back, so text fields keep the caret and receive input.
				// Otherwise the shield holds the focus so the game receives
				// neither keys nor wheel events.
				if (!pageFocused()) {
					if (inputShield != null) {
						inputShield.requestFocus();
					}

					keepGameForeground(parent);
				}

				// Keep the browser above the click shield in the Z order at all
				// times (pure Z re-raise, no side effects).
				User32Native.INSTANCE.SetWindowPos(webviewHwnd(), null, 0, 0, 0, 0,
						Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOACTIVATE);

				// Java-side fallback for the close button: works even if the
				// page script / JS binding fails for any reason.
				pollCloseButtonClick(parent);
			}
		} else {
			lastClientWidth = -1;
			lastClientHeight = -1;
		}

		// Self-healing: the browser must stay hidden whenever the overlay is
		// not open (covers rare cases where the OS/lib re-shows the window).
		if (state == State.HIDDEN && webview != null && User32Native.INSTANCE.IsWindowVisible(webviewHwnd())) {
			User32Native.INSTANCE.ShowWindow(webviewHwnd(), Win32.SW_HIDE);
		}

		// Shortly after closing, keep reclaiming activation/focus until the
		// game window is usable again (the browser/IME may have left the game
		// window unfocused). "pause on lost focus" stays disabled for the
		// whole grace window, so no pause screen can appear meanwhile.
		if (state == State.HIDDEN && System.currentTimeMillis() < closeGraceDeadline) {
			long parent = minecraftWindowHwnd();

			if (parent != 0) {
				keepGameForeground(parent);
				focusGame(parent);
			}
		}
	}

	/** Closes the overlay when the left mouse button is released over the close button area. */
	private void pollCloseButtonClick(long parent) {
		boolean down = (User32Native.INSTANCE.GetAsyncKeyState(Win32.VK_LBUTTON) & 0x8000) != 0;

		if (lastLButtonDown && !down && isCursorOverCloseButton(parent)) {
			LOGGER.info("[wikievery] Close requested via mouse polling");
			// Close-button click sound; hide() then also plays the page turn.
			playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
			hide();
		}

		lastLButtonDown = down;
	}

	/**
	 * The close hit area: the 72x72 frame corner square diagonally outside the
	 * browser's top-left corner (never covered by the native browser window).
	 */
	private boolean isCursorOverCloseButton(long parent) {
		Win32.POINT cursor = new Win32.POINT();

		if (!User32Native.INSTANCE.GetCursorPos(cursor)) {
			return false;
		}

		RECT parentClient = new RECT();

		if (!User32Native.INSTANCE.GetClientRect(Win32.hwnd(parent), parentClient)) {
			return false;
		}

		int browserX = Math.max(0, (parentClient.right - parentClient.left - config.width()) / 2);
		int browserY = Math.max(0, (parentClient.bottom - parentClient.top - config.height()) / 2);
		int buttonX = browserX - FRAME_BORDER;
		int buttonY = browserY - FRAME_BORDER;

		Win32.POINT origin = new Win32.POINT();
		origin.x = 0;
		origin.y = 0;
		User32Native.INSTANCE.ClientToScreen(Win32.hwnd(parent), origin);

		return cursor.x >= origin.x + buttonX && cursor.x < origin.x + buttonX + FRAME_BORDER
				&& cursor.y >= origin.y + buttonY && cursor.y < origin.y + buttonY + FRAME_BORDER;
	}

	/**
	 * Returns the frame texture rect in GUI (scaled) coordinates for the HUD
	 * renderer, or null while the overlay is closed. The 1104x684 frame is
	 * centered on the 960x540 browser window (72px border on every side).
	 */
	public int[] getFrameGuiRect() {
		if (state != State.OPEN || webview == null) {
			return null;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getWindow() == null) {
			return null;
		}

		Window window = mc.getWindow();
		int clientWidth = window.getWidth();
		int clientHeight = window.getHeight();

		if (clientWidth <= 0 || clientHeight <= 0) {
			return null;
		}

		int browserX = Math.max(0, (clientWidth - config.width()) / 2);
		int browserY = Math.max(0, (clientHeight - config.height()) / 2);

		double scaleX = (double) window.getGuiScaledWidth() / clientWidth;
		double scaleY = (double) window.getGuiScaledHeight() / clientHeight;

		int x = (int) ((browserX - FRAME_BORDER) * scaleX);
		int y = (int) ((browserY - FRAME_BORDER) * scaleY);
		int width = (int) (FRAME_WIDTH * scaleX);
		int height = (int) (FRAME_HEIGHT * scaleY);

		return new int[] {x, y, width, height};
	}

	private void show() {
		if (webview == null || state == State.OPEN) {
			return;
		}

		state = State.OPEN;
		LOGGER.info("[wikievery] Overlay shown");

		// Page-turn sound at double the vanilla volume when opening.
		playUiSound(SoundEvents.BOOK_PAGE_TURN, 2.0F);

		long parent = currentParentHwnd();
		positionWindows(parent);

		User32Native.INSTANCE.ShowWindow(webviewHwnd(), Win32.SW_SHOWNA);
	}

	private void hide() {
		if (webview == null || state != State.OPEN) {
			return;
		}

		User32Native.INSTANCE.ShowWindow(webviewHwnd(), Win32.SW_HIDE);

		if (inputShield != null) {
			inputShield.hide();
		}

		editableFocus = false;
		pendingShow = false;

		// Reclaim activation & keyboard focus for the game window: the
		// browser/IME may have left the game window unfocused, and an
		// unfocused game window makes pauseIfInactive re-open the pause
		// screen every frame ("Back to Game" appears broken).
		User32Native.INSTANCE.SetForegroundWindow(Win32.hwnd(parentHwnd));
		focusGame(parentHwnd);

		state = State.HIDDEN;
		LOGGER.info("[wikievery] Overlay hidden");

		// Page-turn sound on close regardless of the closing method; a button
		// click additionally plays the button click sound (they do not clash).
		playUiSound(SoundEvents.BOOK_PAGE_TURN, 2.0F);

		// Keep "pause on lost focus" disabled for a generous grace period so
		// any late focus-loss event caused by the page/IME cannot trap the
		// game in a re-opening pause screen loop. The pause screen itself is
		// also force-closed during closeGraceDeadline below.
		pauseRestoreDeadline = System.currentTimeMillis() + 30000;
		closeGraceDeadline = System.currentTimeMillis() + 3000;
	}

	private void hideBrowser() {
		if (webview != null) {
			User32Native.INSTANCE.ShowWindow(webviewHwnd(), Win32.SW_HIDE);
		}

		if (inputShield != null) {
			inputShield.hide();
		}
	}

	private void positionWindows(long parent) {
		if (parent == 0 || webview == null) {
			return;
		}

		RECT rect = new RECT();

		if (!User32Native.INSTANCE.GetClientRect(Win32.hwnd(parent), rect)) {
			return;
		}

		int clientWidth = rect.right - rect.left;
		int clientHeight = rect.bottom - rect.top;
		int x = Math.max(0, (clientWidth - config.width()) / 2);
		int y = Math.max(0, (clientHeight - config.height()) / 2);

		// The invisible click shield covers the whole client area; the browser
		// is then raised above it, so the page stays clickable while the rest
		// of the game ignores all mouse input.
		if (inputShield != null) {
			inputShield.positionAndShow(clientWidth, clientHeight);
		}

		User32Native.INSTANCE.SetWindowPos(webviewHwnd(), null, x, y, config.width(), config.height(),
				Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
	}

	/** Turns the webview's own top-level window into a borderless, non-activating child of the game. */
	private static void embedChild(long childHwndValue, long parentHwndValue) {
		HWND child = Win32.hwnd(childHwndValue);
		HWND parent = Win32.hwnd(parentHwndValue);

		long style = User32Native.INSTANCE.GetWindowLongPtrW(child, Win32.GWL_STYLE);
		style &= ~(Win32.WS_CAPTION | Win32.WS_THICKFRAME | Win32.WS_MINIMIZEBOX | Win32.WS_MAXIMIZEBOX | Win32.WS_SYSMENU);
		style |= Win32.WS_CHILD | Win32.WS_CLIPSIBLINGS;
		User32Native.INSTANCE.SetWindowLongPtrW(child, Win32.GWL_STYLE, style);

		long exStyle = User32Native.INSTANCE.GetWindowLongPtrW(child, Win32.GWL_EXSTYLE);
		User32Native.INSTANCE.SetWindowLongPtrW(child, Win32.GWL_EXSTYLE, exStyle | Win32.WS_EX_NOACTIVATE);

		User32Native.INSTANCE.SetParent(child, parent);
	}

	/**
	 * True while the user is interacting with the page: either the injected JS
	 * reported an editable field (may be unavailable on this webview build) or
	 * the foreground thread's keyboard focus sits inside the browser window.
	 */
	private boolean pageFocused() {
		if (editableFocus) {
			return true;
		}

		if (webview == null) {
			return false;
		}

		Win32.GUITHREADINFO info = new Win32.GUITHREADINFO();
		info.cbSize = info.size();

		if (!User32Native.INSTANCE.GetGUIThreadInfo(0, info) || info.hwndFocus == null) {
			return false;
		}

		long focus = Pointer.nativeValue(info.hwndFocus);
		long browser = webview.getChildWindow();

		for (int i = 0; i < 16; i++) {
			if (focus == browser) {
				return true;
			}

			if (focus == 0) {
				break;
			}

			HWND parent = User32Native.INSTANCE.GetParent(Win32.hwnd(focus));

			if (parent == null) {
				break;
			}

			focus = Pointer.nativeValue(parent.getPointer());
		}

		return false;
	}

	/**
	 * Keeps the game window as the foreground top-level window (so pressing
	 * Alt-Tab away is not fought, but any activation lost to the browser's own
	 * child windows is reclaimed). Keyboard focus is handled separately: the
	 * shield holds it while the overlay is open.
	 */
	private void keepGameForeground(long parent) {
		if (parent == 0) {
			return;
		}

		User32Native user32 = User32Native.INSTANCE;
		HWND foreground = user32.GetForegroundWindow();

		if (foreground != null && Pointer.nativeValue(foreground.getPointer()) != parent) {
			IntByReference pidRef = new IntByReference();
			user32.GetWindowThreadProcessId(foreground, pidRef);

			if (pidRef.getValue() == Kernel32Native.INSTANCE.GetCurrentProcessId()) {
				user32.SetForegroundWindow(Win32.hwnd(parent));
			}
		}
	}

	private void focusGame(long parent) {
		if (parent != 0) {
			User32Native.INSTANCE.SetFocus(Win32.hwnd(parent));
		}
	}

	/** Plays a UI sound for the local player; safe to call from any thread. */
	private void playUiSound(SoundEvent sound, float volume) {
		Minecraft mc = Minecraft.getInstance();

		if (mc == null) {
			return;
		}

		Runnable play = () -> {
			if (mc.player != null && mc.level != null) {
				mc.player.playSound(sound, volume, 1.0F);
			}
		};

		if (mc.isSameThread()) {
			play.run();
		} else {
			mc.execute(play);
		}
	}

	/** Temporarily disables "pause on lost focus" while our windows are created/shown. */
	private void suppressPauseOnFocusLoss() {
		Minecraft mc = Minecraft.getInstance();

		if (mc != null && mc.options != null && !pauseOptionSuppressed) {
			pauseOptionOriginal = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
			pauseOptionSuppressed = true;
		}
	}

	private void restorePauseOnFocusLoss() {
		if (!pauseOptionSuppressed) {
			return;
		}

		if (System.currentTimeMillis() < pauseRestoreDeadline) {
			return;
		}

		pauseOptionSuppressed = false;
		boolean original = pauseOptionOriginal;
		Minecraft mc = Minecraft.getInstance();

		if (mc != null) {
			if (mc.isSameThread()) {
				mc.options.pauseOnLostFocus = original;
			} else {
				mc.execute(() -> mc.options.pauseOnLostFocus = original);
			}
		}
	}

	/** Runs on the shield thread when the global K hotkey fires. */
	@Override
	public void onHotkey() {
		// The hotkey is only registered while the overlay is open, so this
		// always means "close the overlay". Runs on the shield thread, but
		// hide() only touches Win32 state which is thread-safe.
		if (state == State.OPEN) {
			LOGGER.info("[wikievery] Close via global hotkey");
			hide();
		}
	}

	/** Runs on the shield thread for each wheel event: forward to the browser if the cursor is over it. */
	@Override
	public long routeWheel() {
		if (state != State.OPEN || webview == null) {
			return 0;
		}

		// Only use the cached parent handle here: GLFW is not thread-safe and
		// this callback runs on the shield thread.
		long parent = parentHwnd;

		if (parent == 0) {
			return 0;
		}

		Win32.POINT cursor = new Win32.POINT();

		if (!User32Native.INSTANCE.GetCursorPos(cursor)) {
			return 0;
		}

		RECT rc = new RECT();

		if (!User32Native.INSTANCE.GetClientRect(Win32.hwnd(parent), rc)) {
			return 0;
		}

		int clientWidth = rc.right - rc.left;
		int clientHeight = rc.bottom - rc.top;
		int x = Math.max(0, (clientWidth - config.width()) / 2);
		int y = Math.max(0, (clientHeight - config.height()) / 2);

		Win32.POINT origin = new Win32.POINT();
		User32Native.INSTANCE.ClientToScreen(Win32.hwnd(parent), origin);

		boolean inside = cursor.x >= origin.x + x && cursor.x < origin.x + x + config.width()
				&& cursor.y >= origin.y + y && cursor.y < origin.y + y + config.height();

		return inside ? webviewInputTarget() : 0;
	}

	private long webviewInputTarget;

	/** The WebView2 child window that processes input (cached, refreshed if invalidated). */
	private long webviewInputTarget() {
		if (webview == null) {
			return 0;
		}

		if (webviewInputTarget != 0 && User32Native.INSTANCE.IsWindow(Win32.hwnd(webviewInputTarget))) {
			return webviewInputTarget;
		}

		webviewInputTarget = 0;
		final long[] result = {0};

		WindowEnumProc proc = (hwndValue, data) -> {
			char[] buffer = new char[128];
			User32Native.INSTANCE.GetClassNameW(Win32.hwnd(hwndValue), buffer, buffer.length);
			String className = new String(buffer).trim();

			if (className.startsWith("Chrome_WidgetWin")) {
				result[0] = hwndValue;
				return false;
			}

			return true;
		};

		User32Native.INSTANCE.EnumChildWindows(Win32.hwnd(webview.getChildWindow()), proc, null);
		webviewInputTarget = result[0];
		return result[0];
	}

	/**
	 * Keeps the global hotkey in sync: active only while the overlay is open
	 * and no game screen (e.g. chat) is open. The hotkey always closes the
	 * overlay, so it must stay registered even while the user is interacting
	 * with the page (only the letter K cannot be typed into the page; every
	 * other character works). Called every tick.
	 */
	private void updateHotkey(Minecraft mc) {
		boolean want = state == State.OPEN
				&& (mc == null || mc.gui == null || mc.gui.screen() == null);

		if (want != hotkeyActive) {
			hotkeyActive = want;

			if (inputShield != null) {
				inputShield.setHotkeyEnabled(want);
			}
		}
	}

	/** Runs on the webview UI thread, triggered by the injected JS focus watcher. */
	private void onEditableChanged(String req) {
		boolean editable = req != null && req.contains("true");

		if (editable != editableFocus) {
			LOGGER.info("[wikievery] Editable focus changed: {}", editable);
		}

		editableFocus = editable;
	}

	private HWND webviewHwnd() {
		return Win32.hwnd(webview.getChildWindow());
	}

	private long currentParentHwnd() {
		// GLFW is not thread-safe: only touch it from the render thread.
		Minecraft mc = Minecraft.getInstance();

		if (mc == null || mc.isSameThread()) {
			long hwnd = minecraftWindowHwnd();

			if (hwnd != 0) {
				return hwnd;
			}
		}

		return parentHwnd;
	}

	/** Call from the client thread only. */
	private long minecraftWindowHwnd() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getWindow() == null) {
			return 0;
		}

		Window window = mc.getWindow();
		long hwnd = GLFWNativeWin32.glfwGetWin32Window(window.handle());

		if (hwnd != 0) {
			return hwnd;
		}

		long fallback = findWindowByPid();

		if (fallback != 0) {
			return fallback;
		}

		return parentHwnd;
	}

	/** Fallback if GLFW cannot provide the HWND: a visible top-level window of this process. */
	private static long findWindowByPid() {
		final long[] result = {0};
		final int pid = Kernel32Native.INSTANCE.GetCurrentProcessId();

		WindowEnumProc proc = (hwndValue, data) -> {
			IntByReference pidRef = new IntByReference();
			User32Native.INSTANCE.GetWindowThreadProcessId(Win32.hwnd(hwndValue), pidRef);

			if (pidRef.getValue() == pid && User32Native.INSTANCE.IsWindowVisible(Win32.hwnd(hwndValue))) {
				char[] buffer = new char[512];
				User32Native.INSTANCE.GetWindowTextW(Win32.hwnd(hwndValue), buffer, buffer.length);
				String title = new String(buffer).trim();

				if (!title.isEmpty()) {
					result[0] = hwndValue;
					return false; // stop enumeration
				}
			}

			return true;
		};

		User32Native.INSTANCE.EnumWindows(proc, null);
		return result[0];
	}

	public void shutdown() {
		state = State.CLOSED;
		pauseRestoreDeadline = 0;
		restorePauseOnFocusLoss();

		WikieveryWebview wv = webview;

		if (wv != null) {
			wv.terminate();
		}

		if (inputShield != null) {
			inputShield.destroy();
		}

		webview = null;
		inputShield = null;
		hotkeyActive = false;
	}
}
