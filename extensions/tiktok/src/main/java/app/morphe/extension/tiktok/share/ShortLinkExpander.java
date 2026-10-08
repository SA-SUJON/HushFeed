/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.LinkResolver;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Copy the full link for short links: a vt.tiktok.com or vm.tiktok.com link TikTok hands out is
 * opened once in the background, the way a browser opening it would, and the full video link it
 * points to replaces it on the clipboard. The share itself goes out at once as TikTok made it.
 * TikTok may make the link as the sheet opens rather than at Copy link, so the swap waits a short
 * while for the clipboard to hold that link and only ever replaces that text: a link sent to
 * another app, or something else copied, is left alone. The link is opened through the media
 * transport, with its checks on every redirect, and the page it lands on is never read; only a
 * landing on TikTok's own site over HTTPS is taken.
 */
public final class ShortLinkExpander {
    private static final List<String> SHORT_HOSTS = Arrays.asList("vm.tiktok.com", "vt.tiktok.com");
    private static final List<String> TIKTOK_HOSTS = Arrays.asList(
            "tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com");
    /** How long after the link is made a Copy link still gets the full one. */
    static final long WATCH_MS = 15_000;
    static final String FULL_LINK_COPIED = "Full link copied";
    /** The last short link opened and where it led, so a sheet that asks again doesn't reopen it. */
    private static String lastShort, lastFull;

    /** Where a link ends up after its redirects. */
    interface Resolver {
        String finalUrl(String url) throws IOException;
    }

    static final Resolver NETWORK = LinkResolver::finalUrl;

    private ShortLinkExpander() {
    }

    /** True for a short TikTok link with the switch on, which is when a swap is tried. */
    static boolean wants(@Nullable String url) {
        return url != null && isShort(url) && Settings.EXPAND_SHORT_SHARE_LINKS.get();
    }

    static boolean isShort(String url) {
        return url.startsWith("https://") && SHORT_HOSTS.contains(host(url));
    }

    /**
     * From the share link rewrite: {@code copied} is what TikTok goes on to share or copy, and
     * {@code shortLink} the short link it was made from.
     */
    static void later(String copied, String shortLink) {
        String known = known(shortLink);
        if (known != null) {
            Utils.runOnMainThread(() -> watch(Utils.getContext(), copied, rewrite(known)));
            return;
        }
        Utils.runOnOwnThread("Hushfeed short link", () -> {
            String full = expand(shortLink, NETWORK);
            if (full == null) return;
            remember(shortLink, full);
            Utils.runOnMainThread(() -> watch(Utils.getContext(), copied, rewrite(full)));
        });
    }

    private static String rewrite(String full) {
        return ShareUrlSanitizer.stripAllQueryParams(ShareUrlSanitizer.withCustomDomain(full));
    }

    @Nullable
    private static synchronized String known(String shortLink) {
        return shortLink.equals(lastShort) ? lastFull : null;
    }

    private static synchronized void remember(String shortLink, String full) {
        lastShort = shortLink;
        lastFull = full;
    }

    static synchronized void forgetForTests() {
        lastShort = null;
        lastFull = null;
    }

    /**
     * Swaps {@code full} in for {@code copied} if the clipboard holds it now, or as soon as it does
     * within {@link #WATCH_MS}. Main thread.
     */
    static void watch(@Nullable Context context, String copied, String full) {
        if (context == null) return;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        if (swap(clipboard, copied, full)) return;
        ClipboardManager.OnPrimaryClipChangedListener[] listener = new ClipboardManager.OnPrimaryClipChangedListener[1];
        listener[0] = () -> {
            // Off before the swap, whose own change would otherwise come back here.
            if (!holds(clipboard, copied)) return;
            clipboard.removePrimaryClipChangedListener(listener[0]);
            swap(clipboard, copied, full);
        };
        clipboard.addPrimaryClipChangedListener(listener[0]);
        Utils.runOnMainThreadDelayed(() -> clipboard.removePrimaryClipChangedListener(listener[0]), WATCH_MS);
    }

    /**
     * Where the short link {@code url} leads, when that's a page on TikTok's own site over HTTPS
     * other than another short link or TikTok's old /v/ page. Null otherwise.
     */
    @Nullable
    static String expand(String url, Resolver resolver) {
        if (!isShort(url)) return null;
        String landed;
        try {
            landed = resolver.finalUrl(url);
        } catch (IOException | RuntimeException failure) {
            Logger.printInfo(() -> "Could not open a short share link", failure);
            return null;
        }
        if (landed == null || !landed.startsWith("https://")) return null;
        String host = host(landed);
        if (!TIKTOK_HOSTS.contains(host) || SHORT_HOSTS.contains(host) || path(landed).startsWith("/v/")) return null;
        return landed;
    }

    private static boolean holds(ClipboardManager clipboard, String copied) {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;
            CharSequence text = clip.getItemAt(0).getText();
            return text != null && text.toString().contains(copied);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Puts {@code full} in place of {@code copied} when the clipboard holds it. */
    private static boolean swap(ClipboardManager clipboard, String copied, String full) {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || !text.toString().contains(copied)) return false;
            CharSequence label = clip.getDescription() == null ? null : clip.getDescription().getLabel();
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text.toString().replace(copied, full)));
            Utils.showToastShort(L10n.t(FULL_LINK_COPIED));
            return true;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not swap in the full share link", failure);
            return false;
        }
    }

    private static String host(String url) {
        int start = url.indexOf("://");
        if (start < 0) return "";
        start += 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        return url.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static String path(String url) {
        int start = url.indexOf("://");
        int slash = start < 0 ? -1 : url.indexOf('/', start + 3);
        return slash < 0 ? "" : url.substring(slash);
    }
}
