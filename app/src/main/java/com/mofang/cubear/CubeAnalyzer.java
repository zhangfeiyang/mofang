package com.mofang.cubear;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/** Converts CameraX frames and runs full-frame dynamic Rubik face detection. */
public final class CubeAnalyzer implements ImageAnalysis.Analyzer {
    public interface Listener { void onDetection(DetectedFace face); }
    private final Listener listener;
    private final RubiksFaceDetector detector = new RubiksFaceDetector();
    /** Null when the model could not be loaded, in which case geometry runs alone. */
    private final CubeFaceModel model;
    private final DetectionDump dump;
    private final CornerSmoother smoother = new CornerSmoother();
    private long lastAnalysisNanos;
    private byte[] packedRgba = new byte[0];
    /** Reused across frames: the analysis image size never changes mid-session. */
    private Mat reusableRgba;

    /**
     * Consecutive frames the network has declined before the contour fallback may run.
     *
     * <p>The fallback costs far more than the network and its quads flicker against the model's,
     * so running it on every declined frame burns CPU exactly when the device is already busy and
     * buys nothing: {@link DetectionTracker} bridges these gaps anyway. Three declines mean the
     * network is genuinely blind to this view, which is the case geometry exists for.
     */
    private static final int FALLBACK_AFTER_DECLINES = 3;
    private int modelDeclines;
    private boolean loggedSize;
    private int framesSinceLog;

    public CubeAnalyzer(Listener listener) { this(listener, null, null); }

    public CubeAnalyzer(Listener listener, CubeFaceModel model) {
        this(listener, model, null);
    }

    public CubeAnalyzer(Listener listener, CubeFaceModel model, DetectionDump dump) {
        this.listener = listener;
        this.model = model;
        this.dump = dump;
    }

    @Override public void analyze(@NonNull ImageProxy image) {
        try {
            long now = System.nanoTime();
            if (now - lastAnalysisNanos < 66_000_000L) return;
            if (lastAnalysisNanos > 0 && ++framesSinceLog == 90) {
                // Logcat-only heartbeat: how fast the pipeline really runs, without touching disk.
                android.util.Log.i("CubeAnalyzer", String.format(java.util.Locale.US,
                    "pace %.1f fps (%.0f ms/frame)", 90f / ((now - lastAnalysisNanos) / 1e9f),
                    (now - lastAnalysisNanos) / 90f / 1e6f));
                framesSinceLog = 0;
            }
            lastAnalysisNanos = now;
            if (image.getPlanes().length == 0) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            int width = image.getWidth(), height = image.getHeight();
            int required = width * height * 4;
            if (packedRgba.length != required) packedRgba = new byte[required];
            ByteBuffer source = plane.getBuffer().duplicate();
            if (plane.getPixelStride() == 4 && plane.getRowStride() == width * 4) {
                // Contiguous rows: the whole buffer can be lifted in one copy.
                source.get(packedRgba, 0, required);
            } else {
                packRows(plane.getBuffer(), packedRgba, width, height,
                    plane.getRowStride(), plane.getPixelStride());
            }

            // OUTPUT_IMAGE_FORMAT_RGBA_8888 already hands back R,G,B,A in that byte order, which is
            // what OpenCV's RGBA layout expects, so the buffer is used as-is. An earlier channel
            // remap here assumed A,R,G,B and left the blue channel holding a constant alpha.
            Mat rgba = reuseOrCreateMat(width, height);
            rgba.put(0, 0, packedRgba);
            if (!loggedSize) {
                loggedSize = true;
                if (dump != null) dump.log("analysis_frame " + width + "x" + height);
            }
            DetectedFace face = detectWithModel(rgba);
            // Geometry still runs when the network keeps declining, so a device without the model,
            // or a view it is blind to, degrades instead of going blind.
            if (face == null) {
                face = fallbackDetect(rgba);
            } else {
                modelDeclines = 0;
            }
            listener.onDetection(face);
        } finally {
            image.close();
        }
    }

    private Mat reuseOrCreateMat(int width, int height) {
        if (reusableRgba == null) {
            reusableRgba = new Mat(height, width, CvType.CV_8UC4);
        }
        return reusableRgba;
    }

    private DetectedFace fallbackDetect(Mat rgba) {
        if (model != null && ++modelDeclines < FALLBACK_AFTER_DECLINES) {
            if (dump != null) dump.note("cnn_decline_" + modelDeclines, null);
            return null;
        }
        DetectedFace face = detector.detect(rgba);
        int declines = modelDeclines;
        modelDeclines = 0;
        if (dump != null) dump.recordGeometry(rgba, face, declines);
        return face;
    }

    /** Runs the network and turns its four corners into a sampled face. */
    private DetectedFace detectWithModel(Mat rgba) {
        if (model == null) {
            if (dump != null) dump.recordCnn(rgba, null, null, "no_model");
            return null;
        }
        CubeFaceModel.DebugResult evaluated = model.evaluate(rgba);
        CubeFaceModel.Result result = evaluated.toResult();
        // A dropped frame does not discard the smoothed quad: re-acquisition is already handled by
        // the smoother snapping when the new quad is far from the old one, and keeping the history
        // lets a short gap in detection cost nothing.
        if (result == null) {
            if (dump != null) dump.recordCnn(rgba, evaluated, null, evaluated.reject);
            return null;
        }
        // Smoothing happens before sampling, so the nine sample points sit on steadier positions.
        org.opencv.core.Point[] corners =
            FaceSampler.orderCorners(smoother.update(result.corners));
        FaceSample sample = FaceSampler.sample(rgba, corners, result.presence);
        if (sample == null) {
            if (dump != null) dump.recordCnn(rgba, evaluated, null, "sample_fail");
            return null;
        }
        float[] flat = new float[8];
        for (int i = 0; i < 4; i++) {
            flat[i * 2] = (float) corners[i].x;
            flat[i * 2 + 1] = (float) corners[i].y;
        }
        DetectedFace face = new DetectedFace(sample, flat, rgba.cols(), rgba.rows(), result.presence);
        if (dump != null) dump.recordCnn(rgba, evaluated, face, null);
        return face;
    }

    static void packRows(ByteBuffer source, byte[] target, int width, int height,
                         int rowStride, int pixelStride) {
        ByteBuffer buffer = source.duplicate();
        int base = buffer.position();
        if (pixelStride == 4) {
            int rowBytes = width * 4;
            for (int row = 0; row < height; row++) {
                buffer.position(base + row * rowStride);
                buffer.get(target, row * rowBytes, rowBytes);
            }
            return;
        }
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int sourceOffset = base + row * rowStride + col * pixelStride;
                int targetOffset = (row * width + col) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    target[targetOffset + channel] = buffer.get(sourceOffset + channel);
                }
            }
        }
    }
}
