package com.pickupcode.app.util

/**
 * 身份码 / 出库码页面的识别拦截（隐私红线）。
 *
 * 为什么必须拦：身份码 = **取件授权凭证**。而本应用的自动扫描白名单里恰好包含
 * 淘宝 / 菜鸟 / 拼多多 —— 用户在这三个 App 里打开"身份码"页面时，无障碍服务会触发截屏 + OCR，
 * 于是取件凭证就会被**存进数据库、缓存到 cacheDir、并可能进通知/历史列表**。
 *
 * 所以：只要 OCR 文本里出现身份码类字样，就**不识别、不截图、不入库**，并给用户明确提示。
 *
 * 纯函数（不依赖 Android）→ 可 JVM 单测。
 */
object SensitivePageGuard {

    /**
     * 身份码类页面的特征词。
     *
     * 注意**不能**包含「取件码」——那是本应用的正常识别目标；
     * 这里只列"出示给驿站/柜机核验"的凭证类字样。
     */
    private val IDENTITY_PAGE_KEYWORDS = listOf(
        "身份码",       // 淘宝/拼多多「身份码」、菜鸟「身份码」
        "出库码",       // 菜鸟「出库码」（驿站机器扫的那个）
        "身份条形码",
        "我的身份码",
        "取件身份码"
    )

    /** 该文本是否来自身份码 / 出库码页面（命中即应拒绝采集）。 */
    fun isIdentityCodePage(text: String): Boolean {
        if (text.isBlank()) return false
        return IDENTITY_PAGE_KEYWORDS.any { text.contains(it) }
    }
}
