package com.pickupcode.app.ui.components

import androidx.annotation.DrawableRes
import com.pickupcode.app.R

/**
 * 品牌 logo 映射：把识别来源（品牌名/分享来源 App）映射到内置的品牌 logo 图片。
 *
 * 匹配规则（matchKey，纯函数可单测）：
 * 1. 拼接 source 与 shareSourceName 后规范化（去掉 快递/物流/速递/驿站/旗舰店/超市/门店 等后缀）；
 * 2. 按品牌关键词 contains 匹配，长关键词优先（如「美团外卖」先于「美团」）；
 * 3. 未收录的品牌返回 null，UI 层回退到「类型徽标」。
 *
 * 资源来源：各品牌官网/官方资产抓取并归一化为 128×128 透明 PNG（res/drawable-nodpi/logo_*.png）。
 * 版权说明：品牌 logo 归各品牌所有，此处仅作识别用途（nominative use）。
 */
object BrandLogo {

    private val LOGO_BY_KEY: Map<String, Int> = mapOf(
        "yunda" to R.drawable.logo_yunda,
        "zto" to R.drawable.logo_zto,
        "yto" to R.drawable.logo_yto,
        "sto" to R.drawable.logo_sto,
        "sf" to R.drawable.logo_sf,
        "jt" to R.drawable.logo_jt,
        "jd" to R.drawable.logo_jd,
        "cainiao" to R.drawable.logo_cainiao,
        "deppon" to R.drawable.logo_deppon,
        "luckin" to R.drawable.logo_luckin,
        "mixue" to R.drawable.logo_mixue,
        "mcd" to R.drawable.logo_mcd,
        "kfc" to R.drawable.logo_kfc,
        "sbux" to R.drawable.logo_sbux,
        "ph" to R.drawable.logo_ph,
        "domino" to R.drawable.logo_domino,
        "haidilao" to R.drawable.logo_haidilao,
        "juewei" to R.drawable.logo_juewei,
        "zhy" to R.drawable.logo_zhy,
        "holiland" to R.drawable.logo_holiland,
        "meituan" to R.drawable.logo_meituan,
        "eleme" to R.drawable.logo_eleme,
        "taobao" to R.drawable.logo_taobao
    )

    /** 品牌关键词 → key。顺序无关（匹配时按长度降序）。 */
    private val KEYWORDS: List<Pair<String, String>> = listOf(
        "美团外卖" to "meituan", "美团" to "meituan",
        "饿了么" to "eleme",
        "淘宝" to "taobao",
        "京东物流" to "jd", "京东快递" to "jd", "京东" to "jd",
        "韵达" to "yunda", "中通" to "zto", "圆通" to "yto", "申通" to "sto",
        "顺丰" to "sf", "极兔" to "jt", "菜鸟" to "cainiao", "德邦" to "deppon",
        "瑞幸" to "luckin", "蜜雪冰城" to "mixue", "麦当劳" to "mcd", "肯德基" to "kfc",
        "星巴克" to "sbux", "必胜客" to "ph", "达美乐" to "domino",
        "海底捞" to "haidilao", "绝味" to "juewei", "周黑鸭" to "zhy", "好利来" to "holiland"
    )

    /** 规范化：去首尾空白 + 去掉常见品牌后缀（品牌名识别出来时常带 快递/驿站/旗舰店 等）。 */
    private fun normalize(s: String): String {
        var t = s.trim()
        for (suffix in listOf(
            "官方旗舰店", "旗舰店", "官方店", "快递", "速递", "物流", "速运",
            "驿站", "智能柜", "超市", "官方", "门店", "外卖"
        )) {
            t = t.replace(suffix, "")
        }
        return t
    }

    /**
     * 纯匹配：返回命中的品牌 key（未命中返回 null）。
     * 大小写不敏感、容忍品牌名带后缀（如「韵达快递」→ yunda）。
     */
    fun matchKey(source: String, shareName: String = ""): String? {
        val normalized = normalize(source) + " " + normalize(shareName)
        if (normalized.isBlank()) return null
        return KEYWORDS
            .sortedByDescending { it.first.length }
            .firstOrNull { normalized.contains(it.first) }
            ?.second
    }

    /** 已知分享来源包名 → 品牌 key 兜底。 */
    private fun pkgKey(pkg: String): String? = when (pkg) {
        "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew" -> "meituan"
        "me.ele" -> "eleme"
        "com.taobao.taobao" -> "taobao"
        "com.jingdong.app.mall" -> "jd"
        "com.sf.activity" -> "sf"
        else -> null
    }

    /**
     * 公开：取品牌 logo 资源 id。优先级：来源品牌名 → 分享来源名 → 分享来源包名。
     * 未收录返回 null，UI 回退类型徽标。
     */
    @DrawableRes
    fun logoRes(source: String, shareName: String = "", sharePkg: String = ""): Int? {
        matchKey(source, shareName)?.let { return LOGO_BY_KEY[it] }
        if (source.isBlank() && shareName.isBlank()) {
            pkgKey(sharePkg)?.let { return LOGO_BY_KEY[it] }
        }
        return null
    }
}
