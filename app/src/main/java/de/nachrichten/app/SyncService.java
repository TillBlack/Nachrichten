package de.nachrichten.app;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Hält die Verbindung zu den Relays offen, damit Nachrichten auch bei geschlossener App ankommen. */
public final class SyncService extends Service implements Net.Listener {

    private App app;
    private boolean started;

    @Override
    public void onCreate() {
        super.onCreate();
        app = (App) getApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifs.SERVICE_ID, Notifs.service(app), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(Notifs.SERVICE_ID, Notifs.service(app));
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            app.net.addListener(this);
            app.net.acquire("service");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (started) {
            app.net.removeListener(this);
            app.net.release("service");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onChanged(String peer) { }

    @Override
    public void onStatus() {
        try {
            getSystemService(NotificationManager.class).notify(Notifs.SERVICE_ID, Notifs.service(app));
        } catch (Exception ignored) { }
    }
}
