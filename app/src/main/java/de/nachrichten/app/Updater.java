package de.nachrichten.app;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sucht beim Start auf GitHub nach einer neueren Version und bietet den Download an.
 * Die App installiert selbst nichts (keine Installations-Berechtigung): der Download läuft im Browser,
 * installiert wird wie gewohnt per Antippen. Android nimmt nur APKs mit demselben Schlüssel an.
 */
final class Updater {

    static final String RELEASE_API = "https://api.github.com/repos/TillBlack/Nachrichten/releases/tags/neueste";
    static final String ASSET = "Nachrichten.apk";
    static final String REPO = "https://github.com/TillBlack/Nachrichten";
    static final String OBTAINIUM_DOWNLOAD = "https://obtainium.imranr.dev";
    static final String[] OBTAINIUM_PKGS = {"dev.imranr.obtainium", "dev.imranr.obtainium.fdroid"};
    /** Zeitpunkt der letzten Prüfung (die App läuft oft im Hintergrund weiter, daher nicht nur einmal pro Prozess). */
    private static long lastCheck;

    static long installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ist Obtainium installiert (kann es obtainium://-Links öffnen)? */
    static boolean obtainium(Context c) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://add/" + REPO));
        if (i.resolveActivity(c.getPackageManager()) != null) return true;
        return obtainiumPackage(c) != null;
    }

    private static String obtainiumPackage(Context c) {
        for (String p : OBTAINIUM_PKGS) {
            try { c.getPackageManager().getPackageInfo(p, 0); return p; } catch (Exception ignored) { }
        }
        return null;
    }

    /** Obtainium öffnen: erst nach Updates suchen lassen, dann direkt die Seite von Nachrichten zeigen. */
    static void openObtainium(Base a) {
        Intent refresh = new Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://refresh?id=" + a.getPackageName()));
        Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://add/" + REPO));
        try {
            if (refresh.resolveActivity(a.getPackageManager()) != null) {
                a.startActivity(refresh);
                a.root.postDelayed(() -> { try { a.startActivity(page); } catch (Exception ignored) { } }, 1200);
            } else {
                a.startActivity(page);
            }
            return;
        } catch (Exception ignored) { }
        // falls die Links nicht gehen: Obtainium einfach öffnen
        String pkg = obtainiumPackage(a);
        Intent launch = pkg == null ? null : a.getPackageManager().getLaunchIntentForPackage(pkg);
        try { if (launch != null) a.startActivity(launch); } catch (Exception ignored) { }
    }

    /** Ohne Obtainium: Android lädt die Datei selbst herunter; ein Tipp auf die Benachrichtigung installiert. */
    private static void download(Base a, String url, long version) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(Uri.parse(url))
                    .setTitle("Nachrichten " + version)
                    .setDescription(T.downloadTap())
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(a, android.os.Environment.DIRECTORY_DOWNLOADS, "Nachrichten-" + version + ".apk");
            dm.enqueue(r);
            a.toast(T.downloadStarted());
        } catch (Exception e) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e2) { a.toast(T.noApp()); }
        }
    }

    /** Popup: Obtainium installieren (Weiterleitung zur Download-Seite). url != null: zusätzlich "Direkt herunterladen". */
    static void offerHelper(Base a, String url, long version) {
        Dialog dlg = a.dialog();
        LinearLayout box = a.panel();
        box.addView(a.text(T.helperTitle(), 19, Pal.text, true));
        TextView info = a.text(T.helperText(), 15, Pal.dim, false);
        info.setPadding(0, a.dp(8), 0, 0);
        info.setLineSpacing(0, 1.15f);
        box.addView(info);
        LinearLayout buttons = a.buttonRow();
        TextView no = a.button(T.later(), false);
        no.setOnClickListener(v -> dlg.dismiss());
        a.addTo(buttons, no, true);
        if (url != null) {
            TextView direct = a.button(T.directDownload(), false);
            direct.setOnClickListener(v -> { dlg.dismiss(); download(a, url, version); });
            a.addTo(buttons, direct, false);
        }
        TextView yes = a.button(T.helperInstall(), true);
        yes.setOnClickListener(v -> {
            dlg.dismiss();
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OBTAINIUM_DOWNLOAD))); } catch (Exception e) { a.toast(T.noApp()); }
        });
        a.addTo(buttons, yes, false);
        a.addRow(box, buttons, 18);
        a.showDialog(dlg, box);
    }

    interface Found { void found(long version, String url); }

    private static long cachedVersion, cachedAt;
    private static String cachedUrl;

    /** Fragt GitHub (mit kurzem Zwischenspeicher, damit das Limit von GitHub nicht erreicht wird). */
    private static void fetch(Base a, long maxAgeMs, Found f) {
        long now = System.currentTimeMillis();
        if (cachedAt > 0 && now - cachedAt < maxAgeMs) {
            if (cachedUrl != null && cachedVersion > installedVersion(a)) f.found(cachedVersion, cachedUrl);
            return;
        }
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(get(RELEASE_API, 200_000), "UTF-8"));
                Matcher m = Pattern.compile("versionCode\\s*=\\s*(\\d+)").matcher(rel.optString("body"));
                if (!m.find()) return;
                long latest = Long.parseLong(m.group(1));
                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (ASSET.equals(as.optString("name"))) url = as.optString("browser_download_url");
                }
                if (url == null || !url.startsWith("https://github.com/")) return;
                cachedVersion = latest;
                cachedUrl = url;
                cachedAt = System.currentTimeMillis();
                if (latest <= installedVersion(a)) return;
                String dl = url;
                a.runOnUiThread(() -> { if (!a.isFinishing()) f.found(latest, dl); });
            } catch (Exception ignored) { }
        }).start();
    }

    static boolean enabled(Base a) { return a.app.prefs.getBoolean("updates", true); }

    /** Beim Öffnen der App: großes Fenster (höchstens alle 10 Minuten). */
    static void checkOnStart(Base a) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 10 * 60 * 1000L || !enabled(a)) return;
        lastCheck = now;
        fetch(a, 0, (v, u) -> offer(a, v, u));
    }

    /** Nach dem Verlassen eines Chats: kleiner Hinweis unten rechts (kommt immer wieder, bis aktualisiert oder ausgeschaltet). */
    static void checkAfterChat(MainActivity a) {
        if (!enabled(a)) return;
        fetch(a, 2 * 60 * 1000L, (v, u) -> a.updateHint(v, () -> offer(a, v, u)));
    }

    static void offer(Base a, long version, String url) {
        Dialog dlg = a.dialog();
        LinearLayout box = a.panel();
        box.addView(a.text(T.updateTitle(), 19, Pal.text, true));
        TextView info = a.text(T.updateText(version, installedVersion(a)) + "\n\n" + (obtainium(a) ? T.updateHowObtainium() : T.updateHow()), 15, Pal.dim, false);
        info.setPadding(0, a.dp(8), 0, 0);
        info.setLineSpacing(0, 1.15f);
        box.addView(info);
        LinearLayout buttons = a.buttonRow();
        TextView later = a.button(T.later(), false);
        later.setOnClickListener(v -> dlg.dismiss());
        a.addTo(buttons, later, true);
        TextView go = a.button(T.updateNow(), true);
        go.setOnClickListener(v -> {
            dlg.dismiss();
            if (obtainium(a)) openObtainium(a);
            else offerHelper(a, url, version);
        });
        a.addTo(buttons, go, false);
        a.addRow(box, buttons, 18);
        a.showDialog(dlg, box);
    }

    private static byte[] get(String url, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Nachrichten");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() / 100 != 2) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new java.io.IOException("too large");
            }
            return out.toByteArray();
        }
    }
}
