# Android CamSwap (Open Source)
感谢项目 [android_virtual_cam](https://github.com/w2016561536/android_virtual_cam) 为本项目提供的灵感和代码基础。

Android CamSwap 是一个基于 Xposed 框架的虚拟摄像头模块。它能够拦截 Android 系统相机的预览和拍照请求，并将预览画面替换为用户指定的视频。

本项目采用现代化的 Android 开发技术栈（Kotlin, Jetpack Compose）重构，并引入了基于 ContentProvider 的跨进程数据传输机制，兼容高版本 Android (11+) 的文件权限隔离。

旧版 v2.0 及之前版本的实现细节以历史提交为准，当前分支已完成重构与结构调整。

## ✨ 主要功能

*   **全 API 支持**：同时支持 Camera1 (Camera) 和 Camera2 (CameraDevice) API。
*   **视频替换预览** 🎥：将相机预览画面无缝替换为指定的 MP4 视频。
*   **音频替换** 🎤：可选择播放自定义 MP3 音频文件，或与替换视频同步。
*   **通知栏实时控制**：
    *   ⏭ 切换到下一个 / 上一个视频。
    *   🔄 快速调整视频旋转方向（+90° / -90°）。
    *   🎲 随机播放模式。
*   **自动旋转处理**：读取视频元数据中的旋转角度，通过 OpenGL ES 正确渲染，无需手动处理。
*   **跨进程配置同步**：
    *   主路径：ContentProvider IPC（无需目标应用存储权限）。
    *   回退路径：直接文件读取 + Application 冷启动预热，彻底解决冷启动时配置未就绪问题。
*   **目标应用过滤**：可指定模块仅在特定应用中生效。
*   **现代化 UI**：基于 Material Design 3 和 Jetpack Compose 构建的管理界面。
*   **直播推流**：exoplayer + 拓展，支持RTSP/HLS/DASH和RTMP/RTP协议。
  
## 📱 环境要求

*   **Android 版本**：Android 8.0 (API 26) 及以上（推荐 Android 11+）
*   **Root 权限**：必须
*   **Xposed 框架**：推荐使用 [LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk / Riru 版本均可），[注意：v2.5版本及以上不再支持lsp1.0]

## 🚀 安装与使用

### 1. 安装模块
1. 下载最新版本的 Release APK。
   *   推荐优先选择与你设备架构匹配的包：大多数设备使用 `arm64-v8a`。
2. 安装到你的 Android 设备。
3. 在 **LSPosed 管理器**中启用该模块。
4. **作用域勾选**：  
    *   勾选你需要进行替换的**目标应用**（如相机、微信、抖音等）。

5. 重启目标应用的进程。

### 2. 配置素材
1. 打开 **CamSwap** 应用，授予必要的文件读写权限。
2. 在「管理」页面导入你的 MP4 视频素材。
3. 在「设置」页面选择默认视频、配置音频替换、开启通知栏控制等选项。

### 3. 开始使用
打开任意调用相机的应用，预览画面将被替换为你选择的视频。

> **提示**：若视频画面方向不正确，可在通知栏使用旋转按钮微调，或在设置中手动填写旋转偏移角度。

## 📁 配置文件

模块的配置存储在以下路径（JSON 格式）：
```
/sdcard/DCIM/Camera1/cs_config.json
```

目标视频文件也应放置在该目录下。模块重启后会自动读取。


## 🤝 贡献

欢迎提交 Issue 和 Pull Request！


本项目仅供**安全研究、软件测试和教育目的**使用。  
请勿将本项目用于任何非法用途（包括但不限于人脸识别绕过、身份欺诈等）。  
使用者需自行承担因使用本项目而产生的一切法律责任。

## ❤️ 支持

如果本项目对你有帮助，请点 ⭐ Star 支持！

![Star History](https://api.star-history.com/svg?repos=zensu357/Android-CamSwap-OpenSource&type=20260219)

## 📄 许可证

本项目基于 [GPL-3.0 license](LICENSE) 开源。

## GitHub Actions

This repository now includes three independent GitHub Actions workflows:

- `Android Debug Build`: builds a universal debug APK.
- `Android Release Build`: builds a universal release APK.
- `Android ABI Builds`: builds release APKs separately for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

If you want signed release artifacts in GitHub Actions, add these repository secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

If these secrets are not configured, the release workflows will still build and will fall back to debug signing.
