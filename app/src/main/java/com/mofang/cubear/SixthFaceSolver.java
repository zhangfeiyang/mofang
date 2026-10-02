package com.mofang.cubear;

import cs.min2phase.Tools;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the one face nobody managed to scan.
 *
 * <p>Five faces leave exactly nine stickers unseen, and the cube's own structure pins them down.
 * Every pair of colours on an edge piece, and every triple on a corner piece, identifies that
 * piece uniquely — so once the fully visible pieces are counted, the pieces left over are exactly
 * the ones touching the missing face, and each of those has only one sticker left to place.
 *
 * <p>The five scanned faces still carry stickers of the sixth colour; naming every patch against
 * only the five centres would force those stickers onto a neighbour (yellow onto orange, most
 * often) and the piece check would reject a perfectly good scan. The missing colour's prototype
 * is recovered by shifting the canonical reading by the same Lab offset the five centres show,
 * then refined from the patches that sit nearer that estimate than any scanned centre.
 *
 * <p>Wrong relative rolls die at the first impossible or duplicated piece, so walking every
 * combination of the five faces' four possible rolls is cheap. The sixth face is not always
 * unique — leftover last-layer edges can share a visible colour — and if more than one legal
 * completion survives the caller must wait for another look rather than guess.
 */
public final class SixthFaceSolver {
    private static final String FACES = "URFDLB";
    private static final int NAMING_RETRIES = 6;
    /**
     * How much closer a patch must sit to the missing-colour estimate than to any scanned
     * prototype before it is trusted as a sample of that colour. Tight enough that orange cannot
     * donate itself to yellow; loose enough that a genuine yellow patch under the same lighting
     * still qualifies.
     */
    private static final float OUTLIER_MARGIN = 8f;

    /** Facelet indices per corner/edge piece in the standard URFDLB layout (0..53). */
    private static final int[][] CORNER_FACELETS = {
        {8, 9, 20}, {6, 18, 38}, {0, 36, 47}, {2, 45, 11},
        {29, 26, 15}, {27, 44, 24}, {33, 53, 42}, {35, 17, 51}};
    private static final int[][] EDGE_FACELETS = {
        {5, 10}, {7, 19}, {3, 37}, {1, 46}, {32, 16}, {28, 25},
        {30, 43}, {34, 52}, {23, 12}, {21, 41}, {50, 39}, {48, 14}};

    private SixthFaceSolver() {}

    public static final class Result {
        /** A legal 54-facelet state with the inferred face filled in. */
        public final String state;
        /** Names live frames by the five scanned prototypes until the sixth is learned. */
        public final ScanPalette palette;

        Result(String state, ScanPalette palette) {
            this.state = state;
            this.palette = palette;
        }
    }

    /** @param faces exactly five observations, one per scanned face, carrying Lab readings */
    public static Result solve(FaceSample[] faces) {
        return solve(faces, Long.MAX_VALUE);
    }

    /**
     * As {@link #solve(FaceSample[])}, giving up at {@code deadlineNanos} ({@link System#nanoTime()}).
     * Inconsistent readings make the completion search wander to its node budget on every naming;
     * the caller retries as looks accumulate, so a bounded refusal costs little.
     */
    public static Result solve(FaceSample[] faces, long deadlineNanos) {
        if (faces == null || faces.length != 5) return null;
        float[][] prototypes = new float[5][];
        for (int i = 0; i < 5; i++) {
            if (faces[i] == null || faces[i].lab == null || !faces[i].centerReliable()) return null;
            prototypes[i] = faces[i].lab[4];
        }

        for (int naming = 0; naming <= NAMING_RETRIES; naming++) {
            if (System.nanoTime() > deadlineNanos) return null;
            CubeColor[] names = ColorAssignment.namePrototypes(prototypes, naming);
            if (names == null) break;
            CubeColor missingColor = leftover(names);
            if (missingColor == null) continue;

            float[] sixth = adaptedPrototype(prototypes, names, missingColor);
            sixth = refineFromOutliers(faces, prototypes, sixth);

            // Strict first: every readable sticker keeps its name, and a unique legal completion
            // is returned as-is. Only when strict search finds nothing at all — the signature of
            // a mislabeled sticker, since one wrong label vetoes every completion — does the
            // second pass unname each face's worst-residual cell and let the piece constraints
            // re-derive them.
            for (int untrust : new int[]{0, UNTRUST_PER_FACE}) {
                FaceSample[] named =
                    nameStickers(faces, prototypes, names, missingColor, sixth, untrust);
                Set<String> found = new LinkedHashSet<>();
                searchRolls(named, names, new FaceSample[6], 0, missingColor.face, found,
                    new long[]{NODE_BUDGET, deadlineNanos});
                // Five faces do not always pin the sixth down — leftover D-layer edges can
                // share a visible colour, and two fillings then both pass the solver. Guessing
                // would restore the wrong cube, so an ambiguous primary naming stops here.
                // Later namings are only tried when this colouring produced nothing at all.
                if (found.size() > 1) {
                    if (naming == 0) return null;
                    break;
                }
                if (found.size() == 1) {
                    return new Result(found.iterator().next(),
                        ScanPalette.fromFive(prototypes, names, missingColor));
                }
            }
        }
        return null;
    }

    /** The one canonical colour none of the five prototypes was named. */
    private static CubeColor leftover(CubeColor[] names) {
        boolean[] seen = new boolean[FACES.length()];
        for (CubeColor name : names) {
            int index = FACES.indexOf(name.face);
            if (index < 0 || seen[index]) return null;
            seen[index] = true;
        }
        int missingIndex = -1;
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                if (missingIndex >= 0) return null;
                missingIndex = i;
            }
        }
        return missingIndex < 0 ? null : CubeColor.fromFace(FACES.charAt(missingIndex));
    }

    /**
     * Canonical reading of the missing colour, shifted by the mean Lab offset the five scanned
     * centres show against their own canonical readings. A warm room moves every sticker together,
     * so the sixth colour's expected appearance follows for free.
     */
    private static float[] adaptedPrototype(float[][] prototypes, CubeColor[] names,
                                            CubeColor missing) {
        float[] offset = new float[3];
        for (int i = 0; i < prototypes.length; i++) {
            float[] canonical = labOf(names[i]);
            for (int channel = 0; channel < 3; channel++) {
                offset[channel] += prototypes[i][channel] - canonical[channel];
            }
        }
        float[] sixth = labOf(missing);
        float count = prototypes.length;
        for (int channel = 0; channel < 3; channel++) sixth[channel] += offset[channel] / count;
        return sixth;
    }

    /**
     * Replaces the adapted estimate with the mean of patches that are nearer to it than to any
     * scanned centre. Those patches <em>are</em> the missing colour on a scrambled cube, so using
     * them as the prototype is measuring this cube under this light rather than guessing.
     */
    private static float[] refineFromOutliers(FaceSample[] faces, float[][] known, float[] sixth) {
        float[] sum = new float[3];
        int count = 0;
        for (FaceSample face : faces) {
            for (int cell = 0; cell < 9; cell++) {
                if (!face.reliable[cell]) continue;
                float toSixth = Lab.distance(face.lab[cell], sixth);
                float toKnown = Float.MAX_VALUE;
                for (float[] prototype : known) {
                    toKnown = Math.min(toKnown, Lab.distance(face.lab[cell], prototype));
                }
                if (toSixth + OUTLIER_MARGIN < toKnown) {
                    for (int channel = 0; channel < 3; channel++) sum[channel] += face.lab[cell][channel];
                    count++;
                }
            }
        }
        if (count < 2) return sixth;
        return new float[]{sum[0] / count, sum[1] / count, sum[2] / count};
    }

    private static float[] labOf(CubeColor color) {
        return Lab.fromRgb((color.argb >> 16) & 0xFF, (color.argb >> 8) & 0xFF, color.argb & 0xFF);
    }

    /**
     * Names the 45 visible stickers under the cube's own counting rules.
     *
     * <p>Nearest-prototype naming alone mislabels systematically: on a real scan it counted ten
     * reds (an orange drifted red) and nine greens among the scanned faces, which is impossible —
     * the missing face's centre holds a green too — and the piece check rightly rejected every
     * completion. So the naming runs as a minimum-cost assignment against slots: at most nine per
     * scanned colour, at most eight of the missing colour (its centre is not among these 45), and
     * each centre pinned to the colour it was named. Overflowing a colour now pushes its excess
     * stickers to their next-best colour instead of poisoning the piece check.
     */
    /**
     * Names the 45 visible stickers under the cube's own counting rules.
     *
     * <p>Nearest-prototype naming alone mislabels systematically: on a real scan it counted ten
     * reds (an orange drifted red) and nine greens among the scanned faces, which is impossible —
     * the missing face's centre holds a green too — and the piece check rightly rejected every
     * completion. So the naming runs as a minimum-cost assignment against slots: at most nine per
     * scanned colour, at most eight of the missing colour (its centre is not among these 45), and
     * each centre pinned to the colour it was named. Overflowing a colour now pushes its excess
     * stickers to their next-best colour instead of poisoning the piece check.
     *
     * <p>One wrong label among the fully visible pieces rejects every legal completion, while a
     * hole can be re-derived by the piece constraints downstream — so each face's worst-residual
     * cell is left unnamed rather than forced.
     */
    /**
     * Retry stage: cells per face left unnamed for the piece constraints to re-derive. The
     * assignment's capacity victim — the sticker its colour had to give up — always tops its
     * face's residual list, so unnamming exactly that one cell removes the piece-breaker and
     * nothing else. Strict search runs first; measured on drifted synthetic scans the pair
     * recovers 39/40 where strict alone managed 28/40, and clean scans still resolve exactly.
     */
    private static final int UNTRUST_PER_FACE = 1;

    private static FaceSample[] nameStickers(FaceSample[] faces, float[][] prototypes,
                                             CubeColor[] names, CubeColor missing, float[] sixth,
                                             int untrustPerFace) {
        final double FORBIDDEN = 1e9;
        int slots = prototypes.length * 9 + 8;
        CubeColor[] slotColor = new CubeColor[slots];
        float[][] slotPrototype = new float[slots][];
        int nextSlot = 0;
        for (int p = 0; p < prototypes.length; p++) {
            for (int k = 0; k < 9; k++) {
                slotColor[nextSlot] = names[p];
                slotPrototype[nextSlot] = prototypes[p];
                nextSlot++;
            }
        }
        for (int k = 0; k < 8; k++) {
            slotColor[nextSlot] = missing;
            slotPrototype[nextSlot] = sixth;
            nextSlot++;
        }

        // One row per readable patch; unreliable patches carry no colour evidence and take no slot.
        int rows = 0;
        for (FaceSample face : faces) {
            for (int cell = 0; cell < 9; cell++) if (face.reliable[cell]) rows++;
        }
        double[][] cost = new double[rows][slots];
        CubeColor[][] stickers = new CubeColor[faces.length][9];
        float[] assignedResidual = new float[rows];
        float[] faceResidual = new float[9];
        int[] faceRows = new int[9];
        int row = 0;
        for (int face = 0; face < faces.length; face++) {
            CubeColor own = names[face];
            for (int cell = 0; cell < 9; cell++) {
                if (!faces[face].reliable[cell]) {
                    stickers[face][cell] = CubeColor.UNKNOWN;
                    continue;
                }
                for (int s = 0; s < slots; s++) {
                    cost[row][s] = cell == 4 && slotColor[s] != own
                        ? FORBIDDEN
                        : Lab.distanceSquared(faces[face].lab[cell], slotPrototype[s]);
                }
                row++;
            }
        }
        int[] assignment = Hungarian.solve(cost);

        FaceSample[] named = new FaceSample[faces.length];
        row = 0;
        for (int face = 0; face < faces.length; face++) {
            // The worst-reading cells of each face are exactly where mislabels live; unname the
            // per-face top-K by residual (floor-guarded) and let the piece constraints re-derive.
            java.util.Arrays.fill(faceRows, -1);
            for (int cell = 0; cell < 9; cell++) {
                if (stickers[face][cell] == CubeColor.UNKNOWN) continue;
                int slot = assignment[row];
                assignedResidual[row] = Lab.distance(faces[face].lab[cell], slotPrototype[slot]);
                faceRows[cell] = row;
                faceResidual[cell] = assignedResidual[row];
                row++;
            }
            int marked = 0;
            while (marked < untrustPerFace) {
                int worst = -1;
                float worstValue = -1f;
                for (int cell = 0; cell < 9; cell++) {
                    if (faceRows[cell] < 0) continue;
                    if (faceResidual[cell] > worstValue) { worstValue = faceResidual[cell]; worst = cell; }
                }
                if (worst < 0) break;
                stickers[face][worst] = CubeColor.UNKNOWN;
                faceRows[worst] = -1;
                marked++;
            }
            for (int cell = 0; cell < 9; cell++) {
                if (stickers[face][cell] == CubeColor.UNKNOWN) continue;
                stickers[face][cell] = slotColor[assignment[faceRows[cell]]];
            }
            named[face] = new FaceSample(stickers[face], faces[face].lab, faces[face].reliable,
                faces[face].confidence);
        }
        return named;
    }

    /** Tries every combination of the five faces' unknown rolls; each face's centre is fixed. */
    private static void searchRolls(FaceSample[] named, CubeColor[] names, FaceSample[] slots,
                                    int index, char missing, Set<String> found, long[] budget) {
        if (budget[0] <= 0) return;
        if (index == named.length) {
            char[] state = new char[54];
            for (int face = 0; face < FACES.length(); face++) {
                FaceSample slot = slots[face];
                for (int cell = 0; cell < 9; cell++) {
                    if (slot == null) {
                        state[face * 9 + cell] = cell == 4 ? missing : '?';
                    } else if (slot.stickers[cell] == CubeColor.UNKNOWN) {
                        state[face * 9 + cell] = '?';
                    } else {
                        state[face * 9 + cell] = slot.stickers[cell].face;
                    }
                }
            }
            fillMissing(state, found, budget);
            return;
        }
        int slot = FACES.indexOf(names[index].face);
        FaceSample rotated = named[index];
        for (int turn = 0; turn < 4; turn++) {
            slots[slot] = rotated;
            searchRolls(named, names, slots, index + 1, missing, found, budget);
            rotated = rotated.rotateClockwise();
        }
    }

    /**
     * How many fully visible pieces may fail identification before an arrangement is hopeless.
     *
     * <p>A burned piece means a sticker label is suspect; the piece is left unclaimed and its
     * stickers stay as named. The final verification is what judges the resulting cube, so a
     * burned-but-actually-correct piece costs nothing and a mislabeled one simply fails verify
     * instead of vetoing the whole search.
     */
    private static final int MAX_BURNED_EDGES = 2;
    private static final int MAX_BURNED_CORNERS = 2;
    /**
     * Search nodes one naming attempt may spend. A healthy scan needs a few hundred; a poisoned
     * or hole-riddled one can wander combinatorially, and the caller has retry ladders of its
     * own — hitting the cap simply means this colouring produced nothing.
     */
    private static final int NODE_BUDGET = 60_000;

    /** Takes one node from {@code budget} = {nodes, deadline}; false once either is used up. */
    private static boolean spend(long[] budget) {
        if (budget[0] <= 0) return false;
        if ((--budget[0] & 1023) == 0 && System.nanoTime() > budget[1]) budget[0] = 0;
        return true;
    }

    /**
     * Fills every '?' facelet or rejects the arrangement.
     *
     * <p>Wholly visible pieces are identified and ticked off first; wrong relative rolls produce
     * impossible or repeated pieces here. A piece that cannot be identified is burned (within
     * budget) instead of vetoing the arrangement — real readings carry a few misnamed stickers.
     * Every position with at least one readable sticker but not all of them — the missing face's
     * border, and any spot a suspect sticker left unnamed — is an open position, and the unused
     * pieces are enumerated onto them under the piece-uniqueness rules until the completed
     * 54-facelet string passes the structural check and the solver's own verification. Every
     * legal filling is recorded: the sixth face is not always unique, and returning the first
     * one would silently restore the wrong cube.
     */
    private static void fillMissing(char[] state, Set<String> found, long[] budget) {
        if (!spend(budget)) return;
        boolean[] edgeUsed = new boolean[12];
        boolean[] cornerUsed = new boolean[8];
        List<Integer> openEdges = new ArrayList<>();
        List<Integer> openCorners = new ArrayList<>();
        int burnedEdges = 0;
        int burnedCorners = 0;
        for (int piece = 0; piece < 12; piece++) {
            int a = EDGE_FACELETS[piece][0], b = EDGE_FACELETS[piece][1];
            boolean ka = state[a] != '?', kb = state[b] != '?';
            if (ka && kb) {
                int identified = edgePiece(state[a], state[b]);
                if (identified < 0 || edgeUsed[identified]) {
                    if (++burnedEdges > MAX_BURNED_EDGES) return;
                    continue;
                }
                edgeUsed[identified] = true;
            } else if (ka || kb) {
                openEdges.add(piece);
            } else {
                return;
            }
        }
        for (int piece = 0; piece < 8; piece++) {
            int known = 0;
            for (int facelet : CORNER_FACELETS[piece]) if (state[facelet] != '?') known++;
            if (known == 3) {
                int identified = cornerPiece(state, CORNER_FACELETS[piece]);
                if (identified < 0 || cornerUsed[identified]) {
                    if (++burnedCorners > MAX_BURNED_CORNERS) return;
                    continue;
                }
                cornerUsed[identified] = true;
            } else if (known >= 1) {
                openCorners.add(piece);
            } else {
                return;
            }
        }
        placeEdges(state, openEdges, 0, edgeUsed, openCorners, cornerUsed, found, budget);
    }

    /** Depth-first placement of the unused edges on open positions; corners follow. */
    private static void placeEdges(char[] state, List<Integer> openEdges, int edgeIndex,
                                   boolean[] edgeUsed, List<Integer> openCorners,
                                   boolean[] cornerUsed, Set<String> found, long[] budget) {
        if (!spend(budget)) return;
        if (edgeIndex == openEdges.size()) {
            placeCorners(state, openCorners, 0, cornerUsed, found, budget);
            return;
        }
        int position = openEdges.get(edgeIndex);
        int knownSide = state[EDGE_FACELETS[position][0]] != '?' ? 0 : 1;
        char known = state[EDGE_FACELETS[position][knownSide]];
        int unknown = EDGE_FACELETS[position][1 - knownSide];
        for (int piece = 0; piece < 12; piece++) {
            if (edgeUsed[piece]) continue;
            char[] colors = edgeColors(piece);
            char other;
            if (colors[0] == known) other = colors[1];
            else if (colors[1] == known) other = colors[0];
            else continue;
            edgeUsed[piece] = true;
            state[unknown] = other;
            placeEdges(state, openEdges, edgeIndex + 1, edgeUsed,
                openCorners, cornerUsed, found, budget);
            state[unknown] = '?';
            edgeUsed[piece] = false;
        }
    }

    private static void placeCorners(char[] state, List<Integer> openCorners, int index,
                                     boolean[] cornerUsed, Set<String> found, long[] budget) {
        if (!spend(budget)) return;
        if (index == openCorners.size()) {
            String candidate = new String(state);
            // The cheap structural check prunes before the solver's own tables have to run.
            if (CubeRules.piecesArePlausible(candidate) && Tools.verify(candidate) == 0) {
                found.add(candidate);
                // Two legal completions already mean "ambiguous"; the rest of the search could
                // only confirm that, and on a poor naming it is most of the work.
                if (found.size() > 1) budget[0] = 0;
            }
            return;
        }
        int position = openCorners.get(index);
        int knownCount = 0;
        for (int facelet : CORNER_FACELETS[position]) if (state[facelet] != '?') knownCount++;
        // Exact-size arrays: a leftover zero entry would read as facelet 0 and corrupt the search.
        char[] known = new char[knownCount];
        int[] unknowns = new int[3 - knownCount];
        int knownNext = 0, unknownNext = 0;
        for (int facelet : CORNER_FACELETS[position]) {
            if (state[facelet] != '?') known[knownNext++] = state[facelet];
            else unknowns[unknownNext++] = facelet;
        }
        for (int piece = 0; piece < 8; piece++) {
            if (cornerUsed[piece]) continue;
            char[] colors = cornerColors(piece);
            // Every known colour of this position must sit on the candidate piece; its remaining
            // colours are then placed on the position's open facelets in every order.
            boolean[] taken = new boolean[3];
            boolean matches = true;
            for (int k = 0; k < knownCount && matches; k++) {
                boolean foundColor = false;
                for (int c = 0; c < 3; c++) {
                    if (!taken[c] && colors[c] == known[k]) {
                        taken[c] = true;
                        foundColor = true;
                        break;
                    }
                }
                if (!foundColor) matches = false;
            }
            if (!matches) continue;
            char[] rest = new char[3 - knownCount];
            int r = 0;
            for (int c = 0; c < 3; c++) if (!taken[c]) rest[r++] = colors[c];
            cornerUsed[piece] = true;
            placeRest(state, unknowns, rest, 0, openCorners, index, cornerUsed, found, budget);
            cornerUsed[piece] = false;
        }
    }

    /** Places the chosen piece's remaining colours on the position's open facelets, every order. */
    private static void placeRest(char[] state, int[] unknowns, char[] rest, int restIndex,
                                  List<Integer> openCorners, int cornerIndex,
                                  boolean[] cornerUsed, Set<String> found, long[] budget) {
        if (!spend(budget)) return;
        if (restIndex == rest.length) {
            placeCorners(state, openCorners, cornerIndex + 1, cornerUsed, found, budget);
            return;
        }
        for (int u = 0; u < unknowns.length; u++) {
            if (unknowns[u] < 0) continue;
            char save = state[unknowns[u]];
            state[unknowns[u]] = rest[restIndex];
            unknowns[u] = -unknowns[u] - 1; // mark taken
            placeRest(state, unknowns, rest, restIndex + 1, openCorners, cornerIndex,
                cornerUsed, found, budget);
            unknowns[u] = -unknowns[u] - 1; // restore
            state[unknowns[u]] = save;
        }
    }

    /** The piece carrying colours {a, b}, or -1 when no edge piece can show that pair. */
    private static int edgePiece(char a, char b) {
        for (int piece = 0; piece < 12; piece++) {
            char[] colors = edgeColors(piece);
            if ((colors[0] == a && colors[1] == b) || (colors[0] == b && colors[1] == a)) {
                return piece;
            }
        }
        return -1;
    }

    private static int cornerPiece(char[] state, int[] facelets) {
        char[] observed = {state[facelets[0]], state[facelets[1]], state[facelets[2]]};
        Arrays.sort(observed);
        for (int piece = 0; piece < 8; piece++) {
            char[] colors = cornerColors(piece);
            Arrays.sort(colors);
            if (Arrays.equals(observed, colors)) return piece;
        }
        return -1;
    }

    private static char[] edgeColors(int piece) {
        return new char[]{
            FACES.charAt(EDGE_FACELETS[piece][0] / 9),
            FACES.charAt(EDGE_FACELETS[piece][1] / 9)};
    }

    private static char[] cornerColors(int piece) {
        return new char[]{
            FACES.charAt(CORNER_FACELETS[piece][0] / 9),
            FACES.charAt(CORNER_FACELETS[piece][1] / 9),
            FACES.charAt(CORNER_FACELETS[piece][2] / 9)};
    }
}
