/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

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

@Suppress("unused")
val liveControlsPatch = bytecodePatch(
    name = "LIVE controls",
    description = "Adds a switch that stops a LIVE in the feed counting down and taking you into the room on its " +
        "own, so you stay in the feed until you tap it. Switch: Hushfeed settings > Playback.",
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
    }
}
