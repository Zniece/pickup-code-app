package com.pickupcode.app.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * corpus 回归测试：把真实截图/短信的 OCR 行固化成语料，量化码识别 precision/recall 与地址命中率。
 *
 * - 每条语料是一个动态测试，失败信息直接指出漏识别/多识别/地址不符
 * - [known-failures.txt] 里的语料失败不阻断 CI（用于记录待修的已知缺陷）；
 *   若其中某条开始通过，会打印提示要求把它从清单移除
 * - [printMetrics] 打印聚合指标，作为改动前后的对照基线
 *
 * 新增语料：真机触发识别 → 设置页「🔍 识别调试」→「📤 导出语料」→
 * 人工核对 E 段 → 放到 app/src/test/resources/corpus/ 下。
 */
class CorpusRegressionTest {

    companion object {
        private val corpusDir: File by lazy { resolveCorpusDir() }
        private val fixtures: List<CorpusFixture> by lazy { CorpusFixture.loadAll(corpusDir) }
        private val knownFailures: Set<String> by lazy { loadKnownFailures() }

        private fun resolveCorpusDir(): File {
            val candidates = listOf(
                File("src/test/resources/corpus"),
                File("app/src/test/resources/corpus"),
                File(System.getProperty("user.dir"), "src/test/resources/corpus")
            )
            return candidates.firstOrNull { it.isDirectory }
                ?: error("找不到 corpus 目录。cwd=${System.getProperty("user.dir")}，" +
                    "已尝试：${candidates.joinToString { it.absolutePath }}")
        }

        private fun loadKnownFailures(): Set<String> {
            val f = File(corpusDir, "known-failures.txt")
            if (!f.exists()) return emptySet()
            return f.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }

    @TestFactory
    fun corpus(): List<DynamicNode> {
        check(fixtures.isNotEmpty()) { "corpus 目录为空：${corpusDir.absolutePath}" }
        return fixtures.map { f ->
            DynamicTest.dynamicTest("[${f.id}] ${f.note.ifBlank { f.file }}") {
                val r = CorpusRunner.run(f)
                if (f.id in knownFailures) {
                    if (r.passed) {
                        println("✅ 已知失败语料 ${f.id} 已通过——请从 known-failures.txt 移除")
                    } else {
                        println("⏳ 已知失败 ${f.id}: ${r.failures.joinToString("; ")}")
                    }
                    return@dynamicTest
                }
                assertTrue(r.passed, buildString {
                    append(f.id).append(" (").append(f.file).append(") 未通过:\n")
                    r.failures.forEach { append("  - ").append(it).append('\n') }
                    append("  实际码=").append(r.actualCodes.joinToString { "${it.code}(${it.type})" })
                    append("  地址=[").append(r.actualAddress).append("] from=").append(r.actualFrom)
                })
            }
        }
    }

    @Test
    fun printMetrics() {
        val results = fixtures.map { CorpusRunner.run(it) }
        val m = CorpusRunner.metrics(results)
        val addrRate = if (m.addrTotal == 0) "n/a" else "${m.addrPassed}/${m.addrTotal}"
        println(
            buildString {
                appendLine()
                appendLine("================ corpus 指标 ================")
                appendLine("语料条数      : ${m.total}")
                appendLine("整体通过      : ${m.passed}/${m.total}")
                appendLine("码 precision  : ${"%.4f".format(m.precision)}  (TP=${m.tp} FP=${m.fp})")
                appendLine("码 recall     : ${"%.4f".format(m.recall)}  (FN=${m.fn})")
                appendLine("地址命中      : $addrRate")
                appendLine("已知失败清单  : ${knownFailures.size} 条")
                append("=============================================")
            }
        )
        assertTrue(fixtures.isNotEmpty(), "corpus 为空，无法产出指标")
    }
}
