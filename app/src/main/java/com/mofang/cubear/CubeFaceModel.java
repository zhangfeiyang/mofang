package com.mofang.cubear;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Locates the cube face with a small convolutional network.
 *
 * <p>Replaces the contour-and-lattice search, which could not represent perspective foreshortening
 * and so lost the face whenever the cube was held at an angle. The network regresses the four
 * corners directly and reports whether a face is present at all.
 *
 * <p>The whole frame is fed in at the camera's own portrait aspect, which is also what the training
 * renders used. Centre-cropping to a square instead cuts the top off a cube held high in the frame,
 * and that alone dropped real-frame detection from 19 of 22 to 9 of 22.
 */
public final class CubeFaceModel implements Closeable {
    private static final String TAG = "CubeFaceModel";
    private static final String ASSET = "cubeface.onnx";
    /** Matches the training canvas, and closely matches the 720x1280 analysis frame's aspect. */
    static final int INPUT_WIDTH = 160;
    static final int INPUT_HEIGHT = 288;
    /**
     * Below this the frame is treated as having no usable face.
     *
     * <p>Set high because the score is sharply bimodal: on real footage, moving it from 0.60 to
     * 0.85 costs under 3% of detections but drops the near-45-degree views where the network is
     * unsure which of two faces it is looking at and returns a quadrilateral spanning both. A
     * confidently wrong quad is worse than no detection, since it yields a face that looks
     * perfectly readable and has to be caught later by the cube's own rules.
     */
    static final float PRESENCE_THRESHOLD = 0.85f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final float[] input = new float[3 * INPUT_WIDTH * INPUT_HEIGHT];
    private final byte[] pixels = new byte[INPUT_WIDTH * INPUT_HEIGHT * 4];
    /** Reused across frames; created after OpenCV is loaded, not at class init. */
    private Mat resized;

    private CubeFaceModel(OrtEnvironment environment, OrtSession session) {
        this.environment = environment;
        this.session = session;
        this.inputName = session.getInputNames().iterator().next();
    }

    /** Returns null when the model cannot be loaded, letting the caller fall back to geometry. */
    public static CubeFaceModel create(Context context) {
        try (InputStream stream = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);

            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            boolean nnapi = true;
            try {
                options.addNnapi();
            } catch (Throwable ignored) {
                // NNAPI is unavailable on some devices; give the CPU path a few threads instead.
                nnapi = false;
                options.setIntraOpNumThreads(4);
            }
            OrtSession session = environment.createSession(buffer.toByteArray(), options);
            Log.i(TAG, "loaded " + ASSET + " nnapi=" + nnapi);
            CubeFaceModel model = new CubeFaceModel(environment, session);
            model.warmUp();
            return model;
        } catch (Throwable error) {
            Log.e(TAG, "model unavailable: " + error);
            return null;
        }
    }

    /**
     * Runs one inference before the camera starts. NNAPI compiles its execution plan on the
     * first call — tens to hundreds of milliseconds — and paying that during the first analysed
     * frame reads as a frozen preview. The logged time also tells CPU from accelerator speed,
     * which decides whether the input size is worth revisiting.
     */
    private void warmUp() {
        try {
            org.opencv.core.Mat blank = new org.opencv.core.Mat(
                INPUT_HEIGHT, INPUT_WIDTH, org.opencv.core.CvType.CV_8UC4,
                org.opencv.core.Scalar.all(128));
            long start = android.os.SystemClock.uptimeMillis();
            evaluate(blank);
            blank.release();
            Log.i(TAG, "warmup inference " + (android.os.SystemClock.uptimeMillis() - start) + "ms");
        } catch (Throwable error) {
            Log.w(TAG, "warmup skipped: " + error);
        }
    }

    public static final class Result {
        /** Four corners clockwise from the top-left, in full-frame coordinates. */
        public final Point[] corners;
        public final float presence;

        Result(Point[] corners, float presence) {
            this.corners = corners;
            this.presence = presence;
        }
    }

    /**
     * Full network output, including rejected frames. Used by the debug dump so a low presence
     * score is still recorded instead of disappearing into a null.
     */
    public static final class DebugResult {
        public final Point[] corners;
        public final float presence;
        public final boolean accepted;
        public final String reject;
        public final double areaFraction;
        public final double aspect;
        public final double shortest;

        DebugResult(Point[] corners, float presence, boolean accepted, String reject,
                    double areaFraction, double aspect, double shortest) {
            this.corners = corners;
            this.presence = presence;
            this.accepted = accepted;
            this.reject = reject;
            this.areaFraction = areaFraction;
            this.aspect = aspect;
            this.shortest = shortest;
        }

        Result toResult() {
            return accepted ? new Result(corners, presence) : null;
        }
    }

    /** Runs the network on one RGBA frame. Returns null when no face is confidently present. */
    public Result detect(Mat rgba) {
        return evaluate(rgba).toResult();
    }

    public DebugResult evaluate(Mat rgba) {
        try {
            if (resized == null) resized = new Mat();
            Imgproc.resize(rgba, resized, new Size(INPUT_WIDTH, INPUT_HEIGHT), 0, 0,
                Imgproc.INTER_AREA);
            resized.get(0, 0, pixels);

            int plane = INPUT_WIDTH * INPUT_HEIGHT;
            for (int i = 0; i < plane; i++) {
                input[i] = (pixels[i * 4] & 0xFF) / 255f;
                input[plane + i] = (pixels[i * 4 + 1] & 0xFF) / 255f;
                input[2 * plane + i] = (pixels[i * 4 + 2] & 0xFF) / 255f;
            }

            long[] shape = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
            try (OnnxTensor tensor = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(input), shape);
                 OrtSession.Result output = session.run(
                     Collections.singletonMap(inputName, tensor))) {
                float[] corners = ((float[][]) output.get(0).getValue())[0];
                float presence = ((float[][]) output.get(1).getValue())[0][0];
                Point[] points = new Point[4];
                for (int i = 0; i < 4; i++) {
                    points[i] = new Point(corners[i * 2] * rgba.cols(),
                        corners[i * 2 + 1] * rgba.rows());
                }
                ShapeStats stats = measure(points, rgba.cols(), rgba.rows());
                if (presence < PRESENCE_THRESHOLD) {
                    return new DebugResult(points, presence, false, "presence",
                        stats.areaFraction, stats.aspect, stats.shortest);
                }
                if (stats.reject != null) {
                    return new DebugResult(points, presence, false, stats.reject,
                        stats.areaFraction, stats.aspect, stats.shortest);
                }
                return new DebugResult(points, presence, true, null,
                    stats.areaFraction, stats.aspect, stats.shortest);
            }
        } catch (Throwable error) {
            Log.e(TAG, "inference failed: " + error);
            return new DebugResult(null, -1f, false, "inference", 0, 0, 0);
        }
    }

    private static final class ShapeStats {
        final double areaFraction;
        final double aspect;
        final double shortest;
        final String reject;

        ShapeStats(double areaFraction, double aspect, double shortest, String reject) {
            this.areaFraction = areaFraction;
            this.aspect = aspect;
            this.shortest = shortest;
            this.reject = reject;
        }
    }

    /** Rejects degenerate quads the sampler could not warp meaningfully. */
    private static ShapeStats measure(Point[] corners, int width, int height) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            Point a = corners[i], b = corners[(i + 1) % 4];
            area += a.x * b.y - b.x * a.y;
        }
        area = Math.abs(area) / 2.0;
        double areaFraction = area / (width * (double) height);
        double shortest = Double.MAX_VALUE, longest = 0;
        for (int i = 0; i < 4; i++) {
            double side = Math.hypot(corners[i].x - corners[(i + 1) % 4].x,
                corners[i].y - corners[(i + 1) % 4].y);
            shortest = Math.min(shortest, side);
            longest = Math.max(longest, side);
        }
        double aspect = shortest <= 0 ? 99 : longest / shortest;
        String reject = null;
        if (areaFraction < 0.006) reject = "implausible_area";
        else if (shortest <= 12) reject = "implausible_short";
        else if (aspect >= 2.2) reject = "implausible_aspect";
        return new ShapeStats(areaFraction, aspect, shortest, reject);
    }

    @Override public void close() {
        if (resized != null) resized.release();
        try {
            session.close();
        } catch (Exception ignored) {
            // Nothing useful to do while tearing down.
        }
    }
}
