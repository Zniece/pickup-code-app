# 贡献指南

感谢你愿意为「一键闪记」贡献代码！这是一款自动识别截屏/分享图片中的取餐码和取件码、通知提醒并支持一键标记已取的 Android 应用。

无论你发现了 bug、想加新功能，还是想改进文档，都欢迎参与。提交前请先阅读本文。

## 如何开始

1. **Fork 本仓库**（右上角 Fork 按钮），克隆你的副本到本地：

   ```bash
   git clone https://github.com/<你的用户名>/pickup-code-app.git
   cd pickup-code-app
   ```

2. **编译并运行**：按 [docs/BUILDING.md](docs/BUILDING.md) 完成环境配置与首次构建。

3. 熟悉项目结构：核心代码在 `app/src/main/java/com/pickupcode/app/`，按职责分包（`extractor` 提取、`ocr` 识别、`service` 后台服务、`data` 存储等）。

## 提 Issue

提交 Issue 前，先搜索是否已有相同问题。描述问题时请包含：

- **触发方式**：操作路径（磁贴 / 分享 / 截图 / 手动输入）
- **设备与系统版本**：机型 + Android 版本
- **现象与期望**：发生了什么，期望是什么
- **截图/日志**：能复现问题的截图或 logcat 片段（附 logcat 时请隐去取件码等敏感信息）

## 提交 PR

1. **新建分支**：从最新的 `main` 拉出功能分支，命名用短横线小写，如 `fix-ocr-concurrency`、`docs-build-guide`：

   ```bash
   git checkout -b your-branch-name
   ```

2. **提交**：commit message 用中文或英文均可，但需简洁描述改动，例如：

   ```
   修复: OCR 并发调用导致 ML Kit detector busy
   docs: 新增编译指南
   ```

3. **同步上游**：PR 前确保你的分支基于最新的上游代码：

   ```bash
   git remote add upstream https://github.com/zixij644-elaborate/pickup-code-app.git
   git fetch upstream
   git rebase upstream/main
   ```

4. **推送并创建 PR**：push 到你的 fork，然后在 GitHub 上发起 Pull Request，base 选择上游 `main`。PR 描述中说明改动目的、测试方式；若关联 Issue，使用 `Closes #123` 语法。

## 代码规范

- **语言**：Kotlin，遵循 [官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)。
- **注释**：使用中文；重点写**"为什么"**（背景、误报规避、权衡取舍），而不是复述代码做了什么。可参考 `data/CodeHistoryDao.kt`——每条 SQL 都注明了意图与语义，是全项目注释标杆。
- **命名**：明确自解释，避免魔法数字（常量抽取并命名）。
- **兼容性**：minSdk 26，注意 API 分级（`Build.VERSION.SDK_INT` 判断）。
- **改动范围**：保持 PR 小而聚焦，一个 PR 只解决一个问题，便于评审与回滚。

## 测试

> 项目目前暂无测试目录（`app/src/test`、`app/src/androidTest`），欢迎补充。

若新增测试，使用 JUnit（本地）或 Instrumented test（设备/模拟器），确保改动可验证：

```bash
# 本地单元测试（若存在）
./gradlew testDebugUnitTest
```

## 行为准则

- 尊重现有设计：本项目的评分矩阵、多级地址流水线等逻辑经过大量误报调优，改动核心算法前先与维护者讨论。
- 数据隐私：本项目主打纯本地存储，新增网络功能前请确认是否违反该原则，并在 PR 中说明。
