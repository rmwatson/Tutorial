package com.myhome.tvlauncher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Shows the Home Assistant TV dashboard full screen. Signs in with the saved token, and adds
 * remote-control navigation: arrows move a highlight between buttons, OK presses, Back closes
 * pop-ups and then returns to My Home.
 */
public class HaActivity extends Activity {

    private WebView web;
    private String base;
    private String token;
    private boolean triedToken;

    /** Arrow-key navigation between everything clickable, including inside HA's shadow DOM. */
    private static final String NAV_JS = "(function(){\n"
            + "if (window.__tvnav) return; window.__tvnav = true;\n"
            + "var SEL = '[tabindex]:not([tabindex=\"-1\"]), button:not([disabled]), a[href], input, select, textarea';\n"
            + "var ring = document.createElement('div');\n"
            + "ring.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:3px solid #8ab4f8;"
            + "border-radius:12px;box-shadow:0 0 18px rgba(138,180,248,.7);transition:all .12s;display:none';\n"
            + "document.documentElement.appendChild(ring);\n"
            + "var cur = null;\n"
            + "function collect(root, out){ var all = root.querySelectorAll('*'); for (var i=0;i<all.length;i++){ var el = all[i];"
            + " if (el.matches(SEL)) out.push(el); if (el.shadowRoot) collect(el.shadowRoot, out);} return out; }\n"
            + "function rectOf(el){ var r = el.getBoundingClientRect(); return r; }\n"
            + "function usable(el){ var r = rectOf(el); if (r.width < 10 || r.height < 10) return false;"
            + " var cs = getComputedStyle(el); return cs.visibility !== 'hidden' && cs.display !== 'none'; }\n"
            + "function scope(){ var d = window.__tvDialog(); return d || document; }\n"
            + "function candidates(){ var sc = scope(); var list = collect(sc, []); if (sc !== document && sc.shadowRoot) collect(sc.shadowRoot, list);"
            + " list = list.filter(usable); var seen = {}; var out = [];"
            + " for (var i=list.length-1;i>=0;i--){ var r = rectOf(list[i]);"
            + " var k = Math.round(r.left/6)+','+Math.round(r.top/6)+','+Math.round(r.width/6)+','+Math.round(r.height/6);"
            + " if (!seen[k]) { seen[k] = 1; out.push(list[i]); } } return out.reverse(); }\n"
            + "function show(){ if (!cur || !cur.isConnected) { ring.style.display='none'; return; } var r = rectOf(cur);"
            + " ring.style.display='block'; ring.style.left=(r.left-4)+'px'; ring.style.top=(r.top-4)+'px';"
            + " ring.style.width=(r.width+2)+'px'; ring.style.height=(r.height+2)+'px'; }\n"
            + "function go(el){ cur = el; try { el.focus({preventScroll:true}); } catch(e){}"
            + " var r = rectOf(el); if (r.top < 60 || r.bottom > innerHeight - 40) el.scrollIntoView({block:'center'}); setTimeout(show, 30); setTimeout(show, 250); }\n"
            + "function move(dir){ var c = candidates(); if (!c.length) return;"
            + " if (!cur || !cur.isConnected || c.indexOf(cur) < 0) { c.sort(function(a,b){ var p=rectOf(a), q=rectOf(b); return (p.top-q.top)||(p.left-q.left); });"
            + "   var vis = c.filter(function(e){ var r=rectOf(e); return r.top >= 0 && r.top < innerHeight; }); go(vis[0] || c[0]); return; }\n"
            + " var a = rectOf(cur), ax = a.left + a.width/2, ay = a.top + a.height/2, best = null, bs = 1e9;\n"
            + " for (var i=0;i<c.length;i++){ var el = c[i]; if (el === cur) continue; var r = rectOf(el), x = r.left + r.width/2, y = r.top + r.height/2;"
            + "  var dx = x-ax, dy = y-ay, main, side;\n"
            + "  if (dir==='right'){ if (r.left < a.right - 4 && dx < 8) continue; main = dx; side = dy; }\n"
            + "  else if (dir==='left'){ if (r.right > a.left + 4 && dx > -8) continue; main = -dx; side = dy; }\n"
            + "  else if (dir==='down'){ if (r.top < a.bottom - 4 && dy < 8) continue; main = dy; side = dx; }\n"
            + "  else { if (r.bottom > a.top + 4 && dy > -8) continue; main = -dy; side = dx; }\n"
            + "  if (main <= 0) continue; var s = main + Math.abs(side) * 2.5; if (s < bs) { bs = s; best = el; } }\n"
            + " if (best) go(best); else if (dir==='down') scrollBy(0, 240); else if (dir==='up') scrollBy(0, -240); setTimeout(show, 60); }\n"
            + "window.__tvDialog = function(){ var found = null; (function walk(root){ var all = root.querySelectorAll('*');"
            + " for (var i=0;i<all.length;i++){ var el = all[i]; var t = el.tagName;"
            + "  if ((t.indexOf('DIALOG') >= 0 || t === 'HA-WA-DIALOG' || t === 'WA-DIALOG' || t === 'HA-MD-DIALOG') && el.hasAttribute('open')) found = el;"
            + "  if (el.shadowRoot) walk(el.shadowRoot); } })(document); return found; };\n"
            + "window.__tvDialogOpen = function(){ return !!window.__tvDialog(); };\n"
            + "window.addEventListener('keydown', function(e){ var m = {ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right'}[e.key];"
            + " if (!m) return; var t = e.composedPath()[0]; if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA') && (m === 'left' || m === 'right')) return;"
            + " e.preventDefault(); e.stopPropagation(); move(m); }, true);\n"
            + "window.addEventListener('scroll', show, true); window.addEventListener('resize', show);\n"
            + "setInterval(function(){ if (cur && !cur.isConnected) { cur = null; } show(); }, 500);\n"
            + "setTimeout(function(){ if (!cur) move('down'); }, 1500);\n"
            + "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs prefs = new Prefs(this);
        base = prefs.haUrl();
        token = prefs.haToken();
        if (base == null) {
            Toast.makeText(this, "Set up Home Assistant first: Options → Home Assistant", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay inside Home Assistant; ignore links to other sites.
                return !request.getUrl().toString().startsWith(base);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (token != null && !triedToken) injectToken();
                view.evaluateJavascript(NAV_JS, null);
            }
        });
        setContentView(web);
        web.requestFocus();
        web.loadUrl(base + HomeAssistant.DASHBOARD_PATH);
    }

    /**
     * Home Assistant's web page keeps its sign-in in local storage. Putting the long-lived token
     * there signs the page in without typing a password on the TV.
     */
    private void injectToken() {
        triedToken = true;
        String origin;
        Uri u = Uri.parse(base);
        origin = u.getScheme() + "://" + u.getAuthority();
        String js = "(function(){ try { var t = JSON.parse(localStorage.getItem('hassTokens') || 'null');"
                + " if (t && t.access_token === " + JSONObject.quote(token) + ") return 'ok';"
                + " localStorage.setItem('hassTokens', JSON.stringify({access_token: " + JSONObject.quote(token) + ","
                + " token_type: 'Bearer', expires_in: 315360000, hassUrl: " + JSONObject.quote(origin) + ","
                + " clientId: " + JSONObject.quote(origin + "/") + ", expires: Date.now() + 315360000000, refresh_token: ''}));"
                + " return 'set'; } catch (e) { return 'err'; } })()";
        web.evaluateJavascript(js, result -> {
            if (result != null && result.contains("set")) web.loadUrl(base + HomeAssistant.DASHBOARD_PATH);
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && web != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Back closes an open pop-up first; otherwise returns to My Home. */
    private void handleBack() {
        web.evaluateJavascript("window.__tvDialogOpen ? window.__tvDialogOpen() : false", result -> {
            if ("true".equals(result)) {
                web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
                web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
            } else {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
