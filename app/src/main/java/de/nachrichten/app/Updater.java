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
    /** Kleine Datei im neuesten Release: zählt nicht zum Abfrage-Limit von GitHub. */
    static final String VERSION_FILE = "https://github.com/TillBlack/Nachrichten/releases/latest/download/version.json";
    /** Kontaktcode des Entwicklers für Hilfe (leer = Hinweis ausblenden). */
    static final String DEV_NPUB = "";
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
        if (obtainiumPackage(c) != null) return true;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://add/" + REPO));
        try {
            if (!c.getPackageManager().queryIntentActivities(i, 0).isEmpty()) return true;
        } catch (Exception ignored) { }
        return i.resolveActivity(c.getPackageManager()) != null;
    }

    private static String obtainiumPackage(Context c) {
        for (String p : OBTAINIUM_PKGS) {
            try { c.getPackageManager().getPackageInfo(p, 0); return p; } catch (Exception ignored) { }
        }
        // andere Bauarten (z. B. IzzyOnDroid): an der Startseite erkennen
        try {
            for (android.content.pm.PackageInfo pi : c.getPackageManager().getInstalledPackages(0)) {
                if (pi.packageName.startsWith("dev.imranr.obtainium")) return pi.packageName;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Obtainium öffnen: erst nach Updates suchen lassen, dann direkt die Seite von Nachrichten zeigen. */
    /** Direkt zu Obtainium: oeffnet die Nachrichten-Seite (prueft nur diese App) oder fragt beim ersten Mal nach dem Import. */
    static void openObtainium(Base a) {
        String json = "{\"id\":\"" + a.getPackageName() + "\",\"url\":\"" + REPO + "\",\"author\":\"TillBlack\",\"name\":\"Nachrichten\"}";
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://app/" + Uri.encode(json))));
            a.app.prefs.edit().putBoolean("obtainiumImported", true).apply();
            return;
        } catch (Exception ignored) { }
        // Ausweichweg: Obtainium einfach starten
        String pkg = obtainiumPackage(a);
        Intent launch = pkg == null ? null : a.getPackageManager().getLaunchIntentForPackage(pkg);
        try { if (launch != null) a.startActivity(launch); } catch (Exception ignored) { }
    }

    /** Hinweis: "Hilfe? Schreib dem Entwickler" mit Kontaktcode und Knopf. */
    static void addHelp(Base a, LinearLayout box, Dialog dlg) {
        if (DEV_NPUB.isEmpty()) return;
        String[] parsed = Keys.parseContact(DEV_NPUB);
        if (parsed == null || parsed[0].equals(a.app.keys.pubHex())) return;
        TextView h = a.text(T.helpText(), 13, Pal.dim, false);
        h.setPadding(0, a.dp(16), 0, a.dp(6));
        box.addView(h);
        TextView code = a.text(DEV_NPUB, 12, Pal.text, false);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setBackground(a.round(Pal.field, Pal.line, 10));
        code.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        box.addView(code);
        TextView write = a.outlineButton(T.helpWrite(), Pal.accent);
        write.setOnClickListener(v -> {
            dlg.dismiss();
            if (a.app.db.contact(parsed[0]) == null) a.app.db.addContact(parsed[0], T.developer());
            a.startActivity(new Intent(a, ChatActivity.class).putExtra("peer", parsed[0]));
        });
        a.addRow(box, write, 8);
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
    static void offerHelper(Base a, String url, long version) { offerHelper(a, url, version, false); }

    static void offerHelper(Base a, String url, long version, boolean firstRun) {
        Dialog dlg = a.dialog();
        LinearLayout box = a.panel();
        box.addView(a.text(firstRun ? T.firstObtainiumTitle() : T.helperTitle(), 19, Pal.text, true));
        TextView info = a.text(firstRun ? T.firstObtainiumText() : T.helperText(), 15, Pal.dim, false);
        info.setPadding(0, a.dp(8), 0, 0);
        info.setLineSpacing(0, 1.15f);
        box.addView(info);
        addHelp(a, box, dlg);
        LinearLayout buttons = a.buttonRow();
        TextView no = a.button(T.later(), false);
        no.setOnClickListener(v -> dlg.dismiss());
        a.addTo(buttons, no, true);
        if (firstRun) {
            // "Später" erst nach 5 Sekunden
            dlg.setCancelable(false);
            no.setEnabled(false);
            no.setAlpha(0.4f);
            final int[] left = {5};
            no.setText(T.later() + " (" + left[0] + ")");
            Runnable tick = new Runnable() {
                @Override public void run() {
                    left[0]--;
                    if (left[0] > 0) {
                        no.setText(T.later() + " (" + left[0] + ")");
                        no.postDelayed(this, 1000);
                    } else {
                        no.setText(T.later());
                        no.setEnabled(true);
                        no.setAlpha(1f);
                        dlg.setCancelable(true);
                    }
                }
            };
            no.postDelayed(tick, 1000);
        }
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
            // 1. Versuch: kleine Datei (kein Limit)
            try {
                JSONObject v = new JSONObject(new String(get(VERSION_FILE, 20_000), "UTF-8"));
                long latest = v.getLong("versionCode");
                String url = v.optString("apk");
                if (url.startsWith("https://github.com/TillBlack/")) {
                    cachedVersion = latest;
                    cachedUrl = url;
                    cachedAt = System.currentTimeMillis();
                    if (latest > installedVersion(a)) a.runOnUiThread(() -> { if (!a.isFinishing()) f.found(latest, url); });
                    return;
                }
            } catch (Exception ignored) { }
            // 2. Versuch: GitHub-API (älterer Weg)
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
            if (obtainium(a)) { openObtainium(a); Notifs.updateHint(a.app); }
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
        if (url.contains("api.github.com")) c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
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
