package com.mofang.cubear;

import java.util.Locale;

/** Six sticker colors and their standard URFDLB center mapping. */
public enum CubeColor {
    WHITE('U', 0xFFF4F4F0, "白"),
    RED('R', 0xFFE94335, "红"),
    GREEN('F', 0xFF57C84D, "绿"),
    YELLOW('D', 0xFFF5E629, "黄"),
    ORANGE('L', 0xFFFF9F2D, "橙"),
    BLUE('B', 0xFF3974D9, "蓝"),
    UNKNOWN('?', 0xFF777777, "未知");

    public final char face;
    public final int argb;
    public final String chinese;

    CubeColor(char face, int argb, String chinese) {
        this.face = face;
        this.argb = argb;
        this.chinese = chinese;
    }

    public static CubeColor fromFace(char face) {
        for (CubeColor color : values()) if (color.face == face) return color;
        return UNKNOWN;
    }

    /** Lighting-tolerant HSV classifier tuned for saturated cube stickers. */
    public static CubeColor classify(int red, int green, int blue) {
        float[] hsv = rgbToHsv(red, green, blue);
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (v < 0.20f) return UNKNOWN;
        if (s < 0.22f && v > 0.48f) return WHITE;
        if (s < 0.30f) return UNKNOWN;
        if (h < 12f || h >= 345f) return RED;
        if (h < 42f) return ORANGE;
        if (h < 78f) return YELLOW;
        if (h < 172f) return GREEN;
        if (h < 275f) return BLUE;
        return RED;
    }

    static float[] rgbToHsv(int red, int green, int blue) {
        float r = red / 255f, g = green / 255f, b = blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta == 0) hue = 0;
        else if (max == r) hue = 60f * (((g - b) / delta) % 6f);
        else if (max == g) hue = 60f * (((b - r) / delta) + 2f);
        else hue = 60f * (((r - g) / delta) + 4f);
        if (hue < 0) hue += 360f;
        return new float[]{hue, max == 0 ? 0 : delta / max, max};
    }

    @Override public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
