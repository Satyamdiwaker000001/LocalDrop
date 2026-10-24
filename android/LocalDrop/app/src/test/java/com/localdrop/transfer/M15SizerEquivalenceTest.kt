package com.localdrop.transfer

import com.localdrop.core.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// M15 cross-platform adaptive-block-sizer equivalence test.
//
// The .NET and Kotlin AdaptiveBlockSizer implementations must make exactly
// the same decisions for exactly the same observations (Master Spec: both
// platforms stay behaviour-compatible; M06 ADR 3-8). The expected decisions
// come from an INDEPENDENT from-spec Python implementation
// (test-assets/performance/m15/make_sizer_vectors.py), not from either
// production platform, so a shared bug in both platforms is still detected.
//
// The same vector file is used by the Windows test, so a passing run on both
// platforms proves cross-platform behavioural equivalence.
class M15SizerEquivalenceTest {

    private data class Step(
        val advanceMs: Long,
        val blockBytes: Long,
        val elapsedNanos: Long,
        val stalled: Boolean
    )

    private data class Scenario(
        val name: String,
        val fileSizeBytes: Long,
        val steps: List<Step>,
        val expectedBlockSizes: List<Int>,
        val expectedHistory: List<String>
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "test-assets").isDirectory && File(dir, "shared-protocol").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("LocalDrop repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun loadScenarios(): List<Scenario> {
        val file = File(repoRoot(), "test-assets/performance/m15/sizer-vectors.json")
        require(file.isFile) { "M15 sizer vectors not found at $file" }
        val doc = org.json.JSONObject(file.readText())
        val out = ArrayList<Scenario>()
        val arr = doc.getJSONArray("scenarios")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val steps = ArrayList<Step>()
            val st = s.getJSONArray("steps")
            for (j in 0 until st.length()) {
                val e = st.getJSONObject(j)
                steps.add(
                    Step(
                        advanceMs = e.getLong("advanceMs"),
                        blockBytes = e.getLong("blockBytes"),
                        elapsedNanos = e.getLong("elapsedNanos"),
                        stalled = e.getBoolean("stalled")
                    )
                )
            }
            val sizes = ArrayList<Int>()
            val sz = s.getJSONArray("expectedBlockSizes")
            for (j in 0 until sz.length()) sizes.add(sz.getInt(j))
            val history = ArrayList<String>()
            val hi = s.getJSONArray("expectedHistory")
            for (j in 0 until hi.length()) history.add(hi.getString(j))
            out.add(Scenario(s.getString("name"), s.getLong("fileSizeBytes"), steps, sizes, history))
        }
        assertTrue("no scenarios", out.isNotEmpty())
        return out
    }

    @Test
    fun `android sizer matches the independent oracle`() {
        for (scenario in loadScenarios()) {
            var clock = 0L
            val sizer = AdaptiveBlockSizer(scenario.fileSizeBytes, nanoTime = { clock })
            val observed = ArrayList<Int>()
            observed.add(sizer.currentBlockSize)
            for (step in scenario.steps) {
                clock += step.advanceMs * 1_000_000L
                sizer.observe(step.blockBytes, step.elapsedNanos, step.stalled)
                observed.add(sizer.currentBlockSize)
            }
            assertEquals(scenario.name, scenario.expectedBlockSizes, observed)
            assertEquals(scenario.name, scenario.expectedHistory, sizer.history)
        }
    }

    @Test
    fun `android sizer is deterministic`() {
        for (scenario in loadScenarios()) {
            fun run(): List<Int> {
                var clock = 0L
                val sizer = AdaptiveBlockSizer(scenario.fileSizeBytes, nanoTime = { clock })
                val observed = ArrayList<Int>()
                observed.add(sizer.currentBlockSize)
                for (step in scenario.steps) {
                    clock += step.advanceMs * 1_000_000L
                    sizer.observe(step.blockBytes, step.elapsedNanos, step.stalled)
                    observed.add(sizer.currentBlockSize)
                }
                return observed
            }
            assertEquals(scenario.name, run(), run())
        }
    }

    @Test
    fun `android sizer stays inside the documented bounds`() {
        val sizes = listOf(
            0L, 1L, 100L, 65536L, 1L shl 20, 5_000_000_000L, 21_474_836_480L, Long.MAX_VALUE
        )
        for (size in sizes) {
            var clock = 0L
            val sizer = AdaptiveBlockSizer(size, nanoTime = { clock })
            assertTrue(
                sizer.currentBlockSize in AppConfig.TRANSFER_BLOCK_MIN_BYTES..AppConfig.TRANSFER_BLOCK_MAX_BYTES
            )
            for (i in 0 until 200) {
                clock += 2_500L * 1_000_000L
                val stalled = (i % 3) == 0
                sizer.observe(262144L, if (stalled) 900_000_000L else 1_000_000L, stalled)
                assertTrue(
                    sizer.currentBlockSize in AppConfig.TRANSFER_BLOCK_MIN_BYTES..AppConfig.TRANSFER_BLOCK_MAX_BYTES
                )
            }
            assertTrue(sizer.history.size <= AdaptiveBlockSizer.MAX_HISTORY_ENTRIES)
        }
    }
}
