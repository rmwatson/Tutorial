package com.myhome.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything the launcher remembers: favourites (in order), hidden apps, theme and options. */
final class Prefs {
    private static final String FILE = "launcher";
    private static final String KEY_FAVOURITES = "favourites";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_THEME = "theme";
    private static final String KEY_TAKE_OVER_HOME = "take_over_home";
    private static final String KEY_COLUMNS = "columns";
    private static final String KEY_PAUSED_UNTIL = "paused_until";
    private static final String KEY_JF_SERVER = "jellyfin_server";
    private static final String KEY_JF_TOKEN = "jellyfin_token";
    private static final String KEY_JF_USER_ID = "jellyfin_user_id";
    private static final String KEY_JF_USER_NAME = "jellyfin_user_name";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_MY_APPS = "my_apps";
    private static final String KEY_ONLY_MY_APPS = "only_my_apps";
    private static final String KEY_MISSING = "my_apps_missing";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    List<String> favourites() {
        return split(sp.getString(KEY_FAVOURITES, ""));
    }

    void setFavourites(List<String> packages) {
        sp.edit().putString(KEY_FAVOURITES, TextUtils.join(",", packages)).apply();
    }

    boolean isFavourite(String pkg) {
        return favourites().contains(pkg);
    }

    void toggleFavourite(String pkg) {
        List<String> favs = favourites();
        if (!favs.remove(pkg)) favs.add(pkg);
        setFavourites(favs);
    }

    /** Moves a favourite left (delta = -1) or right (delta = +1). */
    void moveFavourite(String pkg, int delta) {
        List<String> favs = favourites();
        int from = favs.indexOf(pkg);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= favs.size()) return;
        favs.remove(from);
        favs.add(to, pkg);
        setFavourites(favs);
    }

    Set<String> hidden() {
        return new LinkedHashSet<>(split(sp.getString(KEY_HIDDEN, "")));
    }

    void setHidden(String pkg, boolean hide) {
        Set<String> hidden = hidden();
        if (hide) hidden.add(pkg); else hidden.remove(pkg);
        sp.edit().putString(KEY_HIDDEN, TextUtils.join(",", hidden)).apply();
    }

    int theme() {
        return sp.getInt(KEY_THEME, 0);
    }

    void setTheme(int index) {
        sp.edit().putInt(KEY_THEME, index).apply();
    }

    boolean takeOverHome() {
        return sp.getBoolean(KEY_TAKE_OVER_HOME, true);
    }

    void setTakeOverHome(boolean on) {
        sp.edit().putBoolean(KEY_TAKE_OVER_HOME, on).apply();
    }

    /** Lets the stock Google TV home stay open for a while without being replaced. */
    void pauseTakeOver(long millis) {
        sp.edit().putLong(KEY_PAUSED_UNTIL, System.currentTimeMillis() + millis).apply();
    }

    boolean takeOverPaused() {
        return System.currentTimeMillis() < sp.getLong(KEY_PAUSED_UNTIL, 0);
    }

    int columns() {
        return sp.getInt(KEY_COLUMNS, 5);
    }

    void setColumns(int columns) {
        sp.edit().putInt(KEY_COLUMNS, columns).apply();
    }

    // ---- "My apps": a short hand-picked list shown instead of Favourites + All apps

    /** Null until the list has been set up for the first time. */
    List<String> myApps() {
        String raw = sp.getString(KEY_MY_APPS, null);
        return raw == null ? null : split(raw);
    }

    void setMyApps(List<String> packages) {
        sp.edit().putString(KEY_MY_APPS, TextUtils.join(",", packages)).apply();
    }

    void moveMyApp(String pkg, int delta) {
        List<String> apps = myApps();
        if (apps == null) return;
        int from = apps.indexOf(pkg);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= apps.size()) return;
        apps.remove(from);
        apps.add(to, pkg);
        setMyApps(apps);
    }

    boolean onlyMyApps() {
        return sp.getBoolean(KEY_ONLY_MY_APPS, true);
    }

    void setOnlyMyApps(boolean on) {
        sp.edit().putBoolean(KEY_ONLY_MY_APPS, on).apply();
    }

    /** Names of starter apps that weren't installed when My apps was set up. */
    String missingApps() {
        return sp.getString(KEY_MISSING, "");
    }

    void setMissingApps(String names) {
        sp.edit().putString(KEY_MISSING, names).apply();
    }

    // ---- Jellyfin (the password is never stored, only the sign-in token)

    String jellyfinServer() { return sp.getString(KEY_JF_SERVER, null); }

    String jellyfinToken() { return sp.getString(KEY_JF_TOKEN, null); }

    String jellyfinUserId() { return sp.getString(KEY_JF_USER_ID, null); }

    String jellyfinUserName() { return sp.getString(KEY_JF_USER_NAME, null); }

    void setJellyfin(String server, String token, String userId, String userName) {
        sp.edit().putString(KEY_JF_SERVER, server).putString(KEY_JF_TOKEN, token)
                .putString(KEY_JF_USER_ID, userId).putString(KEY_JF_USER_NAME, userName).apply();
    }

    /** Signs out but remembers the server address for next time. */
    void clearJellyfin() {
        sp.edit().remove(KEY_JF_TOKEN).remove(KEY_JF_USER_ID).remove(KEY_JF_USER_NAME).apply();
    }

    String deviceId() {
        String id = sp.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            sp.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : Arrays.asList(s.split(","))) {
            if (!part.isEmpty() && !out.contains(part)) out.add(part);
        }
        return out;
    }
}
