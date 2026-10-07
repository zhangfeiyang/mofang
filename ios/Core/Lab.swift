import Foundation

// Port of com.mofang.cubear.Lab.java
// sRGB 到 CIELAB 的转换与感知距离。
//
// 贴纸判定在 Lab 空间进行,因为亮度承载了色相独力无法区分的红/橙之别:实测真机帧上,
// 红与橙的色相只差 2-4 度,而 L 相差约 30 个单位。

enum Lab {
    /// 把 sRGB 三元组转换到 D65 白点下的 CIELAB。
    static func fromRgb(_ red: Int, _ green: Int, _ blue: Int) -> [Float] {
        let r = linearize(Float(red) / 255)
        let g = linearize(Float(green) / 255)
        let b = linearize(Float(blue) / 255)

        let x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        let y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
        let z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

        let fx = pivot(x), fy = pivot(y), fz = pivot(z)
        return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)]
    }

    private static func linearize(_ channel: Float) -> Float {
        channel <= 0.04045
            ? channel / 12.92
            : powf((channel + 0.055) / 1.055, 2.4)
    }

    private static func pivot(_ value: Float) -> Float {
        value > 0.008856 ? cbrtf(value) : (7.787 * value) + 16 / 116
    }

    /// CIE76 距离的平方。取平方是因为分配求解器只比较代价大小。
    static func distanceSquared(_ a: [Float], _ b: [Float]) -> Float {
        let dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2]
        return dl * dl + da * da + db * db
    }

    static func distance(_ a: [Float], _ b: [Float]) -> Float {
        sqrtf(distanceSquared(a, b))
    }

    /// 色度,即到中性轴的距离。低色度意味着白色或发白的读数。
    static func chroma(_ lab: [Float]) -> Float {
        sqrtf(lab[1] * lab[1] + lab[2] * lab[2])
    }

    /// 判断中心读数像贴纸而非黑色塑料或发白的边缘。白色高 L 低色度;其余五色高色度。
    /// 暗灰中心通常是四边形落在魔方机身上的特征。
    static func isStickerCenter(_ lab: [Float]?) -> Bool {
        guard let lab = lab else { return false }
        let c = chroma(lab)
        // 78 是按照明良好的白色假设的;真机上普通室内光下的白色在 L 60-75,会被整体拒掉。
        // 魔方深色机身 L<45,所以取 68 保留灰塑过滤,同时放行阴影下的白色。
        if lab[0] >= 68 && c < 22 { return true }
        return c >= 20
    }
}
