import Foundation

// Port of com.mofang.cubear.Homography.java
// 平面到平面的射影映射,行主序 3×3 矩阵。
//
// 小巧、省分配、不依赖 OpenCV,因此面精化与采样背后的几何可以在单元测试里验证。

enum Homography {
    /// 把四个 src 点映射到四个 dst 点({x0,y0,..,x3,y3})。
    static func fromQuads(_ src: [Double], _ dst: [Double]) -> [Double]? {
        var a = [[Double]](repeating: [Double](repeating: 0, count: 9), count: 8)
        for i in 0..<4 {
            let x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1]
            a[i * 2] = [x, y, 1, 0, 0, 0, -u * x, -u * y, u]
            a[i * 2 + 1] = [0, 0, 0, x, y, 1, -v * x, -v * y, v]
        }
        guard let h = solve(&a) else { return nil }
        return [h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1]
    }

    /// 正方形 [0,side]²(从原点顺时针的四个角)映射到 quad。
    static func fromSquare(_ side: Double, _ quad: [Double]) -> [Double]? {
        fromQuads([0, 0, side, 0, side, side, 0, side], quad)
    }

    /// 经 Hartley 归一化的最小二乘单应性,n ≥ 4 组对应点,保证像素尺度下法方程良态。
    static func fit(_ src: [Double], _ dst: [Double], _ n: Int) -> [Double]? {
        if n < 4 { return nil }
        if n == 4 { return fromQuads(src, dst) }
        let ts = normaliser(src, n), td = normaliser(dst, n)
        var ata = [[Double]](repeating: [Double](repeating: 0, count: 9), count: 8)
        var row = [Double](repeating: 0, count: 9)
        for i in 0..<n {
            let x = ts[0] * src[i * 2] + ts[2], y = ts[0] * src[i * 2 + 1] + ts[3]
            let u = td[0] * dst[i * 2] + td[2], v = td[0] * dst[i * 2 + 1] + td[3]
            for k in 0..<2 {
                if k == 0 {
                    row = [x, y, 1, 0, 0, 0, -u * x, -u * y, u]
                } else {
                    row = [0, 0, 0, x, y, 1, -v * x, -v * y, v]
                }
                for r in 0..<8 {
                    for c in 0..<9 { ata[r][c] += row[r] * row[c] }
                }
            }
        }
        guard let h = solve(&ata) else { return nil }
        let hn = [h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1]
        // 撤销归一化:H = Td^-1 * Hn * Ts
        let tsm: [Double] = [ts[0], 0, ts[2], 0, ts[0], ts[3], 0, 0, 1]
        let tdInv: [Double] = [1 / td[0], 0, -td[2] / td[0], 0, 1 / td[0], -td[3] / td[0], 0, 0, 1]
        return normalise(multiply(tdInv, multiply(hn, tsm)))
    }

    /// n ≥ 3 组对应点的最小二乘仿射映射,以单应性表示。
    static func fitAffine(_ src: [Double], _ dst: [Double], _ n: Int) -> [Double]? {
        if n < 3 { return nil }
        var a = [[Double]](repeating: [Double](repeating: 0, count: 7), count: 6)
        for i in 0..<n {
            let x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1]
            let rx: [Double] = [x, y, 1, 0, 0, 0, u]
            let ry: [Double] = [0, 0, 0, x, y, 1, v]
            for r in 0..<6 {
                for c in 0..<7 { a[r][c] += rx[r] * rx[c] + ry[r] * ry[c] }
            }
        }
        guard let p = solve(&a) else { return nil }
        return [p[0], p[1], p[2], p[3], p[4], p[5], 0, 0, 1]
    }

    static func apply(_ h: [Double], _ x: Double, _ y: Double, _ out: inout [Double]) {
        let w = h[6] * x + h[7] * y + h[8]
        out[0] = (h[0] * x + h[1] * y + h[2]) / w
        out[1] = (h[3] * x + h[4] * y + h[5]) / w
    }

    static func multiply(_ a: [Double], _ b: [Double]) -> [Double] {
        var out = [Double](repeating: 0, count: 9)
        for r in 0..<3 {
            for c in 0..<3 {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
        }
        return out
    }

    static func invert(_ m: [Double]) -> [Double]? {
        let a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8]
        let co0 = e * i - f * h, co1 = -(d * i - f * g), co2 = d * h - e * g
        let det = a * co0 + b * co1 + c * co2
        if abs(det) < 1e-12 { return nil }
        let inv = 1.0 / det
        return [
            co0 * inv, -(b * i - c * h) * inv, (b * f - c * e) * inv,
            co1 * inv, (a * i - c * g) * inv, -(a * f - c * d) * inv,
            co2 * inv, -(a * h - b * g) * inv, (a * e - b * d) * inv,
        ]
    }

    private static func normalise(_ h: [Double]) -> [Double] {
        if abs(h[8]) < 1e-15 { return h }
        let s = 1.0 / h[8]
        return h.map { $0 * s }
    }

    /// {scale, 未用, tx, ty}:把质心移到原点,平均距离变为 √2。
    private static func normaliser(_ pts: [Double], _ n: Int) -> [Double] {
        var mx = 0.0, my = 0.0
        for i in 0..<n { mx += pts[i * 2]; my += pts[i * 2 + 1] }
        mx /= Double(n); my /= Double(n)
        var spread = 0.0
        for i in 0..<n { spread += hypot(pts[i * 2] - mx, pts[i * 2 + 1] - my) }
        spread /= Double(n)
        let s = spread < 1e-12 ? 1 : sqrt(2) / spread
        return [s, 0, -s * mx, -s * my]
    }

    /// 增广 n×(n+1) 系统上带部分主元的高斯消元。参数会就地修改。
    static func solve(_ a: inout [[Double]]) -> [Double]? {
        let n = a.count
        for col in 0..<n {
            var pivot = col
            for r in (col + 1)..<n where abs(a[r][col]) > abs(a[pivot][col]) { pivot = r }
            if abs(a[pivot][col]) < 1e-12 { return nil }
            a.swapAt(col, pivot)
            for r in (col + 1)..<n {
                let factor = a[r][col] / a[col][col]
                if factor == 0 { continue }
                for c in col...n { a[r][c] -= factor * a[col][c] }
            }
        }
        var x = [Double](repeating: 0, count: n)
        for r in stride(from: n - 1, through: 0, by: -1) {
            var sum = a[r][n]
            for c in (r + 1)..<n { sum -= a[r][c] * x[c] }
            x[r] = sum / a[r][r]
        }
        return x
    }
}
