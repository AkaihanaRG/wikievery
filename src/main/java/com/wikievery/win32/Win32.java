package com.wikievery.win32;

import java.util.List;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary.StdCallCallback;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Minimal hand-rolled Win32 bindings (user32 / gdi32 / kernel32) used to embed
 * the webview window into the game window, draw the close button and manage
 * keyboard focus. jna-platform intentionally is not used here.
 */
public final class Win32 {
	private Win32() {
	}

	// ---------- window styles / constants ----------

	public static final int GWL_STYLE = -16;
	public static final int GWL_EXSTYLE = -20;

	public static final int WS_CAPTION = 0x00C00000;
	public static final int WS_THICKFRAME = 0x00040000;
	public static final int WS_MINIMIZEBOX = 0x00020000;
	public static final int WS_MAXIMIZEBOX = 0x00010000;
	public static final int WS_SYSMENU = 0x00080000;
	public static final int WS_CHILD = 0x40000000;
	public static final int WS_VISIBLE = 0x10000000;
	public static final int WS_CLIPSIBLINGS = 0x04000000;

	public static final int WS_EX_NOACTIVATE = 0x08000000;
	public static final int WS_EX_LAYERED = 0x00080000;

	public static final int SWP_NOZORDER = 0x0004;
	public static final int SWP_NOACTIVATE = 0x0010;
	public static final int SWP_SHOWWINDOW = 0x0040;
	public static final int SWP_FRAMECHANGED = 0x0020;
	public static final int SWP_NOMOVE = 0x0002;
	public static final int SWP_NOSIZE = 0x0001;

	public static final int LWA_ALPHA = 0x00000002;

	public static final int SW_HIDE = 0;
	public static final int SW_SHOWNA = 8;

	public static final int WM_PAINT = 0x000F;
	public static final int WM_CLOSE = 0x0010;
	public static final int WM_QUIT = 0x0012;
	public static final int WM_ERASEBKGND = 0x0014;
	public static final int WM_MOUSEACTIVATE = 0x0021;
	public static final int WM_LBUTTONUP = 0x0202;
	public static final int WM_DESTROY = 0x0002;
	public static final int WM_HOTKEY = 0x0312;
	public static final int WM_MOUSEWHEEL = 0x020A;
	public static final int WM_KEYDOWN = 0x0100;
	public static final int WM_USER = 0x0400;

	public static final int MA_NOACTIVATE = 3;

	public static final int MOD_NOREPEAT = 0x4000;

	public static final int VK_K = 0x4B;
	public static final int VK_ESCAPE = 0x1B;

	public static final int PS_SOLID = 0;

	public static final int VK_LBUTTON = 0x01;

	public static final int COLOR_RED = 0x000000FF;
	public static final int COLOR_WHITE = 0x00FFFFFF;

	// ---------- types ----------

	public static class HWND extends PointerType {
		public HWND() {
		}

		public HWND(Pointer p) {
			super(p);
		}
	}

	public static class RECT extends Structure {
		public int left;
		public int top;
		public int right;
		public int bottom;

		@Override
		protected List<String> getFieldOrder() {
			return List.of("left", "top", "right", "bottom");
		}
	}

	public static class POINT extends Structure {
		public int x;
		public int y;

		@Override
		protected List<String> getFieldOrder() {
			return List.of("x", "y");
		}
	}

	public static class PAINTSTRUCT extends Structure {
		public Pointer hdc;
		public boolean fErase;
		public RECT rcPaint;
		public boolean fRestore;
		public boolean fIncUpdate;
		public byte[] rgbReserved = new byte[32];

		@Override
		protected List<String> getFieldOrder() {
			return List.of("hdc", "fErase", "rcPaint", "fRestore", "fIncUpdate", "rgbReserved");
		}
	}

	public static class MSG extends Structure {
		public Pointer hwnd;
		public int message;
		public long wParam;
		public long lParam;
		public int time;
		public POINT pt;

		@Override
		protected List<String> getFieldOrder() {
			return List.of("hwnd", "message", "wParam", "lParam", "time", "pt");
		}
	}

	public static class GUITHREADINFO extends Structure {
		public int cbSize;
		public int flags;
		public Pointer hwndActive;
		public Pointer hwndFocus;
		public Pointer hwndCapture;
		public Pointer hwndMenuOwner;
		public Pointer hwndMoveSize;
		public Pointer hwndCaret;
		public RECT rcCaret;

		@Override
		protected List<String> getFieldOrder() {
			return List.of("cbSize", "flags", "hwndActive", "hwndFocus", "hwndCapture",
					"hwndMenuOwner", "hwndMoveSize", "hwndCaret", "rcCaret");
		}
	}

	public static class WNDCLASSEX extends Structure {
		public int cbSize;
		public int style;
		public WindowProc lpfnWndProc;
		public int cbClsExtra;
		public int cbWndExtra;
		public Pointer hInstance;
		public Pointer hIcon;
		public Pointer hCursor;
		public Pointer hbrBackground;
		public WString lpszMenuName;
		public WString lpszClassName;
		public Pointer hIconSm;

		@Override
		protected List<String> getFieldOrder() {
			return List.of("cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra",
					"hInstance", "hIcon", "hCursor", "hbrBackground", "lpszMenuName", "lpszClassName", "hIconSm");
		}
	}

	public interface WindowProc extends StdCallCallback {
		long callback(long hwnd, int uMsg, long wParam, long lParam);
	}

	public interface WindowEnumProc extends StdCallCallback {
		boolean callback(long hwnd, Pointer data);
	}

	// ---------- libraries ----------

	public interface User32Native extends StdCallLibrary {
		User32Native INSTANCE = Native.load("user32", User32Native.class);

		short RegisterClassExW(WNDCLASSEX wc);

		HWND CreateWindowExW(int exStyle, WString className, WString windowName, int style,
				int x, int y, int width, int height, HWND parent, Pointer menu, Pointer instance, Pointer param);

		long DefWindowProcW(HWND hwnd, int uMsg, long wParam, long lParam);

		Pointer BeginPaint(HWND hwnd, PAINTSTRUCT ps);

		boolean EndPaint(HWND hwnd, PAINTSTRUCT ps);

		boolean GetClientRect(HWND hwnd, RECT rect);

		int FillRect(Pointer hdc, RECT rect, Pointer brush);

		boolean SetWindowPos(HWND hwnd, HWND insertAfter, int x, int y, int cx, int cy, int flags);

		boolean ShowWindow(HWND hwnd, int cmdShow);

		HWND SetParent(HWND child, HWND parent);

		HWND SetFocus(HWND hwnd);

		long GetWindowLongPtrW(HWND hwnd, int index);

		long SetWindowLongPtrW(HWND hwnd, int index, long value);

		boolean DestroyWindow(HWND hwnd);

		int GetMessageW(MSG msg, HWND hwnd, int msgFilterMin, int msgFilterMax);

		boolean TranslateMessage(MSG msg);

		long DispatchMessageW(MSG msg);

		boolean PostMessageW(HWND hwnd, int msg, long wParam, long lParam);

		void PostQuitMessage(int exitCode);

		boolean SetForegroundWindow(HWND hwnd);

		boolean SetLayeredWindowAttributes(HWND hwnd, int colorKey, int alpha, int flags);

		boolean RegisterHotKey(HWND hwnd, int id, int modifiers, int vk);

		boolean UnregisterHotKey(HWND hwnd, int id);

		HWND GetForegroundWindow();

		HWND GetFocus();

		boolean EnumWindows(WindowEnumProc proc, Pointer data);

		boolean EnumChildWindows(HWND parent, WindowEnumProc proc, Pointer data);

		int GetClassNameW(HWND hwnd, char[] buffer, int length);

		int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);

		boolean IsWindowVisible(HWND hwnd);

		boolean IsWindow(HWND hwnd);

		HWND GetParent(HWND hwnd);

		boolean GetGUIThreadInfo(int idThread, GUITHREADINFO info);

		int GetWindowTextW(HWND hwnd, char[] buffer, int length);

		boolean GetCursorPos(POINT point);

		boolean ClientToScreen(HWND hwnd, POINT point);

		short GetAsyncKeyState(int vKey);
	}

	public interface Gdi32Native extends StdCallLibrary {
		Gdi32Native INSTANCE = Native.load("gdi32", Gdi32Native.class);

		Pointer CreateSolidBrush(int color);

		Pointer CreatePen(int style, int width, int color);

		Pointer SelectObject(Pointer hdc, Pointer object);

		boolean MoveToEx(Pointer hdc, int x, int y, Pointer point);

		boolean LineTo(Pointer hdc, int x, int y);

		boolean DeleteObject(Pointer object);
	}

	public interface Kernel32Native extends StdCallLibrary {
		Kernel32Native INSTANCE = Native.load("kernel32", Kernel32Native.class);

		int GetCurrentProcessId();

		int GetLastError();

		Pointer GetModuleHandleW(Pointer name);
	}

	// ---------- helpers ----------

	public static HWND hwnd(long value) {
		return new HWND(new Pointer(value));
	}
}
