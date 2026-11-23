package de.nachrichten.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Accessoire-Shop: Teile werden nach Anzahl gesendeter Nachrichten freigeschaltet und lassen sich anlegen. */
public class ShopActivity extends Base {

    private static final int COLS = 3;
    private LinearLayout header, grid;
    private TextView sub;
    private int sent, eff;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!app.keys.has()) { finish(); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Ico back = new Ico(this, Ico.BACK);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(T.accessories(), 22, Pal.text, true));
        sub = text("", 13, Pal.dim, false);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        onInsets();
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(8), dp(12), dp(8));
        grid.setPadding(dp(10), 0, dp(10), insetBottom + dp(24));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.acquire("ui");
        build();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.release("ui");
    }

    private void build() {
        sent = app.countedMsgs();
        eff = Math.min(Accessories.COUNT, Math.max(Accessories.unlockedCount(sent), app.shopDaysUsed()));
        if (eff >= Accessories.COUNT) sub.setText(T.allUnlocked(sent));
        else sub.setText(T.shopSub(sent, Accessories.unlockAt(eff) - sent));

        grid.removeAllViews();
        LinearLayout rowView = null;
        int cellW = (getResources().getDisplayMetrics().widthPixels - dp(20) - dp(10) * COLS) / COLS;
        for (int i = 0; i < Accessories.COUNT; i++) {
            if (i % COLS == 0) {
                rowView = new LinearLayout(this);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(rowView);
            }
            rowView.addView(cell(i, cellW), new LinearLayout.LayoutParams(cellW, -2));
        }
    }

    private View cell(int i, int w) {
        boolean unlocked = i < eff;
        int cat = Accessories.category(i);
        int equipped = cat == Accessories.BEHIND ? app.accBehind() : cat == Accessories.FRONT ? app.accFront() : app.accFrame();
        boolean on = equipped == i;

        LinearLayout cellBox = new LinearLayout(this);
        cellBox.setOrientation(LinearLayout.VERTICAL);
        cellBox.setGravity(Gravity.CENTER_HORIZONTAL);
        cellBox.setPadding(dp(6), dp(10), dp(6), dp(10));
        cellBox.setBackground(round(on ? withAlpha(Pal.accent, 0x22) : Pal.card, on ? Pal.accent : Pal.line, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, -2);
        clp.setMargins(dp(4), dp(4), dp(4), dp(4));
        cellBox.setLayoutParams(clp);

        int avSize = Math.min(72, Math.round((w - dp(24)) / getResources().getDisplayMetrics().density));
        java.io.File mine = app.myAvatar();
        Avatar av = new Avatar(app.keys.pubHex(), app.myName().isEmpty() ? "?" : app.myName(), mine != null ? mine.getAbsolutePath() : null)
                .accessories(cat == Accessories.BEHIND ? i : -1, cat == Accessories.FRONT ? i : -1, cat == Accessories.FRAME ? i : -1);
        FrameLayout avWrap = new FrameLayout(this);
        avWrap.addView(av, new FrameLayout.LayoutParams(dp(avSize), dp(avSize), Gravity.CENTER));
        if (!unlocked) av.setAlpha(0.28f);
        cellBox.addView(avWrap, new LinearLayout.LayoutParams(dp(avSize), dp(avSize)));

        TextView name = text(Accessories.name(i), 12, unlocked ? Pal.text : Pal.dim, unlocked);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(0, dp(6), 0, 0);
        cellBox.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView status = text(on ? T.equipped() : unlocked ? T.tapToEquip() : T.needMore(Accessories.unlockAt(i) - sent),
                11, on ? Pal.accent : Pal.dim, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(2), 0, 0);
        cellBox.addView(status, new LinearLayout.LayoutParams(-1, -2));

        if (unlocked) cellBox.setOnClickListener(v -> toggle(i, cat, on));
        else cellBox.setOnClickListener(v -> toast(T.needMore(Accessories.unlockAt(i) - sent)));
        return cellBox;
    }

    private void toggle(int i, int cat, boolean on) {
        String key = cat == Accessories.BEHIND ? "accBehind" : cat == Accessories.FRONT ? "accFront" : "accFrame";
        app.prefs.edit().putInt(key, on ? -1 : i).putLong("profileTs", System.currentTimeMillis()).apply();
        app.net.profileChanged();
        build();
    }

    private static int withAlpha(int color, int alpha) { return (alpha << 24) | (color & 0xFFFFFF); }
}
