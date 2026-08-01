# 从源码编译

本文档面向希望从源码构建「一键闪记」的开发者。编译整个项目只需几步，但需要先装好三个前置依赖。

## 前置要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 项目的 Java/Kotlin 编译目标均为 17 |
| Gradle | 8.9 | 推荐使用仓库自带的 wrapper（见下），无需手动安装 |
| Android SDK | compileSdk 35 / minSdk 26 / targetSdk 35 | 需要 API 35 平台 + Build Tools 34.0.0 |

> Gradle 版本与 AGP（Android Gradle Plugin 8.7.3）有对应关系，请勿随意更换 Gradle 大版本。

### Android SDK 位置配置

Gradle 构建时通过以下任一方式定位 SDK：

1. **`local.properties`**（推荐）：在项目根目录创建文件并写入：

   ```properties
   sdk.dir=/path/to/your/android-sdk
   ```

   `local.properties` 不应提交到版本库（已在 `.gitignore` 中）。

2. **环境变量**：设置 `ANDROID_HOME` 指向 SDK 根目录。

## 编译步骤

### macOS / Linux

```bash
# 进入项目根目录
cd pickup-code-app

# 编译 Debug APK（首次运行会自动下载 Gradle 8.9 与依赖）
./gradlew assembleDebug
```

### Windows

```bat
cd pickup-code-app

REM 若 JDK 不在 PATH 中，先指定（替换为你的实际路径）
set JAVA_HOME=C:\Program Files\Java\jdk-17

gradlew.bat assembleDebug
```

### 项目缺少 gradlew 时（备用方案）

如果克隆的仓库中没有 `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`（目录里只有 `gradle-wrapper.properties`），可先安装任意 Gradle（≥8.9），用它生成 wrapper 后再构建：

```bash
gradle wrapper --gradle-version 8.9 --distribution-type bin
./gradlew assembleDebug
```

## 构建产物

Debug APK 输出到 `app/build/outputs/apk/debug/`。项目开启了 ABI 拆分（仅保留 arm64-v8a 与 armeabi-v7a），因此会生成两个按架构拆分的 APK：

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

将 APK 安装到设备即可使用（Debug 包使用自动签名，可直接 `adb install`，按你的设备架构选择对应文件）：

```bash
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Release 构建签名（可选）

Release 包在未配置签名时按 **unsigned** 构建（便于 CI / 无密钥环境编译）。如需正式签名，在项目根目录创建 `keystore.properties`（已被 `.gitignore` 忽略，不会提交到仓库）：

```properties
STORE_FILE=/path/to/your.keystore
STORE_PASSWORD=存储密码
KEY_ALIAS=你的别名
KEY_PASSWORD=密钥密码
```

也可用同名环境变量（`PICKUP_STORE_FILE` / `PICKUP_STORE_PASSWORD` / `PICKUP_KEY_ALIAS` / `PICKUP_KEY_PASSWORD`）替代。配置齐全后 `./gradlew assembleRelease` 自动使用该签名；同时 Release 构建启用了 R8 混淆与资源收缩（shrinkResources）。

## 常见问题（FAQ）

### 1. 报错 "SDK location not found"

Gradle 找不到 Android SDK。按上文「Android SDK 位置配置」创建 `local.properties` 或设置 `ANDROID_HOME` 后重试。

### 2. 国内网络下载依赖缓慢

Gradle 依赖默认从 `google()` / `mavenCentral()` 下载。可配置阿里云镜像加速，方法是在用户目录 `~/.gradle/init.gradle`（全局，作用于所有项目）：

```groovy
settingsEvaluated { settings ->
    settings.pluginManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        google()
        mavenCentral()
    }
}
```

### 3. 提示 Gradle 版本与 AGP 不兼容

项目锁定 Gradle 8.9（`gradle/wrapper/gradle-wrapper.properties`）。请始终使用 `./gradlew`（wrapper 会自动下载正确版本），不要用系统安装的其他 Gradle 版本直接构建。

### 4. 编译时提示缺少 Build Tools 34.0.0

AGP 8.7 需要 Build Tools 34.0.0。通过 Android SDK Manager 安装：

```bash
sdkmanager "build-tools;34.0.0" "platforms;android-35"
```

## 可选功能配置

以下功能**默认关闭**，需要时在应用内「设置」页填写对应 Key（数据仅保存在本地 DataStore，不上传）：

| 功能 | 入口 | 说明 |
|------|------|------|
| AI 增强识别 | 设置 → AI 识别 | 任意 OpenAI 兼容 API 的 API 地址、API Key、模型名称 |
| 地图验证 | 设置 → 地图验证 | 高德 API Key（可选） |
| 快递100 验证 | 设置 → 快递100验证 | 快递100 API Key |
