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
    private final CornerSmoother smoother = new CornerSmoother();
    private long lastAnalysisNanos;
    private byte[] packedRgba = new byte[0];

    public CubeAnalyzer(Listener listener) { this(listener, null); }

    public CubeAnalyzer(Listener listener, CubeFaceModel model) {
        this.listener = listener;
        this.model = model;
    }

    @Override public void analyze(@NonNull ImageProxy image) {
        try {
            long now = System.nanoTime();
            if (now - lastAnalysisNanos < 95_000_000L) return;
            lastAnalysisNanos = now;
            if (image.getPlanes().length == 0) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            int width = image.getWidth(), height = image.getHeight();
            int required = width * height * 4;
            if (packedRgba.length != required) packedRgba = new byte[required];
            packRows(plane.getBuffer(), packedRgba, width, height,
                plane.getRowStride(), plane.getPixelStride());

            if (DUMP_RAW_PLANE) dumpRawPlaneOnce(packedRgba, width, height);

            // OUTPUT_IMAGE_FORMAT_RGBA_8888 already hands back R,G,B,A in that byte order, which is
            // what OpenCV's RGBA layout expects, so the buffer is used as-is. An earlier channel
            // remap here assumed A,R,G,B and left the blue channel holding a constant alpha.
            Mat rgba = new Mat(height, width, CvType.CV_8UC4);
            try {
                rgba.put(0, 0, packedRgba);
                DetectedFace face = detectWithModel(rgba);
                // Geometry still runs when the network declines, so a device without the model,
                // or a frame it is unsure about, degrades instead of going blind.
                if (face == null) face = detector.detect(rgba);
                listener.onDetection(face);
            } finally {
                rgba.release();
            }
        } finally {
            image.close();
        }
    }

    /** Runs the network and turns its four corners into a sampled face. */
    private DetectedFace detectWithModel(Mat rgba) {
        if (model == null) return null;
        CubeFaceModel.Result result = model.detect(rgba);
        // A dropped frame does not discard the smoothed quad: re-acquisition is already handled by
        // the smoother snapping when the new quad is far from the old one, and keeping the history
        // lets a short gap in detection cost nothing.
        if (result == null) return null;
        // Smoothing happens before sampling, so the nine sample points sit on steadier positions.
        org.opencv.core.Point[] corners =
            FaceSampler.orderCorners(smoother.update(result.corners));
        FaceSample sample = FaceSampler.sample(rgba, corners, result.presence);
        if (sample == null) return null;
        float[] flat = new float[8];
        for (int i = 0; i < 4; i++) {
            flat[i * 2] = (float) corners[i].x;
            flat[i * 2 + 1] = (float) corners[i].y;
        }
        return new DetectedFace(sample, flat, rgba.cols(), rgba.rows(), result.presence);
    }

    /**
     * Writes one analysis buffer to /sdcard/Download so the exact bytes reaching the detector can
     * be checked off-device. Flip on to confirm the camera's channel order on a new device; the
     * preview stream is rendered independently and cannot settle that question.
     */
    static final boolean DUMP_RAW_PLANE = false;

    private static boolean rawPlaneDumped;

    /**
     * Writes one analysis buffer verbatim so the exact bytes the detector sees can be inspected
     * off-device. The preview stream is rendered independently, so it cannot confirm this path.
     */
    private static void dumpRawPlaneOnce(byte[] packed, int width, int height) {
        if (rawPlaneDumped) return;
        rawPlaneDumped = true;
        java.io.File file = new java.io.File("/sdcard/Download/cube_raw_"
            + width + "x" + height + ".bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(packed);
            android.util.Log.d("CubeDetect", "raw plane dumped to " + file.getAbsolutePath());
        } catch (Exception error) {
            android.util.Log.e("CubeDetect", "raw dump failed: " + error);
        }
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
