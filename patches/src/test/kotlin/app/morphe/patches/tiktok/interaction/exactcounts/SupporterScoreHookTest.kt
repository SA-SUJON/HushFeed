package app.morphe.patches.tiktok.interaction.exactcounts

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #552: a LIVE supporters list row's score text (LX/0MN4->LIZ on 48.0.2, static, (Z, J, row) with
 * the row in p3) reads the row's score and formats it, or shows a floor or the server's text
 * instead. The hook reads the same score first and returns it whole when ExactCounts gives one.
 */
class SupporterScoreHookTest {
    private val row = "LX/0MMs;"
    private val formatter = "LX/0MCS;"

    // 21 registers as on 48.0.2: Z in v17, J in v18 and v19, the row in v20, past a 4-bit field.
    private val stock = """
        move-object/from16 v0, p3
        iget-wide v1, v0, $row->LIZIZ:J
        invoke-static {v1, v2}, $formatter->LIZJ(J)Ljava/lang/String;
        move-result-object v1
        return-object v1
    """

    @Test
    fun `the score read is the row's long handed straight to a LIVE count formatter`() {
        val field = scoreText(stock).supporterScoreField(formatter)
        assertNotNull(field)
        assertEquals("$row->LIZIZ:J", "${field!!.definingClass}->${field.name}:${field.type}")
        assertNull("a read handed to another class's formatter isn't the score",
            scoreText(stock).supporterScoreField("LX/0Abc;"))
        assertNull("a long of another type isn't the row's", scoreText("""
            move-object/from16 v0, p3
            iget-wide v1, v0, LX/0Other;->LIZIZ:J
            invoke-static {v1, v2}, $formatter->LIZJ(J)Ljava/lang/String;
            move-result-object v1
            return-object v1
        """).supporterScoreField(formatter))
    }

    @Test
    fun `the hook asks ExactCounts with the row's score and falls through to TikTok's text`() {
        val method = scoreText(stock)
        val field = method.supporterScoreField(formatter)!!
        method.hookSupporterScoreText(field)

        val code = method.implementation!!.instructions.toList()
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("the row copied to v2", 2, (code[0] as TwoRegisterInstruction).registerA)
        assertEquals("from p3", 20, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.IGET_WIDE, code[1].opcode)
        assertEquals("into v0", 0, (code[1] as TwoRegisterInstruction).registerA)
        assertEquals("from the row's copy", 2, (code[1] as TwoRegisterInstruction).registerB)
        assertEquals(field.name, ((code[1] as ReferenceInstruction).reference as FieldReference).name)
        assertEquals(Opcode.INVOKE_STATIC, code[2].opcode)
        val asked = (code[2] as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/feed/ExactCounts;", asked.definingClass)
        assertEquals("supporterScore", asked.name)
        assertEquals(listOf("J"), asked.parameterTypes.map(CharSequence::toString))
        assertEquals(0, (code[2] as FiveRegisterInstruction).registerC)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[3].opcode)
        assertEquals(Opcode.IF_EQZ, code[4].opcode)
        assertEquals(0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_OBJECT, code[5].opcode)
        // Null lands on the stock body's first instruction.
        val offsets = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val target = offsets[4] + (code[4] as OffsetInstruction).codeOffset
        assertEquals(offsets[6], target)
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[6].opcode)
        assertEquals(11, code.size)
    }

    private fun scoreText(body: String) = MutableMethod(
        ImmutableMethod(
            "LX/0MN4;",
            "LIZ",
            listOf(
                ImmutableMethodParameter("Z", null, null),
                ImmutableMethodParameter("J", null, null),
                ImmutableMethodParameter(row, null, null),
            ),
            "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(21, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body) }
}
