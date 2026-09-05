package com.wikievery.webview;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA binding for the C webview library (WebView2 backend on Windows).
 * Trimmed from the dev.webview Java port to only the calls this mod needs.
 */
public interface WebviewNative extends Library {
	int WV_HINT_NONE = 0;
	int WV_HINT_MIN = 1;
	int WV_HINT_MAX = 2;
	int WV_HINT_FIXED = 3;

	interface DispatchCallback extends Callback {
		void callback(long pointer, long arg);
	}

	interface BindCallback extends Callback {
		/**
		 * @param seq request id
		 * @param req the javascript arguments as a json array string
		 * @param arg unused
		 */
		void callback(long seq, String req, long arg);
	}

	long webview_create(boolean debug, PointerByReference window);

	long webview_get_window(long pointer);

	void webview_navigate(long pointer, String url);

	void webview_set_title(long pointer, String title);

	void webview_set_size(long pointer, int width, int height, int hint);

	void webview_run(long pointer);

	void webview_destroy(long pointer);

	void webview_terminate(long pointer);

	void webview_eval(long pointer, String js);

	void webview_init(long pointer, String js);

	void webview_bind(long pointer, String name, BindCallback callback, long arg);

	void webview_dispatch(long pointer, DispatchCallback callback, long arg);
}
