package com.mofang.cubear;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Rectifies a face given its four corners and reads the nine stickers.
 *
 * <p>Shared by both detectors: how a face is located is changing, how it is measured is not.
 * Each cell yields a median colour plus a Lab reading, and cells that are not one flat colour are
 * refused outright rather than reported as a plausible sticker.
 */
public final class FaceSampler {
    private static final int WARP_SIZE = 300;
    /**
     * Half-width of the patch read from each 100px cell. Kept well inside the sticker: a wider
     * patch catches the black border as soon as the quad is a few percent off, and the resulting
     * "not one flat colour" verdict threw away one detected frame in seven on real footage.
     */
    private static final int SAMPLE_RADIUS = 14;
    /** Above this share of pixels far from the median, the cell is a finger, an edge or glare. */
    private static final float MAX_DISPERSION = 0.32f;

    private FaceSampler() {}

    /**
     * Puts four corners into the order the sampler expects: clockwise from the top-left-most.
     *
     * <p>Both detectors produce corners in an arbitrary rotation, so the canonical order is derived
     * here rather than trusted from either of them.
     */
    public static Point[] orderCorners(Point[] input) {
        Point center = new Point();
        for (Point p : input) { center.x += p.x; center.y += p.y; }
        center.x /= 4; center.y /= 4;
        java.util.List<Point> sorted = new java.util.ArrayList<>();
        java.util.Collections.addAll(sorted, input);
        sorted.sort(java.util.Comparator.comparingDouble(
            p -> Math.atan2(p.y - center.y, p.x - center.x)));
        int start = 0;
        double minimum = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double sum = sorted.get(i).x + sorted.get(i).y;
            if (sum < minimum) { minimum = sum; start = i; }
        }
        Point[] ordered = new Point[4];
        for (int i = 0; i < 4; i++) ordered[i] = sorted.get((start + i) % 4);
        // atan2 order is clockwise in image coordinates: TL, TR, BR, BL.
        return ordered;
    }

    /**
     * @param corners four corners ordered clockwise from the top-left, in {@code rgba} coordinates
     * @param quality how well the face was localised, folded into the reported confidence
     */
    public static FaceSample sample(Mat rgba, Point[] corners, float quality) {
        MatOfPoint2f source = new MatOfPoint2f(corners);
        MatOfPoint2f target = new MatOfPoint2f(new Point(0, 0), new Point(WARP_SIZE, 0),
            new Point(WARP_SIZE, WARP_SIZE), new Point(0, WARP_SIZE));
        Mat transform = Imgproc.getPerspectiveTransform(source, target);
        Mat warped = new Mat();
        try {
            Imgproc.warpPerspective(rgba, warped, transform, new Size(WARP_SIZE, WARP_SIZE),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar.all(0));
            CubeColor[] colors = new CubeColor[9];
            float[][] lab = new float[9][];
            boolean[] reliable = new boolean[9];
            int known = 0;
            int cell = WARP_SIZE / 3;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int cx = col * cell + cell / 2;
                    int cy = row * cell + cell / 2;
                    int index = row * 3 + col;
                    Mat patch = new Mat();
                    warped.submat(new Rect(cx - SAMPLE_RADIUS, cy - SAMPLE_RADIUS,
                        SAMPLE_RADIUS * 2, SAMPLE_RADIUS * 2)).copyTo(patch);
                    int[] median = medianRgb(patch);
                    boolean uniform = dispersion(patch, median) <= MAX_DISPERSION;
                    patch.release();

                    lab[index] = Lab.fromRgb(median[0], median[1], median[2]);
                    reliable[index] = uniform;
                    CubeColor color = uniform
                        ? CubeColor.classify(median[0], median[1], median[2])
                        : CubeColor.UNKNOWN;
                    colors[index] = color;
                    if (color != CubeColor.UNKNOWN) known++;
                }
            }
            return new FaceSample(colors, lab, reliable, (known / 9f) * quality);
        } finally {
            source.release(); target.release(); transform.release(); warped.release();
        }
    }

    /**
     * Per-channel median of an RGBA patch. The median ignores specular highlights and the dark
     * bevel around a sticker, both of which drag a mean off the sticker's true colour.
     */
    static int[] medianRgb(Mat patch) {
        byte[] pixels = new byte[(int) (patch.total() * patch.channels())];
        patch.get(0, 0, pixels);
        int count = (int) patch.total();
        int[] result = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            int[] histogram = new int[256];
            for (int i = 0; i < count; i++) histogram[pixels[i * 4 + channel] & 0xFF]++;
            int seen = 0;
            for (int value = 0; value < 256; value++) {
                seen += histogram[value];
                if (seen * 2 >= count) { result[channel] = value; break; }
            }
        }
        return result;
    }

    /** Fraction of pixels far from the patch median, i.e. how much the patch is not one flat colour. */
    static float dispersion(Mat patch, int[] median) {
        byte[] pixels = new byte[(int) (patch.total() * patch.channels())];
        patch.get(0, 0, pixels);
        int count = (int) patch.total();
        int outliers = 0;
        for (int i = 0; i < count; i++) {
            int deviation = 0;
            for (int channel = 0; channel < 3; channel++) {
                deviation = Math.max(deviation,
                    Math.abs((pixels[i * 4 + channel] & 0xFF) - median[channel]));
            }
            if (deviation > 46) outliers++;
        }
        return outliers / (float) count;
    }
}
