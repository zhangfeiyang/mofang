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

        CubeColor[] names = nameProtoypes(prototypes, nameOffset);
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
     */
    private static CubeColor[] nameProtoypes(float[][] prototypes, int offset) {
        CubeColor[] palette = {CubeColor.WHITE, CubeColor.RED, CubeColor.GREEN,
            CubeColor.YELLOW, CubeColor.ORANGE, CubeColor.BLUE};
        float[][] references = new float[6][];
        for (int i = 0; i < 6; i++) {
            int argb = palette[i].argb;
            references[i] = Lab.fromRgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        }

        double[][] cost = new double[6][6];
        for (int prototype = 0; prototype < 6; prototype++) {
            for (int reference = 0; reference < 6; reference++) {
                cost[prototype][reference] = Lab.distanceSquared(prototypes[prototype], references[reference]);
            }
        }

        int[] naming = Hungarian.solve(cost);
        CubeColor[] names = new CubeColor[6];
        for (int prototype = 0; prototype < 6; prototype++) names[prototype] = palette[naming[prototype]];
        if (offset == 0) return names;

        // Retry namings swap the pair the solver was least sure about, then the next pair, and so on.
        List<int[]> pairs = new ArrayList<>();
        for (int a = 0; a < 6; a++) {
            for (int b = a + 1; b < 6; b++) pairs.add(new int[]{a, b});
        }
        pairs.sort(Comparator.comparingDouble(pair -> swapPenalty(cost, naming, pair[0], pair[1])));
        if (offset > pairs.size()) return null;
        int[] pair = pairs.get(offset - 1);
        CubeColor swapped = names[pair[0]];
        names[pair[0]] = names[pair[1]];
        names[pair[1]] = swapped;
        return names;
    }

    /** How much total cost grows if two prototypes trade names; small means the naming was a toss-up. */
    private static double swapPenalty(double[][] cost, int[] naming, int first, int second) {
        double current = cost[first][naming[first]] + cost[second][naming[second]];
        double swapped = cost[first][naming[second]] + cost[second][naming[first]];
        return swapped - current;
    }
}
