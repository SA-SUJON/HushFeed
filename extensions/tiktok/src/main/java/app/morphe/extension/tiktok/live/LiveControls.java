/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** The LIVE controls patch's switches, each read at the call it guards. */
@SuppressWarnings("unused")
public final class LiveControls {
    private LiveControls() {
    }

    /**
     * True while a LIVE preview in the feed should not start the countdown that takes you into
     * its room. TikTok already skips the countdown on the Following feed, so a preview without
     * one is a state it handles; tapping the preview still opens the room. Paused, the switch
     * answers off.
     */
    public static boolean skipAutoEnter() {
        return SettingsStatus.liveControlsEnabled && Settings.STOP_LIVE_AUTO_ENTER.get();
    }
}
