/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.wellbeing.FeedLock;

import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * The video a link from outside TikTok opened. TikTok answers such a link with a For You
 * response that holds that one video, so a feed rule that took it out (a video already seen, a
 * blocked word, a photo post) left TikTok an empty page and its error screen instead of the video
 * (#117). The video is the reader's own pick, so the feed rules let it through, as they do the
 * reader's own posts.
 *
 * <p>A full link names its video and only that one is let through. A share's short link names
 * none until TikTok resolves it, so then the lone video of a one-video response is the link's: a
 * page of the feed always carries several.
 */
public final class LinkedVideo {
    /** How long after a link its video is still let through, covering a slow cold start. */
    static final long WINDOW_MS = 60_000L;

    /** The video id in a TikTok video page or the app's own detail address. */
    private static final Pattern VIDEO_ID = Pattern.compile(
            "/(?:share/video/|v/|@[^/]*/video/|detail/)([0-9]{1,20})/?");

    /** When the last link arrived, or zero once it is forgotten. */
    private static volatile long linkAt;
    /** The video that link named, or null for a short link. */
    private static volatile String linkedId;

    private static volatile Clock clock = SystemClock::elapsedRealtime;

    interface Clock {
        long now();
    }

    private LinkedVideo() {
    }

    /**
     * Called first thing in the main activity's onCreate. A start Android restores, or one
     * relaunched from the recent apps, carries the old link again, which the reader isn't
     * opening now.
     */
    public static void onCreate(Activity activity, Bundle savedState) {
        try {
            if (activity == null || savedState != null) return;
            Intent intent = activity.getIntent();
            if (intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
            note(intent);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read the link TikTok started with", failure);
        }
    }

    /** Called first thing in the main activity's onNewIntent, a link reaching the running app. */
    public static void onNewIntent(Intent intent) {
        try {
            note(intent);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read the link TikTok was handed", failure);
        }
    }

    static void note(Intent intent) {
        if (!FeedLock.isVideoLink(intent)) return;
        Uri data = intent.getData();
        String path = data == null ? null : data.getPath();
        Matcher id = path == null ? null : VIDEO_ID.matcher(path);
        linkedId = id != null && id.matches() ? id.group(1) : null;
        long now = clock.now();
        linkAt = now == 0 ? 1 : now;
        Logger.printDebug(() -> "Linked video: a link opened "
                + (linkedId == null ? "a short link" : "video " + linkedId));
    }

    /** Whether {@code item}, one of {@code size} videos in a For You response, is the one a recent link opened. */
    static boolean spares(Aweme item, int size) {
        long at = linkAt;
        if (at == 0 || item == null) return false;
        if (clock.now() - at > WINDOW_MS) {
            linkAt = 0;
            return false;
        }
        String id = linkedId;
        if (id != null) return id.equals(item.getAid());
        return size == 1;
    }

    static void setClockForTests(Clock testClock) {
        clock = testClock;
    }

    static void forgetForTests() {
        linkAt = 0;
        linkedId = null;
        clock = SystemClock::elapsedRealtime;
    }
}
