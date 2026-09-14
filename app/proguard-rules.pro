# ProGuard/R8 rules for PickupCodeApp

# Keep ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep Room entities
-keep class com.pickupcode.app.data.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# 去掉 release 包里的调试日志（隐私：识别到的码值/OCR 文本/学到的规则曾被打进 logcat）。
# 只裁 v/d/i，保留 w/e —— 警告与错误在用户报障时仍然有用。
# 注：个别含码值的关键点位已另加 BuildConfig.DEBUG 门槛（双保险，防止本规则被误删）。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
