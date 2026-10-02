package com.mofang.cubear;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.graphics.Matrix;

/**
 * Session diagnostics for a low-recall investigation.
 *
 * <p>Every analysed frame is appended as one JSON line. Images are written more sparingly:
 * near-threshold misses, implausible quads, geometry fallback, and every Nth frame. Files land
 * in the app's own external directory so they can be pulled with {@code adb pull} without extra
 * permissions. Logcat is duplicated into {@code session.log} because some phones swallow it.
 */
public final class DetectionDump {
    private static final String TAG = "DetectionDump";
    private static final int MAX_IMAGES = 240;
    private static final int IMAGE_EVERY = 8;
    static final float PRESENCE_THRESHOLD = CubeFaceModel.PRESENCE_THRESHOLD;

    private static volatile DetectionDump active;

    public static DetectionDump active() { return active; }

    /**
     * Starts a dump session, or returns null when dumping is disabled.
     *
     * <p>Dumping writes a JSON line per analysed frame and JPEG-encodes snapshots on the camera
     * thread, which measurably drags the analysis frame rate, so it is off by default. Turn it on
     * for a session with either
     * {@code adb shell setprop debug.cubear.dump 1} or
     * {@code adb shell touch /sdcard/Android/data/com.mofang.cubear/files/dump.flag}.
     */
    public static DetectionDump start(Context context) {
        if (!enabled(context)) return null;
        File root = new File(context.getExternalFilesDir(null), "detect-dump");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        DetectionDump dump = new DetectionDump(new File(root, stamp));
        active = dump;
        dump.log("session start dir=" + dump.dir.getAbsolutePath()
            + " threshold=" + PRESENCE_THRESHOLD);
        return dump;
    }

    private static boolean enabled(Context context) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            String value = (String) props.getMethod("get", String.class)
                .invoke(null, "debug.cubear.dump");
            if (value != null && !value.isEmpty() && !"0".equals(value)) return true;
        } catch (Throwable ignored) {
            // Reflection is just a convenience; the flag file below always works.
        }
        try {
            File flag = new File(context.getExternalFilesDir(null), "dump.flag");
            return flag.exists();
        } catch (Throwable error) {
            return false;
        }
    }

    private final File dir;
    private final File framesDir;
    private final Object lock = new Object();
    private int seq;
    private int imagesWritten;
    private int hits;
    private int misses;
    private int nearMisses;
    private int geometryHits;
    private final float[] presenceSum = new float[1];
    public volatile float lastPresence;
    public volatile String lastReject = "none";
    public volatile String lastSource = "none";
    public volatile int lastKnown = -1;

    private DetectionDump(File dir) {
        this.dir = dir;
        this.framesDir = new File(dir, "frames");
        dir.mkdirs();
        framesDir.mkdirs();
        writeFile(new File(dir, "meta.txt"),
            "threshold=" + PRESENCE_THRESHOLD + "\n"
                + "input=" + CubeFaceModel.INPUT_WIDTH + "x" + CubeFaceModel.INPUT_HEIGHT + "\n"
                + "started=" + System.currentTimeMillis() + "\n");
    }

    public File dir() { return dir; }
    public int framesSeen() { return seq; }
    public int imagesWritten() { return imagesWritten; }

    public void recordCnn(byte[] rgba, int width, int height, CubeFaceModel.DebugResult cnn,
                          DetectedFace face, String extraReject) {
        String reject = extraReject != null ? extraReject
            : (cnn == null ? "no_model" : cnn.reject);
        boolean hit = face != null && reject == null;
        double[] corners = face != null ? cornersOf(face) : (cnn == null ? null : cnn.corners);
        float presence = cnn == null ? -1f : cnn.presence;
        record(rgba, width, height, "cnn", presence, hit, reject, corners, face, cnn);
    }

    /** A detection from the network-free anchor search, after the network declined. */
    public void recordAnchors(byte[] rgba, int width, int height, DetectedFace face, int declines) {
        double[] corners = face == null ? null : cornersOf(face);
        String reject = face == null ? "anchors_miss" : null;
        record(rgba, width, height, "anchors", face == null ? -1f : face.detectionScore,
            face != null, reject, corners, face, null);
        if (face != null) {
            synchronized (lock) { geometryHits++; }
            log("anchor search hit after " + declines + " cnn declines");
        }
    }

    public void note(String event, DetectedFace face) {
        StringBuilder line = new StringBuilder(96);
        line.append("{\"t\":").append(System.currentTimeMillis())
            .append(",\"seq\":").append(seq)
            .append(",\"event\":\"").append(event).append('"');
        if (face != null) {
            line.append(",\"min_side\":").append(fmt(face.minSideFraction()))
                .append(",\"score\":").append(fmt(face.detectionScore));
        }
        line.append("}\n");
        append(new File(dir, "events.jsonl"), line.toString());
        log("event " + event + (face == null ? "" : " side=" + fmt(face.minSideFraction())));
    }

    private void record(byte[] rgba, int width, int height, String source, float presence,
                        boolean hit, String reject, double[] corners, DetectedFace face,
                        CubeFaceModel.DebugResult cnn) {
        int n;
        synchronized (lock) {
            n = ++seq;
            if (hit) hits++; else misses++;
            if (!hit && presence >= 0.40f && presence < PRESENCE_THRESHOLD) nearMisses++;
            if (presence >= 0) presenceSum[0] += presence;
        }
        lastPresence = presence;
        lastReject = reject == null ? "ok" : reject;
        lastSource = source;
        lastKnown = face == null ? -1 : countKnown(face.sample);

        boolean dumpImage = shouldDump(n, source, presence, reject, hit, face);
        String imageName = dumpImage
            ? saveImages(n, rgba, width, height, corners, hit, presence, reject, source) : null;

        String json = toJson(n, width, height, source, presence, hit, reject, corners, face, cnn,
            imageName);
        append(new File(dir, "session.jsonl"), json + "\n");
        log(summaryLine(n, source, presence, reject, face, imageName));
        if (n % 30 == 0) writeSummary();
    }

    private boolean shouldDump(int n, String source, float presence, String reject, boolean hit,
                               DetectedFace face) {
        if (imagesWritten >= MAX_IMAGES) return false;
        if (n <= 4) return true;
        if (n % IMAGE_EVERY == 0) return true;
        if (!hit && presence >= 0.40f && presence < PRESENCE_THRESHOLD) return true;
        if (reject != null && reject.startsWith("implausible")) return true;
        if ("anchors".equals(source) && hit) return true;
        if (hit && face != null && (face.sample.unreliableCount() >= 2 || countKnown(face.sample) < 7)) {
            return true;
        }
        return false;
    }

    private String saveImages(int n, byte[] rgba, int width, int height, double[] corners,
                              boolean hit, float presence, String reject, String source) {
        String stem = String.format(Locale.US, "f%05d_%s", n, hit ? "hit" : "miss");
        try {
            Bitmap full = rgbaToBitmap(rgba, width, height);
            // The untouched frame, so a dump can be replayed through the pipeline offline.
            writeJpeg(new File(framesDir, stem + "_raw.jpg"), full, 90);
            Bitmap overlay = overlayBitmap(full, corners, hit, presence, reject, source);
            writeJpeg(new File(framesDir, stem + ".jpg"), overlay, 72);
            overlay.recycle();
            Bitmap input = rgbaToBitmap(new AreaResizer(width, height, CubeFaceModel.INPUT_WIDTH,
                CubeFaceModel.INPUT_HEIGHT).resizeToRgba(rgba),
                CubeFaceModel.INPUT_WIDTH, CubeFaceModel.INPUT_HEIGHT);
            writeJpeg(new File(framesDir, stem + "_in.jpg"), input, 85);
            input.recycle();
            if (hit && corners != null && corners.length == 8) {
                Bitmap warp = warpedFace(full, corners);
                if (warp != null) {
                    writeJpeg(new File(framesDir, stem + "_warp.jpg"), warp, 80);
                    warp.recycle();
                }
            }
            full.recycle();
            synchronized (lock) { imagesWritten++; }
            return "frames/" + stem + ".jpg";
        } catch (Throwable error) {
            Log.e(TAG, "image dump failed: " + error);
            return null;
        }
    }

    private static Bitmap overlayBitmap(Bitmap full, double[] corners, boolean hit, float presence,
                                        String reject, String source) {
        int srcW = full.getWidth(), srcH = full.getHeight();
        int dstW = Math.min(360, srcW);
        int dstH = Math.max(1, srcH * dstW / srcW);
        Bitmap scaled = Bitmap.createScaledBitmap(full, dstW, dstH, true);
        if (scaled == full) scaled = full.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(scaled);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (corners != null && corners.length == 8) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(hit ? Color.rgb(80, 220, 120) : Color.rgb(255, 80, 70));
            float sx = dstW / (float) srcW, sy = dstH / (float) srcH;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                canvas.drawLine((float) corners[i * 2] * sx, (float) corners[i * 2 + 1] * sy,
                    (float) corners[j * 2] * sx, (float) corners[j * 2 + 1] * sy, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                canvas.drawCircle((float) corners[i * 2] * sx, (float) corners[i * 2 + 1] * sy, 5f, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(180, 0, 0, 0));
        canvas.drawRect(0, 0, dstW, 36, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(14f);
        String label = String.format(Locale.US, "%s p=%.2f %s", source, presence,
            reject == null ? "OK" : reject);
        canvas.drawText(label, 8, 24, paint);
        return scaled;
    }

    /** The face rectified onto a 300 px square, as the sampler sees it. */
    private static Bitmap warpedFace(Bitmap full, double[] corners) {
        float[] src = new float[8];
        for (int i = 0; i < 8; i++) src[i] = (float) corners[i];
        float[] dst = {0, 0, 300, 0, 300, 300, 0, 300};
        Matrix matrix = new Matrix();
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null;
        Bitmap warped = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        new Canvas(warped).drawBitmap(full, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        return warped;
    }

    private static Bitmap rgbaToBitmap(byte[] rgba, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, width * height * 4));
        return bitmap;
    }

    private static void writeJpeg(File file, Bitmap bitmap, int quality) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        }
    }

    private String toJson(int n, int width, int height, String source, float presence, boolean hit,
                          String reject, double[] corners, DetectedFace face,
                          CubeFaceModel.DebugResult cnn, String image) {
        StringBuilder out = new StringBuilder(420);
        out.append("{\"t\":").append(System.currentTimeMillis())
            .append(",\"seq\":").append(n)
            .append(",\"w\":").append(width)
            .append(",\"h\":").append(height)
            .append(",\"src\":\"").append(source).append('"')
            .append(",\"p\":").append(fmt(presence))
            .append(",\"thr\":").append(fmt(PRESENCE_THRESHOLD))
            .append(",\"hit\":").append(hit);
        if (reject != null) out.append(",\"reject\":\"").append(reject).append('"');
        if (cnn != null) {
            out.append(",\"area\":").append(fmt((float) cnn.areaFraction));
            out.append(",\"aspect\":").append(fmt((float) cnn.aspect));
            out.append(",\"shortest\":").append(fmt((float) cnn.shortest));
        }
        if (corners != null) {
            out.append(",\"quad\":[");
            for (int i = 0; i < 4; i++) {
                if (i > 0) out.append(',');
                out.append('[').append(fmt((float) corners[i * 2])).append(',')
                    .append(fmt((float) corners[i * 2 + 1])).append(']');
            }
            out.append(']');
        }
        if (face != null) {
            out.append(",\"refined\":").append(face.refined)
                .append(",\"disputed\":").append(face.disputed);
            out.append(",\"side\":").append(fmt(face.minSideFraction()))
                .append(",\"known\":").append(countKnown(face.sample))
                .append(",\"unreliable\":").append(face.sample.unreliableCount())
                .append(",\"center\":\"").append(face.sample.center().name()).append('"')
                .append(",\"conf\":").append(fmt(face.sample.confidence));
        }
        if (image != null) out.append(",\"img\":\"").append(image).append('"');
        out.append('}');
        return out.toString();
    }

    private void writeSummary() {
        int seen;
        int h;
        int m;
        int near;
        int geo;
        float psum;
        synchronized (lock) {
            seen = seq;
            h = hits;
            m = misses;
            near = nearMisses;
            geo = geometryHits;
            psum = presenceSum[0];
        }
        float meanP = seen == 0 ? 0 : psum / seen;
        float rate = seen == 0 ? 0 : h / (float) seen;
        writeFile(new File(dir, "summary.txt"),
            "frames=" + seen + "\n"
                + "hits=" + h + "\n"
                + "misses=" + m + "\n"
                + "hit_rate=" + fmt(rate) + "\n"
                + "near_misses=" + near + "\n"
                + "geometry_hits=" + geo + "\n"
                + "mean_presence=" + fmt(meanP) + "\n"
                + "images=" + imagesWritten + "\n");
    }

    public void close() {
        writeSummary();
        log("session end hits=" + hits + "/" + seq);
        if (active == this) active = null;
    }

    private static double[] cornersOf(DetectedFace face) {
        double[] points = new double[8];
        for (int i = 0; i < 8; i++) points[i] = face.corners[i];
        return points;
    }

    private static int countKnown(FaceSample sample) {
        int known = 0;
        for (CubeColor color : sample.stickers) if (color != CubeColor.UNKNOWN) known++;
        return known;
    }

    private static String fmt(float value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private String summaryLine(int n, String source, float presence, String reject,
                               DetectedFace face, String image) {
        StringBuilder line = new StringBuilder(160);
        line.append("seq=").append(n)
            .append(" src=").append(source)
            .append(" p=").append(fmt(presence))
            .append(" reject=").append(reject == null ? "ok" : reject);
        if (face != null) {
            line.append(" known=").append(countKnown(face.sample))
                .append(" bad=").append(face.sample.unreliableCount())
                .append(" side=").append(fmt(face.minSideFraction()))
                .append(" c=").append(face.sample.center().name());
        }
        if (image != null) line.append(" img=").append(image);
        return line.toString();
    }

    void log(String message) {
        Log.i(TAG, message);
        String line = System.currentTimeMillis() + " " + message + "\n";
        append(new File(dir, "session.log"), line);
    }

    private static void append(File file, String text) {
        try (FileWriter out = new FileWriter(file, true)) {
            out.write(text);
        } catch (Throwable error) {
            Log.e(TAG, "write failed " + file.getName() + ": " + error);
        }
    }

    private static void writeFile(File file, String text) {
        try (FileWriter out = new FileWriter(file, false)) {
            out.write(text);
        } catch (Throwable error) {
            Log.e(TAG, "write failed " + file.getName() + ": " + error);
        }
    }
}
