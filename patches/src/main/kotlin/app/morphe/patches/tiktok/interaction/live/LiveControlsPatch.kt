/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveControls;"

/** The feed LIVE preview's guide, real-named on 47.0.3, 47.1.3 and 47.1.4. */
internal const val LIVE_PREVIEW_GUIDE_VM = "Lcom/ss/android/ugc/aweme/feed/adapter/widget/guide/LivePreviewGuideEnterVM;"

/**
 * Starts the countdown that takes a LIVE preview in the feed into its room: it checks the feed
 * (homepage_hot for For You, homepage_live for the LIVE tab behind
 * live_preview_page_enable_auto_entering_guide, and it returns on homepage_follow), then either
 * hands a smart countdown to LivePreviewEnterRoomGuideManager or arms a fixed one of
 * live_preview_page_auto_entering_request_delay seconds. When that fires, BottomTipsWidget runs
 * its AutoEnterProgressBar for live_preview_page_auto_entering_guide_duration seconds, and the
 * bar's listener enters the room with "enter_room". Renamed on every build (O63 on 47.0.3, E83 on
 * 47.1.3 and 47.1.4); the only method that loads the enable key. It already returns early on
 * the Following feed and once a countdown has started, so returning at entry is a state TikTok
 * handles: no countdown, no bar, and the preview stays a preview until it's tapped.
 */
internal object LivePreviewAutoEnterFingerprint : Fingerprint(
    definingClass = LIVE_PREVIEW_GUIDE_VM,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    strings = listOf("live_preview_page_enable_auto_entering_guide", "homepage_follow"),
)

/** The LIVE room's top-right viewer count, real-named on 47.0.3, 47.1.3 and 47.1.4. */
internal const val ONLINE_AUDIENCE_RANK_WIDGET = "Lcom/bytedance/android/livesdk/rank/impl/widget/OnlineAudienceRankWidget;"

/** How many places the widget turns the viewer count into text; the same on all three builds. */
internal const val VIEWER_COUNT_SITES = 4

/**
 * One place the widget formats the viewer count: `int-to-long vLow, vCount`, a static (J)String
 * formatter called on the pair (vLow, vLow + 1), `move-result-object vResult`, then
 * `sget-object Locale.ENGLISH` and `toUpperCase` on vResult. [formatIndex] is the formatter call.
 */
internal data class ViewerCountSite(
    val method: Method,
    val formatIndex: Int,
    val pairLow: Int,
    val result: Int,
)

/**
 * Every call in [widget] to a static (J)Ljava/lang/String; helper that isn't the JDK's. That
 * helper is the "K+", "M+" and "B+" abbreviator (X.0LE4.LIZJ on 47.0.3, X.0LDD on 47.1.3, X.0LDH
 * on 47.1.4), renamed per build, so the shape is matched, not the name. Throws if any such call
 * has a shape the hook can't take: a site the patch skipped would keep showing the rounded
 * count with nothing to say so.
 */
internal fun viewerCountSites(widget: ClassDef): List<ViewerCountSite> {
    val sites = mutableListOf<ViewerCountSite>()
    for (method in widget.methods) {
        val instructions = method.implementation?.instructions?.toList() ?: continue
        instructions.forEachIndexed { index, instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@forEachIndexed
            if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
                return@forEachIndexed
            }
            if (call.returnType != "Ljava/lang/String;" ||
                call.parameterTypes.map(CharSequence::toString) != listOf("J")
            ) {
                return@forEachIndexed
            }
            if (call.definingClass.startsWith("Ljava/")) return@forEachIndexed
            val where = "${widget.type}->${method.name} at $index"
            val invoke = instruction as? FiveRegisterInstruction
                ?: throw PatchException("LIVE controls: viewer count call is not a plain invoke at $where")
            val low = invoke.registerC
            val widened = instructions.getOrNull(index - 1)
            val moved = instructions.getOrNull(index + 1)
            val localeGet = instructions.getOrNull(index + 2)
            val locale = (localeGet as? ReferenceInstruction)?.reference as? FieldReference
            val upper = instructions.getOrNull(index + 3)
            val upperCall = (upper as? ReferenceInstruction)?.reference as? MethodReference
            val shaped = invoke.registerCount == 2 && invoke.registerD == low + 1 &&
                widened?.opcode == Opcode.INT_TO_LONG &&
                (widened as TwoRegisterInstruction).registerA == low &&
                moved?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                localeGet?.opcode == Opcode.SGET_OBJECT &&
                locale?.definingClass == "Ljava/util/Locale;" && locale.name == "ENGLISH" &&
                upper?.opcode == Opcode.INVOKE_VIRTUAL && upperCall?.name == "toUpperCase" &&
                (upper as FiveRegisterInstruction).registerC == (moved as OneRegisterInstruction).registerA
            if (!shaped) throw PatchException("LIVE controls: viewer count call has an unexpected shape at $where")
            sites += ViewerCountSite(method, index, low, (moved as OneRegisterInstruction).registerA)
        }
    }
    return sites
}

@Suppress("unused")
val liveControlsPatch = bytecodePatch(
    name = "LIVE controls",
    description = "Adds a switch that stops a LIVE in the feed counting down and taking you into the room on its " +
        "own, so you stay in the feed until you tap it, and one that shows a LIVE's exact viewer count instead " +
        "of TikTok's rounded one. Switches: Hushfeed settings > Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val guide = LivePreviewAutoEnterFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveControls()V",
        )
        guide.guardAtEntry(
            "LIVE controls",
            "invoke-static {}, $EXTENSION->skipAutoEnter()Z",
            "return-void",
        )

        // The formatter's result lands in the upper half of the long it was given, so the count
        // can't be read back after the call and the hook needs no spare register: the count is
        // handed over before the call and TikTok's text after it, on the main thread in one
        // straight line of code, and the extension answers with the exact number or TikTok's
        // text. Both added calls use only registers the original instructions just wrote: the
        // first reads the pair int-to-long filled, the second reads and rewrites the register
        // move-result-object filled. No frame grows.
        val widget = classDefByOrNull(ONLINE_AUDIENCE_RANK_WIDGET)
            ?: throw PatchException("LIVE controls: $ONLINE_AUDIENCE_RANK_WIDGET not found")
        val sites = viewerCountSites(widget)
        if (sites.size != VIEWER_COUNT_SITES) {
            throw PatchException("LIVE controls: expected $VIEWER_COUNT_SITES viewer count calls, found ${sites.size}")
        }
        val mutableWidget = mutableClassDefBy(ONLINE_AUDIENCE_RANK_WIDGET)
        for ((method, group) in sites.groupBy { it.method }) {
            val mutable = mutableWidget.findMutableMethodOf(method)
            // Last site first, so an earlier site's index is still right when it is edited.
            for (site in group.sortedByDescending { it.formatIndex }) {
                mutable.addInstructions(
                    site.formatIndex + 2,
                    "invoke-static/range {v${site.result} .. v${site.result}}, $EXTENSION->viewerCountText(Ljava/lang/String;)" +
                        "Ljava/lang/String;\nmove-result-object v${site.result}",
                )
                mutable.addInstruction(
                    site.formatIndex,
                    "invoke-static/range {v${site.pairLow} .. v${site.pairLow + 1}}, $EXTENSION->noteViewerCount(J)V",
                )
            }
        }
    }
}
