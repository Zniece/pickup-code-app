# 一键闪记 — Android 截屏+OCR 取餐码/取件码识别 App

## 功能

- 从控制面板（Quick Settings Tile）一键触发截屏
- ML Kit 离线 OCR 识别屏幕文字
- 正则+位置加权策略自动提取取餐码/取件码
- 高优先级通知，锁屏可见
- 历史记录，标记"已取"

## 技术栈

| 模块 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| OCR | Google ML Kit Text Recognition (中文·离线) |
| 截屏 | MediaProjection API |
| 触发方式 | Quick Settings Tile |
| 通知 | Ongoing Notification (锁屏可见) |
| 存储 | Room (本地SQLite) |

## 项目结构

```
pickup-code-app/
├── app/src/main/java/com/pickupcode/app/
│   ├── App.kt                          # Application
│   ├── MainActivity.kt                 # 主界面 + Compose UI
│   ├── data/
│   │   ├── CodeHistory.kt              # Room Entity
│   │   └── CodeHistoryDao.kt           # DAO + Database
│   ├── ocr/
│   │   └── OCREngine.kt                # ML Kit 封装
│   ├── extractor/
│   │   └── CodeExtractor.kt            # 正则匹配 + 加权策略
│   ├── notification/
│   │   ├── CodeNotificationManager.kt  # 通知管理
│   │   └── DoneReceiver.kt            # "已取完"按钮
│   ├── service/
│   │   ├── ScreenshotService.kt        # 前台服务·截屏+OCR
│   │   └── PickupCodeTileService.kt    # Quick Settings Tile
│   └── ui/theme/
│       ├── Color.kt
│       └── Theme.kt
```

## 构建

用 Android Studio 打开 `pickup-code-app/` 目录即可。

## 权限

| 权限 | 用途 |
|------|------|
| FOREGROUND_SERVICE | 后台执行截屏+OCR |
| SYSTEM_ALERT_WINDOW | 浮动提示（可选） |
| POST_NOTIFICATIONS | 锁屏通知 |
| MediaProjection | 截屏 |

## 支持的取餐码/取件码格式

详见 `research/pickup-code-formats.md`
