package com.mofang.cubear;

/**
 * The cube's own structural constraints, used to reject and localise misreads.
 *
 * <p>A scan is not 54 independent colours. Every corner joins three mutually adjacent faces and
 * every edge joins two, so a corner reading white-yellow-red or an edge reading red-orange is
 * impossible no matter how confident the pixels looked. Checking this catches bad stickers that
 * survive colour matching, and points at which sticker is wrong rather than only saying the whole
 * scan failed.
 */
public final class CubeRules {
    /** Facelet indices of the three stickers on each corner, standard URFDLB layout. */
    static final int[][] CORNERS = {
        {8, 9, 20}, {6, 18, 38}, {0, 36, 47}, {2, 45, 11},
        {29, 26, 15}, {27, 44, 24}, {33, 53, 42}, {35, 17, 51}
    };
    /** Facelet indices of the two stickers on each edge. */
    static final int[][] EDGES = {
        {5, 10}, {7, 19}, {3, 37}, {1, 46}, {32, 16}, {28, 25},
        {30, 43}, {34, 52}, {23, 12}, {21, 41}, {50, 39}, {48, 14}
    };
    /** Face letters that sit opposite each other and therefore never share a piece. */
    private static final String[] OPPOSITE = {"UD", "RL", "FB"};

    private CubeRules() {}

    public static boolean areOpposite(char a, char b) {
        for (String pair : OPPOSITE) {
            if (pair.indexOf(a) >= 0 && pair.indexOf(b) >= 0) return a != b;
        }
        return false;
    }

    /** Centres define the six colours, so a repeat means two faces were read as the same colour. */
    public static boolean centersAreDistinct(String state) {
        boolean[] seen = new boolean[128];
        for (int face = 0; face < 6; face++) {
            char centre = state.charAt(face * 9 + 4);
            if (seen[centre]) return false;
            seen[centre] = true;
        }
        return true;
    }

    /**
     * Counts, per sticker, how many structural rules it takes part in breaking. Zero everywhere
     * means the reading is at least piece-wise consistent; the highest counts are the stickers most
     * worth re-deciding.
     */
    public static int[] violations(String state) {
        int[] blame = new int[54];
        for (int[] corner : CORNERS) {
            char a = state.charAt(corner[0]), b = state.charAt(corner[1]), c = state.charAt(corner[2]);
            if (a == b || b == c || a == c
                || areOpposite(a, b) || areOpposite(b, c) || areOpposite(a, c)) {
                for (int index : corner) blame[index]++;
            }
        }
        for (int[] edge : EDGES) {
            char a = state.charAt(edge[0]), b = state.charAt(edge[1]);
            if (a == b || areOpposite(a, b)) {
                for (int index : edge) blame[index]++;
            }
        }
        // Every corner and every edge of a real cube is unique; duplicates mean a misread.
        blamePieceDuplicates(state, CORNERS, blame);
        blamePieceDuplicates(state, EDGES, blame);
        return blame;
    }

    private static void blamePieceDuplicates(String state, int[][] pieces, int[] blame) {
        String[] signatures = new String[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            char[] colors = new char[pieces[i].length];
            for (int j = 0; j < pieces[i].length; j++) colors[j] = state.charAt(pieces[i][j]);
            java.util.Arrays.sort(colors);
            signatures[i] = new String(colors);
        }
        for (int i = 0; i < pieces.length; i++) {
            for (int j = i + 1; j < pieces.length; j++) {
                if (signatures[i].equals(signatures[j])) {
                    for (int index : pieces[i]) blame[index]++;
                    for (int index : pieces[j]) blame[index]++;
                }
            }
        }
    }

    /** True when no corner or edge breaks a structural rule. */
    public static boolean piecesArePlausible(String state) {
        if (!centersAreDistinct(state)) return false;
        for (int count : violations(state)) if (count > 0) return false;
        return true;
    }

    /** Total rule breaches, for ranking one candidate reading against another. */
    public static int violationCount(String state) {
        int total = 0;
        for (int count : violations(state)) total += count;
        return total;
    }
}
