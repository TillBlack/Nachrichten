package de.nachrichten.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sucht beim Start auf GitHub nach einer neueren Version und installiert sie nach Bestätigung.
 * Android prüft dabei die Signatur: nur APKs mit demselben Schlüssel werden angenommen.
 */
final class Updater {

    static final String RELEASE_API = "https://api.github.com/repos/TillBlack/Nachrichten/releases/tags/neueste";
    static final String ASSET = "Nachrichten.apk";
    private static boolean checkedThisRun;

    static long installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
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
                long size = 0;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (ASSET.equals(as.optString("name"))) { url = as.optString("browser_download_url"); size = as.optLong("size"); }
                }
                if (url == null || !url.startsWith("https://github.com/")) return;
                String dl = url;
                long sz = size;
                a.runOnUiThread(() -> { if (!a.isFinishing()) offer(a, latest, dl, sz); });
            } catch (Exception ignored) { }
        }).start();
    }

    private static void offer(Base a, long version, String url, long size) {
        Dialog dlg = a.dialog();
        LinearLayout box = a.panel();
        box.addView(a.text(T.updateTitle(), 19, Pal.text, true));
        TextView info = a.text(T.updateText(version, installedVersion(a)), 15, Pal.dim, false);
        info.setPadding(0, a.dp(8), 0, 0);
        box.addView(info);
        TextView status = a.text("", 14, Pal.accent, false);
        status.setPadding(0, a.dp(8), 0, 0);
        box.addView(status);
        LinearLayout buttons = a.buttonRow();
        TextView later = a.button(T.later(), false);
        later.setOnClickListener(v -> dlg.dismiss());
        a.addTo(buttons, later, true);
        TextView go = a.button(T.updateNow(), true);
        go.setOnClickListener(v -> {
            if (!a.getPackageManager().canRequestPackageInstalls()) {
                // einmalig erlauben, dass diese App Updates installieren darf
                Toast.makeText(a, T.allowInstall(), Toast.LENGTH_LONG).show();
                try {
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                } catch (Exception ignored) { }
                return;
            }
            go.setEnabled(false);
            later.setEnabled(false);
            status.setText(T.downloading());
            new Thread(() -> {
                boolean ok = download(a, url, size, p -> a.runOnUiThread(() -> status.setText(T.downloading() + " " + p + " %")));
                a.runOnUiThread(() -> {
                    if (ok) dlg.dismiss();
                    else {
                        status.setText(T.updateFailed());
                        go.setEnabled(true);
                        later.setEnabled(true);
                    }
                });
            }).start();
        });
        a.addTo(buttons, go, false);
        a.addRow(box, buttons, 18);
        dlg.setCanceledOnTouchOutside(false);
        a.showDialog(dlg, box);
    }

    interface Progress { void at(int percent); }

    /** Lädt die APK direkt in eine Installations-Sitzung und startet die Bestätigung von Android. */
    private static boolean download(Activity a, String url, long size, Progress progress) {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(a.getPackageName());
            if (size > 0) params.setSize(size);
            sessionId = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(sessionId)) {
                HttpURLConnection c = open(url);
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : size;
                try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("app.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done > 200L * 1024 * 1024) throw new java.io.IOException("too large");
                        if (total > 0) {
                            int p = (int) (done * 100 / total);
                            if (p != last) { last = p; progress.at(p); }
                        }
                    }
                    s.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent cb = PendingIntent.getBroadcast(a, sessionId, new Intent(a, Result.class), flags);
                s.commit(cb.getIntentSender());
            }
            return true;
        } catch (Exception e) {
            if (sessionId >= 0) try { pi.abandonSession(sessionId); } catch (Exception ignored) { }
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Nachrichten-Updater");
        c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
        if (c.getResponseCode() / 100 != 2) throw new java.io.IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private static byte[] get(String url, int limit) throws Exception {
        HttpURLConnection c = open(url);
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

    /** Antwort von Android auf die Installation. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(c, T.updateFailed(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
