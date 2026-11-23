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
    static final String HELPER = "de.tillblack.updater";
    static final String HELPER_APK = "https://github.com/TillBlack/updater/releases/download/neueste/Updater.apk";
    private static boolean checkedThisRun;

    static long installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean helperInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(HELPER, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Nach der Installation einmal fragen, ob der Helfer mitinstalliert werden soll. */
    static void offerHelper(Base a) {
        if (helperInstalled(a) || a.app.prefs.getBoolean("helperAsked", false)) return;
        a.app.prefs.edit().putBoolean("helperAsked", true).apply();
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
        TextView yes = a.button(T.helperInstall(), true);
        yes.setOnClickListener(v -> {
            dlg.dismiss();
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(HELPER_APK))); } catch (Exception e) { a.toast(T.noApp()); }
        });
        a.addTo(buttons, yes, false);
        a.addRow(box, buttons, 18);
        a.showDialog(dlg, box);
    }

    /** Einmal pro App-Start prüfen (wenn in den Einstellungen nicht ausgeschaltet). */
    static void checkOnStart(Base a) {
        if (checkedThisRun || !a.app.prefs.getBoolean("updates", true)) return;
        checkedThisRun = true;
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(get(RELEASE_API, 200_000), "UTF-8"));
                Matcher m = Pattern.compile("versionCode\\s*=\\s*(\\d+)").matcher(rel.optString("body"));
                if (!m.find()) return;
                long latest = Long.parseLong(m.group(1));
                if (latest <= installedVersion(a)) return;
                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (ASSET.equals(as.optString("name"))) url = as.optString("browser_download_url");
                }
                if (url == null || !url.startsWith("https://github.com/")) return;
                String dl = url;
                a.runOnUiThread(() -> { if (!a.isFinishing()) offer(a, latest, dl); });
            } catch (Exception ignored) { }
        }).start();
    }

    private static void offer(Base a, long version, String url) {
        Dialog dlg = a.dialog();
        LinearLayout box = a.panel();
        box.addView(a.text(T.updateTitle(), 19, Pal.text, true));
        TextView info = a.text(T.updateText(version, installedVersion(a)) + (helperInstalled(a) ? "" : "\n\n" + T.updateHow()), 15, Pal.dim, false);
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
            if (helperInstalled(a)) {
                // der Helfer lädt, prüft und übergibt an Android; danach schließt er sich
                try {
                    a.startActivity(new Intent("de.tillblack.updater.INSTALL").setPackage(HELPER)
                            .putExtra("url", url).putExtra("package", a.getPackageName()).putExtra("name", "Nachrichten"));
                    return;
                } catch (Exception ignored) { }
            }
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                a.toast(T.noApp());
            }
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
