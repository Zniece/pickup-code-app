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
    val timestamp: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val doneAt: Long = 0       // 标记已取/删除的时间，0=未操作
)
