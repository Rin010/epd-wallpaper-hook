# EpdWallpaperManager

[![Build APK](https://github.com/Rin010/epd-wallpaper-hook/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Rin010/epd-wallpaper-hook/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Rin010/epd-wallpaper-hook)](https://github.com/Rin010/epd-wallpaper-hook/releases/latest)

面向小米多看电纸书 Pro 的锁屏、关机和重启画面管理器。项目由 Android 管理程序和 LSPosed 模块组成，通过精确 Hook 系统框架，在保留系统原画面作为安全回退的同时，支持导入、预览、选择和轮换自定义图片。

> [!IMPORTANT]
> 当前版本仅在小米多看电纸书 Pro、Android 8.1、`1404×1872` 屏幕上完成实机验证。其他型号、固件版本或分辨率可能需要重新定位 Hook 点和日期布局。

## 功能

- 统一管理系统自带图片和用户导入图片。
- 锁屏图片支持多选轮换，关机和重启画面分别单选。
- 每张图片可独立启用或禁用锁屏用途，并选择黑色日期、白色日期或关闭日期。
- 用户图片支持删除；系统图片不可删除，但可以禁用或用于其他画面。
- 单击缩略图可全屏预览，预览中的日期位置、字号、粗细和描边与系统渲染逻辑一致。
- 双列图库和分页设计适配低刷新率墨水屏，支持左右滑动翻页。
- 提供总开关以及锁屏、关机、重启分类开关；关闭分类后隐藏对应控件。
- 每天午夜附近自动重建一次已锁定画面，使日期和轮换图片更新；采用宽松时间窗口，每天最多唤醒一次 CPU，不点亮屏幕或背光。设备当时正在使用则跳过，下一次正常锁屏会自然更新。
- 支持从旧版 `/sdcard/Wallpaper/` 图库迁移，迁移不会删除源文件。
- 配置异常、图片缺失或解码失败时自动回退到系统原画面。

## 使用教程

### 1. 准备 ROOT 和 LSPosed

让你的AI Harness参考教程[https://ximin.top/blog/140.html](https://ximin.top/blog/140.html)获取ROOT权限,并安装LSPosed。

ROOT、刷写启动镜像和修改系统环境均有变砖风险。该教程发布较早，作者也注明部分较新固件可能不再适用；操作前请核对设备型号与固件版本，并备份原始启动镜像和重要分区。

### 2. 安装模块

1. 从 [Releases](https://github.com/Rin010/epd-wallpaper-hook/releases/latest) 下载最新 APK 并安装。
2. 在 LSPosed 中启用“电纸书画面管理”。
3. 作用域只选择“系统框架”。
4. 重启电纸书，使 Hook 在 `system_server` 中生效。
5. 打开管理程序，首次同步时在 Magisk 中永久授予 ROOT 权限。

升级 APK 后，如果版本包含 Hook 逻辑变更，也需要重启设备。仅管理界面发生变化时通常无需重启。

### 3. 导入和配置图片

1. 点击“导入图片”，从系统文件选择器选择一张或多张图片。
2. 图片尺寸与屏幕相同时直接导入；尺寸不同时，根据弹窗说明选择裁切、填充、等比缩放或点对点显示方式。
3. 等待“导入中”提示消失。处理过程包括缩放、16 级灰度转换、PNG 压缩和系统目录同步，在电纸书上通常需要数秒。
4. 为图片勾选“锁屏”，或将其设为关机、重启画面。新导入图片默认启用锁屏用途，但默认关闭日期。
5. 日期按钮按“黑 → 白 → 关”循环切换；未勾选锁屏时日期按钮和预览日期自动隐藏。
6. 点击“同步到系统”可重新同步全部图片和配置。

系统自带的锁屏、关机和重启图片都可以跨用途选择。例如，系统关机图也可以加入锁屏轮换。

## 图片处理

导入程序读取 EXIF 方向，并以设备物理屏幕 `1404×1872` 为目标：

- 图片相对屏幕更宽：可左右裁切铺满，或完整显示并在上下填充黑色/白色。
- 图片相对屏幕更高：可上下裁切铺满，或完整显示并在左右填充黑色/白色。
- 宽高比一致：等比放大或缩小到屏幕，不做非等比变形。
- 点对点模式：保持原始像素；超出方向居中裁切，不足方向居中填充黑色或白色。

保存前会转换为屏幕适用的 4-bit 索引 PNG（16 级灰度），减少存储占用和运行时处理开销。

## 存储与隐私

| 内容 | 位置 | 用途 |
| --- | --- | --- |
| 管理副本 | 应用私有目录 | 图库管理和预览 |
| 运行时图片 | `/data/system/epd-wallpaper/images/` | 供系统进程直接读取 |
| 运行时配置 | `/data/system/epd-wallpaper/config.properties` | 保存开关、用途和日期模式 |

管理程序仅在导入、删除、配置变更或手动同步时使用 ROOT，将文件原子同步到系统目录，并设置 `system:system`、`0640` 和正确的 SELinux 标签。Hook 在 `system_server` 内直接读取系统目录，不通过 Binder 或 ContentProvider 传递图片。

应用不申请网络权限，不包含统计或遥测功能；`android:allowBackup` 也已关闭。用户选择的图片不会由本项目上传到网络。

## 常见问题

### 安装后没有生效

- 确认 LSPosed 中已启用模块，作用域只包含“系统框架”。
- 修改作用域或更新 Hook 后重启设备。
- 检查总开关和对应分类开关是否开启。
- 打开管理程序并执行一次“同步到系统”，确认 Magisk 已授予 ROOT。

### 自定义图片短暂出现后又恢复系统图片

确认图片已成功同步到 `/data/system/epd-wallpaper/images/`，并检查 Magisk 授权、文件权限和 SELinux 标签。读取失败时模块会有意回退到系统图片，避免锁屏或关机界面不可用。

### 导入图片需要几秒

这是预期行为。电纸书需要完成整张图片的解码、适配、逐像素灰度量化、PNG 压缩、强制落盘和 ROOT 同步。处理期间会显示“导入中”。

## 从源码构建

构建环境：

- JDK 8
- Gradle 6.7.1
- Android SDK Platform 27
- Android Build Tools 30.0.2

```bash
gradle --no-daemon :app:assembleRelease
```

生成的 APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

## CI 与发布

- 推送到 `main`、提交 Pull Request 或手动运行时，[Build APK](https://github.com/Rin010/epd-wallpaper-hook/actions/workflows/build.yml) 会构建 Release APK，并保留构建产物 14 天。
- 推送 `v*` 标签时，[Release APK](https://github.com/Rin010/epd-wallpaper-hook/actions/workflows/release.yml) 会校验标签与 `app/build.gradle` 中的 `versionName`、构建并验证签名，然后创建 GitHub Release。

发布 `1.5.0` 的示例：

```bash
git tag -a v1.5.0 -m "EpdWallpaperManager v1.5.0"
git push origin v1.5.0
```

## 项目结构

```text
app/
├── libs/                         # Xposed API 编译依赖
└── src/main/
    ├── assets/                   # Xposed 入口和默认作用域
    ├── java/top/ximin/epdwallpaper/
    │   ├── EpdWallpaperHook.java       # 系统框架 Hook
    │   ├── DailyLockRefresh.java       # 低功耗午夜锁屏刷新
    │   ├── MainActivity.java           # 管理界面和导入流程
    │   ├── ImageImporter.java          # 分辨率与方向适配
    │   ├── IndexedPngEncoder.java      # 4-bit 灰度 PNG 编码
    │   ├── SystemWallpaperStore.java   # ROOT 同步与权限处理
    │   └── WallpaperConfig.java        # 配置和迁移
    └── res/
```

## 参与贡献

欢迎提交 Issue 和 Pull Request。报告兼容性或 Hook 问题时，请附上设备型号、固件版本、Android 版本、LSPosed 版本、复现步骤和相关日志；请先删除日志中的账号、序列号和本地路径等隐私信息。

## 致谢

- [习泯小点：小米多看电纸书系列 ROOT 及息屏内容修改](https://ximin.top/blog/140.html)
- [Magisk](https://github.com/topjohnwu/Magisk)
- [LSPosed](https://github.com/LSPosed/LSPosed)
