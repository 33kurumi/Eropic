<div align="center">

<img src="docs/img/banner.png" alt="Eropic" width="100%">

# Eropic

**本地优先的个人图片与存储管理工具 · Android 12+**

手机里 120GB 照片，系统相册看不全、管不了、清不掉？Eropic 只做三件事：**看得全、找得准、动得了真实文件**。

[![Android](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/github/v/release/33kurumi/Eropic?label=release&color=6C5CE7)](https://github.com/33kurumi/Eropic/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**简体中文** · [English](README.en.md)

</div>

---

## 为什么会有 Eropic

手机存储报警，一看 `Pictures` 占了 120GB。想清理，却发现：

- **系统相册看不全** —— 只显示被媒体库收录的目录，深层文件夹、散落的截图根本翻不到；
- **第三方清理 App 不敢用** —— 要么要云同步、要账号，要么"智能清理"直接替你删；
- **想要的是"看得见的真相"** —— 哪几张是重复的、哪几张是截图糊图、哪个目录最占空间，先把数据摆出来，**删不删由我决定**。

所以 Eropic 的定位很克制：**它只负责检索与展示，删除决策永远交给人**；但它一旦动手，就是对**真实存储位置**动（真实移动、真实重命名、真实进回收站），而不是只在应用里改个数据库。

## 核心特性

### 一、看得全：索引与浏览

- **自建索引**：以系统 MediaStore 为权威源建本地索引（Room 可重建缓存），增量同步 + 周期对账，10 万张也能翻得动。
- **文件夹即相册**：按真实目录结构浏览；目录里显示**图片张数与总体积**（可开关）；在任意中层文件夹一键「**包含子文件夹**」，把整个子树的图片聚合到这一层。
- **文件夹网格模式**：封面卡 + 张数/体积，点卡下钻；支持置顶、换封面、**真实重命名目录**、整目录移入回收站。
- **排序与缩放**：按拍摄时间 / 文件名 / 大小 / 分辨率 / 格式排序；网格**双指捏合** 2~8 列；日期标题可折叠，支持「全部展开 / 全部收起」。
- **全屏查看器**：双指缩放、双击放大、左右翻页、幻灯片、设为壁纸、用其他应用打开、查看原始 EXIF（机型/镜头/光圈/快门/ISO/GPS）。
- **搜索**：空格＝同时包含、双引号＝整段、减号＝排除（`猫 -截图`），带搜索历史与保存筛选。

### 二、找得准：重复 / 相似 / 该清理的

- **精确重复**：文件内容 SHA-256 完全一致 —— 名字不同也能抓出来。
- **相似图片**：感知哈希（dHash + pHash）+ 分桶倒排候选 + 颜色布局/直方图加权精排，支持严格 / 标准 / 宽松三档。
- **八类候选**：重复、相似、大文件、久远、截图、模糊、连拍、大图（按分辨率）。
- **质量评分**：组内逐张算「总像素 / 格式 / bpp / 时间 / 文件名 / 清晰度」得分，**给出建议保留哪张**，并把"为什么这么选"逐条写在图上。
- **对比预览**：组内多张左右滑动、参数对照、相似度与评分依据；支持「保留本组」标记，重扫不再打扰。
- **组合筛选**：按大小 / 分辨率 / 格式 / 时间 / 目录组合筛选，支持保存为筛选条件。

> ⚠️ **Eropic 不自动删除任何东西。** 没有"一键智能清理"，没有后台偷偷删；所有勾选只产生候选列表，执行由你按下。

### 三、动得了：真实文件操作与回收站

- **真实移动 / 复制 / 重命名**：作用于真实存储路径（同卷 `rename`，跨卷复制后删除），改完自动通知媒体库，相册里立刻生效。
- **批量重命名**：模板 `{date}_{n}`、`{name}`、`{ext}`、`{time}`，冲突项预览里标红。
- **回收站**：删除先移入 `卷根/.EropicTrash`（默认保留 30 天，可选 7/60 天/永久），支持一键恢复、永久删除、5 秒撤销；位置可见可改。
- **批处理可控**：长任务有进度条，可暂停 / 继续 / 取消；结束进「操作结果」页（成功/失败原因/字节数），**操作历史支持逐条撤销**。
- **空目录清理**：扫出空文件夹、批量删除；存储总览按格式 / 年份 / 目录看占用。

### 四、识图：本地 + 联网

- **本地相似图**：选中一张，按感知哈希距离找库里最像的（颜色画像精排、支持关键词过滤）；「找同角色」用标签投票给出候选。
- **联网以图搜图**：Google Lens / 百度识图 / SauceNAO / ascii2d / IQDB / onegai.moe / Lenso.ai 七家。**点下去才上传你选中的那一张**，结果以列表展示。

## 截图

<div align="center">
<img src="docs/img/screenshots/01.png" width="30%"> <img src="docs/img/screenshots/02.png" width="30%"> <img src="docs/img/screenshots/03.png" width="30%">
<img src="docs/img/screenshots/04.png" width="30%"> <img src="docs/img/screenshots/05.png" width="30%"> <img src="docs/img/screenshots/06.png" width="30%">
</div>

> 截图位已就绪（对应 `docs/img/screenshots/01.png` ~ `06.png`），用真机截图覆盖同名文件即可，README 不用改。

## 下载与安装

到 [Releases](https://github.com/33kurumi/Eropic/releases) 下载 `Eropic-release-<版本>-<构建时间>.apk`。

```bash
# 手机开 USB 调试后
adb install -r Eropic-release-*.apk
```

**首次启动需要两个权限**（应用内都会有引导）：

1. **照片和视频** → 选「允许全部」（只授权部分照片会让图库看起来"漏图"，页面顶部会提示补授权）；
2. **所有文件访问权限**（写操作才需要）→ 设置里点「去授权」，用于真实移动/重命名/回收站。

> 想更省事的清理路线：授予权限 → 图库点「开始建立索引」→ 清理页看重复/相似分组 → 勾选 → 移入回收站 → 确认没问题后再永久删除。
>
> 从早期 **debug 签名**的旧包换到正式签名版，必须**先卸载再安装**（`adb uninstall io.github.kurumi33.eropic`）；卸载会清掉应用内索引与设置，**照片和回收站目录里的文件不受影响**。

## 从源码构建

```bash
git clone https://github.com/33kurumi/Eropic.git
cd Eropic

# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew
./gradlew.bat :app:assembleRelease      # 产物在 app/build/outputs/apk/release/
```

环境要求：JDK 17、Android SDK（compileSdk 36 / build-tools 36）、Gradle 8.13（随仓库 wrapper 提供）。

签名：仓库内自带 `keystore/eropic-release.jks`（别名 `eropic`，RSA 2048，30 年有效），口令写在 `keystore.properties`（不入库，需要你自备；没有则该文件缺省时回退 debug 签名）。换机器时把这两个文件拷过去即可继续用同一签名升级。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 · Jetpack Compose（Material 3）· 自定义主题与动态取色 |
| 数据 | MediaStore（权威源）+ Room（可重建索引，DB v7）· Paging 3 · DataStore |
| 图像 | Coil 3 · 自建缩略图磁盘 LRU（WebP）· dHash/pHash/SHA-256 |
| 后台 | WorkManager（周期对账、缩略图补齐）· MediaStore 变更观察（1.5s 防抖） |
| 依赖注入 | 手写 `AppContainer`（不引第三方 DI） |
| 最低版本 | Android 12（API 31），targetSdk 36 |

架构一句话：**MediaStore 是唯一权威源，Room 只是可随时重建的缓存**；写操作走「先备份到回收站再改索引」，任何一步失败都能回滚。

## 隐私

- **不联网也能用**：浏览、查重、清理、文件操作全部本地完成。
- **唯一的联网**：你主动点「联网识图」时，把**你选中的那一张图**传给对应搜图服务（部分服务需先传到临时图床，1 小时后自动失效）。
- **无账号、无统计、无埋点、无云同步**；日志只存在本机，需要时你自己打包分享。

## 已知边界

- 搜索是 `LIKE '%kw%'` 全表扫描（十万级可接受，未上 FTS）；
- 视频只做浏览统计，**不做查重**（当前版本只针对图片）；
- 回收站是应用自己的 `.EropicTrash` 目录，**不复刻系统回收站**；
- 联网识图多数走网页接口，服务方改版可能失效；
- 大库首次索引需要几分钟（可中断，已算结果不丢）。

## 版本与更新

版本策略：`1.1.x` 递增，只有成组新功能才进 1.2；**只分发 release 包**。
应用内「设置 → 关于与更新 → 检查更新」会读 GitHub Release 的最新版本并给出下载/安装。

## 反馈

- 提 Issue：[github.com/33kurumi/Eropic/issues](https://github.com/33kurumi/Eropic/issues)
- 邮箱：Aemeath33@163.com

## 许可证

[MIT](LICENSE) © 33kurumi

致谢这些开源项目带来的思路与参考：[Aves](https://github.com/deckerst/aves)、[Immich](https://github.com/immich-app/immich)、[PhotoPrism](https://github.com/photoprism/photoprism)、[Fossify Gallery](https://github.com/FossifyOrg/Gallery)、[PicImageSearch](https://github.com/kitUIN/PicImageSearch)。
