package com.mofang.cubear;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Detects sticker-like blobs anywhere in the image and RANSAC-fits a 3x3 face grid. */
public final class RubiksFaceDetector {
    private static final int MAX_ANALYSIS_WIDTH = 640;
    private static final int WARP_SIZE = 300;
    /** Logs why a frame was rejected. Left in because the failure is invisible from the UI. */
    static final boolean DIAGNOSTICS = true;
    private static final String TAG = "CubeDetect";
    private long lastLogNanos;

    public DetectedFace detect(Mat inputRgba) {
        if (inputRgba == null || inputRgba.empty()) return null;
        double scale = Math.min(1.0, MAX_ANALYSIS_WIDTH / (double) inputRgba.cols());
        Mat rgba = new Mat();
        if (scale < 1.0) {
            Imgproc.resize(inputRgba, rgba, new Size(Math.round(inputRgba.cols() * scale),
                Math.round(inputRgba.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
        } else inputRgba.copyTo(rgba);

        Mat rgb = new Mat(), hsv = new Mat(), saturated = new Mat(), white = new Mat();
        Mat kernel = new Mat(), hierarchy = new Mat();
        List<MatOfPoint> saturatedContours = new ArrayList<>();
        List<MatOfPoint> whiteContours = new ArrayList<>();
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            // Colored stickers plus bright low-saturation white stickers.
            Core.inRange(hsv, new Scalar(0, 52, 42), new Scalar(180, 255, 255), saturated);
            Core.inRange(hsv, new Scalar(0, 0, 195), new Scalar(180, 45, 255), white);
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.morphologyEx(saturated, saturated, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(saturated, saturated, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(white, white, Imgproc.MORPH_OPEN, kernel);
            Imgproc.findContours(saturated, saturatedContours, hierarchy, Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE);
            Imgproc.findContours(white, whiteContours, hierarchy, Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE);

            List<Candidate> saturatedCandidates =
                findStickerCandidates(saturatedContours, rgba.cols(), rgba.rows());
            List<Candidate> whiteCandidates =
                findStickerCandidates(whiteContours, rgba.cols(), rgba.rows());
            int saturatedCount = saturatedCandidates.size();
            List<Candidate> candidates = saturatedCandidates;
            mergeDistinct(candidates, whiteCandidates);
            Grid grid = fitGrid(candidates, rgba.cols(), rgba.rows());
            if (DIAGNOSTICS) {
                logFrame(rgba.cols(), rgba.rows(), saturatedContours.size(), whiteContours.size(),
                    saturatedCount, whiteCandidates.size(), candidates, grid);
            }
            if (grid == null) return null;
            Point[] corners = FaceSampler.orderCorners(grid.corners());
            FaceSample sample = FaceSampler.sample(rgba, corners, grid.matches / 9f);
            if (sample == null) return null;

            float[] fullCorners = new float[8];
            for (int i = 0; i < 4; i++) {
                fullCorners[i * 2] = (float) (corners[i].x / scale);
                fullCorners[i * 2 + 1] = (float) (corners[i].y / scale);
            }
            return new DetectedFace(sample, fullCorners, inputRgba.cols(), inputRgba.rows(),
                grid.matches / 9f);
        } finally {
            for (MatOfPoint contour : saturatedContours) contour.release();
            for (MatOfPoint contour : whiteContours) contour.release();
            rgba.release(); rgb.release(); hsv.release(); saturated.release(); white.release();
            kernel.release(); hierarchy.release();
        }
    }

    private static void mergeDistinct(List<Candidate> target, List<Candidate> additions) {
        for (Candidate addition : additions) {
            boolean duplicate = false;
            for (Candidate existing : target) {
                double threshold = Math.min(existing.size, addition.size) * 0.30;
                if (Math.hypot(existing.center.x - addition.center.x,
                    existing.center.y - addition.center.y) < threshold) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) target.add(addition);
        }
        target.sort(Comparator.comparingDouble((Candidate c) -> c.quality).reversed());
        if (target.size() > 70) target.subList(70, target.size()).clear();
    }

    /** Counts why contours were discarded, so a frame that finds nothing can still explain itself. */
    private static final int[] REJECTIONS = new int[5];
    private static final int TOO_SMALL = 0, TOO_BIG = 1, NOT_QUAD = 2, BAD_ASPECT = 3, NOT_SQUARE = 4;

    private static List<Candidate> findStickerCandidates(List<MatOfPoint> contours, int width, int height) {
        double imageArea = width * (double) height;
        double minArea = Math.max(55, imageArea * 0.00016);
        double maxArea = imageArea * 0.075;
        List<Candidate> out = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            double area = Math.abs(Imgproc.contourArea(contour));
            if (area < minArea) { REJECTIONS[TOO_SMALL]++; continue; }
            if (area > maxArea) { REJECTIONS[TOO_BIG]++; continue; }
            MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
            MatOfPoint2f approx = new MatOfPoint2f();
            try {
                double perimeter = Imgproc.arcLength(curve, true);
                Imgproc.approxPolyDP(curve, approx, perimeter * 0.045, true);
                if (approx.total() < 4 || approx.total() > 8) { REJECTIONS[NOT_QUAD]++; continue; }
                MatOfPoint approxInt = new MatOfPoint(approx.toArray());
                boolean convex = Imgproc.isContourConvex(approxInt);
                approxInt.release();
                if (!convex) { REJECTIONS[NOT_QUAD]++; continue; }
                RotatedRect rect = Imgproc.minAreaRect(curve);
                double shortSide = Math.min(rect.size.width, rect.size.height);
                double longSide = Math.max(rect.size.width, rect.size.height);
                if (shortSide < 7 || longSide / shortSide > 1.75) { REJECTIONS[BAD_ASPECT]++; continue; }
                double rectangularity = area / Math.max(1.0, rect.size.area());
                if (rectangularity < 0.56) { REJECTIONS[NOT_SQUARE]++; continue; }
                out.add(new Candidate(rect.center, Math.sqrt(rect.size.area()), rectangularity));
            } finally {
                curve.release();
                approx.release();
            }
        }
        out.sort(Comparator.comparingDouble((Candidate c) -> c.quality).reversed());
        if (out.size() > 70) return new ArrayList<>(out.subList(0, 70));
        return out;
    }

    /** Best grid coverage seen on the last frame, for diagnostics only. */
    private static int lastBestMatches;

    private static Grid fitGrid(List<Candidate> points, int width, int height) {
        lastBestMatches = 0;
        if (points.size() < 7) return null;
        Grid best = null;
        int n = points.size();
        for (int centerIndex = 0; centerIndex < n; centerIndex++) {
            Candidate center = points.get(centerIndex);
            for (int uIndex = 0; uIndex < n; uIndex++) {
                if (uIndex == centerIndex) continue;
                Vec u = Vec.between(center.center, points.get(uIndex).center);
                if (u.length < center.size * 0.62 || u.length > center.size * 2.25) continue;
                for (int vIndex = uIndex + 1; vIndex < n; vIndex++) {
                    if (vIndex == centerIndex) continue;
                    Vec v = Vec.between(center.center, points.get(vIndex).center);
                    if (v.length < center.size * 0.62 || v.length > center.size * 2.25) continue;
                    double ratio = u.length / v.length;
                    if (ratio < 0.52 || ratio > 1.92) continue;
                    double cosine = Math.abs((u.x * v.x + u.y * v.y) / (u.length * v.length));
                    if (cosine > 0.68) continue;
                    Grid hypothesis = scoreGrid(center, u, v, points);
                    if (hypothesis.matches > lastBestMatches) lastBestMatches = hypothesis.matches;
                    if (hypothesis.matches < 7) continue;
                    if (!reasonable(hypothesis.corners(), width, height)) continue;
                    if (best == null || hypothesis.score > best.score) best = hypothesis;
                }
            }
        }
        return best;
    }

    private static Grid scoreGrid(Candidate origin, Vec u, Vec v, List<Candidate> candidates) {
        Set<Integer> used = new HashSet<>();
        int matches = 0;
        double error = 0;
        double tolerance = Math.min(u.length, v.length) * 0.43;
        for (int row = -1; row <= 1; row++) {
            for (int col = -1; col <= 1; col++) {
                Point expected = new Point(origin.center.x + col * u.x + row * v.x,
                    origin.center.y + col * u.y + row * v.y);
                int nearest = -1;
                double nearestDistance = tolerance;
                for (int i = 0; i < candidates.size(); i++) {
                    if (used.contains(i)) continue;
                    Candidate candidate = candidates.get(i);
                    double sizeRatio = candidate.size / origin.size;
                    if (sizeRatio < 0.43 || sizeRatio > 2.30) continue;
                    double distance = Math.hypot(candidate.center.x - expected.x,
                        candidate.center.y - expected.y);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = i;
                    }
                }
                if (nearest >= 0) {
                    used.add(nearest);
                    matches++;
                    error += nearestDistance / tolerance;
                }
            }
        }
        double orthogonality = 1.0 - Math.abs((u.x * v.x + u.y * v.y) / (u.length * v.length));
        double score = matches + orthogonality * 0.8 - error * 0.12;
        return new Grid(origin.center, u, v, matches, score);
    }

    private static boolean reasonable(Point[] corners, int width, int height) {
        double area = Math.abs(polygonArea(corners));
        double imageArea = width * (double) height;
        if (area < imageArea * 0.008 || area > imageArea * 0.82) return false;
        for (Point point : corners) {
            if (point.x < -width * 0.08 || point.x > width * 1.08
                || point.y < -height * 0.08 || point.y > height * 1.08) return false;
        }
        return true;
    }

    private static double polygonArea(Point[] points) {
        double area = 0;
        for (int i = 0; i < points.length; i++) {
            Point a = points[i], b = points[(i + 1) % points.length];
            area += a.x * b.y - b.x * a.y;
        }
        return area / 2.0;
    }

    /** Emits one line per second describing how far the frame got and what threw the contours away. */
    private void logFrame(int width, int height, int satContours, int whiteContours,
                          int satCandidates, int whiteCandidates, List<Candidate> merged, Grid grid) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 1_000_000_000L) { java.util.Arrays.fill(REJECTIONS, 0); return; }
        lastLogNanos = now;
        String sizes = "";
        if (!merged.isEmpty()) {
            double smallest = Double.MAX_VALUE, largest = 0;
            for (Candidate candidate : merged) {
                smallest = Math.min(smallest, candidate.size);
                largest = Math.max(largest, candidate.size);
            }
            sizes = String.format(java.util.Locale.ROOT, " size=%.0f..%.0f", smallest, largest);
        }
        android.util.Log.d(TAG, String.format(java.util.Locale.ROOT,
            "%dx%d contours=%d/%d cand=%d+%d->%d%s grid=%s best=%d "
                + "reject[small=%d big=%d notquad=%d aspect=%d notsquare=%d]",
            width, height, satContours, whiteContours, satCandidates, whiteCandidates,
            merged.size(), sizes, grid == null ? "none" : "ok", lastBestMatches,
            REJECTIONS[TOO_SMALL], REJECTIONS[TOO_BIG], REJECTIONS[NOT_QUAD],
            REJECTIONS[BAD_ASPECT], REJECTIONS[NOT_SQUARE]));
        java.util.Arrays.fill(REJECTIONS, 0);
    }

    private static final class Candidate {
        final Point center;
        final double size;
        final double quality;
        Candidate(Point center, double size, double quality) {
            this.center = center; this.size = size; this.quality = quality;
        }
    }

    private static final class Vec {
        final double x, y, length;
        Vec(double x, double y) { this.x = x; this.y = y; this.length = Math.hypot(x, y); }
        static Vec between(Point from, Point to) { return new Vec(to.x - from.x, to.y - from.y); }
    }

    private static final class Grid {
        final Point center;
        final Vec u, v;
        final int matches;
        final double score;
        Grid(Point center, Vec u, Vec v, int matches, double score) {
            this.center = center; this.u = u; this.v = v; this.matches = matches; this.score = score;
        }
        Point[] corners() {
            double margin = 1.52;
            return new Point[]{
                new Point(center.x - margin*u.x - margin*v.x, center.y - margin*u.y - margin*v.y),
                new Point(center.x + margin*u.x - margin*v.x, center.y + margin*u.y - margin*v.y),
                new Point(center.x + margin*u.x + margin*v.x, center.y + margin*u.y + margin*v.y),
                new Point(center.x - margin*u.x + margin*v.x, center.y - margin*u.y + margin*v.y)
            };
        }
    }
}
