package app.morphe.patches.tiktok.misc.popups

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockPopupsAnchorsTest {
    private val filter = "Lx/Filter;->filterTask(Lx/Ctx;Lx/Task;Ljava/lang/String;Lx/Obs;)Z"

    private fun method(smali: String) = MutableMethod(
        ImmutableMethod(
            "Lcom/bytedance/poplayer/core/PopupTaskExecutor;", "start", emptyList(), "V",
            AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(12, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    @Test
    fun `the filter call is found with its task and answer registers`() {
        val call = popupFilterCalls(
            method(
                """
                    const/4 v9, 0x0
                    invoke-interface {v5, v6, v4, v1, v7}, $filter
                    move-result v9
                    return-void
                """,
            ),
        )
        assertNotNull(call)
        assertEquals(1, call!!.size)
        assertEquals(1, call[0].index)
        assertEquals(4, call[0].taskRegister)
        assertEquals(9, call[0].resultRegister)
    }

    @Test
    fun `a method with three filter calls, no answer read or a different shape is refused`() {
        assertNull(popupFilterCalls(method("""
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            move-result v9
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            move-result v9
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            move-result v9
            return-void
        """)))
        assertNull(popupFilterCalls(method("""
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            return-void
        """)))
        assertNull(popupFilterCalls(method("""
            invoke-interface {v5, v6, v4, v7}, Lx/Filter;->filterTask(Lx/Ctx;Lx/Task;Lx/Obs;)Z
            move-result v9
            return-void
        """)))
    }

    @Test
    fun `a range call is read with the task in the third register`() {
        val ranged = method(
            """
                invoke-interface/range {v5 .. v9}, $filter
                move-result v1
                return-void
            """,
        )
        val call = popupFilterCalls(ranged)!!.single()
        assertEquals(7, call.taskRegister)
        assertEquals(1, call.resultRegister)
    }

    @Test
    fun `the hook lands before the call and is or-ed in after it, also when the answer reuses the task register`() {
        val m = method(
            """
                invoke-interface {v5, v6, v3, v1, v0}, $filter
                move-result v3
                const/4 v2, 0x1
                return-void
            """,
        )
        val call = popupFilterCalls(m)!!.single()
        assertEquals(3, call.taskRegister)
        assertEquals(3, call.resultRegister)
        m.hookPopupFilterCall(call)

        val opcodes = m.implementation!!.instructions.map { it.opcode }.filter { it != Opcode.NOP }
        assertEquals(
            listOf(
                Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT,
                Opcode.OR_INT, Opcode.CONST_4, Opcode.RETURN_VOID,
            ),
            opcodes,
        )
        val scratch = (m.implementation!!.instructions.first { it.opcode == Opcode.MOVE_RESULT } as OneRegisterInstruction).registerA
        assertTrue("scratch v$scratch is not one of the call's registers", scratch !in listOf(5, 6, 3, 1, 0))
        val or = m.implementation!!.instructions.first { it.opcode == Opcode.OR_INT } as ThreeRegisterInstruction
        assertEquals(3, or.registerA)
        assertEquals(3, or.registerB)
        assertEquals(scratch, or.registerC)
    }

    @Test
    fun `the popup layer's filter call is one method with usable registers on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { classDef -> classDef.methods.asSequence().filter { PopupTaskStartFingerprint.takes(it, classDef) } }
                .toList()
            assertEquals("methods taken: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val calls = popupFilterCalls(taken.single())
            assertNotNull("no usable filterTask call in ${taken.single().name}", calls)
            println("${apk.name}: " + calls!!.joinToString { "at ${it.index} task v${it.taskRegister} answer v${it.resultRegister}" })
            for (call in calls) {
                assertTrue("answer register v${call.resultRegister}", call.resultRegister in 0..255)
            }
        }
    }
}
