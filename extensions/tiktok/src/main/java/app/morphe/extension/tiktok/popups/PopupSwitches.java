/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The two nags Block popups can't reach through its checklist, each behind a switch of its own.
 *
 * <p>The two-step verification suggestion is a bottom sheet TikTok's pop suite builds and shows
 * itself, in PopSuiteManagerService.showCommonTuxIntroPopSheet, which tags it UPSELL_2SV_POPUP. The
 * popup layer task that asks for it carries no label, so the checklist never sees it, and a label
 * about verification is one the checklist refuses anyway. With the switch on the sheet isn't built
 * and the task gets null back, which the popup layer logs as a failed show and moves on from.
 *
 * <p>The LIVE bubble is the one TikTok floats at the top of the feed when someone is live. It's
 * raised from LiveBubbleUtil's check, outside the popup layer altogether, and with the switch on
 * that check returns before anything shows.
 *
 * <p>Both are off until turned on, and pausing Hushfeed brings both back. A sign-in check, a code
 * or a CAPTCHA never comes through either place.
 */
public final class PopupSwitches {
    private PopupSwitches() {
    }

    /** Injection point, at the start of the pop suite's intro sheet. True skips the sheet. */
    public static boolean hideIntroSheet() {
        try {
            if (!Settings.HIDE_2SV_SUGGESTION.get()) return false;
            Logger.printDebug(() -> "Block popups: skipped the two-step verification suggestion");
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Injection point, at the start of the LIVE bubble's check. True skips the bubble. */
    public static boolean hideLiveBubble() {
        try {
            if (!Settings.HIDE_LIVE_BUBBLE.get()) return false;
            Logger.printDebug(() -> "Block popups: skipped the LIVE bubble");
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }
}
