package com.pickupcode.app.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 截图治理策略（纯函数）单测。
 * 时间常量：MIN/HOUR/DAY 便于把"多久之前"写成人类可读的形式。
 */
class ScreenshotStoreTest {

    private val now = 1_700_000_000_000L
    private val minute = 60 * 1000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun entry(name: String, ageMs: Long, sizeBytes: Long = 100) =
        ScreenshotStore.Entry("/cache/screenshots/$name", sizeBytes, now - ageMs)

    @Test
    @DisplayName("被引用且未超期的截图：一个都不删")
    fun keepsReferencedFresh() {
        val e = listOf(entry("a.jpg", ageMs = 2 * day), entry("b.jpg", ageMs = hour))
        val plan = ScreenshotStore.planDeletions(e, referenced = setOf(e[0].path, e[1].path), now = now)
        assertTrue(plan.isEmpty()) { "不该删任何仍被引用且未超期的截图，实际: $plan" }
    }

    @Test
    @DisplayName("孤儿文件但还在宽限期内：先不删（避免删掉正在入库的截图）")
    fun keepsFreshOrphanWithinGrace() {
        val e = listOf(entry("a.jpg", ageMs = 1 * minute))
        val plan = ScreenshotStore.planDeletions(e, referenced = emptySet(), now = now)
        assertTrue(plan.isEmpty()) { "宽限期内不应删，实际: $plan" }
    }

    @Test
    @DisplayName("孤儿文件且已过宽限期：删")
    fun deletesStaleOrphan() {
        val e = listOf(entry("a.jpg", ageMs = 1 * hour))
        val plan = ScreenshotStore.planDeletions(e, referenced = emptySet(), now = now)
        assertEquals(listOf(e[0].path), plan)
    }

    @Test
    @DisplayName("仍被引用但超过 30 天硬保留期：删（调用方随后清空 DB 引用）")
    fun deletesReferencedBeyondTtl() {
        val e = listOf(entry("old.jpg", ageMs = 31 * day))
        val plan = ScreenshotStore.planDeletions(e, referenced = setOf(e[0].path), now = now)
        assertEquals(listOf(e[0].path), plan)
    }

    @Test
    @DisplayName("刚好在 TTL 边界内：不删")
    fun keepsJustWithinTtl() {
        val e = listOf(entry("edge.jpg", ageMs = 29 * day))
        val plan = ScreenshotStore.planDeletions(e, referenced = setOf(e[0].path), now = now)
        assertTrue(plan.isEmpty()) { "29 天不该被 TTL 命中，实际: $plan" }
    }

    @Test
    @DisplayName("总量超限：按最旧优先删到阈值以内")
    fun enforcesSizeCapOldestFirst() {
        val e = listOf(
            entry("newest.jpg", ageMs = 1 * day, sizeBytes = 60),
            entry("middle.jpg", ageMs = 5 * day, sizeBytes = 60),
            entry("oldest.jpg", ageMs = 10 * day, sizeBytes = 60)
        )
        // 上限 130：删掉最旧的 60 → 剩 120 ≤ 130，只需删 1 个
        val plan = ScreenshotStore.planDeletions(
            e, referenced = e.map { it.path }.toSet(), now = now,
            ttlMs = 365 * day, graceMs = hour, maxTotalBytes = 130
        )
        assertEquals(listOf(e[2].path), plan) { "应只删最旧的那个，实际: $plan" }
    }

    @Test
    @DisplayName("总量超限时，孤儿优先被删（不吃掉被引用的文件）")
    fun orphansDeletedBeforeReferencedUnderCap() {
        val orphan = entry("orphan.jpg", ageMs = 2 * hour, sizeBytes = 100)
        val referenced = entry("kept.jpg", ageMs = 1 * day, sizeBytes = 100)
        val plan = ScreenshotStore.planDeletions(
            listOf(orphan, referenced), referenced = setOf(referenced.path), now = now,
            ttlMs = 365 * day, graceMs = hour, maxTotalBytes = 150
        )
        assertEquals(listOf(orphan.path), plan) { "孤儿应优先承担超量删除，实际: $plan" }
    }

    @Test
    @DisplayName("TTL 与孤儿规则叠加时每个文件只出现一次")
    fun noDuplicateVictims() {
        val e = listOf(entry("both.jpg", ageMs = 40 * day))
        val plan = ScreenshotStore.planDeletions(e, referenced = emptySet(), now = now)
        assertEquals(1, plan.size) { "同一文件不应重复出现: $plan" }
    }

    @Test
    @DisplayName("空目录/无文件时不炸")
    fun handlesEmpty() {
        assertTrue(ScreenshotStore.planDeletions(emptyList(), emptySet(), now).isEmpty())
    }
}
