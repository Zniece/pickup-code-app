package com.pickupcode.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BrandLogoTest {

    @Test
    @DisplayName("快递品牌名（带后缀）匹配")
    fun courier_match() {
        assertEquals("yunda", BrandLogo.matchKey("韵达快递"))
        assertEquals("zto", BrandLogo.matchKey("中通"))
        assertEquals("yto", BrandLogo.matchKey("圆通速递"))
        assertEquals("sto", BrandLogo.matchKey("申通快递"))
        assertEquals("sf", BrandLogo.matchKey("顺丰"))
        assertEquals("jt", BrandLogo.matchKey("极兔速递"))
        assertEquals("jd", BrandLogo.matchKey("京东物流"))
        assertEquals("jd", BrandLogo.matchKey("京东快递"))
        assertEquals("cainiao", BrandLogo.matchKey("菜鸟驿站"))
        assertEquals("deppon", BrandLogo.matchKey("德邦物流"))
    }

    @Test
    @DisplayName("餐饮品牌名（带后缀）匹配")
    fun food_match() {
        assertEquals("luckin", BrandLogo.matchKey("瑞幸咖啡"))
        assertEquals("mixue", BrandLogo.matchKey("蜜雪冰城"))
        assertEquals("mcd", BrandLogo.matchKey("麦当劳"))
        assertEquals("kfc", BrandLogo.matchKey("肯德基"))
        assertEquals("sbux", BrandLogo.matchKey("星巴克"))
        assertEquals("ph", BrandLogo.matchKey("必胜客"))
        assertEquals("domino", BrandLogo.matchKey("达美乐"))
        assertEquals("haidilao", BrandLogo.matchKey("海底捞"))
        assertEquals("juewei", BrandLogo.matchKey("绝味鸭脖"))
        assertEquals("zhy", BrandLogo.matchKey("周黑鸭"))
        assertEquals("holiland", BrandLogo.matchKey("好利来"))
    }

    @Test
    @DisplayName("分享来源名匹配（长词优先）")
    fun share_source_match() {
        assertEquals("meituan", BrandLogo.matchKey("", "美团外卖"))
        assertEquals("meituan", BrandLogo.matchKey("", "美团"))
        assertEquals("eleme", BrandLogo.matchKey("", "饿了么"))
        assertEquals("taobao", BrandLogo.matchKey("", "淘宝"))
        // source 与 shareName 同时存在时优先 source
        assertEquals("yunda", BrandLogo.matchKey("韵达", "微信"))
    }

    @Test
    @DisplayName("未收录品牌返回 null（回退类型徽标）")
    fun unknown_returns_null() {
        assertNull(BrandLogo.matchKey("欢猫智柜"))
        assertNull(BrandLogo.matchKey("快递"))   // 兜底品牌名
        assertNull(BrandLogo.matchKey("餐饮"))   // 兜底品牌名
        assertNull(BrandLogo.matchKey(""))
        assertNull(BrandLogo.matchKey("   "))
        assertNull(BrandLogo.matchKey("", ""))
    }
}
