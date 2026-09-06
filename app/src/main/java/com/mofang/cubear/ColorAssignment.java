package com.mofang.cubear;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides all 54 sticker colours at once instead of thresholding each patch on its own.
 *
 * <p>Per-patch thresholding fails on real frames because the gap between red and orange within a
 * single face (2-4 degrees of hue) is smaller than the drift of one physical colour across frames.
 * Here the six centre stickers act as that scan's colour prototypes, and a minimum-cost assignment
 * distributes the remaining stickers under the cube's own constraint of exactly nine per colour.
 */
public final class ColorAssignment {
    /** Cost added for pairing a sticker with a prototype it is forbidden to take. */
    private static final double FORBIDDEN = 1e9;

    private ColorAssignment() {}

    public static final class Result {
        /** Six faces of nine resolved colours, in the order the faces were supplied. */
        public final CubeColor[][] colors;
        /** Lab distance from each sticker to the prototype it was assigned, same layout. */
        public final float[][] residuals;
        /** Prototype index each face's centre defined, i.e. which colour that face carries. */
        public final CubeColor[] faceColors;
        /** The six centre readings that acted as prototypes, aligned with {@link #faceColors}. */
        public final float[][] prototypes;

        Result(CubeColor[][] colors, float[][] residuals, CubeColor[] faceColors,
               float[][] prototypes) {
            this.colors = colors;
            this.residuals = residuals;
            this.faceColors = faceColors;
            this.prototypes = prototypes;
        }

        /** Sticker positions ordered by how poorly they matched, worst first, as face*9+index. */
        public List<Integer> byDescendingResidual() {
            List<Integer> order = new ArrayList<>(54);
            for (int i = 0; i < 54; i++) order.add(i);
            order.sort(Comparator.comparingDouble((Integer i) -> -residuals[i / 9][i % 9]));
            return order;
        }
    }

    /**
     * Resolves six scanned faces into a consistent colouring.
     *
     * @param faces exactly six faces carrying Lab readings
     * @param nameOffset rotates the prototype-to-colour naming, letting callers retry alternative
     *     namings when the primary one fails the cube's legality check; 0 is the best-cost naming
     * @return the resolved colouring, or null if the faces lack Lab data
     */
    public static Result assign(FaceSample[] faces, int nameOffset) {
        if (faces.length != 6) return null;
        for (FaceSample face : faces) if (face == null || face.lab == null) return null;

        float[][] prototypes = new float[6][];
        for (int face = 0; face < 6; face++) prototypes[face] = faces[face].lab[4];

        CubeColor[] names = namePrototypes(prototypes, nameOffset);
        if (names == null) return null;

        // 54 stickers against 54 slots: nine slots per prototype enforce nine stickers per colour.
        double[][] cost = new double[54][54];
        for (int sticker = 0; sticker < 54; sticker++) {
            int face = sticker / 9, cell = sticker % 9;
            float[] lab = faces[face].lab[cell];
            boolean isCenter = cell == 4;
            // An occluded or blown-out patch carries no colour evidence, so it costs the same
            // against every prototype and the nine-per-colour constraint decides it instead.
            boolean wildcard = !isCenter && !faces[face].reliable[cell];
            for (int slot = 0; slot < 54; slot++) {
                int prototype = slot / 9;
                if (isCenter && prototype != face) {
                    cost[sticker][slot] = FORBIDDEN;
                } else if (isCenter || wildcard) {
                    cost[sticker][slot] = 0;
                } else {
                    cost[sticker][slot] = Lab.distanceSquared(lab, prototypes[prototype]);
                }
            }
        }

        int[] assignment = Hungarian.solve(cost);
        CubeColor[][] colors = new CubeColor[6][9];
        float[][] residuals = new float[6][9];
        for (int sticker = 0; sticker < 54; sticker++) {
            int face = sticker / 9, cell = sticker % 9;
            int prototype = assignment[sticker] / 9;
            colors[face][cell] = names[prototype];
            residuals[face][cell] = Lab.distance(faces[face].lab[cell], prototypes[prototype]);
        }

        CubeColor[] faceColors = new CubeColor[6];
        for (int face = 0; face < 6; face++) faceColors[face] = names[face];
        return new Result(colors, residuals, faceColors, prototypes);
    }

    /**
     * Gives each prototype a distinct colour name. Solving this as a one-to-one assignment rather
     * than nearest-reference matters: red and orange are close to each other but only one of them
     * can take each name, so the darker prototype is forced onto red.
     *
     * <p>Works for five or six prototypes. Five leaves one canonical colour unused — that is the
     * missing face — and retries may reassign a prototype onto that leftover when the first
     * naming was a toss-up.
     */
    static CubeColor[] namePrototypes(float[][] prototypes, int offset) {
        if (prototypes == null || prototypes.length == 0 || prototypes.length > 6) return null;
        CubeColor[] palette = {CubeColor.WHITE, CubeColor.RED, CubeColor.GREEN,
            CubeColor.YELLOW, CubeColor.ORANGE, CubeColor.BLUE};
        int n = prototypes.length;
        float[][] references = new float[palette.length][];
        for (int i = 0; i < palette.length; i++) {
            int argb = palette[i].argb;
            references[i] = Lab.fromRgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        }

        double[][] cost = new double[n][palette.length];
        for (int prototype = 0; prototype < n; prototype++) {
            for (int reference = 0; reference < palette.length; reference++) {
                cost[prototype][reference] =
                    Lab.distanceSquared(prototypes[prototype], references[reference]);
            }
        }

        int[] naming = Hungarian.solve(cost);
        CubeColor[] names = new CubeColor[n];
        for (int prototype = 0; prototype < n; prototype++) {
            names[prototype] = palette[naming[prototype]];
        }
        if (offset == 0) return names;

        // Retry namings try the cheapest alternative first: swapping two assigned names, or —
        // when a colour is still unused — giving that leftover name to one prototype.
        List<int[]> alternatives = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) alternatives.add(new int[]{a, b, 0});
        }
        boolean[] used = new boolean[palette.length];
        for (int column : naming) used[column] = true;
        for (int prototype = 0; prototype < n; prototype++) {
            for (int unused = 0; unused < palette.length; unused++) {
                if (!used[unused]) alternatives.add(new int[]{prototype, unused, 1});
            }
        }
        alternatives.sort(Comparator.comparingDouble(alt -> alt[2] == 0
            ? swapPenalty(cost, naming, alt[0], alt[1])
            : cost[alt[0]][alt[1]] - cost[alt[0]][naming[alt[0]]]));
        if (offset > alternatives.size()) return null;
        int[] alternative = alternatives.get(offset - 1);
        if (alternative[2] == 0) {
            CubeColor swapped = names[alternative[0]];
            names[alternative[0]] = names[alternative[1]];
            names[alternative[1]] = swapped;
        } else {
            names[alternative[0]] = palette[alternative[1]];
        }
        return names;
    }

    /** How much total cost grows if two prototypes trade names; small means the naming was a toss-up. */
    private static double swapPenalty(double[][] cost, int[] naming, int first, int second) {
        double current = cost[first][naming[first]] + cost[second][naming[second]];
        double swapped = cost[first][naming[second]] + cost[second][naming[first]];
        return swapped - current;
    }
}
