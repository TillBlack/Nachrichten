package de.nachrichten.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Zuschneide-Editor: zeigt das gewählte Bild und einen verschiebbaren,
 * größenveränderbaren Kreis. "Fertig" schneidet den Bereich unter dem Kreis
 * als quadratisches Bild aus und gibt dessen Pfad zurück (EXTRA "out").
 * Eingabe: EXTRA "src" = Pfad zu einer Datei mit den Rohbytes des Bildes.
 */
public class CropActivity extends Base {

    public static final int OUT_SIZE = 512;
    private CropView cropView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String src = getIntent().getStringExtra("src");
        Bitmap bmp = src == null ? null : decode(src);
        if (bmp == null) { toast(T.readError()); setResult(RESULT_CANCELED); finish(); return; }

        root.setBackgroundColor(0xFF000000);
        cropView = new CropView(this, bmp);
        root.addView(cropView, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = text(T.cropHint(), 14, 0xFFFFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(10), dp(24), dp(10));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(hint, hp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.addView(barButton(T.cancel(), 0x33FFFFFF, 0xFFFFFFFF, () -> { setResult(RESULT_CANCELED); finish(); }));
        bar.addView(space());
        bar.addView(barButton(T.done(), Pal.accent, Pal.onAccent, this::finishCrop));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bar, bp);
        this.bar = bar;
    }

    private LinearLayout bar;

    @Override
    protected void onInsets() {
        if (bar != null) bar.setPadding(dp(16), dp(10), dp(16), dp(10) + insetBottom);
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(1)));
        return v;
    }

    private TextView barButton(String label, int bg, int fg, Runnable run) {
        TextView t = text(label, 16, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(13), dp(22), dp(13));
        t.setBackground(round(bg, 0, 26));
        t.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        t.setLayoutParams(lp);
        return t;
    }

    private void finishCrop() {
        Bitmap out = cropView.crop(OUT_SIZE);
        if (out == null) { toast(T.readError()); return; }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 90, bo);
            File f = new File(getCacheDir(), "crop_out_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(bo.toByteArray()); }
            android.content.Intent r = new android.content.Intent();
            r.putExtra("out", f.getAbsolutePath());
            setResult(RESULT_OK, r);
            finish();
        } catch (Throwable t) {
            toast(T.readError());
        }
    }

    /** Bild aus Datei lesen, auf Anzeigegröße verkleinern und EXIF-Drehung anwenden. */
    private Bitmap decode(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int maxSide = 1280, sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) return null;
            int rot = 0;
            try {
                int or = new android.media.ExifInterface(path)
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                rot = or == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) { }
            if (rot != 0) {
                Matrix mx = new Matrix();
                mx.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), mx, true);
                if (r != bm) bm.recycle();
                bm = r;
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }
}
