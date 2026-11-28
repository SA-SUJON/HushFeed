/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.interaction.BioMentions;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.live.LiveRoomInfo;
import app.morphe.extension.tiktok.playback.PictureInPicture;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * What a LIVE watched unseen still says about itself (#554). TikTok builds a room's host bar, with
 * the host's picture and name and how many are watching, from the answer to the room's enter, so a
 * room Watch LIVEs unseen holds back has none of it. This draws the same from the room TikTok
 * already loaded with the feed, and sends nothing: the host's picture and name at the top left,
 * which open their profile the way a link to it does, and under them how long the LIVE has been on
 * and how many people are watching. In the small window only that line stays, beside the video.
 *
 * <p>The room's own start time is read first. A room from the feed often has none until its enter
 * is answered, and then the start comes from the room's ID, which carries the time the room was
 * made in its top 32 bits, the way a post ID does. The viewer count is the one TikTok loaded with
 * the room, written whole when Show exact counts or Show exact LIVE viewer counts is on.
 *
 * <p>Redrawn with Ghost mode's eye ({@link LiveUnseenButton}), once a second. Main thread only.
 */
public final class LiveUnseenRoomBar {
    private LiveUnseenRoomBar() {
    }

    /** TikTok's own image view, which loads a picture through TikTok's image loader and its cache. */
    static final String IMAGE_VIEW = "com.bytedance.lighten.loader.SmartImageView";
    /** A start read from the room's ID longer ago than this is taken as a misread, not a LIVE. */
    static final long LONGEST_FROM_ID_MS = 7L * 24 * 60 * 60 * 1000;
    /** The host's picture, smallest first: the bar shows it 28dp across. */
    private static final String[][] PICTURES = {
            {"getAvatarThumb", "avatarThumb"},
            {"getAvatarMedium", "avatarMedium"},
    };
    private static final String SEPARATOR = " · ";
    private static final int SIDE_DP = 12;
    private static final int TOP_DP = 8;
    private static final int GAP_DP = 6;
    private static final int PICTURE_DP = 28;
    private static final int NAME_MAX_DP = 160;
    private static final int NAME_SP = 13;
    private static final int LINE_SP = 11;
    private static final int SMALL_SIDE_DP = 4;
    private static final int SMALL_LINE_SP = 9;

    // Everything below is touched on the main thread only.
    private static WeakReference<LinearLayout> barReference = new WeakReference<>(null);
    /** The room's activity while it's paused, when the bar stays off unless it's the small window. */
    private static WeakReference<Activity> pausedOn = new WeakReference<>(null);
    /** The host the bar on screen shows, which a tap opens. */
    private static Host drawnHost;
    /** Whether the bar on screen is laid out for the small window, or null before it's laid out. */
    private static Boolean drawnSmall;
    private static boolean noImageView;

    /** The room's host as the bar shows them. */
    static final class Host {
        final String name;
        final String handle;
        final String picture;

        Host(String name, String handle, String picture) {
            this.name = name;
            this.handle = handle;
            this.picture = picture;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Host)) return false;
            Host host = (Host) other;
            return name.equals(host.name) && Objects.equals(handle, host.handle)
                    && Objects.equals(picture, host.picture);
        }

        @Override public int hashCode() {
            return Objects.hash(name, handle, picture);
        }
    }

    /** Shows, hides or redraws the bar for {@code page}, the room page in front. Never throws. */
    static void refresh(Activity activity, Object page) {
        try {
            draw(activity, page, System.currentTimeMillis());
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not draw the LIVE's host and viewers for Ghost mode", failure);
            remove();
        }
    }

    private static void draw(Activity activity, Object page, long nowMs) {
        if (activity == null) return;
        Object room = page == null ? null : LiveRecordChip.roomOf(page);
        boolean small = inPictureInPicture(activity);
        boolean wanted = LiveUnseenButton.enabled() && LiveUnseen.unbuilt(LiveRecordChip.roomKey(room))
                && !activity.isFinishing() && !activity.isDestroyed()
                && !FeedVisibility.isFeedWindow(activity) && (small || pausedOn.get() != activity);
        Host host = wanted && !small ? host(room) : null;
        String line = wanted ? line(room, nowMs, small ? "\n" : SEPARATOR) : "";
        if (host == null && line.isEmpty()) {
            remove();
            return;
        }
        LinearLayout bar = barReference.get();
        if (bar == null || bar.getContext() != activity || bar.getParent() == null) {
            remove();
            bar = showBar(activity);
            if (bar == null) return;
        }
        LinearLayout pill = (LinearLayout) bar.getChildAt(0);
        TextView text = (TextView) bar.getChildAt(1);
        if (!Boolean.valueOf(small).equals(drawnSmall)) shape(text, small);
        if (host != null && !host.equals(drawnHost)) drawHost(pill, host);
        pill.setVisibility(host == null ? View.GONE : View.VISIBLE);
        if (!line.contentEquals(text.getText())) text.setText(line);
        text.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        place(bar, small);
        // Clear screen hides the room's controls, and a screen recording hides Hushfeed's own
        // buttons so they aren't filmed (#265); the bar goes with them.
        boolean hidden = LiveRecordChip.clearScreenOn() || LiveRecordChip.filmingScreen();
        bar.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    /** The room's activity is pausing: the bar goes, unless the room carries on in the small window. */
    static void paused(Activity activity) {
        pausedOn = new WeakReference<>(activity);
        if (!inPictureInPicture(activity)) remove();
    }

    static void resumed(Activity activity) {
        if (pausedOn.get() == activity) pausedOn = new WeakReference<>(null);
    }

    static void remove() {
        View bar = barReference.get();
        barReference = new WeakReference<>(null);
        drawnHost = null;
        drawnSmall = null;
        if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
    }

    private static boolean inPictureInPicture(Activity activity) {
        return activity != null && Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode();
    }

    /** The host as the room TikTok loaded has them, or null when it names no one. */
    static Host host(Object room) {
        Object owner = room == null ? null : Reflect.readField(room, "owner");
        if (owner == null) return null;
        String handle = Reflect.string(owner, "getUsername", "username");
        String name = Reflect.firstNonBlank(Reflect.string(owner, "getNickName", "nickName"), handle);
        return name == null ? null : new Host(name, handle, picture(owner));
    }

    /** The address of the host's picture, or null when the room has none. */
    static String picture(Object owner) {
        for (String[] size : PICTURES) {
            Object image = Reflect.property(owner, size[0], size[1]);
            Object urls = image == null ? null : Reflect.readField(image, "mUrls");
            if (!(urls instanceof List)) continue;
            for (Object url : (List<?>) urls) {
                if (url instanceof String && "https".equalsIgnoreCase(Uri.parse((String) url).getScheme())) {
                    return (String) url;
                }
            }
        }
        return null;
    }

    /**
     * "LIVE for 12:05 · 1.2K watching", each part only when the room has it, joined by
     * {@code separator}. Empty when it has neither.
     */
    static String line(Object room, long nowMs, String separator) {
        List<String> parts = new ArrayList<>(2);
        long started = startedAtMs(room, nowMs);
        if (started > 0) parts.add(L10n.f("LIVE for %1$s", LiveRoomInfo.runningTime(nowMs - started)));
        long viewers = viewers(room);
        if (viewers > 0) {
            parts.add(L10n.f("%1$s watching", viewerCount(viewers, exactViewers(), Locale.getDefault())));
        }
        return TextUtils.join(separator, parts);
    }

    /**
     * When the LIVE started, in milliseconds, or 0 when nothing says. The room's own start time
     * first, then the time its ID was made, when that's within the last week.
     */
    static long startedAtMs(Object room, long nowMs) {
        long started = LiveRoomInfo.startedAtMs(room);
        if (started > 0) return started;
        long fromId = AlwaysShowPublishDatePatch.fromId(LiveRecordChip.roomKey(room), nowMs) * 1000L;
        return fromId > 0 && nowMs - fromId <= LONGEST_FROM_ID_MS ? fromId : 0;
    }

    /** How many are watching as the room TikTok loaded says, or 0 when it doesn't. */
    static long viewers(Object room) {
        Object count = Reflect.readField(room, "userCount");
        return count instanceof Number ? ((Number) count).longValue() : 0;
    }

    private static boolean exactViewers() {
        return Settings.SHOW_EXACT_COUNTS.get()
                || (SettingsStatus.liveControlsEnabled && Settings.SHOW_EXACT_LIVE_VIEWERS.get());
    }

    /**
     * {@code count} whole and grouped ("1,234"), or the way TikTok shortens it ("1.2K", "12.3K",
     * "1M"), cut to a tenth and never rounded up past what's there.
     */
    static String viewerCount(long count, boolean exact, Locale locale) {
        if (exact || count < 1_000) return NumberFormat.getIntegerInstance(locale).format(count);
        long unit = count >= 1_000_000_000L ? 1_000_000_000L : count >= 1_000_000L ? 1_000_000L : 1_000L;
        String suffix = unit == 1_000_000_000L ? "B" : unit == 1_000_000L ? "M" : "K";
        NumberFormat tenths = NumberFormat.getNumberInstance(locale);
        tenths.setMinimumFractionDigits(0);
        tenths.setMaximumFractionDigits(1);
        return tenths.format(BigDecimal.valueOf(count / (unit / 10), 1)) + suffix;
    }

    /** TikTok's own link to {@code handle}'s profile, opened inside TikTok, or null for no username. */
    static Intent profileIntent(Context context, String handle) {
        String link = BioMentions.profileLink(handle);
        if (context == null || link == null) return null;
        // No user action: otherwise leaving for the profile reads as leaving TikTok, and the LIVE
        // shrinks into its small window.
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static void openHost(View pill) {
        Host host = drawnHost;
        Intent intent = profileIntent(pill.getContext(), host == null ? null : host.handle);
        if (intent == null) return;
        pill.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        try {
            pill.getContext().startActivity(intent);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open the LIVE host's profile", failure);
        }
    }

    private static LinearLayout showBar(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.START);

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int inset = SettingsUi.dp(activity, 4);
        pill.setPaddingRelative(inset, inset, SettingsUi.dp(activity, 12), inset);
        pill.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_ROUND_ENDS));
        pill.setFocusable(true);
        SettingsUi.markAsButton(pill);
        pill.setOnClickListener(LiveUnseenRoomBar::openHost);

        FrameLayout picture = new FrameLayout(activity);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(SettingsUi.OVERLAY_ACCENT);
        picture.setBackground(circle);
        picture.setClipToOutline(true);
        TextView initial = new TextView(activity);
        initial.setGravity(Gravity.CENTER);
        initial.setTextColor(SettingsUi.OVERLAY_TEXT);
        initial.setTypeface(Typeface.DEFAULT_BOLD);
        initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP);
        picture.addView(initial, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int side = SettingsUi.dp(activity, PICTURE_DP);
        pill.addView(picture, new LinearLayout.LayoutParams(side, side));

        TextView name = new TextView(activity);
        name.setTextColor(SettingsUi.OVERLAY_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(SettingsUi.dp(activity, NAME_MAX_DP));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.setMarginStart(SettingsUi.dp(activity, 8));
        pill.addView(name, nameParams);
        bar.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView line = new TextView(activity);
        line.setTextColor(SettingsUi.OVERLAY_TEXT);
        line.setMaxLines(3);
        line.setBackground(SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY));
        bar.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The line stays beside the video when Keep watching in a small window keeps the rest out.
        PictureInPicture.keepInLiveWindow(line);

        content.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        barReference = new WeakReference<>(bar);
        drawnHost = null;
        drawnSmall = null;
        return bar;
    }

    /** The line full size under the host, or smaller and alone in the small window. */
    private static void shape(TextView line, boolean small) {
        drawnSmall = small;
        Context context = line.getContext();
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? SMALL_LINE_SP : LINE_SP);
        int padding = SettingsUi.dp(context, small ? 2 : 3);
        line.setPadding(padding * 3, padding, padding * 3, padding);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) line.getLayoutParams();
        params.topMargin = small ? 0 : SettingsUi.dp(context, GAP_DP);
        line.setLayoutParams(params);
    }

    private static void drawHost(LinearLayout pill, Host host) {
        drawnHost = host;
        Context context = pill.getContext();
        FrameLayout picture = (FrameLayout) pill.getChildAt(0);
        ((TextView) picture.getChildAt(0)).setText(initial(host.name));
        while (picture.getChildCount() > 1) picture.removeViewAt(1);
        View image = pictureView(context, host.picture);
        if (image != null) {
            picture.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ((TextView) pill.getChildAt(1)).setText(host.name);
        boolean opens = BioMentions.profileLink(host.handle) != null;
        pill.setClickable(opens);
        pill.setContentDescription(opens ? L10n.f(context, "Open %1$s's profile", host.name) : host.name);
    }

    /** The first letter of the host's name, shown until or instead of their picture. */
    static String initial(String name) {
        if (name == null || name.isEmpty()) return "";
        return new String(Character.toChars(Character.toUpperCase(name.codePointAt(0))));
    }

    /**
     * TikTok's own image view loading {@code url}, or null when there's no picture or this TikTok
     * has no such view; the initial shows then.
     */
    private static View pictureView(Context context, String url) {
        if (url == null || noImageView) return null;
        try {
            Object view = Class.forName(IMAGE_VIEW, true, context.getClassLoader())
                    .getConstructor(Context.class).newInstance(context);
            if (!(view instanceof ImageView)) return null;
            ((ImageView) view).setImageURI(Uri.parse(url));
            return (View) view;
        } catch (Throwable unavailable) {
            noImageView = true;
            Logger.printInfo(() -> "No TikTok image view for the LIVE host's picture: " + unavailable);
            return null;
        }
    }

    /** Where the bar sits: under the status bar full size, in the corner in the small window. */
    private static void place(LinearLayout bar, boolean small) {
        Context context = bar.getContext();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        int side = SettingsUi.dp(context, small ? SMALL_SIDE_DP : SIDE_DP);
        int top = small ? side : belowStatusBar((View) bar.getParent()) + SettingsUi.dp(context, TOP_DP);
        if (params.getMarginStart() == side && params.topMargin == top) return;
        params.setMarginStart(side);
        params.topMargin = top;
        bar.setLayoutParams(params);
    }

    /** How far down {@code content} the status bar reaches, 0 when the room starts below it. */
    private static int belowStatusBar(View content) {
        if (content == null || Build.VERSION.SDK_INT < 23) return 0;
        WindowInsets insets = content.getRootWindowInsets();
        if (insets == null) return 0;
        int[] at = new int[2];
        content.getLocationInWindow(at);
        return Math.max(0, insets.getSystemWindowInsetTop() - at[1]);
    }

    static void resetForTests() {
        remove();
        pausedOn = new WeakReference<>(null);
        noImageView = false;
    }
}
