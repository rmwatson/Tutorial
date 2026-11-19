package com.myhome.tvlauncher;

import android.graphics.drawable.Drawable;

/** One app on the TV. */
final class AppEntry {
    final String packageName;
    final String activityName;
    final String category;
    final String label;
    final Drawable banner; // wide 16:9 TV artwork, may be null
    final Drawable icon;   // square icon, used when there's no banner

    AppEntry(String packageName, String activityName, String category, String label,
             Drawable banner, Drawable icon) {
        this.packageName = packageName;
        this.activityName = activityName;
        this.category = category;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
    }
}
