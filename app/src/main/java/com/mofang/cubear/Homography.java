package com.mofang.cubear;

/**
 * Plane-to-plane projective maps as row-major 3x3 matrices.
 *
 * <p>Small, allocation-light, and free of OpenCV so the geometry behind face refinement and
 * sampling can be unit-tested on the JVM.
 */
final class Homography {
    private Homography() {}

    /** Maps the four {@code src} points onto the four {@code dst} points ({x0,y0,..,x3,y3}). */
    static double[] fromQuads(double[] src, double[] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1];
            a[i * 2] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[i * 2 + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        double[] h = solve(a);
        return h == null ? null : new double[]{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1};
    }

    /** The square [0,side]^2, corners clockwise from the origin, mapped onto {@code quad}. */
    static double[] fromSquare(double side, double[] quad) {
        return fromQuads(new double[]{0, 0, side, 0, side, side, 0, side}, quad);
    }

    /**
     * Least-squares homography through {@code n >= 4} correspondences, using Hartley
     * normalisation so the normal equations stay well conditioned at pixel scale.
     */
    static double[] fit(double[] src, double[] dst, int n) {
        if (n < 4) return null;
        if (n == 4) return fromQuads(src, dst);
        double[] ts = normaliser(src, n), td = normaliser(dst, n);
        double[][] ata = new double[8][9];
        double[] row = new double[9];
        for (int i = 0; i < n; i++) {
            double x = ts[0] * src[i * 2] + ts[2], y = ts[0] * src[i * 2 + 1] + ts[3];
            double u = td[0] * dst[i * 2] + td[2], v = td[0] * dst[i * 2 + 1] + td[3];
            for (int k = 0; k < 2; k++) {
                if (k == 0) {
                    row[0] = x; row[1] = y; row[2] = 1; row[3] = 0; row[4] = 0; row[5] = 0;
                    row[6] = -u * x; row[7] = -u * y; row[8] = u;
                } else {
                    row[0] = 0; row[1] = 0; row[2] = 0; row[3] = x; row[4] = y; row[5] = 1;
                    row[6] = -v * x; row[7] = -v * y; row[8] = v;
                }
                for (int r = 0; r < 8; r++) {
                    for (int c = 0; c < 9; c++) ata[r][c] += row[r] * row[c];
                }
            }
        }
        double[] h = solve(ata);
        if (h == null) return null;
        double[] hn = {h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1};
        // Undo the normalisation: H = Td^-1 * Hn * Ts.
        double[] tsm = {ts[0], 0, ts[2], 0, ts[0], ts[3], 0, 0, 1};
        double[] tdInv = {1 / td[0], 0, -td[2] / td[0], 0, 1 / td[0], -td[3] / td[0], 0, 0, 1};
        return normalise(multiply(tdInv, multiply(hn, tsm)));
    }

    /** Least-squares affine map through {@code n >= 3} correspondences, as a homography. */
    static double[] fitAffine(double[] src, double[] dst, int n) {
        if (n < 3) return null;
        double[][] a = new double[6][7];
        for (int i = 0; i < n; i++) {
            double x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1];
            double[] rx = {x, y, 1, 0, 0, 0, u};
            double[] ry = {0, 0, 0, x, y, 1, v};
            for (int r = 0; r < 6; r++) {
                for (int c = 0; c < 7; c++) a[r][c] += rx[r] * rx[c] + ry[r] * ry[c];
            }
        }
        double[] p = solve(a);
        return p == null ? null : new double[]{p[0], p[1], p[2], p[3], p[4], p[5], 0, 0, 1};
    }

    static void apply(double[] h, double x, double y, double[] out) {
        double w = h[6] * x + h[7] * y + h[8];
        out[0] = (h[0] * x + h[1] * y + h[2]) / w;
        out[1] = (h[3] * x + h[4] * y + h[5]) / w;
    }

    static double[] multiply(double[] a, double[] b) {
        double[] out = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double co0 = e * i - f * h, co1 = -(d * i - f * g), co2 = d * h - e * g;
        double det = a * co0 + b * co1 + c * co2;
        if (Math.abs(det) < 1e-12) return null;
        double inv = 1.0 / det;
        return new double[]{
            co0 * inv, -(b * i - c * h) * inv, (b * f - c * e) * inv,
            co1 * inv, (a * i - c * g) * inv, -(a * f - c * d) * inv,
            co2 * inv, -(a * h - b * g) * inv, (a * e - b * d) * inv};
    }

    private static double[] normalise(double[] h) {
        if (Math.abs(h[8]) < 1e-15) return h;
        double s = 1.0 / h[8];
        for (int i = 0; i < 9; i++) h[i] *= s;
        return h;
    }

    /** {scale, unused, tx, ty} moving the centroid to the origin at mean distance sqrt(2). */
    private static double[] normaliser(double[] pts, int n) {
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += pts[i * 2]; my += pts[i * 2 + 1]; }
        mx /= n; my /= n;
        double spread = 0;
        for (int i = 0; i < n; i++) spread += Math.hypot(pts[i * 2] - mx, pts[i * 2 + 1] - my);
        spread /= n;
        double s = spread < 1e-12 ? 1 : Math.sqrt(2) / spread;
        return new double[]{s, 0, -s * mx, -s * my};
    }

    /** Gaussian elimination with partial pivoting on an augmented n x (n+1) system. */
    static double[] solve(double[][] a) {
        int n = a.length;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            if (Math.abs(a[pivot][col]) < 1e-12) return null;
            double[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;
            for (int r = col + 1; r < n; r++) {
                double factor = a[r][col] / a[col][col];
                if (factor == 0) continue;
                for (int c = col; c <= n; c++) a[r][c] -= factor * a[col][c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = a[r][n];
            for (int c = r + 1; c < n; c++) sum -= a[r][c] * x[c];
            x[r] = sum / a[r][r];
        }
        return x;
    }
}
