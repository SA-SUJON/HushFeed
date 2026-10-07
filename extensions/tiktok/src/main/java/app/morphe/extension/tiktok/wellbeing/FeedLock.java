/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A feed that stays shut: For You, Following and the other feed tabs sit behind a calm panel and
 * their pager doesn't swipe, while Inbox, profiles and search work as they always do.
 *
 * <p>It is not a second hold. The daily budget's panel, its stopped playback and its pager block
 * already do all of this, so the lock is a second reason for them to be there: {@link
 * SessionLockOverlay} puts its panel up when either the budget is spent or this is on, the
 * pager's {@code onInterceptTouchEvent} and {@code onTouchEvent} turn swipes down through {@link
 * FinishLastVideo#holdsSwipe}, and {@link SessionPlaybackHold} stops the video under the panel.
 * A spent budget keeps its own panel, with its countdown, when both apply.
 *
 * <p>A link to one video still opens that video. TikTok starts the app on it with a link
 * (cold, through the main activity's creation, and warm, through its new intent) and plays it at
 * the top of the feed, where nothing tells it from a feed video but the intent that brought it.
 * So a link entry lets the first video that comes up stay uncovered, and the next video, by any
 * route, ends the exception. A video opened from a message, a profile or search is not on the
 * recommendation feed at all, so the panel never covers it.
 */
public final class FeedLock {
    /** How long a link entry waits for its video to come up before it is forgotten. */
    static final long LINK_WINDOW_MS = 20_000L;

    /** Set on a link entry and cleared when its video binds or the window passes. */
    private static volatile boolean linkPending;
    private static volatile long linkAt;
    /** The video the player had on screen when a warm link arrived, which is not the link's. */
    private static volatile String baseline;
    /** The one video a link opened, uncovered while it is the one on screen. */
    private static volatile String permitted;

    private static volatile Clock clock = SystemClock::elapsedRealtime;

    interface Clock {
        long now();
    }

    private FeedLock() {
    }

    /** Whether the switch is on. Paused, the setting answers its unpatched value, off. */
    public static boolean isOn() {
        return Settings.FEED_LOCK.get();
    }

    /** Whether the lock wants the feed covered right now: on, and no link video to let through. */
    public static boolean covers() {
        return isOn() && !linkVideoOnScreen();
    }

    private static boolean linkVideoOnScreen() {
        if (linkPending) {
            if (clock.now() - linkAt > LINK_WINDOW_MS) {
                linkPending = false;
            } else if (baseline == null) {
                // A start from a link: nothing was playing, and the link's video is on its way.
                return true;
            }
        }
        String video = permitted;
        return video != null && video.equals(SessionPlaybackHold.currentAwemeId());
    }

    /** A link started or reached the main activity. The next new video is the link's. */
    public static void noteLinkEntry() {
        if (!isOn()) return;
        baseline = SessionPlaybackHold.currentAwemeId();
        permitted = null;
        linkAt = clock.now();
        linkPending = true;
    }

    /** Called with the intent TikTok's main activity is handed while it is already running. */
    public static void onNewIntent(Intent intent) {
        try {
            if (intent != null && intent.getData() != null) noteLinkEntry();
        } catch (Throwable failure) {
            Logger.printException(() -> "The feed lock could not read a new intent", failure);
        }
    }

    /** Called with each video the player reports, after it has become the current one. */
    static void onVideo(String awemeId) {
        if (awemeId == null) return;
        if (linkPending) {
            if (clock.now() - linkAt > LINK_WINDOW_MS) {
                linkPending = false;
            } else if (!awemeId.equals(baseline)) {
                permitted = awemeId;
                linkPending = false;
                // The panel may be up over the video the link is replacing.
                Utils.runOnMainThread(SessionLockOverlay::sync);
                return;
            }
        }
        String video = permitted;
        if (video != null && !video.equals(awemeId)) {
            permitted = null;
            Utils.runOnMainThread(SessionLockOverlay::sync);
        }
    }

    /**
     * Whether the panel belongs over the main activity now: the lock is on, no link video is
     * being let through, the main activity is the one in front and the recommendation feed is
     * certainly what it shows. Stricter than the budget's check on purpose: a video opened from
     * a message, a profile or search is a page of the same activity, and the budget's panel
     * covers it, where this one must not.
     */
    static boolean coversFeedOn(Activity main) {
        if (!covers() || main == null) return false;
        Activity front = Utils.getVisibleActivity();
        if (front != null && front != main) return false;
        return FeedVisibility.onRecommendationFeed(main);
    }

    /** Called at the pager's touch methods, so the idle case is a couple of volatile reads. */
    static boolean holdsSwipe(Activity activity) {
        // The swipe stays off through a link's video: it opens that video, and nothing past it.
        return isOn() && activity != null && FeedVisibility.onRecommendationFeed(activity);
    }

    static void resetForTests() {
        linkPending = false;
        linkAt = 0;
        baseline = null;
        permitted = null;
        clock = SystemClock::elapsedRealtime;
    }

    static void setClockForTests(Clock replacement) {
        clock = replacement == null ? SystemClock::elapsedRealtime : replacement;
    }
}
