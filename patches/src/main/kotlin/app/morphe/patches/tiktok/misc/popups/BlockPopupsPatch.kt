/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.popups

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val POPUP_LABELS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/popups/PopupLabels;"
private const val POPUP_TASK_EXECUTOR = "Lcom/bytedance/poplayer/core/PopupTaskExecutor;"
private const val FILTER_TASK = "filterTask"

/**
 * TikTok's popup layer runs a queue of popup tasks. The method that starts one asks a filter
 * whether to drop it, with a string it logs before the call. The class and that string are the
 * same on 47.0.3, 47.1.3 and 47.1.4. The method's name and every type around it are renamed on
 * each build, so the call is found by its shape below, not by name.
 */
internal object PopupTaskStartFingerprint : Fingerprint(
    definingClass = POPUP_TASK_EXECUTOR,
    returnType = "V",
    strings = listOf("getEnterFrom: use custom enterFrom: "),
)

/** The filter call in the task's start method: where it is, and the registers it reads and gets back. */
internal class PopupFilterCall(
    val index: Int,
    val taskRegister: Int,
    val resultRegister: Int,
    /** Every register the call reads, so the hook's scratch register is none of them. */
    val callRegisters: List<Int>,
)

/**
 * Every `filterTask(context, task, String, observer)Z` call in [method], each followed by the
 * move-result that takes its answer, in code order. The answer is true when the popup is
 * dropped. Null when there is none, or when a call's answer isn't read right away. The start
 * method asks twice, once on each of its two paths to showing a popup, and both are hooked.
 */
internal fun popupFilterCalls(method: Method): List<PopupFilterCall>? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val found = mutableListOf<PopupFilterCall>()
    for ((index, instruction) in instructions.withIndex()) {
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) continue
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
        if (reference.name != FILTER_TASK || reference.returnType != "Z") continue
        val types = reference.parameterTypes.map(CharSequence::toString)
        if (types.size != 4 || types[2] != "Ljava/lang/String;") continue
        val next = instructions.getOrNull(index + 1) ?: return null
        if (next.opcode != Opcode.MOVE_RESULT) return null
        // Receiver, context, task, label, observer: the task is the third register either way.
        val task = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerE
            is RegisterRangeInstruction -> instruction.startRegister + 2
            else -> return null
        }
        found += PopupFilterCall(index, task, (next as OneRegisterInstruction).registerA, instruction.namedRegisters())
    }
    return found.takeIf { it.isNotEmpty() && it.size <= 2 }
}

/**
 * Puts the reader's ticks into one filter call: the hook runs on every path into the call and
 * its verdict is or-ed into the call's answer.
 */
internal fun MutableMethod.hookPopupFilterCall(call: PopupFilterCall) {
    val after = call.index + 2
    // A register that is dead here and that neither the call nor its answer uses: the
    // hook's verdict waits in it across the call. The call may clobber the task's own
    // register with its answer, so the verdict is taken before the call, not after.
    val scratch = getFreeRegisterProvider(
        call.index, 1, call.callRegisters + call.resultRegister,
    ).getFreeRegister()
    if (scratch > 255 || call.resultRegister > 255) {
        throw PatchException("Block popups: no register low enough for the hook (v$scratch).")
    }
    // After the call: a popup the reader ticked is dropped as if TikTok's filter had. A plain
    // insert leaves any branch that lands on the next instruction pointing at that instruction,
    // so a path that skipped the call also skips this, and never reads the scratch register.
    addInstruction(after, "or-int v${call.resultRegister}, v${call.resultRegister}, v$scratch")
    // Before the call, on every path into it. The range form takes any register.
    addInstructionsAtControlFlowLabel(
        call.index,
        """
            invoke-static/range {v${call.taskRegister} .. v${call.taskRegister}}, $POPUP_LABELS_DESCRIPTOR->shouldDrop(Ljava/lang/Object;)Z
            move-result v$scratch
        """,
    )
}

@Suppress("unused")
val blockPopupsPatch = bytecodePatch(
    name = "Block popups",
    description = "Lets you stop TikTok's own popups one at a time, like the follow-your-friends card or an upsell. The checklist lists each popup TikTok has tried to show on your phone, so one appears there after its first showing. Tick it and it stays away from then on. Nothing is blocked until you tick something. CAPTCHA, verification, sign-in, age, ban and legal consent screens are never listed and never blocked. Switch: Hushfeed settings > Feed screen.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePopupLabels()V",
        )
        val method = PopupTaskStartFingerprint.method
        val calls = popupFilterCalls(method)
            ?: throw PatchException("Block popups: the popup layer has no filterTask call to hook.")
        // Later calls first, so an earlier insert doesn't move the index of the next one.
        for (call in calls.asReversed()) method.hookPopupFilterCall(call)
    }
}
