package app.morphe.patches.tiktok.interaction.gesture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the set of methods the double tap guard is installed on.
 *
 * <p>The patch shipped for months guarding only `BaseListFragmentPanel`, and a reporter said
 * twice that "do nothing" still liked the video. It resolved, so nothing failed and nothing
 * looked wrong. The reason is that a fingerprint resolving says the method exists, not that it
 * is the one the gesture reaches: the modern feed panel's own listener holds the digg component
 * and calls `DiggPanelComponent.handleDoubleClick` without going near the legacy panel.
 *
 * <p>Traced on the 46.2.3 fixture and confirmed on 46.7.3 and 46.8.3. Dropping any entry makes
 * this fail, which is the only thing standing between the next refactor and the same bug.
 */
class DoubleTapRoutesTest {
    @Test
    fun everyRouteFromADoubleTapToALikeIsGuarded() {
        assertEquals(
            listOf(
                "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;" to "handleDoubleClick",
                "Lcom/ss/android/ugc/feed/platform/panel/digg/DiggPanelComponent;" to "handleDoubleClick",
                "Lcom/ss/android/ugc/aweme/feed/landscape/LandscapeFragmentPanel;" to "handleDoubleClick",
            ),
            doubleTapRouteMethods,
        )
    }

    @Test
    fun theClassThatCannotLikeIsNotGuarded() {
        // LX/0SPC declares the same method and returns immediately. Guarding it would add an
        // injection that can only ever be dead.
        val owners = doubleTapRouteMethods.map { it.first }
        assertEquals(emptyList<String>(), owners.filter { it == "LX/0krU;" })
    }

    /**
     * #555: Seek back / forward reads the player's progress, so Double-tap controls records it
     * itself. Left in Long-press controls, a bundle without that patch had nothing to seek with.
     * Long-press controls depends on this patch, so its edge seek keeps the one hook.
     */
    @Test
    fun `the double tap patch records the progress its seek reads`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val gesture = File(root, "app/morphe/patches/tiktok/interaction/gesture")
        val hook = "recordProgress(Ljava/lang/Object;Ljava/lang/String;JJ)V"
        val doubleTap = File(gesture, "DoubleTapPatch.kt").readText()
        val longPress = File(gesture, "LongPressPatch.kt").readText()
        assertTrue("Double-tap controls no longer records the progress",
            "PlayerProgressFingerprint.method.addInstruction" in doubleTap && hook in doubleTap)
        assertFalse("Long-press controls records it a second time", hook in longPress)
        val dependencies = Regex("""dependsOn\(([^)]*)\)""").find(longPress)?.groupValues?.get(1).orEmpty()
            .split(',').map { it.trim() }
        assertTrue("Long-press controls lost the patch that records its progress: $dependencies",
            "doubleTapPatch" in dependencies)
    }
}
