# 微信收款小票识别助手 V0.3.0

目标设备：nubia Z30 Pro / Android 12 / MyOS 12；图片目录 `Pictures/WeiXin/`；目标打印应用 Thermer（“恩叶”58mm 蓝牙热敏打印机）。

## 本版行为与安全边界

- 监测新保存的 `mmexport*.jpg/jpeg` 图片。
- ML Kit QR 检测 + 中文 OCR + 小票字段/版式启发式识别。
- 疑似小票及需复核图片进入“待人工确认”列表。
- 用户可预览原图、忽略，或点击“确认并选择打印应用”。
- 确认后只通过 Android 分享面板把图片交给用户选择的应用；不会直接连接蓝牙，也不会自动执行打印。
- 分享面板启动成功记录为 `SHARE_OPENED`，仍保留在待处理列表以便恢复；这不代表已选择打印应用或打印成功。再次分享前请先确认是否已经打印，避免重复出纸。
- 本地数据库不保存 QR 载荷或完整 OCR 原文。

## 工程配置

- Java；minSdk 26；compileSdk 34；targetSdk 33
- Android Gradle Plugin 8.2.2；Gradle 8.2；JDK 17
- ML Kit barcode-scanning 17.3.0、text-recognition-chinese 16.0.1
- SQLite schema v4：增加人工复核状态；原有 v3 数据库升级时将 `PROBABLE_RECEIPT` / `REVIEW` 迁移为待确认。

## 云端构建 APK（推荐）

仓库已加入 `.github/workflows/android-apk.yml`。将整个项目目录上传到你自己的 GitHub 仓库（默认分支 `main` 或 `master`），打开仓库的 **Actions**，运行 **Build Android APK**。工作流会安装 JDK 17、Gradle 8.2、Android SDK Platform 34 / Build Tools 34.0.0，先运行单元测试，再构建 Debug APK，并将 `app-debug.apk` 上传为 Actions Artifact（保留 14 天）。

也可以在有 Android Studio 的电脑打开本目录，使用 JDK 17 和 SDK Platform 34，执行：

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

## 当前验证边界

本工作容器没有 Gradle、Android SDK、`android.jar`，且不能解析外网域名，因此本次无法在这里执行 Android 全项目编译，也没有生成 APK。必须以 GitHub Actions 的真实构建结果为准；本地/云端构建成功也不等于已在 nubia Z30 Pro 实机验证。

## 首次使用注意

首次启动会对旧图片建立基线，不会处理旧图。之后保存新的微信小票图片才会触发识别。Android 12 需要授予图片读取权限；MyOS 可能还需允许应用后台运行/不受电池优化限制。二维码是当前识别流程的前置条件，无二维码小票可能漏判；真实小票样本尚未验证该假设。
