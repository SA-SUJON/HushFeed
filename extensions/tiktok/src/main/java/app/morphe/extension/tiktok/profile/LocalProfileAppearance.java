/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ReplacementSpan;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Selects native presentation arguments. Account models and appeal actions stay native. */
@SuppressWarnings("unused")
public final class LocalProfileAppearance {
    public static final String LEGACY_NICKNAME = "user_account_user_name";
    public static final String NICKNAME = "user_info_nickname";
    public static final String NAVBAR_NICKNAME = "nav_bar_center_nickname";
    public static final String RESTRICTION_NOTICE = "user_account_ags_warning";
    // The declared host's native blue verified icon, also used by its older nickname renderer.
    // LocalProfileAppearanceAnchorsTest holds it to the patch's NATIVE_BLUE_BADGE.
    public static final int VERIFIED_BADGE = 0x7f01026f;
    /** Which header drew your own name's check, and whether a picked picture took its place (#549). */
    public static final String BADGE_PICTURE = "profile badge picture";
    /**
     * The component whose native render on this thread is drawing the local check. The render bridges
     * set it around TikTok's nickname renderers when TikTok had no badge of its own, and the older
     * header sets it from its own verified decision, so a real check is never swapped.
     */
    private static final ThreadLocal<String> localCheck = new ThreadLocal<>();
    private static final Map<Object, Binding> bindings = new WeakHashMap<>();
    private static final Map<Object, Binding> repaints = new WeakHashMap<>();
    private static final Map<Object, String> localTexts = new WeakHashMap<>();

    private static final class Binding {
        final String uid, component;
        Binding(String uid, String component) { this.uid = uid; this.component = component; }
    }

    /** A null native verification value stays unknown. Existing badges keep their native value. */
    @Nullable public static Boolean legacyBadge(@Nullable Boolean nativeVerified, String viewedUid,
            String component) {
        boolean local = Boolean.FALSE.equals(nativeVerified) && LEGACY_NICKNAME.equals(component)
                && ownProfile(viewedUid, Settings.LOCAL_PROFILE_VERIFIED_BADGE);
        // The older header places its check later in this same call, so this decision marks that render.
        if (local) localCheck.set(LEGACY_NICKNAME);
        else localCheck.remove();
        return local ? Boolean.TRUE : nativeVerified;
    }

    /** Just before TikTok's nickname renderer runs. Only a render TikTok gave no badge draws our check. */
    public static void beginBadgeRender(@Nullable Integer nativeBadge, String component) {
        if (nativeBadge == null && nickname(component)) localCheck.set(component);
        else localCheck.remove();
    }

    /** After the renderer, and before the older header decides again. */
    public static void endBadgeRender() {
        localCheck.remove();
    }

    /** The header's badge span, where TikTok's nickname renderer lays the name out as spans. */
    public static void headerBadgeSpan(SpannableStringBuilder text, Object span, int start, int end, int flags) {
        placeBadge(text, span, start, end, flags, NICKNAME, "header");
    }

    /** The header's badge span in TikTok's newer measured layout of the same name. */
    public static void headerLayoutBadgeSpan(SpannableStringBuilder text, Object span, int start, int end, int flags) {
        placeBadge(text, span, start, end, flags, NICKNAME, "header v2");
    }

    /** The name in the top bar that shows once the profile scrolls. */
    public static void navbarBadgeSpan(SpannableStringBuilder text, Object span, int start, int end, int flags) {
        placeBadge(text, span, start, end, flags, NAVBAR_NICKNAME, "top bar");
    }

    /** The older profile header's name. It sets its one badge once per decision, so the mark ends here. */
    public static void legacyBadgeSpan(SpannableStringBuilder text, Object span, int start, int end, int flags) {
        placeBadge(text, span, start, end, flags, LEGACY_NICKNAME, "older header");
        if (LEGACY_NICKNAME.equals(localCheck.get())) localCheck.remove();
    }

    /** Sets the span where TikTok would have, with the picked picture standing in for a check that's ours. */
    private static void placeBadge(SpannableStringBuilder text, Object span, int start, int end, int flags,
            String component, String path) {
        Object drawn = span;
        try {
            drawn = badgeSpan(span, component, path);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Badge picture failed on the " + path, failure);
        }
        // TikTok's own setSpan wrapper catches too, so a bad range never stops the name from drawing.
        try {
            text.setSpan(drawn, start, end, flags);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Badge span not placed on the " + path + ": " + failure);
        }
    }

    static Object badgeSpan(Object nativeSpan, String component, String path) {
        if (!component.equals(localCheck.get()) || !(nativeSpan instanceof ReplacementSpan)) {
            HookStatus.bound(BADGE_PICTURE, path + " native check");
            return nativeSpan;
        }
        ReplacementSpan picture = CustomBadge.span(Utils.getContext(), (ReplacementSpan) nativeSpan);
        HookStatus.bound(BADGE_PICTURE, path + (picture != null ? " picture shown" : " check kept"));
        return picture != null ? picture : nativeSpan;
    }

    /** Called again at deferred rendering, with the original native nullable resource. */
    @Nullable public static Integer badgeResource(@Nullable Integer nativeBadge, boolean noVerificationIcon,
            String viewedUid, String component) {
        if (nativeBadge != null || !noVerificationIcon || !nickname(component)) return nativeBadge;
        return ownProfile(viewedUid, Settings.LOCAL_PROFILE_VERIFIED_BADGE)
                ? Integer.valueOf(VERIFIED_BADGE) : null;
    }

    /** The native owner starts a new binding even when the switch is off. */
    public static synchronized void beginNicknameBind(Object owner, String viewedUid, String component) {
        localCheck.remove();
        if (owner != null && nickname(component)) bindings.put(owner, new Binding(viewedUid, component));
    }

    public static synchronized void captureNicknameRepaint(Object repaint, Object owner) {
        Binding binding = bindings.get(owner);
        if (repaint != null && binding != null) repaints.put(repaint, binding);
    }

    /** A reused owner must not replay another binding's captured nickname or account arrow. */
    public static synchronized boolean currentNicknameRepaint(Object repaint, Object owner,
            String viewedUid, String component) {
        Binding expected = repaints.get(repaint);
        return expected != null && expected == bindings.get(owner)
                && Objects.equals(expected.uid, viewedUid) && Objects.equals(expected.component, component);
    }

    public static synchronized boolean hasLocalNicknameText(Object owner) { return localTexts.containsKey(owner); }

    public static synchronized void clearLocalNicknameText(Object owner) { localTexts.remove(owner); }

    /** The image span is produced by the host's native icon factory. Keep all native click/tag spans. */
    public static SpannableStringBuilder badgeText(Object owner, SpannableStringBuilder nativeText,
            Object nativeBadgeSpan, String viewedUid, String component) {
        if (owner == null || nativeText == null || !(nativeBadgeSpan instanceof ReplacementSpan)
                || !nickname(component) || !ownProfile(viewedUid, Settings.LOCAL_PROFILE_VERIFIED_BADGE)) {
            clearLocalNicknameText(owner);
            return nativeText;
        }
        SpannableStringBuilder local = new SpannableStringBuilder(nativeText);
        local.append('\u00a0').append('\ufffc');
        // A badge picture you picked (#549) takes the blue check's place, in the check's own space.
        ReplacementSpan picture = CustomBadge.span(Utils.getContext(), (ReplacementSpan) nativeBadgeSpan);
        HookStatus.bound(BADGE_PICTURE, "add name" + (picture != null ? " picture shown" : " check kept"));
        local.setSpan(picture != null ? picture : nativeBadgeSpan, local.length() - 1, local.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        // A native factory or span copy can run application code. Revalidate before publication.
        if (!ownProfile(viewedUid, Settings.LOCAL_PROFILE_VERIFIED_BADGE)) {
            clearLocalNicknameText(owner);
            return nativeText;
        }
        synchronized (LocalProfileAppearance.class) { localTexts.put(owner, viewedUid); }
        return local;
    }

    /** Only the exact warning owner's attempt to show its own root can be hidden. */
    public static int noticeVisibility(int nativeVisibility, @Nullable View view,
            @Nullable View ownerRoot, String viewedUid, String component) {
        return nativeVisibility == View.VISIBLE && view != null && view == ownerRoot
                && RESTRICTION_NOTICE.equals(component)
                && ownProfile(viewedUid, Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE)
                ? View.GONE : nativeVisibility;
    }

    private static boolean ownProfile(String viewedUid, BooleanSetting setting) {
        if (!SettingsStatus.followStatusEnabled || !setting.get()) return false;
        String own = SignedInUser.id();
        if (!validUid(own) || !own.equals(viewedUid)) return false;
        // Reads are fresh. Pause and account changes can occur during a native bind.
        return setting.get() && SettingsStatus.followStatusEnabled && own.equals(SignedInUser.id());
    }

    private static boolean nickname(String component) {
        return NICKNAME.equals(component) || NAVBAR_NICKNAME.equals(component);
    }

    private static boolean validUid(String uid) {
        if (uid == null || uid.isEmpty() || uid.equals("0") || uid.length() > 128) return false;
        for (int i = 0; i < uid.length(); i++) {
            if (Character.isWhitespace(uid.charAt(i)) || Character.isISOControl(uid.charAt(i))) return false;
        }
        return true;
    }

    private LocalProfileAppearance() {}
}
