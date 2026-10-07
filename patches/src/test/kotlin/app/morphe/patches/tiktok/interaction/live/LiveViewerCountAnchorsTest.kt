package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE controls shows the exact viewer count by wrapping the widget's four calls to TikTok's
 * "K+/M+/B+" abbreviator. Each declared build has the widget under its real name with exactly
 * those four calls, each in the shape the hook writes around: `int-to-long`, the formatter on the
 * pair, `move-result-object`, then `Locale.ENGLISH` and `toUpperCase` on the result. The hook
 * adds a call reading the pair before the formatter and one reading and rewriting the result
 * register after it, so it needs no register of its own; this test holds that every register
 * involved is one a plain invoke can name, and that the same four shapes exist on all three builds.
 */
class LiveViewerCountAnchorsTest {
    @Test
    fun `each declared build has the widget with four hookable viewer count calls`() {
        val shapes = mutableMapOf<String, List<Pair<Int, Int>>>()
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val widgets = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) {
                container.getEntry(entry)!!.dexFile.classes.filterTo(widgets) { it.type == ONLINE_AUDIENCE_RANK_WIDGET }
            }
            assertEquals("${apk.name}: widget classes", 1, widgets.size)
            val widget = widgets.single()

            val sites = viewerCountSites(widget)
            assertEquals("${apk.name}: viewer count calls", VIEWER_COUNT_SITES, sites.size)

            val formatters = sites.map { site ->
                val call = site.method.implementation!!.instructions.toList()[site.formatIndex] as ReferenceInstruction
                (call.reference as MethodReference).let { "${it.definingClass}->${it.name}" }
            }.toSet()
            assertEquals("${apk.name}: one formatter behind all four: $formatters", 1, formatters.size)

            sites.forEach { site ->
                val instructions = site.method.implementation!!.instructions.toList()
                // Register safety: the inserted calls name the pair and the result register, with
                // the range form so any register number works, and nothing else.
                assertTrue(
                    "${apk.name}: ${site.method.name} frame holds v${site.pairLow + 1} and v${site.result}",
                    site.method.implementation!!.registerCount > maxOf(site.pairLow + 1, site.result),
                )
                // The original instructions keep owning the registers: int-to-long defines the
                // pair right before the formatter, move-result-object defines the result right
                // after it, and the added calls sit between those and nothing else.
                assertEquals(Opcode.INT_TO_LONG, instructions[site.formatIndex - 1].opcode)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[site.formatIndex + 1].opcode)
                assertEquals(Opcode.SGET_OBJECT, instructions[site.formatIndex + 2].opcode)
            }
            // Every static (J)String call to a non-JDK class in the widget is one of the four,
            // so no formatted count is left showing TikTok's rounded text.
            val all = widget.methods.flatMap { it.implementation?.instructions.orEmpty() }.count {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_STATIC && ref != null && !ref.definingClass.startsWith("Ljava/") &&
                    ref.returnType == "Ljava/lang/String;" && ref.parameterTypes.map(CharSequence::toString) == listOf("J")
            }
            assertEquals("${apk.name}: static (J)String calls in the widget", VIEWER_COUNT_SITES, all)

            shapes[apk.name] = sites.map { it.pairLow to it.result }.sortedBy { it.first * 100 + it.second }
        }
        assertEquals("the same register shapes on every declared build: $shapes", 1, shapes.values.toSet().size)
    }
}
