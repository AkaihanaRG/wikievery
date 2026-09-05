![image](https://github.com/AkaihanaRG/wikievery/blob/master/D510CC162A40185BB40AEC27DA5096FB.png)

## 万象通揽 wikievery

> **中文** · [English](#English)

万象通揽 wikievery 是一个基于 WebView 的便携式 Wiki 查阅模组，让你无需再切出游戏、打开浏览器去查资料。只需按下快捷键，即可随时呼出 Wiki 页面，支持搜索、查询、复制和粘贴等操作。默认快捷键为 K，你可以在设置中自由更改。

我做这个模组，是因为在高版本的 Fabric 环境下，似乎找不到这样轻便的 Wiki 查看工具。我不想仅仅为了查 Wiki 就去装一个动辄几百 MB 的内置浏览器模组。而得益于 WebView 出色的轻量化特性，本模组体积仅有惊人的 310 KB，却几乎能实现 Edge 浏览器的全部功能——包括播放音视频媒体，它本质上就是一个内嵌的 Edge。而wikievery这个名字意思就是随时随地都可以wiki。

目前模组仍处于早期开发阶段，可能存在一些兼容性问题或未知 Bug。如果 Star 数量足够多，我会持续维护下去。

基于 [webview/webview_java](https://github.com/webview/webview_java)（C 语言
[webview](https://github.com/webview/webview) 库的 JNA 移植，Windows 后端为
WebView2）。

### 功能

- 按 **K** 在游戏窗口中央显示一个固定的 960×540 网页；再按 **K** 或按 **ESC** 关闭。
- 浏览器是**嵌入游戏窗口内部的原生子窗口**，不是独立桌面窗口；固定在中央、不可缩放，
  拖动/缩放游戏窗口时自动重新居中。
- 游戏自身绘制 **50% 黑色滤镜**和**边框贴图**（1104×684，浏览器四周各留 72px 边框），
  网页悬浮在"世界之上"。
- **关闭区域**：点击边框左上角 72×72 的区域即可关闭。
- 音效：开/关时播放 2 倍音量的翻页音效；通过关闭按钮关闭时会额外播放原版按钮点击音效。
- 页面可滚动、可点链接、可输入（打字时除保留键 K/ESC 外均正常，K/ESC 用于关闭）。
- 全部配置项见 `config/wikievery.properties`。

### 实现原理（简述）

- 通过 `webview/webview_java` 的 JNA 绑定创建 WebView2 窗口并**重新挂到 Minecraft
  的原生窗口（HWND）之下**。由于 MC 26.2 是"不混淆"版本，整个开发环境走 Loom 的
  no-remap 管线、使用官方命名。
- 黑色滤镜与边框贴图通过 Fabric HUD API（`HudElementRegistry` + GUI 渲染态）绘制，
  位于"游戏画面层"与"原生浏览器窗口层"之间。
- 输入处理是几套原生 Win32 手法的组合：
  - 一个透明的**输入盾牌**子窗口：吞掉页面区域以外的点击/滚轮，并持有键盘焦点，
    使游戏收不到任何输入；
  - **全局热键**（`RegisterHotKey`）：即使焦点被浏览器抢走，K/ESC 也能可靠关闭；
  - 每 tick 回收焦点，并在打开期间抑制"失焦自动暂停"，避免原版暂停界面卡死。

### 运行需求

- Minecraft **26.2**、**Fabric Loader ≥ 0.19.5**、**Fabric API**（`0.159.0+26.2`
  测试通过）
- **Java 25**（MC 26.2 必需）
- Windows + **Microsoft Edge WebView2 Evergreen 运行时**（装过 Edge 一般都有）

### 安装

1. 从 [Releases](../../releases) 下载 `wikievery-<版本>.jar`。
2. 放入 `mods/` 目录（需同时安装 Fabric API）。
3. 进入游戏，在世界里按 **K** 即可打开网页。

### 配置

首次启动会在 `config/wikievery.properties` 生成配置文件：

```properties
url=https://zh.minecraft.wiki/
width=960
height=540
debug=false
```

改完需重启游戏。（`debug=true` 会通过右键菜单启用 WebView2 开发者工具。）

### 从源码构建

- 需要 JDK 25。
- 执行 `./gradlew build`（Windows 用 `gradlew.bat build`），产物在 `build/libs/`。
- 项目自带**局域网联机调试环境**（先 `start-server.bat` 再 `start-client.bat`），
  客户端会自动以普通（非主机）玩家身份加入 `localhost:25565`。

### 鸣谢与许可

- [webview/webview_java](https://github.com/webview/webview_java) 与 C 项目
  [webview](https://github.com/webview/webview)（MIT）：JNA 绑定与随附的原生库
  （`webview.dll`、`WebView2Loader.dll`）。
- 边框贴图素材由项目作者绘制。
- 本模组以 MIT 许可发布（见 `LICENSE`）。

---
## English

# Wikievery

This is a portable WebView-based Wiki viewer mod that eliminates the need to alt-tab out of the game and open a browser just to look up information. With a single keystroke, you can instantly bring up the Wiki page, and it supports searching, querying, copying, and pasting. The default hotkey is K, and you can change it in the settings.

I created this mod because I couldn't find a lightweight Wiki viewer for modern Fabric versions—and I didn't want to install a bulky built‑in browser mod (often hundreds of MB) solely for Wiki access. Thanks to WebView's excellent lightweight design, this mod takes up only 310 KB, yet it delivers nearly all the functionality of Edge, including media playback. In essence, it's Edge embedded in your game.The name "wikievery" means "let everybody "wiki" everything everyday and everywhere!"

The mod is still in early development, so there may be some compatibility issues or unknown bugs. If it gets enough stars, I'll continue to maintain it.

Built on top of [webview/webview_java](https://github.com/webview/webview_java), a
JNA binding for the C [webview](https://github.com/webview/webview) library
(WebView2 backend on Windows).

---

## Features

- Press **K** to summon a fixed 960×540 web page (WebView2) centered in the game window;
  press **K** or **ESC** to close it.
- The browser is an embedded native window *inside* the Minecraft window — not a
  separate desktop window. It is centered, fixed in size, and re-centers
  automatically when the game window is resized.
- A **50 % dim filter** plus a **frame texture** (1104×684, 72 px border around the
  browser) are drawn by the game itself, so the page floats "above the world".
- **Close hit-area**: click the 72×72 top-left corner of the frame to close.
- Sound feedback: a double-volume page-turn on open/close, and the vanilla button
  click when closing via the button.
- The page scrolls, links work, and text fields are usable (typing works except the
  reserved `K`/`ESC` keys, which close the overlay).
- Fully configurable via `config/wikievery.properties`.

## How it works (short version)

- A small window (WebView2) is created and **re-parented into the Minecraft HWND** via
  the `webview/webview_java` JNA binding. Because MC 26.2 is unobfuscated, the
  environment runs with Loom's no-remap pipeline and official names.
- The dim filter and frame are rendered through Fabric's HUD API
  (`HudElementRegistry`, GUI render state), which sits between the game world and the
  native browser window.
- Input handling is a mix of native Win32 techniques:
  - a transparent **input-shield** child window blocks clicks/wheel outside the page
    and holds keyboard focus so the game receives nothing;
  - **global hotkeys** (`RegisterHotKey`) make K/ESC close reliably even when the
    browser stole focus;
  - focus is reclaimed every tick and "pause on lost focus" is suppressed while the
    overlay is open, preventing the vanilla pause screen from being stuck.

## Requirements

- Minecraft **26.2**, **Fabric Loader ≥ 0.19.5**, **Fabric API** (tested with
  `0.159.0+26.2`)
- **Java 25** runtime (MC 26.2 requires it)
- Windows + **Microsoft Edge WebView2 Evergreen Runtime** (usually already installed
  with Edge)

## Installation

1. Download `wikievery-<version>.jar` from the [Releases](../../releases).
2. Put it into your `mods/` folder (Fabric API must also be installed).
3. Launch the game; press **K** in a world to open the page.

## Configuration

On first launch a file is created at `config/wikievery.properties`:

```properties
url=https://zh.minecraft.wiki/
width=960
height=540
debug=false
```

Restart the game after editing. (`debug=true` enables WebView2 devtools via the
right-click menu.)

## Building from source

- JDK 25
- Run `./gradlew build` (Windows: `gradlew.bat build`). The mod jar lands in
  `build/libs/`.
- For development you can run the bundled **LAN test setup** (`start-server.bat`
  then `start-client.bat`); the client auto-joins `localhost:25565` as a regular
  (non-host) player.

## Credits & license

- [webview/webview_java](https://github.com/webview/webview_java) and the C
  [webview](https://github.com/webview/webview) project (MIT) — JNA binding and the
  bundled native libraries (`webview.dll`, `WebView2Loader.dll`).
- The frame texture asset is authored by the project owner.
- This mod itself is released under the MIT License (see `LICENSE`).
