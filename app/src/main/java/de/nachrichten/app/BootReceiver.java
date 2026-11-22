package de.nachrichten.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet nach dem Einschalten des Handys den Hintergrunddienst wieder. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        Context app = c.getApplicationContext();
        if (app instanceof App) ((App) app).applyBackground();
    }
}
