package com.myhome.tvlauncher;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads a few sensor values from Home Assistant for the home screen header. */
final class HomeAssistant {

    /** Header readings: label → entity id. */
    static final String[][] READINGS = {
            {"Outside", "sensor.gw3000c_outdoor_temperature"},
            {"Solar", "sensor.envoy_122531003368_current_power_production"},
            {"Battery", "sensor.envoy_122531003368_battery"},
    };

    /** The dashboard opened by the Home button, in kiosk mode (no header or sidebar). */
    static final String DASHBOARD_PATH = "/dashboard-tv/home?kiosk";

    interface Callback {
        /** label → formatted value (e.g. "10.5°", "1.2 kW", "47%"); null on failure. */
        void done(Map<String, String> values, String error);
    }

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    HomeAssistant(Prefs prefs) {
        this.prefs = prefs;
    }

    boolean isConfigured() {
        return prefs.haUrl() != null && prefs.haToken() != null;
    }

    /** Accepts "192.168.1.5:8123", "homeassistant.local:8123", full URLs… and returns a clean base URL. */
    static String normaliseUrl(String input) {
        String s = input.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        return s;
    }

    void loadReadings(final Callback cb) {
        final String base = prefs.haUrl();
        final String token = prefs.haToken();
        if (base == null || token == null) {
            main.post(() -> cb.done(null, "Not set up"));
            return;
        }
        NET.execute(() -> {
            Map<String, String> out = new LinkedHashMap<>();
            String error = null;
            for (String[] r : READINGS) {
                try {
                    JSONObject st = new JSONObject(get(base + "/api/states/" + r[1], token));
                    out.put(r[0], format(r[0], st));
                } catch (SecurityException e) {
                    error = "Home Assistant token rejected";
                    break;
                } catch (Exception e) {
                    out.put(r[0], "–");
                    if (error == null) error = "Can't reach Home Assistant";
                }
            }
            final String err = error;
            final boolean any = out.values().stream().anyMatch(v -> !"–".equals(v));
            main.post(() -> cb.done(any ? out : null, any ? null : err));
        });
    }

    private static String format(String label, JSONObject st) {
        String state = st.optString("state");
        JSONObject attrs = st.optJSONObject("attributes");
        String unit = attrs == null ? "" : attrs.optString("unit_of_measurement", "");
        double v;
        try {
            v = Double.parseDouble(state);
        } catch (NumberFormatException e) {
            return "–";
        }
        if (unit.contains("°")) return String.format(java.util.Locale.getDefault(), "%.1f°", v);
        if (unit.equals("%")) return Math.round(v) + "%";
        if (unit.equals("W") && Math.abs(v) >= 1000) {
            return String.format(java.util.Locale.getDefault(), "%.1f kW", v / 1000);
        }
        if (unit.equals("kW") || unit.equals("W")) {
            return String.format(java.util.Locale.getDefault(), unit.equals("kW") ? "%.1f kW" : "%.0f W", v);
        }
        return String.format(java.util.Locale.getDefault(), "%.1f %s", v, unit).trim();
    }

    private static String get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new SecurityException("HTTP " + code);
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
