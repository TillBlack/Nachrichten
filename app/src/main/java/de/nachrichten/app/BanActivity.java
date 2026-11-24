package de.nachrichten.app;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Sperrbildschirm: deckt den ganzen Bildschirm ab, lässt sich mit keinem Knopf schließen
 * und zeigt keine Nachrichten. Ein gesperrter Nutzer kann nur noch (begrenzt) dem Administrator
 * schreiben und eine einzige Nachricht an einen Kontakt senden.
 */
public class BanActivity extends Base {

    private LinearLayout col;
    private ScrollView scroll;
    private LinearLayout sent; // zeigt die zuletzt gesendete Nachricht (sonst sieht man nichts)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Zurück-Taste wirkungslos machen (der Bildschirm lässt sich nicht verlassen).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { });
        }
        onInsets();
        build();
    }

    @Override
    protected void onInsets() {
        if (scroll != null) scroll.setPadding(dp(28), insetTop + dp(40), dp(28), insetBottom + dp(28));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.acquire("ui");
        // Falls die Sperre inzwischen aufgehoben wurde: zurück zur App.
        if (!app.amBanned()) { finish(); return; }
        build();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.release("ui");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { /* bewusst leer: Sperrbildschirm */ }

    private void build() {
        col.removeAllViews();

        Ico lock = new Ico(this, Ico.LOCK);
        lock.color = Pal.red;
        lock.setScaleX(2.6f);
        lock.setScaleY(2.6f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(0, dp(12), 0, dp(24));
        col.addView(lock, lp);

        TextView title = text(T.banned(), 26, Pal.text, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = text(T.bannedText(), 16, Pal.dim, false);
        info.setGravity(Gravity.CENTER);
        info.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.setMargins(0, dp(12), 0, dp(4));
        col.addView(info, il);

        long until = app.myBanUntil();
        String when = until == -1 ? T.bannedPermanent() : T.bannedUntil(time(until) + " " + shortWhen(until));
        TextView du = text(when, 14, Pal.accent, true);
        du.setGravity(Gravity.CENTER);
        col.addView(du, new LinearLayout.LayoutParams(-1, -2));

        // Verwarnung, falls vorhanden
        String[] warn = app.db.warnFor(app.keys.pubHex());
        if (warn != null && warn[0] != null && !warn[0].isEmpty()) {
            LinearLayout wb = new LinearLayout(this);
            wb.setOrientation(LinearLayout.VERTICAL);
            wb.setBackground(round(Pal.card, Pal.red, 12));
            wb.setPadding(dp(14), dp(12), dp(14), dp(12));
            wb.addView(text(T.modWarnLabel(), 13, Pal.red, true));
            TextView wt = text(warn[0], 15, Pal.text, false);
            wt.setLineSpacing(0, 1.2f);
            wt.setPadding(0, dp(4), 0, 0);
            wb.addView(wt);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
            wl.setMargins(0, dp(20), 0, 0);
            col.addView(wb, wl);
        }

        // Einspruch an den Administrator
        int adminLeft = app.appealAdminLeft();
        TextView aInfo = text(T.banAppealAdminInfo(adminLeft), 13, Pal.dim, false);
        aInfo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.setMargins(0, dp(28), 0, dp(8));
        col.addView(aInfo, al);

        TextView admin = button(T.banAppealAdmin(), true);
        admin.setEnabled(adminLeft > 0);
        admin.setAlpha(adminLeft > 0 ? 1f : 0.4f);
        if (adminLeft > 0) admin.setOnClickListener(v -> composeAdmin());
        col.addView(admin, wide());

        // Eine Nachricht an einen Kontakt
        int contactLeft = app.appealContactLeft();
        TextView cInfo = text(T.banAppealContactInfo(contactLeft), 13, Pal.dim, false);
        cInfo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(0, dp(20), 0, dp(8));
        col.addView(cInfo, cl);

        TextView contact = outlineButton(T.banAppealContact(), Pal.text);
        contact.setEnabled(contactLeft > 0);
        contact.setAlpha(contactLeft > 0 ? 1f : 0.4f);
        if (contactLeft > 0) contact.setOnClickListener(v -> pickContact());
        col.addView(contact, wide());

        sent = new LinearLayout(this);
        sent.setOrientation(LinearLayout.VERTICAL);
        col.addView(sent, wide());
    }

    private LinearLayout.LayoutParams wide() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        return lp;
    }

    // ------------------------------------------------------------------ Einspruch schreiben

    private void composeAdmin() {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.banAppealAdmin(), 18, Pal.text, true));
        EditText f = field("", true);
        addRow(box, f, 14);
        LinearLayout buttons = buttonRow();
        TextView no = button(T.cancel(), false);
        no.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, no, true);
        TextView ok = button(T.suggestSend(), true);
        ok.setOnClickListener(v -> {
            String t = f.getText().toString().trim();
            if (t.isEmpty()) return;
            dlg.dismiss();
            app.net.sendBanAppeal(t);
            app.useAppealAdmin();
            build();
            showSent(T.banAppealAdmin(), t);
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 18);
        showDialog(dlg, box);
    }

    private void pickContact() {
        List<String> keys = app.db.contactKeys();
        if (keys.isEmpty()) { toast(T.bannedNoContacts()); return; }
        String[] names = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) names[i] = app.nameOf(keys.get(i));
        menu(T.chooseContact(), names, null, k -> composeContact(keys.get(k)));
    }

    private void composeContact(String peer) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(app.nameOf(peer), 18, Pal.text, true));
        EditText f = field("", true);
        addRow(box, f, 14);
        LinearLayout buttons = buttonRow();
        TextView no = button(T.cancel(), false);
        no.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, no, true);
        TextView ok = button(T.suggestSend(), true);
        ok.setOnClickListener(v -> {
            String t = f.getText().toString().trim();
            if (t.isEmpty()) return;
            dlg.dismiss();
            app.net.sendBanContact(peer, t);
            app.useAppealContact();
            build();
            showSent(app.nameOf(peer), t);
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 18);
        showDialog(dlg, box);
    }

    /** Zeigt die zuletzt gesendete Nachricht (der Nutzer sieht sonst keine Nachrichten). */
    private void showSent(String to, String body) {
        if (sent == null) return;
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(round(Pal.card, Pal.line, 12));
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(T.appealSent() + " · " + to, 12, Pal.accent, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(text(time(System.currentTimeMillis()), 12, Pal.dim, false));
        bubble.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView bt = text(body, 15, Pal.text, false);
        bt.setLineSpacing(0, 1.2f);
        bt.setPadding(0, dp(4), 0, 0);
        bubble.addView(bt);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        sent.addView(bubble, lp);
    }
}
