package com.localdrop.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

// M07 verification audit (§2): FileReceiverDestination finalization safety.
// Proves: rename fast path, occupied-final no-clobber, forced staging-copy
// path byte-exact with no leftovers, commit-failure cleanup, missing-temp
// failure, uniquification sequence. No partial file may ever appear AT the
// final name.
class ReceiverDestinationTest {

    private fun testDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "localdrop-dest-" + UUID.randomUUID())
        dir.mkdirs()
        return dir
    }

    // Overrides renameNow to fail the first `failCount` renames, forcing the
    // staging-copy path (failCount=1) or a commit failure (failCount>=2).
    private class ControlledRenameDest(dir: File, var failCount: Int) : FileReceiverDestination(dir) {
        var renameCalls = 0
        override fun renameNow(source: File, target: File): Boolean {
            renameCalls++
            if (failCount > 0) {
                failCount--
                return false
            }
            return super.renameNow(source, target)
        }
    }

    @Test
    fun `fast path renames with no staging leftover`() {
        val dir = testDir()
        try {
            val dest = FileReceiverDestination(dir)
            val temp = dest.tempFileFor("a.bin")
            assertEquals("a.bin.localdrop-part", temp.name)
            temp.writeBytes(byteArrayOf(1, 2, 3))
            val final = dest.uniqueFinalFileFor("a.bin")
            dest.finalize(temp, final)
            assertArrayEquals(byteArrayOf(1, 2, 3), final.readBytes())
            assertFalse(temp.exists())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".localdrop-commit") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `occupied final throws and occupant untouched`() {
        val dir = testDir()
        try {
            val dest = FileReceiverDestination(dir)
            val occupant = File(dir, "keep.bin")
            occupant.writeBytes("original-user-file".toByteArray())
            val temp = dest.tempFileFor("keep.bin")
            temp.writeBytes("incoming".toByteArray())
            val claimed = File(dir, "keep.bin") // bypass uniquification on purpose
            try {
                dest.finalize(temp, claimed)
                fail("must throw on occupied final")
            } catch (e: java.io.IOException) {
                // Expected.
            }
            assertArrayEquals("original-user-file".toByteArray(), occupant.readBytes())
            assertTrue(temp.exists()) // caller (engine) owns temp cleanup
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".localdrop-commit") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `forced staging copy completes byte exact with no leftovers`() {
        val dir = testDir()
        try {
            val dest = ControlledRenameDest(dir, 1) // temp->final rename fails
            val content = ByteArray(100_000) { ((it * 31) and 0xFF).toByte() }
            val temp = dest.tempFileFor("copy.bin")
            temp.writeBytes(content)
            val final = dest.uniqueFinalFileFor("copy.bin")
            dest.finalize(temp, final)
            assertArrayEquals(content, final.readBytes())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".localdrop-commit") })
            assertTrue(dest.renameCalls >= 2) // rename tried, then staging commit
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `commit failure cleans staging and exposes no final`() {
        val dir = testDir()
        try {
            val dest = ControlledRenameDest(dir, 10) // every rename fails
            val temp = dest.tempFileFor("doomed.bin")
            temp.writeBytes("partial-data".toByteArray())
            val final = dest.uniqueFinalFileFor("doomed.bin")
            try {
                dest.finalize(temp, final)
                fail("must throw when commit rename fails")
            } catch (e: java.io.IOException) {
                // Expected.
            }
            assertFalse(final.exists()) // NO partial file at the final name
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".localdrop-commit") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing temp throws without creating final`() {
        val dir = testDir()
        try {
            val dest = ControlledRenameDest(dir, 10) // force copy path with absent temp
            val temp = File(dir, "ghost.bin.localdrop-part")
            assertFalse(temp.exists())
            try {
                dest.finalize(temp, File(dir, "ghost.bin"))
                fail("must throw for missing temp")
            } catch (e: java.io.IOException) {
                // Expected (copy of nothing / length mismatch).
            } catch (e: Exception) {
                // Also acceptable: fail-closed either way.
            }
            assertFalse(File(dir, "ghost.bin").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniquification sequence never reuses occupied names`() {
        val dir = testDir()
        try {
            val dest = FileReceiverDestination(dir)
            File(dir, "f.bin").writeBytes("0".toByteArray())
            File(dir, "f (2).bin").writeBytes("1".toByteArray())
            assertEquals("f (3).bin", dest.uniqueFinalFileFor("f.bin").name)
            assertEquals("plain.bin", dest.uniqueFinalFileFor("plain.bin").name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
