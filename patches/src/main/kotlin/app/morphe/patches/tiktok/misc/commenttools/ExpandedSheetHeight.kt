/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructions
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val COMMENT_PAGE_CONTAINER =
    "Lcom/ss/android/ugc/aweme/commentv2/commentpage/commonvideo/DefaultCommentPageContainerFragment;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"

/**
 * The click on the comment sheet's expand chevron (`LX/1Szk;->onClick` on 48.0.2, `LX/17fI;` on
 * 47.9.3), which the container builds when `comment_panel_full_page` is 1. Expanding, it animates
 * the sheet from its height to the window's height (the second of a Pair of the window's bounds)
 * less 8dp, and collapsing it animates back. The listener holds the container and the sheet.
 */
internal object CommentSheetExpandClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = COMMENT_PAGE_CONTAINER),
        methodCall(definingClass = "Lkotlin/Pair;", name = "getSecond"),
        methodCall(definingClass = "Landroid/animation/ValueAnimator;", name = "ofInt"),
    ),
    custom = { _, classDef -> "Landroid/view/View\$OnClickListener;" in classDef.interfaces },
)

/**
 * Where the expand click has the height it stretches the sheet to: [index] is just past the
 * `sub-int/2addr` that takes the gap ([gap]) off the window's height ([height]), the listener's
 * [sheet] field is the view it grows.
 */
internal class ExpandedSheetSite(val index: Int, val height: Int, val gap: Int, val sheet: FieldReference)

/**
 * The one subtraction between the window's height and the first `ofInt`, with the sheet field the
 * listener reads in between to turn the 8dp into pixels. Throws when the click is shaped another
 * way, so a build that moved it fails at patch time instead of hooking the wrong number.
 */
internal fun Method.expandedSheetSite(): ExpandedSheetSite {
    fun fail(why: String): Nothing = throw PatchException("Comment tools: the expand click $why.")
    val instructions = implementation?.instructions?.toList() ?: fail("has no body")
    val windowHeight = instructions.indexOfFirst {
        it.getReference<MethodReference>()?.let { call -> call.definingClass == "Lkotlin/Pair;" && call.name == "getSecond" } == true
    }
    val animator = instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_STATIC &&
            it.getReference<MethodReference>()?.let { call -> call.definingClass == "Landroid/animation/ValueAnimator;" && call.name == "ofInt" } == true
    }
    if (windowHeight < 0 || animator < windowHeight) fail("no longer reads the window's height before its animation")
    val between = (windowHeight + 1 until animator)
    val subtractions = between.filter { instructions[it].opcode == Opcode.SUB_INT_2ADDR }
    val subtraction = subtractions.singleOrNull() ?: fail("takes ${subtractions.size} numbers off the window's height, not one")
    val sheets = between.mapNotNull { at ->
        val instruction = instructions[at]
        if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        instruction.getReference<FieldReference>()?.takeIf { it.definingClass == definingClass && it.type == VIEW_GROUP }
    }.distinctBy { it.name }
    val sheet = sheets.singleOrNull() ?: fail("reads ${sheets.size} sheet views before its animation, not one")
    val sub = instructions[subtraction] as TwoRegisterInstruction
    if (sub.registerA > 15 || sub.registerB > 15) fail("keeps its height out of a 4-bit register")
    if (isBranchTarget(subtraction + 1)) fail("is branched onto after its height")
    return ExpandedSheetSite(subtraction + 1, sub.registerA, sub.registerB, sheet)
}

/**
 * Keeps the sheet the chevron stretches below the status bar (#556): the height it animates to
 * goes through CommentSheetGates.expandedHeight with the sheet and the gap, in a register that
 * is free there.
 */
internal fun MutableMethod.resolveExpandedSheetHeight(): CommentToolsWrite {
    val site = expandedSheetSite()
    val view = getFreeRegisterProvider(site.index, 1, listOf(site.height, site.gap)).getFreeRegister4Bit()
    return {
        addInstructions(
            site.index,
            """
                move-object/from16 v$view, p0
                iget-object v$view, v$view, ${site.sheet.definingClass}->${site.sheet.name}:${site.sheet.type}
                invoke-static {v$view, v${site.height}, v${site.gap}}, $COMMENT_SHEET_GATES_DESCRIPTOR->expandedHeight(Landroid/view/View;II)I
                move-result v${site.height}
            """,
        )
    }
}
