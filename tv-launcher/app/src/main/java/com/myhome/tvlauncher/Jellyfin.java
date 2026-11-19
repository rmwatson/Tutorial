package com.myhome.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Talks to a Jellyfin server: signing in and fetching "continue watching" / "next up". */
final class Jellyfin {

    /** Package names of the Jellyfin Android TV app (normal and debug builds). */
    static final String[] APP_PACKAGES = {"org.jellyfin.androidtv", "org.jellyfin.androidtv.debug"};
    static final String START_ACTIVITY = "org.jellyfin.androidtv.ui.startup.StartupActivity";
    static final String EXTRA_ITEM_ID = "ItemId";

    static final class Item {
        String id;
        String title;
        String subtitle;
        String imageUrl;
        /** 0–100, or -1 for "not started" (next-up episodes). */
        double percent = -1;
    }

    interface Callback<T> {
        void done(T result, String error);
    }

    /** Thrown for HTTP errors so callers can tell "signed out" (401) from "server down". */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static final ExecutorService NET = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    Jellyfin(Prefs prefs) {
        this.prefs = prefs;
    }

    boolean isSignedIn() {
        return prefs.jellyfinToken() != null && prefs.jellyfinServer() != null;
    }

    /** Accepts "192.168.1.10:8096", "jellyfin.local" etc. and returns "http://host:port". */
    static String normaliseServer(String input) {
        String s = input.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        Uri u = Uri.parse(s);
        if (u.getPort() == -1 && "http".equals(u.getScheme()) && (u.getPath() == null || u.getPath().isEmpty())) {
            s = s + ":8096";
        }
        return s;
    }

    void signIn(final String serverInput, final String user, final String password, final Callback<String> cb) {
        NET.execute(() -> {
            try {
                String server = normaliseServer(serverInput);
                JSONObject body = new JSONObject().put("Username", user).put("Pw", password);
                JSONObject res = new JSONObject(request(server + "/Users/AuthenticateByName", null, body.toString()));
                String token = res.getString("AccessToken");
                JSONObject u = res.getJSONObject("User");
                prefs.setJellyfin(server, token, u.getString("Id"), u.optString("Name", user));
                post(cb, u.optString("Name", user), null);
            } catch (HttpError e) {
                post(cb, null, e.code == 401 ? "Wrong username or password." : "The server said: " + e.getMessage());
            } catch (Exception e) {
                post(cb, null, "Couldn't reach the server. Check the address (e.g. 192.168.1.10:8096).");
            }
        });
    }

    void signOut() {
        final String server = prefs.jellyfinServer();
        final String token = prefs.jellyfinToken();
        prefs.clearJellyfin();
        if (server == null || token == null) return;
        NET.execute(() -> {
            try {
                request(server + "/Sessions/Logout", token, "");
            } catch (Exception ignored) {
            }
        });
    }

    /** Resume items first, then next-up episodes of shows that aren't already there. */
    void loadContinueWatching(final Callback<List<Item>> cb) {
        final String server = prefs.jellyfinServer();
        final String token = prefs.jellyfinToken();
        final String userId = prefs.jellyfinUserId();
        if (server == null || token == null || userId == null) {
            post(cb, null, "Not signed in");
            return;
        }
        NET.execute(() -> {
            try {
                String common = "&Limit=16&Fields=PrimaryImageAspectRatio,Overview&ImageTypeLimit=1"
                        + "&EnableImageTypes=Primary,Backdrop,Thumb&EnableTotalRecordCount=false";
                JSONObject resume;
                try {
                    resume = new JSONObject(request(server + "/UserItems/Resume?userId=" + userId
                            + "&MediaTypes=Video" + common, token, null));
                } catch (HttpError e) {
                    if (e.code == 401) throw e;
                    // Servers older than 10.9 use the previous address.
                    resume = new JSONObject(request(server + "/Users/" + userId + "/Items/Resume?MediaTypes=Video"
                            + common, token, null));
                }
                List<Item> items = new ArrayList<>();
                Set<String> seenSeries = new HashSet<>();
                Set<String> seenIds = new HashSet<>();
                addItems(server, resume.optJSONArray("Items"), items, seenSeries, seenIds, false);
                try {
                    JSONObject nextUp = new JSONObject(request(server + "/Shows/NextUp?userId=" + userId
                            + common, token, null));
                    addItems(server, nextUp.optJSONArray("Items"), items, seenSeries, seenIds, true);
                } catch (Exception ignored) {
                    // Next up is a bonus; resume items alone are fine.
                }
                post(cb, items, null);
            } catch (HttpError e) {
                post(cb, null, e.code == 401 ? "Jellyfin sign-in expired" : "Jellyfin: " + e.getMessage());
            } catch (Exception e) {
                post(cb, null, "Can't reach Jellyfin right now");
            }
        });
    }

    private static void addItems(String server, JSONArray arr, List<Item> out, Set<String> seenSeries,
                                 Set<String> seenIds, boolean nextUp) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("Id");
            String seriesId = o.optString("SeriesId", "");
            if (id.isEmpty() || seenIds.contains(id)) continue;
            if (nextUp && !seriesId.isEmpty() && seenSeries.contains(seriesId)) continue;
            seenIds.add(id);
            if (!seriesId.isEmpty()) seenSeries.add(seriesId);

            Item item = new Item();
            item.id = id;
            boolean episode = "Episode".equals(o.optString("Type"));
            if (episode) {
                item.title = o.optString("SeriesName", o.optString("Name"));
                String se = "";
                if (o.has("ParentIndexNumber") && o.has("IndexNumber")) {
                    se = "S" + o.optInt("ParentIndexNumber") + ":E" + o.optInt("IndexNumber") + " · ";
                }
                item.subtitle = se + o.optString("Name");
            } else {
                item.title = o.optString("Name");
                int year = o.optInt("ProductionYear", 0);
                item.subtitle = year > 0 ? String.valueOf(year) : "";
            }

            JSONObject ud = o.optJSONObject("UserData");
            long runtime = o.optLong("RunTimeTicks", 0);
            if (ud != null && !nextUp) {
                long pos = ud.optLong("PlaybackPositionTicks", 0);
                item.percent = ud.optDouble("PlayedPercentage", runtime > 0 ? pos * 100.0 / runtime : 0);
                if (runtime > pos && pos > 0) {
                    long minsLeft = Math.max(1, (runtime - pos) / 600_000_000L);
                    item.subtitle = (item.subtitle.isEmpty() ? "" : item.subtitle + " · ") + minsLeft + " min left";
                }
            } else if (nextUp) {
                item.subtitle = "Next: " + item.subtitle;
            }
            item.imageUrl = pickImage(server, o, episode);
            out.add(item);
        }
    }

    /** Picks wide (16:9) artwork where there is some. */
    private static String pickImage(String server, JSONObject o, boolean episode) {
        String id = o.optString("Id");
        JSONObject tags = o.optJSONObject("ImageTags");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        String path = null;
        if (tags != null && tags.has("Thumb")) {
            path = "/Items/" + id + "/Images/Thumb?tag=" + tags.optString("Thumb");
        } else if (episode && tags != null && tags.has("Primary")) {
            path = "/Items/" + id + "/Images/Primary?tag=" + tags.optString("Primary");
        } else if (o.has("ParentThumbItemId")) {
            path = "/Items/" + o.optString("ParentThumbItemId") + "/Images/Thumb?tag=" + o.optString("ParentThumbImageTag");
        } else if (backdrops != null && backdrops.length() > 0) {
            path = "/Items/" + id + "/Images/Backdrop/0?tag=" + backdrops.optString(0);
        } else if (o.has("ParentBackdropItemId")) {
            JSONArray pb = o.optJSONArray("ParentBackdropImageTags");
            path = "/Items/" + o.optString("ParentBackdropItemId") + "/Images/Backdrop/0?tag="
                    + (pb != null && pb.length() > 0 ? pb.optString(0) : "");
        } else if (tags != null && tags.has("Primary")) {
            path = "/Items/" + id + "/Images/Primary?tag=" + tags.optString("Primary");
        }
        return path == null ? null : server + path + "&maxWidth=480&quality=85";
    }

    void loadImage(final String url, final Callback<Bitmap> cb) {
        Bitmap cached = IMAGES.get(url);
        if (cached != null) {
            cb.done(cached, null);
            return;
        }
        final String token = prefs.jellyfinToken();
        NET.execute(() -> {
            try {
                HttpURLConnection c = open(url, token);
                try (InputStream in = c.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) IMAGES.put(url, bmp);
                    post(cb, bmp, bmp == null ? "bad image" : null);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                post(cb, null, e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- HTTP

    private String authHeader(String token) {
        String h = "MediaBrowser Client=\"My Home\", Device=\"TV\", DeviceId=\"" + prefs.deviceId()
                + "\", Version=\"1.0\"";
        if (token != null) h += ", Token=\"" + token + "\"";
        return h;
    }

    private HttpURLConnection open(String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        String auth = authHeader(token);
        c.setRequestProperty("Authorization", auth);
        c.setRequestProperty("X-Emby-Authorization", auth);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    /** GET when body is null, otherwise POST the JSON body. */
    private String request(String url, String token, String body) throws IOException {
        HttpURLConnection c = open(url, token);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private <T> void post(final Callback<T> cb, final T result, final String error) {
        main.post(() -> cb.done(result, error));
    }
}
