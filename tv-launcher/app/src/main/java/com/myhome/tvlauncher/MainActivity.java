package com.myhome.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The home screen: clock, a row of favourite apps, then every app in a grid. */
public class MainActivity extends Activity {

    /** Background themes: name + gradient colours (top-left to bottom-right) + highlight colour. */
    private static final String[] THEME_NAMES = {"Midnight", "Ocean", "Sunset", "Forest", "Aurora", "Ember", "Graphite"};
    private static final int[][] THEME_COLOURS = {
            {0xFF0F2027, 0xFF203A43, 0xFF2C5364},
            {0xFF003973, 0xFF0B5D8F, 0xFF1C92D2},
            {0xFF2B1055, 0xFF7B2A6E, 0xFFD3545B},
            {0xFF0B2E1F, 0xFF134E32, 0xFF2D6A4F},
            {0xFF0B0F2B, 0xFF1D3E53, 0xFF2BA38E},
            {0xFF1F0A06, 0xFF5A1A0C, 0xFFB5451B},
            {0xFF141414, 0xFF262626, 0xFF3A3A3A},
    };
    private static final int[] THEME_ACCENTS = {
            0xFF8AB4F8, 0xFF9BE7FF, 0xFFFFC371, 0xFF95D5B2, 0xFF7CF5D0, 0xFFFFB38A, 0xFFFFFFFF,
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private ScrollView scroller;
    private TextView clock;
    private TextView date;
    private boolean dirty = true;
    /** Tag of the card to focus after rebuilding the screen, e.g. "fav:com.netflix.ninja". */
    private String focusTag;

    private Jellyfin jellyfin;
    private LinearLayout jellyfinSection;
    private List<Jellyfin.Item> jellyfinItems;
    private String jellyfinStatus;
    private int wideCardWidth;
    private int accentColour;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 10_000);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
            if (!isFinishing()) rebuild();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        jellyfin = new Jellyfin(prefs);
        root = new FrameLayout(this);
        setContentView(root);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dirty) rebuild();
        handler.post(tick);
        refreshJellyfin();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already here: jump back to the top.
        if (scroller != null) scroller.smoothScrollTo(0, 0);
        focusTag = null;
        focusFirstCard();
    }

    @Override
    public void onBackPressed() {
        // A home screen has nowhere to go back to; scroll to the top instead.
        if (scroller != null) scroller.smoothScrollTo(0, 0);
        focusFirstCard();
    }

    // ---------------------------------------------------------------- building the screen

    private void rebuild() {
        dirty = false;
        View focused = getCurrentFocus();
        if (focusTag == null && focused != null && focused.getTag() instanceof String) {
            focusTag = (String) focused.getTag();
        }

        int theme = Math.max(0, Math.min(prefs.theme(), THEME_NAMES.length - 1));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, THEME_COLOURS[theme]);
        root.setBackground(bg);
        root.removeAllViews();

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipChildren(false);
        scroller.setVerticalScrollBarEnabled(false);
        root.addView(scroller, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        int side = dp(48);
        page.setPadding(side, dp(28), side, dp(48));
        scroller.addView(page, new FrameLayout.LayoutParams(-1, -2));

        page.addView(buildHeader());

        List<AppEntry> apps = loadApps();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        for (AppEntry a : apps) byPackage.put(a.packageName, a);

        int accent = THEME_ACCENTS[theme];
        int columns = Math.max(3, Math.min(prefs.columns(), 8));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int gap = dp(20);
        int cardWidth = (screenWidth - 2 * side - (columns - 1) * gap) / columns;
        int cardHeight = cardWidth * 9 / 16;

        accentColour = accent;
        wideCardWidth = cardWidth * 5 / 4;

        // Continue watching (Jellyfin)
        jellyfinSection = null;
        if (jellyfin.isSignedIn()) {
            jellyfinSection = new LinearLayout(this);
            jellyfinSection.setOrientation(LinearLayout.VERTICAL);
            jellyfinSection.setClipChildren(false);
            jellyfinSection.setClipToPadding(false);
            page.addView(jellyfinSection, new LinearLayout.LayoutParams(-1, -2));
            fillJellyfinSection();
        }

        // Favourites row
        page.addView(sectionTitle("Favourites"));
        List<AppEntry> favs = new ArrayList<>();
        for (String pkg : prefs.favourites()) {
            AppEntry a = byPackage.get(pkg);
            if (a != null) favs.add(a);
        }
        if (favs.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Press and hold OK on any app below to add it here.");
            hint.setTextColor(0xB3FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            hint.setPadding(0, dp(8), 0, dp(16));
            page.addView(hint);
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPadding(0, dp(12), dp(24), dp(12));
            int favWidth = cardWidth * 5 / 4;
            int favHeight = favWidth * 9 / 16;
            for (int i = 0; i < favs.size(); i++) {
                View card = buildCard(favs.get(i), "fav:", favWidth, favHeight, accent);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(favWidth, -2);
                if (i > 0) lp.leftMargin = gap;
                row.addView(card, lp);
            }
            hs.addView(row);
            page.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        }

        // All apps grid
        Set<String> hidden = prefs.hidden();
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry a : apps) if (!hidden.contains(a.packageName)) visible.add(a);
        page.addView(sectionTitle("All apps"));
        LinearLayout row = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(10);
                page.addView(row, rlp);
            }
            View card = buildCard(visible.get(i), "all:", cardWidth, cardHeight, accent);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
            if (i % columns != 0) lp.leftMargin = gap;
            row.addView(card, lp);
        }

        updateClock();
        final String want = focusTag;
        focusTag = null;
        root.post(() -> {
            View target = want == null ? null : root.findViewWithTag(want);
            if (target == null && want != null && want.contains(":")) {
                // e.g. an app was just removed from favourites: focus it in the grid instead.
                target = root.findViewWithTag("all:" + want.substring(want.indexOf(':') + 1));
            }
            if (target != null) target.requestFocus(); else focusFirstCard();
        });
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = new TextView(this);
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        left.addView(clock);
        left.addView(date);
        header.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        header.addView(pill("Theme", v -> {
            prefs.setTheme((prefs.theme() + 1) % THEME_NAMES.length);
            Toast.makeText(this, "Theme: " + THEME_NAMES[prefs.theme()], Toast.LENGTH_SHORT).show();
            focusTag = "btn:Theme";
            rebuild();
        }));
        header.addView(pill("Options", v -> showOptions()));
        header.addView(pill("TV settings", v -> safeStart(new Intent(Settings.ACTION_SETTINGS))));
        return header;
    }

    private TextView pill(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTag("btn:" + text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(0x33FFFFFF);
        b.setBackground(shape);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            shape.setColor(has ? Color.WHITE : 0x33FFFFFF);
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(4));
        return t;
    }

    private View buildCard(final AppEntry app, String tagPrefix, int width, int height, int accent) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setClipChildren(false);

        final FrameLayout card = new FrameLayout(this);
        card.setTag(tagPrefix + app.packageName);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(10));
        cardBg.setColor(0x26FFFFFF);
        card.setBackground(cardBg);

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setColor(Color.TRANSPARENT);
        card.setForeground(ring);

        ImageView image = new ImageView(this);
        if (app.banner != null) {
            image.setImageDrawable(app.banner);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // No TV banner: show the app icon and name on a tile instead.
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            image.setImageDrawable(app.icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize = height / 2;
            tile.addView(image, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView name = new TextView(this);
            name.setText(app.label);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(8), dp(4), dp(8), 0);
            tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            card.addView(tile, new FrameLayout.LayoutParams(-1, -1));
        }
        wrapper.addView(card, new LinearLayout.LayoutParams(width, height));

        final TextView caption = new TextView(this);
        caption.setText(app.label);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setPadding(0, dp(8), 0, 0);
        caption.setAlpha(0f);
        wrapper.addView(caption, new LinearLayout.LayoutParams(width, -2));

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.1f : 1f;
            card.animate().scaleX(s).scaleY(s).translationZ(has ? dp(8) : 0).setDuration(150).start();
            caption.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            ring.setStroke(has ? dp(3) : 0, accent);
            if (has) keepVisible(card);
        });
        card.setOnClickListener(v -> launch(app));
        card.setOnLongClickListener(v -> {
            showAppMenu(app, (String) card.getTag());
            return true;
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
                showAppMenu(app, (String) card.getTag());
                return true;
            }
            return false;
        });
        return wrapper;
    }

    /** Scrolls so the focused card sits comfortably on screen. */
    private void keepVisible(View card) {
        if (scroller == null) return;
        int[] loc = new int[2];
        card.getLocationInWindow(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int top = loc[1];
        int bottom = top + card.getHeight();
        int margin = dp(90);
        if (top < margin) {
            scroller.smoothScrollBy(0, top - margin);
        } else if (bottom > screenH - margin) {
            scroller.smoothScrollBy(0, bottom - (screenH - margin));
        }
    }

    private void focusFirstCard() {
        View first = findFirstCard(root);
        if (first != null) first.requestFocus();
    }

    private View findFirstCard(View v) {
        if (v.getTag() instanceof String && ((String) v.getTag()).matches("(jf|fav|all):.*")) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findFirstCard(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        date.setText(greeting + "  ·  " + new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    // ---------------------------------------------------------------- Jellyfin

    private void refreshJellyfin() {
        if (!jellyfin.isSignedIn()) return;
        jellyfin.loadContinueWatching((items, error) -> {
            if (error != null) {
                jellyfinStatus = error;
                if (!jellyfin.isSignedIn()) jellyfinItems = null;
            } else {
                jellyfinStatus = null;
                jellyfinItems = items;
            }
            fillJellyfinSection();
        });
    }

    /** Redraws only the Continue watching row, keeping focus where it was. */
    private void fillJellyfinSection() {
        if (jellyfinSection == null) return;
        View focused = getCurrentFocus();
        String keep = null;
        if (focused != null && focused.getTag() instanceof String
                && ((String) focused.getTag()).startsWith("jf:")) {
            keep = (String) focused.getTag();
        }
        jellyfinSection.removeAllViews();
        jellyfinSection.addView(sectionTitle("Continue watching"));

        List<Jellyfin.Item> items = jellyfinItems;
        if (items == null || items.isEmpty()) {
            TextView hint = new TextView(this);
            String msg;
            if (jellyfinStatus != null && jellyfinStatus.contains("expired")) {
                msg = "Jellyfin sign-in expired. Sign in again from Options.";
            } else if (jellyfinStatus != null) {
                msg = jellyfinStatus + ".";
            } else if (items == null) {
                msg = "Loading from Jellyfin…";
            } else {
                msg = "Nothing in progress. Start something in Jellyfin and it'll show up here.";
            }
            hint.setText(msg);
            hint.setTextColor(0xB3FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            hint.setPadding(0, dp(8), 0, dp(16));
            jellyfinSection.addView(hint);
            return;
        }

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(12), dp(24), dp(12));
        int w = wideCardWidth;
        int h = w * 9 / 16;
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            if (i > 0) lp.leftMargin = dp(20);
            row.addView(buildMediaCard(items.get(i), w, h), lp);
        }
        hs.addView(row);
        jellyfinSection.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        if (keep != null) {
            View again = jellyfinSection.findViewWithTag(keep);
            if (again == null) again = findFirstCard(jellyfinSection);
            if (again != null) again.requestFocus();
        }
    }

    private View buildMediaCard(final Jellyfin.Item item, int width, int height) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setClipChildren(false);

        final FrameLayout card = new FrameLayout(this);
        card.setTag("jf:" + item.id);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(10));
        cardBg.setColor(0x26FFFFFF);
        card.setBackground(cardBg);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setColor(Color.TRANSPARENT);
        card.setForeground(ring);

        // Title shown on the tile until the artwork arrives (or if there is none).
        TextView placeholder = new TextView(this);
        placeholder.setText(item.title);
        placeholder.setTextColor(Color.WHITE);
        placeholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(12), 0, dp(12), 0);
        card.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (item.imageUrl != null) {
            jellyfin.loadImage(item.imageUrl, (Bitmap bmp, String err) -> {
                if (bmp != null) image.setImageBitmap(bmp);
            });
        }

        if (item.percent > 0) {
            View track = new View(this);
            track.setBackgroundColor(0x66000000);
            card.addView(track, new FrameLayout.LayoutParams(-1, dp(5), Gravity.BOTTOM));
            View bar = new View(this);
            bar.setBackgroundColor(accentColour);
            int barWidth = (int) (width * Math.min(100, item.percent) / 100);
            card.addView(bar, new FrameLayout.LayoutParams(Math.max(dp(4), barWidth), dp(5),
                    Gravity.BOTTOM | Gravity.START));
        }
        wrapper.addView(card, new LinearLayout.LayoutParams(width, height));

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(2), dp(10), dp(2), 0);
        wrapper.addView(title, new LinearLayout.LayoutParams(width, -2));
        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(item.subtitle);
            sub.setTextColor(0xB3FFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(dp(2), dp(2), dp(2), 0);
            wrapper.addView(sub, new LinearLayout.LayoutParams(width, -2));
        }

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            card.animate().scaleX(s).scaleY(s).translationZ(has ? dp(8) : 0).setDuration(150).start();
            ring.setStroke(has ? dp(3) : 0, accentColour);
            if (has) keepVisible(card);
        });
        card.setOnClickListener(v -> openInJellyfin(item.id));
        card.setOnLongClickListener(v -> {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add("Open in Jellyfin");
            actions.add(() -> openInJellyfin(item.id));
            labels.add("Refresh Continue watching");
            actions.add(this::refreshJellyfin);
            dialog(item.title, labels, actions);
            return true;
        });
        return wrapper;
    }

    /** Opens the film/episode page in the Jellyfin app, where Resume is the first button. */
    private void openInJellyfin(String itemId) {
        PackageManager pm = getPackageManager();
        for (String pkg : Jellyfin.APP_PACKAGES) {
            Intent i = new Intent()
                    .setClassName(pkg, Jellyfin.START_ACTIVITY)
                    .putExtra(Jellyfin.EXTRA_ITEM_ID, itemId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (i.resolveActivity(pm) != null) {
                safeStart(i);
                return;
            }
        }
        Toast.makeText(this, "Install the Jellyfin app from the Play Store first", Toast.LENGTH_LONG).show();
    }

    private void showJellyfinSignIn() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        final EditText server = field("Server, e.g. 192.168.1.10:8096", InputType.TYPE_TEXT_VARIATION_URI);
        String saved = prefs.jellyfinServer();
        if (saved != null) server.setText(saved);
        final EditText user = field("Username", InputType.TYPE_TEXT_VARIATION_NORMAL);
        final EditText pass = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(server);
        form.addView(user);
        form.addView(pass);

        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sign in to Jellyfin")
                .setView(form)
                .setPositiveButton("Sign in", (di, w) -> {
                    String s = server.getText().toString().trim();
                    if (s.isEmpty()) {
                        Toast.makeText(this, "Enter your server address", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "Signing in…", Toast.LENGTH_SHORT).show();
                    jellyfin.signIn(s, user.getText().toString().trim(), pass.getText().toString(),
                            (name, error) -> {
                                if (error != null) {
                                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Toast.makeText(this, "Signed in as " + name, Toast.LENGTH_SHORT).show();
                                jellyfinItems = null;
                                jellyfinStatus = null;
                                rebuild();
                                refreshJellyfin();
                            });
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0xF0202124));
    }

    private EditText field(String hint, int variation) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x99FFFFFF);
        return e;
    }

    // ---------------------------------------------------------------- apps

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> found = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) continue;
                if (found.containsKey(ai.packageName)) continue;
                Drawable banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                Drawable icon = ai.loadIcon(pm);
                String label = String.valueOf(ri.loadLabel(pm));
                found.put(ai.packageName, new AppEntry(ai.packageName, ai.name, category, label, banner, icon));
            }
        }
        List<AppEntry> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(new ComponentName(app.packageName, app.activityName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!safeStart(intent)) {
            Intent fallback = getPackageManager().getLeanbackLaunchIntentForPackage(app.packageName);
            if (fallback == null) fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (fallback != null) safeStart(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private boolean safeStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // ---------------------------------------------------------------- menus

    private void showAppMenu(final AppEntry app, final String tag) {
        final String pkg = app.packageName;
        final boolean fav = prefs.isFavourite(pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(fav ? "Remove from favourites" : "Add to favourites");
        actions.add(() -> { prefs.toggleFavourite(pkg); focusTag = (fav ? "all:" : "fav:") + pkg; rebuild(); });
        if (fav) {
            labels.add("Move left");
            actions.add(() -> { prefs.moveFavourite(pkg, -1); focusTag = "fav:" + pkg; rebuild(); });
            labels.add("Move right");
            actions.add(() -> { prefs.moveFavourite(pkg, 1); focusTag = "fav:" + pkg; rebuild(); });
        }
        labels.add("Hide from All apps");
        actions.add(() -> { prefs.setHidden(pkg, true); rebuild(); });
        labels.add("App info");
        actions.add(() -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        labels.add("Uninstall");
        actions.add(() -> safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        focusTag = tag;
        dialog(app.label, labels, actions);
    }

    private void showOptions() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final boolean takeOver = prefs.takeOverHome();

        labels.add("Home button opens My Home: " + (takeOver ? "ON" : "OFF"));
        actions.add(() -> {
            prefs.setTakeOverHome(!takeOver);
            Toast.makeText(this, takeOver ? "Home button: stock Google TV home" : "Home button: My Home",
                    Toast.LENGTH_SHORT).show();
        });
        labels.add("Set up Home button (Accessibility settings)");
        actions.add(() -> {
            Toast.makeText(this, "Turn on \"" + getString(R.string.service_name) + "\"", Toast.LENGTH_LONG).show();
            safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        labels.add("Apps per row: " + prefs.columns());
        actions.add(() -> {
            int next = prefs.columns() + 1;
            prefs.setColumns(next > 7 ? 4 : next);
            focusTag = "btn:Options";
            rebuild();
        });
        if (jellyfin.isSignedIn()) {
            labels.add("Jellyfin: signed in as " + prefs.jellyfinUserName() + " (sign out)");
            actions.add(() -> {
                jellyfin.signOut();
                jellyfinItems = null;
                Toast.makeText(this, "Signed out of Jellyfin", Toast.LENGTH_SHORT).show();
                rebuild();
            });
        } else {
            labels.add("Jellyfin: sign in for Continue watching");
            actions.add(this::showJellyfinSignIn);
        }
        final Set<String> hidden = prefs.hidden();
        labels.add("Hidden apps (" + hidden.size() + ")");
        actions.add(this::showHiddenApps);
        labels.add("Open stock Google TV home");
        actions.add(this::openStockHome);

        focusTag = "btn:Options";
        dialog("Options", labels, actions);
    }

    private void showHiddenApps() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (final String pkg : prefs.hidden()) {
            String label = pkg;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            labels.add("Show " + label);
            actions.add(() -> { prefs.setHidden(pkg, false); focusTag = "all:" + pkg; rebuild(); });
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show();
            return;
        }
        dialog("Hidden apps", labels, actions);
    }

    /** Opens Google's own home screen for 10 minutes, without changing any setting. */
    private void openStockHome() {
        prefs.pauseTakeOver(10 * 60 * 1000L);
        String[] stock = {"com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
                "com.google.android.leanbacklauncher"};
        for (String pkg : stock) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(i, 0) != null) {
                safeStart(i);
                Toast.makeText(this, "Google TV home for the next 10 minutes", Toast.LENGTH_LONG).show();
                return;
            }
        }
        Toast.makeText(this, "Stock home screen not found", Toast.LENGTH_SHORT).show();
    }

    private void dialog(String title, final List<String> labels, final List<Runnable> actions) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (di, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0xF0202124));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
