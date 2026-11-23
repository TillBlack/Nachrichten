package de.nachrichten.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import de.nachrichten.R;

/** Benachrichtigungen: neue Nachrichten und der Hinweis des Hintergrunddienstes. */
final class Notifs {

    static final String CH_MSG = "messages", CH_SERVICE = "service";
    static final int SERVICE_ID = 1;

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel m = new NotificationChannel(CH_MSG, T.chMessages(), NotificationManager.IMPORTANCE_HIGH);
        m.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(m);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, T.chService(), NotificationManager.IMPORTANCE_MIN);
        s.setShowBadge(false);
        s.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(s);
    }

    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    static void message(App app, String peer, String body) {
        if (!allowed(app)) return;
        Intent open = new Intent(app, ChatActivity.class).putExtra("peer", peer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(app, peer.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        // Auf dem gesperrten Bildschirm: weder Name noch Inhalt, nur ein neutraler Hinweis
        Notification hidden = new Notification.Builder(app, CH_MSG)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Nachrichten")
                .setContentText(T.newMessage())
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        Notification n = new Notification.Builder(app, CH_MSG)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(app.nameOf(peer))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        app.getSystemService(NotificationManager.class).notify(peer.hashCode(), n);
    }

    static void clear(Context c, String peer) {
        c.getSystemService(NotificationManager.class).cancel(peer.hashCode());
    }

    static Notification service(App app) {
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(app, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(T.serviceTitle())
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
