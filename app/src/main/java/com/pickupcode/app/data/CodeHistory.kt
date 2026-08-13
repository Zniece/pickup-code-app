package com.pickupcode.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "code_history")
data class CodeHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val type: String,          // "pickup_food" / "pickup_parcel"
    val source: String,        // 品牌/驿站名
    val screenshotPath: String = "", // 截屏图片路径
    val rawTextSnippet: String, // OCR原始文本片段
    val pickupAddress: String = "", // 取件地址
    val geoVerified: Boolean = false,   // 地址是否经过地图验证
    val geoConfidence: Float = 0f,      // 地图验证置信度 0-1
    val geoFormattedAddress: String = "", // 地图返回的规范化地址
    val timestamp: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val doneAt: Long = 0,       // 标记已取/删除的时间，0=未操作
    val shareSourcePkg: String = "", // 分享来源 App 包名（如 com.tencent.mm）；无=空
    val shareSourceName: String = "", // 分享来源 App 可读名（如 微信）；无=空
    val cabinetNumber: String = "",  // 独立柜号（如 丰巢2号柜 / 12号格口）；无=空（DB v5）
    val expiryTime: Long = 0  // 到期提醒时刻 ms；0=无需提醒（取餐码/券码恒为 0）（DB v6）
)
