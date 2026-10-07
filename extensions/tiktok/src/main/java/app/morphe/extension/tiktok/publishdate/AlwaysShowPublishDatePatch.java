/*
 * Thanks to lyyako for the original implementation and help with this patch.
 *
 * Originally adapted for TikTok 43.8.3; ported to TikTok 46.2.3:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.publishdate;

import android.content.Context;
import android.text.format.DateUtils;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Always show publish date, and its two options: the time to the second, with the time zone, on
 * the creator's row, and each video's date on a profile's grid.
 *
 * <p>Both read the time TikTok sent with the video (Aweme.createTime, Unix seconds). A video
 * without one still carries it in its ID, whose top 32 bits are the posting time in Unix seconds,
 * so that's where it comes from then. Everything is written the way the phone's language and time
 * zone write a date.
 */
public final class AlwaysShowPublishDatePatch {
    /** A post ID's top 32 bits are its posting time in Unix seconds. */
    private static final int ID_TIME_SHIFT = 32;

    /** 2016-01-01 UTC. Nothing on TikTok was posted before it, so an earlier time isn't one. */
    static final long EARLIEST_POST_SECONDS = 1_451_606_400L;

    /** A phone clock a day behind still sees every post as already made. */
    private static final long CLOCK_SLACK_SECONDS = 86_400L;

    /** Above this a createTime is in milliseconds, the way some older models carried it. */
    private static final long MILLISECONDS_FROM = 100_000_000_000L;

    private AlwaysShowPublishDatePatch() {
    }

    public static boolean showPostTimeForMainFeeds(boolean original) {
        return Settings.ALWAYS_SHOW_PUBLISH_DATE.get() ? false : original;
    }

    /**
     * Called where the creator's row has its post time text ready ("2d ago", "10-7"). Returns
     * that text, or the full date and time to the second with the time zone while the switch is on.
     */
    public static String postTime(String original, Object item) {
        if (!Settings.PUBLISH_DATE_EXACT_TIME.get()) {
            return original;
        }
        try {
            long seconds = postedAt(item, System.currentTimeMillis());
            return seconds > 0 ? exactTime(seconds, Locale.getDefault(), TimeZone.getDefault()) : original;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write the exact posting time", ex);
            return original;
        }
    }

    /**
     * The date a profile grid cell shows under its view count ("Oct 7", or "Oct 7, 2025" from
     * another year), or null when the switch is off or the video has no time.
     */
    public static String gridDate(Object item) {
        if (!SettingsStatus.alwaysShowPublishDateEnabled || !Settings.PUBLISH_DATE_ON_GRID.get()) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            long seconds = postedAt(item, now);
            return seconds > 0 ? shortDate(Utils.getContext(), seconds, now) : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write a grid cell's date", ex);
            return null;
        }
    }

    /** When the item was posted, in Unix seconds: its createTime, else what its ID says, else 0. */
    static long postedAt(Object item, long nowMillis) {
        if (!(item instanceof Aweme)) {
            return 0;
        }
        Aweme aweme = (Aweme) item;
        long created;
        try {
            created = aweme.getCreateTime();
        } catch (Throwable ex) {
            // A build without the getter still has the ID.
            created = 0;
        }
        if (created >= MILLISECONDS_FROM) {
            created /= 1000;
        }
        if (plausible(created, nowMillis)) {
            return created;
        }
        String id;
        try {
            id = aweme.getAid();
        } catch (Throwable ex) {
            return 0;
        }
        return fromId(id, nowMillis);
    }

    /** The posting time a post ID carries, in Unix seconds, or 0 when it isn't one. */
    static long fromId(String id, long nowMillis) {
        if (id == null) {
            return 0;
        }
        long value;
        try {
            value = Long.parseLong(id.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
        long seconds = value >>> ID_TIME_SHIFT;
        return plausible(seconds, nowMillis) ? seconds : 0;
    }

    private static boolean plausible(long seconds, long nowMillis) {
        return seconds >= EARLIEST_POST_SECONDS && seconds <= nowMillis / 1000 + CLOCK_SLACK_SECONDS;
    }

    /**
     * The date with the time to the second and the zone, the way the phone's language writes them:
     * "Oct 7, 2025, 3:04:05 PM EDT" in American English, "07.10.2025, 15:04:05 MESZ" in German.
     */
    static String exactTime(long seconds, Locale locale, TimeZone zone) {
        // A new instance per call: DateFormat isn't thread safe.
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.LONG, locale);
        format.setTimeZone(zone);
        return format.format(new Date(seconds * 1000L));
    }

    /**
     * A short date in the phone's language and time zone: the month and day for this year
     * ("Oct 7"), with the year for any other ("Oct 7, 2025").
     */
    static String shortDate(Context context, long seconds, long nowMillis) {
        long millis = seconds * 1000L;
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                | (sameYear(millis, nowMillis) ? DateUtils.FORMAT_NO_YEAR : DateUtils.FORMAT_SHOW_YEAR);
        return DateUtils.formatDateTime(context, millis, flags);
    }

    private static boolean sameYear(long millis, long nowMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        int year = calendar.get(Calendar.YEAR);
        calendar.setTimeInMillis(nowMillis);
        return year == calendar.get(Calendar.YEAR);
    }
}
