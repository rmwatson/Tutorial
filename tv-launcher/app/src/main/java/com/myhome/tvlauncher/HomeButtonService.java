package com.myhome.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV doesn't let you pick a different home app. When this service is switched on
 * (Settings > System > Accessibility), it watches for the stock Google TV home screen and
 * opens My Home in its place, so the Home button on the remote lands here.
 */
public class HomeButtonService extends AccessibilityService {
    private long lastLaunch;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !isStockLauncher(pkg.toString())) return;
        Prefs prefs = new Prefs(this);
        if (!prefs.takeOverHome() || prefs.takeOverPaused()) return;

        // Don't fire repeatedly while the stock launcher draws several windows.
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 800) return;
        lastLaunch = now;

        openMyHome();
    }

    /**
     * Catches the remote's Home button before Google TV sees it and opens My Home directly,
     * so Google's home screen never appears on the way.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        Prefs prefs = new Prefs(this);
        if (!prefs.takeOverHome() || prefs.takeOverPaused()) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            lastLaunch = SystemClock.elapsedRealtime();
            openMyHome();
        }
        return true;
    }

    private void openMyHome() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(home);
    }

    static boolean isStockLauncher(String pkg) {
        return pkg.equals("com.google.android.apps.tv.launcherx")
                || pkg.equals("com.google.android.tvlauncher")
                || pkg.equals("com.google.android.leanbacklauncher");
    }

    @Override
    public void onInterrupt() {
    }
}
