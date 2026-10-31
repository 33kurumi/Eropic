# docs/img

仓库里**唯一**保留的 docs 内容：给 GitHub 首页与 Release 说明用的图片素材。
内部开发文档（架构、ADR、功能索引等）已移到 `dev-docs/`，不进版本库。

## 目录

| 文件 | 用途 | 规格建议 |
| --- | --- | --- |
| `banner.png` | README 顶部横幅（中英两份 README 共用） | 1280×400，深色背景，左侧图标 |
| `screenshots/01.png` | 图库 / Gallery | 竖屏截图，宽 ≥ 720px |
| `screenshots/02.png` | 文件夹 / Folders | 同上 |
| `screenshots/03.png` | 清理 / Clean up | 同上 |
| `screenshots/04.png` | 对比预览 / Compare | 同上 |
| `screenshots/05.png` | 识图 / Image search | 同上 |
| `screenshots/06.png` | 回收站 / Trash | 同上 |

## 怎么换成真机截图

1. 手机上打开 Eropic，截图（电源键 + 音量减），用数据线或网盘传到电脑；
2. 按下表改名为 `01.png` … `06.png`，覆盖 `screenshots/` 下的同名占位图；
3. 建议压一下体积：长边 ≤ 1080、PNG 或 WebP（GitHub 单文件建议 < 1MB）：

```bash
# 需要 Pillow
python -c "from PIL import Image; import glob,os; [print(p) for p in glob.glob('docs/img/screenshots/*.png')]"
```

4. README 与 README.en.md 引用的是固定文件名，**不用改 README**。

## 注意

- 截图会公开在仓库里，**别用含个人隐私、人脸、聊天记录、定位信息的照片**；建议专门建一个放风景/示例图的测试目录来截图。
- banner 里的图标来自 `app/src/main/res/drawable-nodpi/ic_launcher_user.png`，换图标后可重新生成（脚本没入库，需要时让开发者重跑）。
