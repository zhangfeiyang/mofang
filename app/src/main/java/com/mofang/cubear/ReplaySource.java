package com.mofang.cubear;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ImageView;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Debug builds only: plays a recorded video through the real analysis pipeline in place of the
 * camera, and shows each frame behind the overlay.
 *
 * <p>Some phones hide third-party logcat and an emulator has no cube to look at, so the whole
 * scan → review → guidance flow is otherwise impossible to watch away from a real session.
 * Started with {@code adb shell am start -n com.mofang.cubear/.MainActivity --es replay <path>}.
 */
final class ReplaySource {
    private static final String TAG = "ReplaySource";
    private static final int WIDTH = 720, HEIGHT = 1280;
    private static final long FRAME_MS = 66;
    private static final int BATCH = 6;

    private final String path;
    private final CubeAnalyzer analyzer;
    private final ExecutorService analysisThread;
    private final ImageView screen;
    private volatile boolean running;
    private Thread thread;

    ReplaySource(String path, CubeAnalyzer analyzer, ExecutorService analysisThread, ImageView screen) {
        this.path = path;
        this.analyzer = analyzer;
        this.analysisThread = analysisThread;
        this.screen = screen;
    }

    void start() {
        running = true;
        thread = new Thread(this::run, "cubear-replay");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void run() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            int count = frames == null ? 0 : Integer.parseInt(frames);
            MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
            params.setPreferredConfig(Bitmap.Config.ARGB_8888);
            Log.i(TAG, "replaying " + path + " frames=" + count);
            // The camera delivers about one analysed frame per two video frames.
            for (int start = 0; start < count && running; start += BATCH * 2) {
                List<Bitmap> batch = retriever.getFramesAtIndex(start, Math.min(BATCH * 2, count - start), params);
                for (int i = 0; i < batch.size() && running; i += 2) {
                    long began = SystemClock.uptimeMillis();
                    feed(batch.get(i));
                    long rest = FRAME_MS - (SystemClock.uptimeMillis() - began);
                    if (rest > 0) SystemClock.sleep(rest);
                }
                for (Bitmap bitmap : batch) bitmap.recycle();
            }
        } catch (Exception error) {
            Log.e(TAG, "replay failed: " + error);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to do while tearing down.
            }
        }
    }

    private void feed(Bitmap source) throws Exception {
        Bitmap frame = Bitmap.createScaledBitmap(source, WIDTH, HEIGHT, true);
        // ARGB_8888 lies in memory as R,G,B,A bytes — the layout CameraX's RGBA output uses.
        ByteBuffer pixels = ByteBuffer.allocate(WIDTH * HEIGHT * 4);
        frame.copyPixelsToBuffer(pixels);
        final byte[] rgba = pixels.array();
        screen.post(() -> {
            if (running) screen.setImageBitmap(frame);
        });
        analysisThread.submit(() -> analyzer.process(rgba, WIDTH, HEIGHT)).get();
    }
}
