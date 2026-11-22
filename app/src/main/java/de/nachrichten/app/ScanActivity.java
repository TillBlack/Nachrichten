package de.nachrichten.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** QR-Code-Scanner mit Camera2 und ZXing. Gibt den Inhalt als Extra "code" zurück. */
public class ScanActivity extends Base {

    private static final int REQ_CAM = 1;

    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread camThread;
    private Handler camHandler;
    private Size size;
    private volatile boolean done;
    private final QRCodeReader qr = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    private LinearLayout bottom;
    private TextView backBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        root.setBackgroundColor(0xFF000000);
        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(new Frame(this), new FrameLayout.LayoutParams(-1, -1));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView hint = text(T.scanHint(), 16, 0xFFFFFFFF, true);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint);
        backBtn = text(T.cancel(), 16, 0xFFFFFFFF, true);
        backBtn.setPadding(dp(24), dp(12), dp(24), dp(12));
        backBtn.setBackground(round(0x66000000, 0xAAFFFFFF, 22));
        backBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(18), 0, 0);
        bottom.addView(backBtn, bp);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    @Override
    protected void onInsets() {
        if (bottom != null) bottom.setPadding(dp(24), 0, dp(24), insetBottom + dp(36));
    }

    @Override
    protected void applyColors() {
        super.applyColors();
        root.setBackgroundColor(0xFF000000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        startCamera();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_CAM) return;
        if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) {
            toast(T.cameraDenied());
            finish();
        }
        // bei Erlaubnis startet onResume die Kamera
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    // ------------------------------------------------------------------ Kamera

    private void startCamera() {
        if (camera != null) return;
        camThread = new HandlerThread("cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        if (preview.isAvailable()) openCamera();
        else preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null && cm.getCameraIdList().length > 0) id = cm.getCameraIdList()[0];
            if (id == null) { finish(); return; }
            StreamConfigurationMap map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            size = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            layoutPreview();
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, camHandler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; createSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); camera = null; }
            }, camHandler);
        } catch (SecurityException | CameraAccessException e) {
            toast(T.cameraDenied());
            finish();
        }
    }

    /** Größe um 1280x720, im Seitenverhältnis 4:3 oder 16:9. */
    private static Size chooseSize(Size[] sizes) {
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            long score = Math.abs(px - 1280L * 720L);
            if (s.getWidth() > 1920) score += 10_000_000L;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    /** Vorschau im Hochformat ohne Verzerren: Breite füllen, Höhe passend (Sensor liegt quer). */
    private void layoutPreview() {
        runOnUiThread(() -> {
            int vw = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int vh = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
            float ratio = (float) size.getWidth() / size.getHeight(); // > 1
            int w = vw, h = Math.round(vw * ratio);
            if (h < vh) { h = vh; w = Math.round(vh / ratio); }
            preview.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        });
    }

    private void createSession() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface surface = new Surface(st);
            CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(surface);
            rb.addTarget(reader.getSurface());
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) return;
                    session = s;
                    try { s.setRepeatingRequest(rb.build(), null, camHandler); } catch (Exception ignored) { }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { }
            }, camHandler);
        } catch (Exception ignored) { }
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || done) return;
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), rowStride = y.getRowStride(), pixStride = y.getPixelStride();
            byte[] data = new byte[w * h];
            if (pixStride == 1 && rowStride == w) {
                buf.get(data, 0, Math.min(data.length, buf.remaining()));
            } else {
                byte[] row = new byte[rowStride];
                for (int yy = 0; yy < h; yy++) {
                    buf.position(yy * rowStride);
                    int len = Math.min(rowStride, buf.remaining());
                    buf.get(row, 0, len);
                    for (int xx = 0; xx < w; xx++) data[yy * w + xx] = row[xx * pixStride];
                }
            }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            Result res;
            try {
                res = qr.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            } catch (Exception notFound) {
                qr.reset();
                return;
            }
            String text = res.getText();
            if (Keys.parseContact(text) == null) return;
            done = true;
            runOnUiThread(() -> {
                setResult(RESULT_OK, new Intent().putExtra("code", text));
                finish();
            });
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private void stopCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) { }
        camera = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        reader = null;
        if (camThread != null) { camThread.quitSafely(); camThread = null; }
    }

    /** Abgedunkelter Rand mit hellem Rahmen in der Mitte. */
    private final class Frame extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Frame(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.68f;
            float l = (w - s) / 2, t = (h - s) / 2 - dp(40);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x88000000);
            c.drawRect(0, 0, w, t, p);
            c.drawRect(0, t + s, w, h, p);
            c.drawRect(0, t, l, t + s, p);
            c.drawRect(l + s, t, w, t + s, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xFFFFFFFF);
            r.set(l, t, l + s, t + s);
            c.drawRoundRect(r, dp(14), dp(14), p);
        }
    }
}
