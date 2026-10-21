package eu.kanade.tachiyomi.util.waifu2x

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * JVM port of the compare-and-delete assertions from upstream's androidTest
 * ImageEnhancementCacheTest. The bitmap-dependent decode cases stay
 * device-only upstream; these cover the guard that must never delete a
 * freshly re-saved cache entry because of a stale snapshot.
 */
class ImageEnhancementCacheDiscardTest {

    @TempDir
    lateinit var directory: Path

    @BeforeEach
    fun relaxAndroidLog() {
        // Successful discards log through android.util.Log, which the JVM
        // unit-test android jar throws on; the logging itself is not under test.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun unmockAndroidLog() {
        unmockkAll()
    }

    @Test
    fun rejectingAnOldSnapshotDoesNotDeleteItsNewReplacement() {
        val file = directory.resolve("replacement.webp").toFile()
        val oldSnapshot = Buffer().write("invalid".toByteArray())
        val replacement = "replacement bytes".toByteArray()
        file.writeBytes(replacement)

        ImageEnhancementCache.discardCachedImage(file, oldSnapshot)

        assertArrayEquals(replacement, file.readBytes())
    }

    @Test
    fun aMatchingSnapshotIsDeleted() {
        val file = directory.resolve("current.webp").toFile().apply { writeBytes("current".toByteArray()) }
        val snapshot = Buffer().write("current".toByteArray())

        ImageEnhancementCache.discardCachedImage(file, snapshot)

        assertFalse(file.exists())
    }

    @Test
    fun discardingWithoutAnExpectedSourceDeletesUnconditionally() {
        val file = directory.resolve("stale.webp").toFile().apply { writeBytes("stale".toByteArray()) }

        ImageEnhancementCache.discardCachedImage(file)
        assertFalse(file.exists())

        ImageEnhancementCache.discardCachedImage(file)
        assertFalse(file.exists())
    }
}
