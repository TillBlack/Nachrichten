package de.nachrichten.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Hält Datenbank, Schlüssel und Netz für alle Bildschirme. */
public final class App extends Application {

    static App inst;
    SharedPreferences prefs;
    Db db;
    Keys keys;
    Net net;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(new File(getFilesDir(), "crash.txt")))) {
                e.printStackTrace(w);
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        prefs = getSharedPreferences("nachrichten", MODE_PRIVATE);
        T.lang = Math.max(0, Math.min(T.CODES.length - 1, prefs.getInt("lang", defaultLang())));
        Pal.set(prefs.getBoolean("light", false));
        db = new Db(this);
        keys = new Keys(prefs);
        net = new Net(this);
        Notifs.channels(this);
    }

    private static int defaultLang() {
        String l = java.util.Locale.getDefault().getLanguage();
        for (int i = 0; i < T.CODES.length; i++) if (T.CODES[i].equals(l)) return i;
        return 0;
    }

    List<String> relays() {
        String s = prefs.getString("relays", null);
        if (s == null) return new ArrayList<>(Arrays.asList(Net.DEFAULT_RELAYS));
        List<String> out = new ArrayList<>();
        for (String u : s.split("\n")) if (!u.trim().isEmpty()) out.add(u.trim());
        return out;
    }

    void setRelays(List<String> list) {
        prefs.edit().putString("relays", String.join("\n", list)).apply();
        net.relaysChanged();
    }

    /** Ordner für Anhänge. */
    java.io.File mediaDir() {
        java.io.File d = new java.io.File(getFilesDir(), "media");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Mein Profilbild (oder null). */
    java.io.File myAvatar() {
        String p = prefs.getString("avatar", null);
        if (p == null) return null;
        java.io.File f = new java.io.File(p);
        return f.isFile() ? f : null;
    }

    /** Darf dieser Kontakt mein Profil (Bild, Biografie, Pronomen) sehen? */
    boolean profileVisibleTo(String pub) {
        if (!prefs.getBoolean("profilePrivate", false)) return true;
        return prefs.getStringSet("profileAllowed", java.util.Collections.emptySet()).contains(pub);
    }

    int accBehind() { return prefs.getInt("accBehind", -1); }
    int accFront() { return prefs.getInt("accFront", -1); }
    int accFrame() { return prefs.getInt("accFrame", -1); }

    /** Erscheine ich selbst in der öffentlichen Nutzerliste? */
    boolean selfListed() {
        return prefs.getBoolean("dirConsent", false) && !prefs.getBoolean("profilePrivate", false) && !myName().isEmpty();
    }

    String myName() { return prefs.getString("name", ""); }

    boolean background() { return prefs.getBoolean("bg", true); }

    /** Startet oder stoppt den Hintergrunddienst passend zur Einstellung. */
    void applyBackground() {
        Intent i = new Intent(this, SyncService.class);
        if (background() && keys.has()) {
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) { }
        } else {
            stopService(i);
        }
    }

    /** Anzeigename eines Kontakts (oder Kurzform des Codes). */
    String nameOf(String pub) {
        Db.Contact c = db.contact(pub);
        if (c != null && c.name != null && !c.name.trim().isEmpty()) return c.name;
        String npub = de.nachrichten.crypto.Bech32.encode("npub", de.nachrichten.crypto.Event.unhex(pub));
        return npub.substring(0, 12) + "…" + npub.substring(npub.length() - 4);
    }
}
