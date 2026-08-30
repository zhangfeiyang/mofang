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
import java.util.Map;
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
    private static final float PRESENCE_THRESHOLD = 0.85f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final float[] input = new float[3 * INPUT_WIDTH * INPUT_HEIGHT];
    private final byte[] pixels = new byte[INPUT_WIDTH * INPUT_HEIGHT * 4];

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
            options.setIntraOpNumThreads(2);
            try {
                options.addNnapi();
            } catch (Throwable ignored) {
                // NNAPI is unavailable on some devices; the CPU path is fast enough at this size.
            }
            OrtSession session = environment.createSession(buffer.toByteArray(), options);
            Log.i(TAG, "loaded " + ASSET);
            return new CubeFaceModel(environment, session);
        } catch (Throwable error) {
            Log.e(TAG, "model unavailable: " + error);
            return null;
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

    /** Runs the network on one RGBA frame. Returns null when no face is confidently present. */
    public Result detect(Mat rgba) {
        Mat resized = new Mat();
        try {
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
                if (presence < PRESENCE_THRESHOLD) return null;

                Point[] points = new Point[4];
                for (int i = 0; i < 4; i++) {
                    points[i] = new Point(corners[i * 2] * rgba.cols(),
                        corners[i * 2 + 1] * rgba.rows());
                }
                return plausible(points, rgba.cols(), rgba.rows())
                    ? new Result(points, presence) : null;
            }
        } catch (Throwable error) {
            Log.e(TAG, "inference failed: " + error);
            return null;
        } finally {
            resized.release();
        }
    }

    /** Rejects degenerate quads the sampler could not warp meaningfully. */
    private static boolean plausible(Point[] corners, int width, int height) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            Point a = corners[i], b = corners[(i + 1) % 4];
            area += a.x * b.y - b.x * a.y;
        }
        area = Math.abs(area) / 2.0;
        if (area < width * (double) height * 0.006) return false;
        double shortest = Double.MAX_VALUE, longest = 0;
        for (int i = 0; i < 4; i++) {
            double side = Math.hypot(corners[i].x - corners[(i + 1) % 4].x,
                corners[i].y - corners[(i + 1) % 4].y);
            shortest = Math.min(shortest, side);
            longest = Math.max(longest, side);
        }
        return shortest > 12 && longest / shortest < 4.0;
    }

    @Override public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // Nothing useful to do while tearing down.
        }
    }
}
