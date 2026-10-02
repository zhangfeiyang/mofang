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
     * <p>The first model needed 0.85 to keep out near-45-degree views where it was unsure which
     * face it saw and returned a quad spanning both — nothing downstream could tell such a quad
     * from a real face. The retrained model is far more precise, and a straddle now has to get
     * past the lattice refiner and the agreement check before it can be captured, so the
     * threshold only needs to drop frames without a cube: at 0.6 it keeps 20 of 21 held-out
     * frames, against 17 at 0.85.
     */
    static final float PRESENCE_THRESHOLD = 0.6f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final float[] input = new float[3 * INPUT_WIDTH * INPUT_HEIGHT];
    private AreaResizer resizer;
    /** Which execution provider won the start-up race, for the logs. */
    final String provider;

    private CubeFaceModel(OrtEnvironment environment, OrtSession session, String provider) {
        this.environment = environment;
        this.session = session;
        this.inputName = session.getInputNames().iterator().next();
        this.provider = provider;
    }

    /**
     * Loads the model on whichever execution provider runs it fastest on this device.
     *
     * <p>NNAPI is not reliably faster for a network this small: on many phones it falls back
     * operator by operator, or pays more in dispatch than the 2-4 CPU threads need for the whole
     * graph. Both are timed on a few warm runs and the quicker one is kept. Returns null when the
     * model cannot be loaded, letting the caller fall back to the lattice search.
     */
    public static CubeFaceModel create(Context context) {
        byte[] bytes;
        try (InputStream stream = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            bytes = buffer.toByteArray();
        } catch (Throwable error) {
            Log.e(TAG, "model unavailable: " + error);
            return null;
        }
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        CubeFaceModel cpu = open(environment, bytes, false);
        CubeFaceModel nnapi = open(environment, bytes, true);
        if (cpu == null) return nnapi;
        if (nnapi == null) return cpu;
        long cpuNanos = cpu.benchmark(), nnapiNanos = nnapi.benchmark();
        CubeFaceModel winner = nnapiNanos < cpuNanos * 0.9 ? nnapi : cpu;
        (winner == nnapi ? cpu : nnapi).close();
        Log.i(TAG, String.format(java.util.Locale.US, "cpu %.1f ms, nnapi %.1f ms -> %s",
            cpuNanos / 1e6, nnapiNanos / 1e6, winner.provider));
        return winner;
    }

    private static CubeFaceModel open(OrtEnvironment environment, byte[] bytes, boolean nnapi) {
        try {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            if (nnapi) {
                options.addNnapi();
            } else {
                options.setIntraOpNumThreads(Math.max(1, Math.min(4,
                    Runtime.getRuntime().availableProcessors() / 2)));
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            }
            OrtSession session = environment.createSession(bytes, options);
            Log.i(TAG, "loaded " + ASSET + (nnapi ? " on nnapi" : " on cpu"));
            return new CubeFaceModel(environment, session, nnapi ? "nnapi" : "cpu");
        } catch (Throwable error) {
            Log.w(TAG, (nnapi ? "nnapi" : "cpu") + " session unavailable: " + error);
            return null;
        }
    }

    /**
     * Median of a few inferences after one warm-up. The warm-up also pays NNAPI's plan
     * compilation, which would otherwise land on the first camera frame as a frozen preview.
     */
    private long benchmark() {
        try {
            byte[] blank = new byte[INPUT_WIDTH * INPUT_HEIGHT * 4];
            java.util.Arrays.fill(blank, (byte) 128);
            evaluate(blank, INPUT_WIDTH, INPUT_HEIGHT);
            long[] times = new long[3];
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                evaluate(blank, INPUT_WIDTH, INPUT_HEIGHT);
                times[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(times);
            return times[1];
        } catch (Throwable error) {
            Log.w(TAG, "benchmark failed: " + error);
            return Long.MAX_VALUE;
        }
    }

    public static final class Result {
        /** Four corners as x0,y0..x3,y3 in full-frame pixels, in the network's own order. */
        public final double[] corners;
        public final float presence;

        Result(double[] corners, float presence) {
            this.corners = corners;
            this.presence = presence;
        }
    }

    /**
     * Full network output, including rejected frames. Used by the debug dump so a low presence
     * score is still recorded instead of disappearing into a null.
     */
    public static final class DebugResult {
        public final double[] corners;
        public final float presence;
        public final boolean accepted;
        public final String reject;
        public final double areaFraction;
        public final double aspect;
        public final double shortest;

        DebugResult(double[] corners, float presence, boolean accepted, String reject,
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
    public Result detect(byte[] rgba, int width, int height) {
        return evaluate(rgba, width, height).toResult();
    }

    public DebugResult evaluate(byte[] rgba, int width, int height) {
        try {
            if (resizer == null || !resizer.fits(width, height)) {
                resizer = new AreaResizer(width, height, INPUT_WIDTH, INPUT_HEIGHT);
            }
            resizer.resize(rgba, input);
            long[] shape = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
            try (OnnxTensor tensor = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(input), shape);
                 OrtSession.Result output = session.run(
                     Collections.singletonMap(inputName, tensor))) {
                float[] corners = ((float[][]) output.get(0).getValue())[0];
                float presence = ((float[][]) output.get(1).getValue())[0][0];
                double[] points = new double[8];
                for (int i = 0; i < 4; i++) {
                    points[i * 2] = corners[i * 2] * width;
                    points[i * 2 + 1] = corners[i * 2 + 1] * height;
                }
                ShapeStats stats = measure(points, width, height);
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
    private static ShapeStats measure(double[] corners, int width, int height) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            area += corners[i * 2] * corners[n * 2 + 1] - corners[n * 2] * corners[i * 2 + 1];
        }
        area = Math.abs(area) / 2.0;
        double areaFraction = area / (width * (double) height);
        double shortest = Double.MAX_VALUE, longest = 0;
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            double side = Math.hypot(corners[i * 2] - corners[n * 2], corners[i * 2 + 1] - corners[n * 2 + 1]);
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
        try {
            session.close();
        } catch (Exception ignored) {
            // Nothing useful to do while tearing down.
        }
    }
}
