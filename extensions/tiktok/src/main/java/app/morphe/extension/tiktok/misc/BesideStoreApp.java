/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;

/**
 * Device registration for a TikTok that runs under another package name, beside the store app.
 *
 * <p>Morphe's Clone app patch renames the package so Android installs the copy next to the store
 * app. TikTok's AppLog then puts the new name in the {@code package} field of the header every
 * {@code device_register} request carries, and TikTok's servers answer a package they don't know
 * with device and install ids of 0, so signing in and everything after it fails. Reporting
 * {@value #STORE_PACKAGE} there gets real ids back (niposch/revanced-tiktok-patches measured it
 * with only that field changed). {@code real_package_name}, the signature hash and the rest of the
 * header still say what is actually installed.
 *
 * <p>Only the one write of the running package into {@code package} comes here. A build that kept
 * TikTok's package name, a paused Hushfeed and a call before Hushfeed has a context (when the
 * pause hasn't been decided yet) all write what TikTok would have.
 */
@SuppressWarnings("unused")
public final class BesideStoreApp {
    /** The package the store app installs as, the one TikTok's servers register devices under. */
    static final String STORE_PACKAGE = "com.zhiliaoapp.musically";
    /** The header field TikTok's servers read the app's identity from. */
    static final String PACKAGE_KEY = "package";

    private static volatile boolean logged;

    private BesideStoreApp() {
    }

    /**
     * In place of the {@code header.put("package", context.getPackageName())} in AppLog's package
     * header. Same arguments and result as the call it replaces, which throws as it always did.
     */
    public static JSONObject putPackage(JSONObject header, String key, Object value) throws JSONException {
        return header.put(key, reported(key, value));
    }

    /** What goes into the header for {@code key}: the store package for a renamed copy's own name. */
    @Nullable
    static Object reported(@Nullable String key, @Nullable Object value) {
        if (!PACKAGE_KEY.equals(key) || !(value instanceof String running) || !renamed(running)) return value;
        if (!logged) {
            logged = true;
            Logger.printInfo(() -> "Beside the store app: registering " + running + " as " + STORE_PACKAGE);
        }
        return STORE_PACKAGE;
    }

    /** Whether {@code running} is this app's own package and isn't the store app's, unpaused. */
    static boolean renamed(String running) {
        Context context = Utils.getContext();
        if (context == null || Setting.isPaused() || STORE_PACKAGE.equals(running)) return false;
        return running.equals(context.getPackageName());
    }
}
