package com.mofang.cubear;

/**
 * Snaps a coarse face quadrilateral onto the sticker lattice that is actually in the frame.
 *
 * <p>A detector places corners loosely. The first network put its sticker centres a median 0.31
 * pitches from where they belong, so the sampler regularly read a border or the neighbouring
 * sticker; the retrained one does far better (0.13 on held-out frames) but still drifts on steep
 * views and on cubes unlike the ones it saw. The stickers themselves are easy to see once the face
 * is roughly rectified — bright or saturated patches separated by the dark plastic body — so this
 * class rectifies the region around the coarse quad, finds those patches, and fits the 3x3
 * lattice they form: a median centre error of about 0.08 pitches on the labelled real frames,
 * from either network's corners. CubeAnalyzer only trusts the result when it agrees with the
 * detector.
 *
 * <p>Two properties matter more than raw accuracy:
 * <ul>
 * <li>A window shifted by one row onto the neighbouring face is the dangerous failure, because it
 * reads nine perfectly flat stickers. After rectification that face's stickers come out
 * foreshortened, so every blob is weighted by how square and evenly sized it is; a folded window
 * loses the weight of its foreign row.</li>
 * <li>The fit is iterated on tiles rectified by its own previous answer and must stop moving. A
 * fit that keeps sliding is not anchored on one face and is refused rather than trusted.</li>
 * </ul>
 *
 * <p>Pure Java on the RGBA buffer, so it runs without OpenCV and is tested on the JVM. Not thread
 * safe: one instance per analysis thread, its scratch buffers are reused across frames.
 */
final class FaceRefiner {
    /** Lattice margin around the face, in sticker pitches, covered by the rectified tile. */
    static final double MARGIN = 1.0;
    /** Tile edge in pixels: five pitches at 32 px each. */
    static final int TILE = 160;
    private static final int ITERATIONS = 3;
    /** A later iteration may not move any sticker centre further than this, in pitches. */
    private static final double MAX_SETTLE = 0.35;
    private static final double MAX_RMS = 0.2;
    private static final double MIN_SUPPORT = 4.5;
    private static final double W_PRIOR = 0.3, W_SCALE = 0.6;
    private static final int MAX_BLOBS = 40;

    static final class Result {
        /** Lattice boundary, clockwise from the top-left-most corner, image pixels: x0,y0..x3,y3. */
        final double[] quad;
        /** Mean sticker pitch in image pixels. */
        final double pitch;
        /** Stickers that anchored the final fit. */
        final int matched;
        /** Summed blob weight of the final fit, 0..9. */
        final double support;

        Result(double[] quad, double pitch, int matched, double support) {
            this.quad = quad;
            this.pitch = pitch;
            this.matched = matched;
            this.support = support;
        }
    }

    private final double scale = TILE / (3 + 2 * MARGIN);
    private final int[] value = new int[TILE * TILE];
    private final byte[] mask = new byte[TILE * TILE];
    private final byte[] horizontal = new byte[TILE * TILE];
    private final byte[] eroded = new byte[TILE * TILE];
    private final int[] labels = new int[TILE * TILE];
    private final int[] queue = new int[TILE * TILE];
    private final int[] histogram = new int[256];
    private final double[] point = new double[2];
    private final Blob[] blobs = new Blob[MAX_BLOBS];
    private int blobCount;

    FaceRefiner() {
        for (int i = 0; i < MAX_BLOBS; i++) blobs[i] = new Blob();
    }

    /**
     * @param rgba frame bytes, R,G,B,A per pixel, rows packed
     * @param quad coarse quad in frame pixels, clockwise from the top-left-most corner
     * @return the refined quad, or null when no face lattice could be anchored
     */
    Result refine(byte[] rgba, int width, int height, double[] quad) {
        double[] current = quad.clone();
        double[] previous = null;
        Result result = null;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            double[] toImage = Homography.fromSquare(3, current);
            if (toImage == null) return null;
            renderTile(rgba, width, height, toImage);
            findBlobs();
            Fit fit = fitLattice(iteration > 0);
            if (fit == null || fit.count < 5 || fit.support < MIN_SUPPORT) return null;

            // Matched blobs in lattice units of the current quad, against their window cells.
            double[] src = new double[fit.count * 2], dst = new double[fit.count * 2];
            for (int k = 0; k < fit.count; k++) {
                src[k * 2] = fit.cellI[k] + 1.5;
                src[k * 2 + 1] = fit.cellJ[k] + 1.5;
                Blob blob = blobs[fit.blob[k]];
                dst[k * 2] = blob.x / scale - MARGIN;
                dst[k * 2 + 1] = blob.y / scale - MARGIN;
            }
            double[] lattice = fit.count >= 6
                ? Homography.fit(src, dst, fit.count)
                : Homography.fitAffine(src, dst, fit.count);
            if (lattice == null) return null;
            double rms = 0;
            for (int k = 0; k < fit.count; k++) {
                Homography.apply(lattice, src[k * 2], src[k * 2 + 1], point);
                rms += sq(point[0] - dst[k * 2]) + sq(point[1] - dst[k * 2 + 1]);
            }
            rms = Math.sqrt(rms / fit.count);
            if (rms > MAX_RMS) return null;

            double[] corners = new double[8];
            double[][] unit = {{0, 0}, {3, 0}, {3, 3}, {0, 3}};
            for (int c = 0; c < 4; c++) {
                Homography.apply(lattice, unit[c][0], unit[c][1], point);
                Homography.apply(toImage, point[0], point[1], point);
                corners[c * 2] = point[0];
                corners[c * 2 + 1] = point[1];
            }
            double[] ordered = orderCorners(corners);
            double shortest = Double.MAX_VALUE, longest = 0, total = 0;
            for (int c = 0; c < 4; c++) {
                int n = (c + 1) % 4;
                double side = Math.hypot(ordered[n * 2] - ordered[c * 2],
                    ordered[n * 2 + 1] - ordered[c * 2 + 1]);
                shortest = Math.min(shortest, side);
                longest = Math.max(longest, side);
                total += side;
            }
            if (!(shortest >= 9) || longest / shortest > 2.6) return null;
            result = new Result(ordered, total / 12.0, fit.count, fit.support);
            if (previous != null && iteration == ITERATIONS - 1
                    && maxCentreShift(previous, ordered) / result.pitch > MAX_SETTLE) {
                return null;
            }
            previous = ordered;
            current = ordered;
        }
        return result;
    }

    // ------------------------------------------------------------------------- tile and blobs

    /** Rectifies lattice units [-MARGIN, 3+MARGIN]^2 into the tile, bilinear, black outside. */
    private void renderTile(byte[] rgba, int width, int height, double[] toImage) {
        double inv = 1.0 / scale;
        for (int ty = 0; ty < TILE; ty++) {
            double ly = ty * inv - MARGIN;
            for (int tx = 0; tx < TILE; tx++) {
                double lx = tx * inv - MARGIN;
                double w = toImage[6] * lx + toImage[7] * ly + toImage[8];
                double sx = (toImage[0] * lx + toImage[1] * ly + toImage[2]) / w;
                double sy = (toImage[3] * lx + toImage[4] * ly + toImage[5]) / w;
                int index = ty * TILE + tx;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                if (x0 < 0 || y0 < 0 || x0 >= width - 1 || y0 >= height - 1) {
                    value[index] = 0;
                    continue;
                }
                double fx = sx - x0, fy = sy - y0;
                int p = (y0 * width + x0) * 4;
                int q = p + width * 4;
                int max = 0;
                for (int ch = 0; ch < 3; ch++) {
                    double top = (rgba[p + ch] & 0xFF) * (1 - fx) + (rgba[p + 4 + ch] & 0xFF) * fx;
                    double bottom = (rgba[q + ch] & 0xFF) * (1 - fx) + (rgba[q + 4 + ch] & 0xFF) * fx;
                    int v = (int) (top * (1 - fy) + bottom * fy + 0.5);
                    if (v > max) max = v;
                }
                value[index] = max;
            }
        }
    }

    private void findBlobs() {
        int lo = (int) Math.round(MARGIN * scale), hi = (int) Math.round((MARGIN + 3) * scale);
        java.util.Arrays.fill(histogram, 0);
        for (int y = lo; y < hi; y++) {
            for (int x = lo; x < hi; x++) histogram[value[y * TILE + x]]++;
        }
        int threshold = clamp(otsu(histogram), 28, 170);
        for (int i = 0; i < value.length; i++) mask[i] = (byte) (value[i] > threshold ? 1 : 0);
        int k = Math.max(1, (int) Math.round(scale * 0.06));
        erode(k);

        java.util.Arrays.fill(labels, 0);
        double expected = sq(0.80 * scale);
        blobCount = 0;
        int next = 1;
        for (int start = 0; start < labels.length; start++) {
            if (eroded[start] == 0 || labels[start] != 0) continue;
            int label = next++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            labels[start] = label;
            long area = 0;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int minX = TILE, minY = TILE, maxX = -1, maxY = -1;
            while (head < tail) {
                int at = queue[head++];
                int x = at % TILE, y = at / TILE;
                area++;
                sx += x; sy += y; sxx += (double) x * x; syy += (double) y * y; sxy += (double) x * y;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x > 0) tail = visit(at - 1, label, tail);
                if (x < TILE - 1) tail = visit(at + 1, label, tail);
                if (y > 0) tail = visit(at - TILE, label, tail);
                if (y < TILE - 1) tail = visit(at + TILE, label, tail);
            }
            if (area < 0.20 * expected || area > 2.2 * expected) continue;
            // Cut by the tile edge: its centroid is biased toward the inside.
            if (minX == 0 || minY == 0 || maxX >= TILE - 1 || maxY >= TILE - 1) continue;
            double mx = sx / area, my = sy / area;
            double cxx = sxx / area - mx * mx, cyy = syy / area - my * my, cxy = sxy / area - mx * my;
            double trace = cxx + cyy, det = cxx * cyy - cxy * cxy;
            double disc = Math.sqrt(Math.max(trace * trace / 4 - det, 0));
            double l1 = trace / 2 + disc, l2 = Math.max(trace / 2 - disc, 1e-6);
            double aspect = Math.sqrt(l1 / l2);
            double fill = area / (12 * Math.sqrt(l1 * l2));
            if (aspect > 2.2 || fill < 0.58) continue;
            if (blobCount == MAX_BLOBS) continue;
            Blob blob = blobs[blobCount++];
            blob.x = mx;
            blob.y = my;
            blob.area = area;
            blob.aspect = aspect;
        }
    }

    private int visit(int at, int label, int tail) {
        if (eroded[at] == 0 || labels[at] != 0) return tail;
        labels[at] = label;
        queue[tail] = at;
        return tail + 1;
    }

    /**
     * Square erosion of radius k, done as two sliding-window passes. Pixels beyond the tile count
     * as set, so the border does not erode (OpenCV's default for erosion).
     */
    private void erode(int k) {
        for (int y = 0; y < TILE; y++) {
            int base = y * TILE, zeros = 0;
            for (int x = 0; x < Math.min(k, TILE); x++) if (mask[base + x] == 0) zeros++;
            for (int x = 0; x < TILE; x++) {
                int enter = x + k, leave = x - k - 1;
                if (enter < TILE && mask[base + enter] == 0) zeros++;
                if (leave >= 0 && mask[base + leave] == 0) zeros--;
                horizontal[base + x] = (byte) (zeros == 0 ? 1 : 0);
            }
        }
        for (int x = 0; x < TILE; x++) {
            int zeros = 0;
            for (int y = 0; y < Math.min(k, TILE); y++) if (horizontal[y * TILE + x] == 0) zeros++;
            for (int y = 0; y < TILE; y++) {
                int enter = y + k, leave = y - k - 1;
                if (enter < TILE && horizontal[enter * TILE + x] == 0) zeros++;
                if (leave >= 0 && horizontal[leave * TILE + x] == 0) zeros--;
                eroded[y * TILE + x] = (byte) (zeros == 0 ? 1 : 0);
            }
        }
    }

    static int otsu(int[] histogram) {
        long total = 0, weighted = 0;
        for (int v = 0; v < 256; v++) { total += histogram[v]; weighted += (long) v * histogram[v]; }
        if (total == 0) return 128;
        double sumB = 0, wB = 0, best = -1;
        int threshold = 128;
        for (int v = 0; v < 256; v++) {
            wB += histogram[v];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += (double) v * histogram[v];
            double mB = sumB / wB, mF = (weighted - sumB) / wF;
            double between = wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; threshold = v; }
        }
        return threshold;
    }

    // ------------------------------------------------------------------------- lattice

    private static final class Blob {
        double x, y, area, aspect;
    }

    private static final class Fit {
        int count;
        final int[] blob = new int[9], cellI = new int[9], cellJ = new int[9];
        double support;
    }

    /** How much one blob counts as a sticker of a lattice, 0..1. */
    static double blobWeight(double aspect, double areaRatio, double residualRatio, boolean strict) {
        double aLo = strict ? 1.30 : 1.55, aHi = strict ? 1.75 : 2.2;
        double wAspect = clamp01((aHi - aspect) / (aHi - aLo));
        double dev = Math.abs(Math.log(areaRatio));
        double dLo = strict ? 0.30 : 0.45, dHi = strict ? 0.75 : 1.0;
        double wArea = clamp01((dHi - dev) / (dHi - dLo));
        return wAspect * wArea * (1 - residualRatio * residualRatio);
    }

    /**
     * Finds the 3x3 window of blobs that best looks like one face.
     *
     * <p>A hypothesis is a blob plus two lattice steps to neighbours one or two pitches away (two,
     * so a finger over the middle row does not break the lattice) at roughly right angles. Every
     * 3x3 window of its 7x7 extension is scored by the summed weight of the blobs it explains,
     * minus small penalties for drifting from the coarse quad's centre and pitch.
     */
    private Fit fitLattice(boolean strict) {
        int n = blobCount;
        if (n < 4) return null;
        double pitch = scale;
        double prior = (1.5 + MARGIN) * scale;
        double[] stepX = new double[2 * n], stepY = new double[2 * n];
        int[] cellOf = new int[49];
        double[] residOf = new double[49];
        double[] gi = new double[n], gj = new double[n], resid = new double[n];
        double[] memberArea = new double[9];
        int[] members = new int[9], memberCell = new int[9];
        double[] weights = new double[9];

        double bestScore = -Double.MAX_VALUE;
        Fit best = null;
        for (int c = 0; c < n; c++) {
            Blob origin = blobs[c];
            int steps = 0;
            for (int i = 0; i < n; i++) {
                if (i == c) continue;
                double dx = blobs[i].x - origin.x, dy = blobs[i].y - origin.y;
                double dist = Math.hypot(dx, dy);
                if (dist > 0.6 * pitch && dist < 1.6 * pitch) {
                    stepX[steps] = dx; stepY[steps] = dy; steps++;
                } else if (dist >= 1.6 * pitch && dist < 3.1 * pitch) {
                    stepX[steps] = dx / 2; stepY[steps] = dy / 2; steps++;
                }
            }
            for (int a = 0; a < steps; a++) {
                for (int b = a + 1; b < steps; b++) {
                    double ux = stepX[a], uy = stepY[a], vx = stepX[b], vy = stepY[b];
                    double lu = Math.hypot(ux, uy), lv = Math.hypot(vx, vy);
                    if (lu <= 0.6 * pitch || lu >= 1.6 * pitch || lv <= 0.6 * pitch || lv >= 1.6 * pitch) continue;
                    double cos = Math.abs(ux * vx + uy * vy) / (lu * lv);
                    double ratio = lu / lv;
                    if (cos > 0.45 || ratio <= 0.6 || ratio >= 1.67) continue;
                    double det = ux * vy - uy * vx;
                    if (Math.abs(det) < 1e-9) continue;
                    double tol = 0.3 * Math.min(lu, lv);
                    java.util.Arrays.fill(cellOf, -1);
                    for (int i = 0; i < n; i++) {
                        double dx = blobs[i].x - origin.x, dy = blobs[i].y - origin.y;
                        double ci = (dx * vy - dy * vx) / det, cj = (ux * dy - uy * dx) / det;
                        double ri = Math.rint(ci), rj = Math.rint(cj);
                        double ex = (ci - ri) * ux + (cj - rj) * vx, ey = (ci - ri) * uy + (cj - rj) * vy;
                        double r = Math.hypot(ex, ey);
                        gi[i] = ri; gj[i] = rj; resid[i] = r;
                        if (Math.abs(ri) > 3 || Math.abs(rj) > 3 || r > tol) continue;
                        int cell = (int) ((ri + 3) * 7 + (rj + 3));
                        if (cellOf[cell] < 0 || residOf[cell] > r) { cellOf[cell] = i; residOf[cell] = r; }
                    }
                    double latticePitch = Math.sqrt(lu * lv);
                    double scalePenalty = Math.abs(Math.log(latticePitch / pitch));
                    for (int oi = -2; oi <= 2; oi++) {
                        for (int oj = -2; oj <= 2; oj++) {
                            int m = 0;
                            for (int di = -1; di <= 1; di++) {
                                for (int dj = -1; dj <= 1; dj++) {
                                    int cell = (oi + di + 3) * 7 + (oj + dj + 3);
                                    if (cellOf[cell] < 0) continue;
                                    members[m] = cellOf[cell];
                                    memberCell[m] = (di + 1) * 3 + (dj + 1);
                                    memberArea[m] = blobs[cellOf[cell]].area;
                                    m++;
                                }
                            }
                            if (m < 4 || m < bestScore - 0.5) continue;
                            double median = median(memberArea, m);
                            double support = 0;
                            for (int k = 0; k < m; k++) {
                                Blob blob = blobs[members[k]];
                                weights[k] = blobWeight(blob.aspect, blob.area / median,
                                    resid[members[k]] / tol, strict);
                                support += weights[k];
                            }
                            double cx = origin.x + oi * ux + oj * vx, cy = origin.y + oi * uy + oj * vy;
                            double offset = Math.hypot(cx - prior, cy - prior) / pitch;
                            double score = support - W_PRIOR * offset - W_SCALE * scalePenalty;
                            if (score > bestScore) {
                                bestScore = score;
                                if (best == null) best = new Fit();
                                best.count = 0;
                                best.support = support;
                                for (int k = 0; k < m; k++) {
                                    if (weights[k] <= 0.25) continue;
                                    best.blob[best.count] = members[k];
                                    best.cellI[best.count] = memberCell[k] / 3 - 1;
                                    best.cellJ[best.count] = memberCell[k] % 3 - 1;
                                    best.count++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------- helpers

    /** Clockwise (image coordinates) from the top-left-most corner, like FaceSampler.orderCorners. */
    static double[] orderCorners(double[] quad) {
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += quad[i * 2]; cy += quad[i * 2 + 1]; }
        cx /= 4; cy /= 4;
        Integer[] index = {0, 1, 2, 3};
        final double fcx = cx, fcy = cy;
        java.util.Arrays.sort(index, (a, b) -> Double.compare(
            Math.atan2(quad[a * 2 + 1] - fcy, quad[a * 2] - fcx),
            Math.atan2(quad[b * 2 + 1] - fcy, quad[b * 2] - fcx)));
        int start = 0;
        double minimum = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double sum = quad[index[i] * 2] + quad[index[i] * 2 + 1];
            if (sum < minimum) { minimum = sum; start = i; }
        }
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            int from = index[(start + i) % 4];
            out[i * 2] = quad[from * 2];
            out[i * 2 + 1] = quad[from * 2 + 1];
        }
        return out;
    }

    /**
     * Mean distance between corresponding sticker centres of two quads, in pitches of {@code a},
     * at whichever cyclic corner correspondence fits best — the two may start from different
     * corners when the face sits near 45 degrees.
     */
    static double meanCentreShift(double[] a, double[] b) {
        double[] ha = Homography.fromSquare(3, a);
        if (ha == null) return Double.MAX_VALUE;
        double pitch = 0;
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            pitch += Math.hypot(a[n * 2] - a[i * 2], a[n * 2 + 1] - a[i * 2 + 1]);
        }
        pitch /= 12;
        double[] pa = new double[2], pb = new double[2];
        double best = Double.MAX_VALUE;
        for (int shift = 0; shift < 4; shift++) {
            double[] rolled = new double[8];
            for (int i = 0; i < 4; i++) {
                rolled[i * 2] = b[((i + shift) % 4) * 2];
                rolled[i * 2 + 1] = b[((i + shift) % 4) * 2 + 1];
            }
            double[] hb = Homography.fromSquare(3, rolled);
            if (hb == null) continue;
            double total = 0;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    Homography.apply(ha, c + 0.5, r + 0.5, pa);
                    Homography.apply(hb, c + 0.5, r + 0.5, pb);
                    total += Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                }
            }
            best = Math.min(best, total / 9);
        }
        return best / Math.max(pitch, 1e-9);
    }

    /**
     * Whether a refined lattice and the detector's quad describe the same face. On the demo
     * video the refiner alone slid onto a neighbouring row in 13% of frames and the detector
     * alone straddled edges too; requiring the two to agree within this many pitches keeps what
     * each gets right.
     */
    static final double AGREEMENT = 0.4;

    static boolean agrees(double[] lattice, double[] coarse) {
        return meanCentreShift(lattice, coarse) < AGREEMENT;
    }

    /** Largest distance between corresponding sticker centres of two quads, in pixels. */
    static double maxCentreShift(double[] a, double[] b) {
        double[] ha = Homography.fromSquare(3, a), hb = Homography.fromSquare(3, b);
        if (ha == null || hb == null) return Double.MAX_VALUE;
        double[] pa = new double[2], pb = new double[2];
        double worst = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                Homography.apply(ha, c + 0.5, r + 0.5, pa);
                Homography.apply(hb, c + 0.5, r + 0.5, pb);
                worst = Math.max(worst, Math.hypot(pa[0] - pb[0], pa[1] - pb[1]));
            }
        }
        return worst;
    }

    private static double median(double[] values, int n) {
        double[] copy = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(copy);
        return n % 2 == 1 ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2;
    }

    private static double sq(double v) { return v * v; }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
