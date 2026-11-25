package de.nachrichten.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Zuschneide-Ansicht: zeigt ein Bild und darüber einen verschiebbaren,
 * in der Größe veränderbaren kreisförmigen Auswahlbereich.
 * Verschieben mit einem Finger, Größe ändern mit zwei Fingern (Zoom-Geste).
 * crop() liefert das quadratische Bild unter dem Kreis (die App stellt
 * Profil-/Gruppenbilder ohnehin rund dar).
 */
public class CropView extends View {

    private final Bitmap bmp;
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imgRect = new RectF();
    private float scale = 1f;          // Bild-px -> View-px
    private float cx, cy, rad;         // Auswahlkreis in View-Koordinaten
    private final ScaleGestureDetector scaler;
    private float lastX, lastY;
    private boolean moving;

    public CropView(Context c, Bitmap b) {
        super(c);
        this.bmp = b;
        dim.setColor(0xB3000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        ring.setStrokeWidth(dp(2.5f));
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setLayerType(LAYER_TYPE_HARDWARE, null);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                rad *= d.getScaleFactor();
                clampCircle();
                invalidate();
                return true;
            }
        });
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutImage(w, h);
    }

    private void layoutImage(int w, int h) {
        if (bmp == null || w == 0 || h == 0) return;
        scale = Math.min(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        imgRect.set(left, top, left + dw, top + dh);
        cx = imgRect.centerX();
        cy = imgRect.centerY();
        rad = Math.min(dw, dh) * 0.42f;
        clampCircle();
    }

    private void clampCircle() {
        float maxR = Math.min(imgRect.width(), imgRect.height()) / 2f;
        if (rad > maxR) rad = maxR;
        if (rad < dp(28)) rad = dp(28);
        if (cx - rad < imgRect.left) cx = imgRect.left + rad;
        if (cx + rad > imgRect.right) cx = imgRect.right - rad;
        if (cy - rad < imgRect.top) cy = imgRect.top + rad;
        if (cy + rad > imgRect.bottom) cy = imgRect.bottom - rad;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) return;
        c.drawBitmap(bmp, null, imgRect, null);
        int save = c.saveLayer(0, 0, getWidth(), getHeight(), null);
        c.drawRect(0, 0, getWidth(), getHeight(), dim);
        c.drawCircle(cx, cy, rad, clear);
        c.restoreToCount(save);
        c.drawCircle(cx, cy, rad, ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); moving = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (moving && e.getPointerCount() == 1 && !scaler.isInProgress()) {
                    cx += e.getX() - lastX;
                    cy += e.getY() - lastY;
                    lastX = e.getX(); lastY = e.getY();
                    clampCircle();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                moving = false;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // nach dem Zoomen den verbleibenden Finger als neue Referenz nehmen
                int rem = e.getActionIndex() == 0 ? 1 : 0;
                if (rem < e.getPointerCount()) { lastX = e.getX(rem); lastY = e.getY(rem); moving = true; }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                moving = false;
                break;
        }
        return true;
    }

    /** Quadratisches Bild unter dem Auswahlkreis, auf out×out skaliert. null bei Fehler. */
    public Bitmap crop(int out) {
        try {
            float bx = (cx - rad - imgRect.left) / scale;
            float by = (cy - rad - imgRect.top) / scale;
            float side = (2 * rad) / scale;
            int ix = Math.max(0, Math.round(bx));
            int iy = Math.max(0, Math.round(by));
            int is = Math.round(side);
            if (ix + is > bmp.getWidth()) is = bmp.getWidth() - ix;
            if (iy + is > bmp.getHeight()) is = bmp.getHeight() - iy;
            if (is <= 0) return null;
            Bitmap sq = Bitmap.createBitmap(bmp, ix, iy, is, is);
            if (sq.getWidth() != out) {
                Bitmap s = Bitmap.createScaledBitmap(sq, out, out, true);
                if (s != sq) sq.recycle();
                sq = s;
            }
            return sq;
        } catch (Throwable t) {
            return null;
        }
    }
}
