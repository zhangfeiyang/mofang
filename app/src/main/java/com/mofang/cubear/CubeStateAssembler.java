package com.mofang.cubear;

import cs.min2phase.Tools;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Collects face observations and resolves them into a legal URFDLB facelet string.
 *
 * <p>Observations are kept as a pool rather than slotted into six fixed buckets on arrival. Deciding
 * "is this a face I already have?" from an absolute colour distance is the same mistake as naming a
 * sticker from an absolute hue: two faces whose centres happened to fall close under different
 * shading would collide, one would permanently overwrite the other, and the scan would sit at five
 * faces forever. Instead the pool is grouped by relative distance, and the cube's own structure
 * decides which grouping is right.
 */
public final class CubeStateAssembler {
    private static final char[] ORDER = {'U', 'R', 'F', 'D', 'L', 'B'};
    private static final int POOL_LIMIT = 40;
    /** How many alternative prototype namings to try before giving up on a legal state. */
    private static final int NAMING_RETRIES = 6;

    private final List<FaceSample> pool = new ArrayList<>();
    private int lastGroupCount;
    private ScanPalette palette;

    /** The colour prototypes behind the last successful assembly, for naming later frames. */
    public ScanPalette palette() { return palette; }

    /** Records an observation. Returns true when it revealed a face colour not yet seen. */
    public boolean put(FaceSample face) {
        if (face == null) return false;
        if (face.lab == null) {
            if (face.center() == CubeColor.UNKNOWN || face.containsUnknown()) return false;
        } else if (!face.centerReliable()) {
            return false;
        }
        pool.add(face);
        if (pool.size() > POOL_LIMIT) pool.remove(0);
        int groups = distinctFaceCount();
        boolean grew = groups > lastGroupCount;
        lastGroupCount = groups;
        return grew;
    }

    public int size() { return lastGroupCount; }
    public boolean isComplete() { return lastGroupCount >= 6; }
    public List<FaceSample> observations() { return new ArrayList<>(pool); }

    public void clear() {
        pool.clear();
        lastGroupCount = 0;
        palette = null;
    }

    /** Provisional centre colours of the grouped faces, for progress display only. */
    public Set<CubeColor> scannedColors() {
        Set<CubeColor> colors = EnumSet.noneOf(CubeColor.class);
        for (FaceSample face : pool) colors.add(face.center());
        colors.remove(CubeColor.UNKNOWN);
        return colors;
    }

    /**
     * Two observations count as the same face when they sit closer together than this share of the
     * whole pool's colour spread. It is a ratio, not a distance, so a warm room or a dim frame moves
     * every reading together and changes nothing. The six sticker colours are designed to be told
     * apart, so even the tightest genuine pair, red and orange, stays well above this.
     */
    private static final double SAME_FACE_SHARE = 0.30;

    /**
     * How many distinct face colours the pool supports.
     *
     * <p>Merging is only ever forced once there are more than six groups, since a cube has six
     * colours. Below that, groups are joined only when they are close relative to the pool's own
     * spread, so nothing depends on an absolute colour distance.
     */
    private int distinctFaceCount() {
        List<float[]> centers = centersWithLab();
        if (centers.size() != pool.size()) return Math.min(pool.size(), countByEnum());
        if (centers.isEmpty()) return 0;
        return Math.min(6, countGroups(mergeRelatively(centers)));
    }

    private int countByEnum() {
        Set<CubeColor> colors = EnumSet.noneOf(CubeColor.class);
        for (FaceSample face : pool) colors.add(face.center());
        colors.remove(CubeColor.UNKNOWN);
        return colors.size();
    }

    private List<float[]> centersWithLab() {
        List<float[]> centers = new ArrayList<>();
        for (FaceSample face : pool) {
            if (face.centerLab() == null) return centers;
            centers.add(face.centerLab());
        }
        return centers;
    }

    /** Joins groups that are close relative to the pool's overall spread, never past six groups. */
    private static int[] mergeRelatively(List<float[]> points) {
        double widest = 0;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                widest = Math.max(widest, Lab.distance(points.get(i), points.get(j)));
            }
        }
        double cut = widest * SAME_FACE_SHARE;

        int[] label = new int[points.size()];
        for (int i = 0; i < label.length; i++) label[i] = i;
        int groups = points.size();
        while (groups > 1) {
            double best = Double.MAX_VALUE;
            int mergeA = -1, mergeB = -1;
            for (int a = 0; a < label.length; a++) {
                for (int b = a + 1; b < label.length; b++) {
                    if (label[a] == label[b]) continue;
                    double spread = linkage(points, label, label[a], label[b]);
                    if (spread < best) { best = spread; mergeA = label[a]; mergeB = label[b]; }
                }
            }
            if (mergeA < 0) break;
            // Above six groups the cube itself forces a merge; below, only closeness justifies one.
            if (groups <= 6 && best > cut) break;
            for (int i = 0; i < label.length; i++) if (label[i] == mergeB) label[i] = mergeA;
            groups--;
        }
        return label;
    }

    private static int countGroups(int[] label) {
        List<Integer> seen = new ArrayList<>();
        for (int value : label) if (!seen.contains(value)) seen.add(value);
        return seen.size();
    }

    /** Complete linkage keeps groups compact, which suits six tight colour clusters. */
    private static double linkage(List<float[]> points, int[] label, int groupA, int groupB) {
        double worst = 0;
        for (int i = 0; i < label.length; i++) {
            if (label[i] != groupA) continue;
            for (int j = 0; j < label.length; j++) {
                if (label[j] != groupB) continue;
                worst = Math.max(worst, Lab.distance(points.get(i), points.get(j)));
            }
        }
        return worst;
    }

    private static int[] partition(List<float[]> points, int k) {
        int[] label = new int[points.size()];
        for (int i = 0; i < label.length; i++) label[i] = i;
        int groups = points.size();
        while (groups > k) {
            double best = Double.MAX_VALUE;
            int mergeA = -1, mergeB = -1;
            for (int a = 0; a < label.length; a++) {
                for (int b = a + 1; b < label.length; b++) {
                    if (label[a] == label[b]) continue;
                    double spread = linkage(points, label, label[a], label[b]);
                    if (spread < best) { best = spread; mergeA = label[a]; mergeB = label[b]; }
                }
            }
            if (mergeA < 0) break;
            for (int i = 0; i < label.length; i++) if (label[i] == mergeB) label[i] = mergeA;
            groups--;
        }
        return label;
    }

    public String assembleLegalState() {
        List<float[]> centers = centersWithLab();
        if (centers.size() != pool.size() || pool.size() < 6) return assembleWithoutReadings();

        int[] label = partition(centers, 6);
        List<List<FaceSample>> groups = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < label.length; i++) {
            int index = seen.indexOf(label[i]);
            if (index < 0) { seen.add(label[i]); groups.add(new ArrayList<>()); index = groups.size() - 1; }
            groups.get(index).add(pool.get(i));
        }
        if (groups.size() != 6) return null;
        for (List<FaceSample> group : groups) {
            group.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        }

        // Every look at a face is combined, rather than one being crowned. Then, if the cube still
        // rejects the reading, one face at a time drops the look that agrees with its fellows least.
        FaceSample[] chosen = new FaceSample[6];
        for (int i = 0; i < 6; i++) chosen[i] = consensus(groups.get(i), -1);
        String state = tryAssemble(chosen);
        if (state != null) return state;

        // Then all six at once. Swapping a single face cannot help when two of them are spoiled,
        // and a scan that saw the cube at an angle tends to spoil several the same way.
        FaceSample[] trimmed = new FaceSample[6];
        for (int i = 0; i < 6; i++) {
            List<FaceSample> group = groups.get(i);
            trimmed[i] = group.size() < 2 ? chosen[i]
                : consensus(group, byDisagreement(group).get(0));
        }
        state = tryAssemble(trimmed);
        if (state != null) return state;

        for (int face = 0; face < 6; face++) {
            List<FaceSample> group = groups.get(face);
            if (group.size() < 2) continue;
            FaceSample original = chosen[face];
            List<Integer> worstFirst = byDisagreement(group);
            for (int attempt = 0; attempt < Math.min(2, worstFirst.size()); attempt++) {
                chosen[face] = consensus(group, worstFirst.get(attempt));
                state = tryAssemble(chosen);
                if (state != null) return state;
            }
            chosen[face] = original;
        }
        return null;
    }

    /**
     * Merges every look at one face into a single reading, per cell, by median.
     *
     * <p>Picking the group's most confident look instead is what let a bad capture sink a whole
     * scan. A quadrilateral straddling two faces reads nine perfectly flat stickers, so it scores a
     * <em>higher</em> confidence than an honest look that lost a patch to a finger, and confidence
     * alone hands the face to the straddle. Two of those anywhere in the pool and no amount of
     * swapping one face at a time recovers, because each swap still trusts the other straddle.
     * A median across looks simply outvotes it.
     *
     * @param skip index of a look to leave out, or -1 to use them all
     */
    private static FaceSample consensus(List<FaceSample> group, int skip) {
        FaceSample reference = null;
        for (int i = 0; i < group.size(); i++) {
            if (i != skip) { reference = group.get(i); break; }
        }
        if (reference == null) return null;

        List<FaceSample> aligned = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            if (i != skip) aligned.add(alignTo(reference, group.get(i)));
        }
        if (aligned.size() == 1) return aligned.get(0);

        float[][] lab = new float[9][3];
        boolean[] reliable = new boolean[9];
        float confidence = 0;
        for (int cell = 0; cell < 9; cell++) {
            for (int axis = 0; axis < 3; axis++) {
                float[] values = new float[aligned.size()];
                for (int i = 0; i < aligned.size(); i++) values[i] = aligned.get(i).lab[cell][axis];
                java.util.Arrays.sort(values);
                int middle = values.length / 2;
                lab[cell][axis] = values.length % 2 == 1 ? values[middle]
                    : (values[middle - 1] + values[middle]) / 2f;
            }
            int trusted = 0;
            for (FaceSample look : aligned) if (look.reliable[cell]) trusted++;
            reliable[cell] = trusted * 2 > aligned.size();
        }
        for (FaceSample look : aligned) confidence += look.confidence;
        return new FaceSample(reference.stickers, lab, reliable, confidence / aligned.size());
    }

    /**
     * Rotates a look into the orientation that best matches the reference.
     *
     * <p>Looks at one face arrive at whatever roll the phone happened to be held at, so they cannot
     * be averaged cell by cell until they are brought into a common orientation.
     */
    private static FaceSample alignTo(FaceSample reference, FaceSample sample) {
        FaceSample best = sample, rotated = sample;
        double lowest = Double.MAX_VALUE;
        for (int turn = 0; turn < 4; turn++) {
            double cost = 0;
            for (int cell = 0; cell < 9; cell++) {
                cost += Lab.distance(reference.lab[cell], rotated.lab[cell]);
            }
            if (cost < lowest) { lowest = cost; best = rotated; }
            rotated = rotated.rotateClockwise();
        }
        return best;
    }

    /** Looks ordered by how far they sit from their group's consensus, worst first. */
    private static List<Integer> byDisagreement(List<FaceSample> group) {
        FaceSample agreed = consensus(group, -1);
        double[] cost = new double[group.size()];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            FaceSample aligned = alignTo(agreed, group.get(i));
            for (int cell = 0; cell < 9; cell++) {
                cost[i] += Lab.distance(agreed.lab[cell], aligned.lab[cell]);
            }
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(cost[b], cost[a]));
        return order;
    }

    /** Runs the colour assignment and orientation search for one choice of six observations. */
    private String tryAssemble(FaceSample[] chosen) {
        for (int naming = 0; naming <= NAMING_RETRIES; naming++) {
            ColorAssignment.Result result = ColorAssignment.assign(chosen, naming);
            if (result == null) break;
            FaceSample[] resolved = new FaceSample[6];
            for (int i = 0; i < 6; i++) {
                resolved[i] = new FaceSample(result.colors[i], chosen[i].lab,
                    chosen[i].reliable, chosen[i].confidence);
            }
            String state = orderAndSearch(resolved);
            if (state != null) {
                palette = ScanPalette.from(result);
                return state;
            }
        }
        return null;
    }

    /** Fallback for samples without Lab readings, which keeps the assembler usable on its own. */
    private String assembleWithoutReadings() {
        EnumMap<CubeColor, FaceSample> byCenter = new EnumMap<>(CubeColor.class);
        for (FaceSample face : pool) {
            if (face.center() != CubeColor.UNKNOWN) byCenter.put(face.center(), face);
        }
        if (byCenter.size() != 6) return null;
        FaceSample[] ordered = new FaceSample[6];
        for (int i = 0; i < ORDER.length; i++) {
            ordered[i] = byCenter.get(CubeColor.fromFace(ORDER[i]));
            if (ordered[i] == null) return null;
        }
        return searchRotations(ordered, 0, new StringBuilder(54));
    }

    /** Sorts the six resolved faces into URFDLB order, then brute-forces each face's camera roll. */
    private String orderAndSearch(FaceSample[] resolved) {
        FaceSample[] ordered = new FaceSample[6];
        for (int i = 0; i < ORDER.length; i++) {
            for (FaceSample face : resolved) {
                if (face.center().face == ORDER[i]) { ordered[i] = face; break; }
            }
            if (ordered[i] == null) return null;
        }
        return searchRotations(ordered, 0, new StringBuilder(54));
    }

    private String searchRotations(FaceSample[] faces, int index, StringBuilder state) {
        if (index == faces.length) {
            String candidate = state.toString();
            // The structural check is cheap and rejects most misreads before the solver's own
            // parity and permutation checks have to run.
            if (!CubeRules.piecesArePlausible(candidate)) return null;
            return Tools.verify(candidate) == 0 ? candidate : null;
        }
        FaceSample rotated = faces[index];
        for (int turn = 0; turn < 4; turn++) {
            int oldLength = state.length();
            for (CubeColor color : rotated.stickers) state.append(color.face);
            String found = searchRotations(faces, index + 1, state);
            if (found != null) return found;
            state.setLength(oldLength);
            rotated = rotated.rotateClockwise();
        }
        return null;
    }
}
