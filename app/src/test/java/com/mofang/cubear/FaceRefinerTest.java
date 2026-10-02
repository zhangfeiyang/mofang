package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

/**
 * Refinement and lattice sampling on rendered faces with known geometry.
 *
 * <p>The renderer paints a projective 3x3 sticker lattice — rounded stickers on black plastic —
 * over a textured background, so the refiner sees the same structure it meets on real frames
 * while the true sticker centres are known exactly.
 */
public class FaceRefinerTest {
    private static final int W = 720, H = 1280;
    private static final int[] PALETTE = {
        0xF0F0EA, 0xD8402F, 0x5FC044, 0xF2D51F, 0xF08A26, 0x2E6FD0};

    @Test public void homographyFitRecoversAProjectiveMap() {
        double[] truth = Homography.fromQuads(
            new double[]{0, 0, 3, 0, 3, 3, 0, 3},
            new double[]{100, 120, 400, 90, 430, 420, 80, 380});
        assertNotNull(truth);
        double[] src = new double[18], dst = new double[18], p = new double[2];
        for (int i = 0; i < 9; i++) {
            src[i * 2] = i % 3 + 0.5;
            src[i * 2 + 1] = i / 3 + 0.5;
            Homography.apply(truth, src[i * 2], src[i * 2 + 1], p);
            dst[i * 2] = p[0];
            dst[i * 2 + 1] = p[1];
        }
        double[] fitted = Homography.fit(src, dst, 9);
        assertNotNull(fitted);
        Homography.apply(fitted, 0, 0, p);
        assertEquals(100, p[0], 1e-6);
        assertEquals(120, p[1], 1e-6);
        Homography.apply(fitted, 3, 3, p);
        assertEquals(430, p[0], 1e-6);
        assertEquals(420, p[1], 1e-6);
        double[] inverse = Homography.invert(fitted);
        Homography.apply(inverse, 430, 420, p);
        assertEquals(3, p[0], 1e-9);
        assertEquals(3, p[1], 1e-9);
    }

    @Test public void refinerSnapsALooseQuadOntoTheStickers() {
        Random random = new Random(7);
        int good = 0, trials = 40;
        double worst = 0;
        for (int trial = 0; trial < trials; trial++) {
            double[] face = randomFace(random);
            int[] stickers = randomStickers(random);
            byte[] frame = render(face, stickers, random);
            double pitch = meanSide(face) / 3;
            // The network's typical miss: a third of a pitch off, a few degrees of roll, 15% scale.
            double[] coarse = perturb(face, random, 0.35 * pitch, Math.toRadians(8), 0.15);
            FaceRefiner.Result result = new FaceRefiner().refine(frame, W, H,
                FaceRefiner.orderCorners(coarse));
            assertNotNull("trial " + trial + " must refine", result);
            double error = FaceRefiner.maxCentreShift(FaceRefiner.orderCorners(face), result.quad) / pitch;
            worst = Math.max(worst, error);
            if (error < 0.12) good++;
        }
        assertTrue("refined centres within 0.12 pitch on " + good + "/" + trials + ", worst " + worst,
            good >= trials - 1);
    }

    @Test public void latticeSamplingReadsEveryStickerOfARefinedFace() {
        Random random = new Random(11);
        double[] face = randomFace(random);
        int[] stickers = randomStickers(random);
        byte[] frame = render(face, stickers, random);
        FaceRefiner.Result result = new FaceRefiner().refine(frame, W, H,
            FaceRefiner.orderCorners(perturb(face, random, 0.2 * meanSide(face) / 3, 0.05, 0.08)));
        assertNotNull(result);
        FaceSample sample = FaceSampler.sample(frame, W, H, result.quad, true, 1f);
        assertNotNull(sample);
        assertEquals("all nine flat stickers are readable", 0, sample.unreliableCount());
        // Readings must match the painted colours cell for cell, up to the quad's rotation.
        double[] ordered = FaceRefiner.orderCorners(face);
        float[][] expected = new float[9][];
        for (int i = 0; i < 9; i++) expected[i] = labOf(PALETTE[stickers[i]]);
        assertTrue("sampled cells line up with the painted stickers",
            bestRotationError(sample, expected) < 6f);
        assertNotNull(ordered);
    }

    @Test public void coarseSamplingStaysOnStickersDespiteALooseQuad() {
        Random random = new Random(5);
        double[] face = randomFace(random);
        int[] stickers = randomStickers(random);
        byte[] frame = render(face, stickers, random);
        double[] loose = perturb(face, random, 0.18 * meanSide(face) / 3, 0.03, 0.05);
        FaceSample sample = FaceSampler.sample(frame, W, H, FaceRefiner.orderCorners(loose), false, 1f);
        assertNotNull(sample);
        assertTrue(sample.unreliableCount() <= 2);
    }

    @Test public void refinerRefusesAFrameWithoutACube() {
        Random random = new Random(3);
        byte[] frame = new byte[W * H * 4];
        paintBackground(frame, random);
        double[] quad = {200, 400, 500, 400, 500, 700, 200, 700};
        assertNull(new FaceRefiner().refine(frame, W, H, quad));
    }

    @Test public void otsuSplitsABimodalHistogram() {
        int[] histogram = new int[256];
        histogram[30] = 500;
        histogram[200] = 1500;
        int t = FaceRefiner.otsu(histogram);
        assertTrue(t >= 30 && t < 200);
    }

    // ----------------------------------------------------------------------- rendering

    static double[] randomFace(Random random) {
        double size = 260 + random.nextDouble() * 160;
        double cx = 240 + random.nextDouble() * 240, cy = 480 + random.nextDouble() * 320;
        double roll = (random.nextDouble() - 0.5) * Math.toRadians(50);
        double[] quad = new double[8];
        double[][] unit = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        // Mild perspective: one side shorter than the other.
        double squeeze = 1 + (random.nextDouble() - 0.5) * 0.3;
        for (int i = 0; i < 4; i++) {
            double ux = unit[i][0] * size / 2, uy = unit[i][1] * size / 2;
            if (uy < 0) ux *= squeeze;
            double x = ux * Math.cos(roll) - uy * Math.sin(roll);
            double y = ux * Math.sin(roll) + uy * Math.cos(roll);
            quad[i * 2] = cx + x;
            quad[i * 2 + 1] = cy + y;
        }
        return quad;
    }

    static int[] randomStickers(Random random) {
        int[] stickers = new int[9];
        for (int i = 0; i < 9; i++) stickers[i] = random.nextInt(6);
        return stickers;
    }

    /** Paints the face whose lattice boundary is {@code face} (corner i = lattice corner i). */
    static byte[] render(double[] face, int[] stickers, Random random) {
        byte[] frame = new byte[W * H * 4];
        paintBackground(frame, random);
        double[] toImage = Homography.fromSquare(3, face);
        double[] toLattice = Homography.invert(toImage);
        double minX = W, minY = H, maxX = 0, maxY = 0;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, face[i * 2]); maxX = Math.max(maxX, face[i * 2]);
            minY = Math.min(minY, face[i * 2 + 1]); maxY = Math.max(maxY, face[i * 2 + 1]);
        }
        double[] p = new double[2];
        double half = 0.43, radius = 0.12;
        for (int y = (int) Math.max(0, minY - 30); y < Math.min(H, maxY + 30); y++) {
            for (int x = (int) Math.max(0, minX - 30); x < Math.min(W, maxX + 30); x++) {
                Homography.apply(toLattice, x, y, p);
                // The cube body extends a little past the lattice boundary.
                if (p[0] < -0.08 || p[0] > 3.08 || p[1] < -0.08 || p[1] > 3.08) continue;
                int rgb = 0x161618;
                int col = (int) Math.floor(p[0]), row = (int) Math.floor(p[1]);
                if (col >= 0 && col < 3 && row >= 0 && row < 3) {
                    double dx = Math.abs(p[0] - col - 0.5), dy = Math.abs(p[1] - row - 0.5);
                    double ox = Math.max(0, dx - (half - radius)), oy = Math.max(0, dy - (half - radius));
                    if (dx < half && dy < half && Math.hypot(ox, oy) < radius) {
                        rgb = PALETTE[stickers[row * 3 + col]];
                    }
                }
                int at = (y * W + x) * 4;
                int noise = random.nextInt(9) - 4;
                frame[at] = (byte) clamp(((rgb >> 16) & 0xFF) + noise);
                frame[at + 1] = (byte) clamp(((rgb >> 8) & 0xFF) + noise);
                frame[at + 2] = (byte) clamp((rgb & 0xFF) + noise);
                frame[at + 3] = (byte) 255;
            }
        }
        return frame;
    }

    static void paintBackground(byte[] frame, Random random) {
        int base = 120 + random.nextInt(60);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int at = (y * W + x) * 4;
                // A soft wood-like gradient with stripes, not a flat field.
                int v = base + (int) (25 * Math.sin(x * 0.013 + y * 0.002)) + random.nextInt(7) - 3;
                frame[at] = (byte) clamp(v + 20);
                frame[at + 1] = (byte) clamp(v + 5);
                frame[at + 2] = (byte) clamp(v - 15);
                frame[at + 3] = (byte) 255;
            }
        }
    }

    static double[] perturb(double[] quad, Random random, double shift, double roll, double scale) {
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += quad[i * 2]; cy += quad[i * 2 + 1]; }
        cx /= 4; cy /= 4;
        double angle = (random.nextDouble() * 2 - 1) * roll;
        double s = 1 + (random.nextDouble() * 2 - 1) * scale;
        double direction = random.nextDouble() * Math.PI * 2;
        double tx = Math.cos(direction) * shift, ty = Math.sin(direction) * shift;
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = (quad[i * 2] - cx) * s, y = (quad[i * 2 + 1] - cy) * s;
            out[i * 2] = cx + tx + x * Math.cos(angle) - y * Math.sin(angle);
            out[i * 2 + 1] = cy + ty + x * Math.sin(angle) + y * Math.cos(angle);
        }
        return out;
    }

    static double meanSide(double[] quad) {
        double total = 0;
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            total += Math.hypot(quad[n * 2] - quad[i * 2], quad[n * 2 + 1] - quad[i * 2 + 1]);
        }
        return total / 4;
    }

    private static float[] labOf(int rgb) {
        return Lab.fromRgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /** Mean Lab error between a sample and the expected cells, at the best of four rotations. */
    private static float bestRotationError(FaceSample sample, float[][] expected) {
        float best = Float.MAX_VALUE;
        FaceSample rotated = sample;
        for (int turn = 0; turn < 4; turn++) {
            float total = 0;
            for (int i = 0; i < 9; i++) total += Lab.distance(rotated.lab[i], expected[i]);
            best = Math.min(best, total / 9);
            // Mirror images cannot occur: the refiner keeps clockwise order.
            rotated = rotated.rotateClockwise();
        }
        return best;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
