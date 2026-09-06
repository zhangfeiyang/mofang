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
    /**
     * Looks a colour group needs before the scan may complete on it. One look cannot be told
     * apart from a straddle or a fluke; two independent looks agreeing is the cheapest
     * redundancy the user can be asked for.
     */
    private static final int MIN_LOOKS_PER_FACE = 2;
    /** How many alternative prototype namings to try before giving up on a legal state. */
    private static final int NAMING_RETRIES = 6;

    private final List<FaceSample> pool = new ArrayList<>();
    private int lastGroupCount;
    private ScanPalette palette;
    private String lastFailure = "";
    /** Remaining attempts across the retry ladders of one {@link #assemble()} call. */
    private int searchBudget;

    /** The colour prototypes behind the last successful assembly, for naming later frames. */
    public ScanPalette palette() { return palette; }

    /** Records an observation. Returns true when it revealed a face colour not yet seen. */
    public boolean put(FaceSample face) {
        if (face == null) return false;
        if (face.lab == null) {
            if (face.center() == CubeColor.UNKNOWN || face.containsUnknown()) return false;
        } else if (!face.centerReliable() || !Lab.isStickerCenter(face.centerLab())) {
            return false;
        }
        // No spatial-split gate here, deliberately. On a scrambled cube an honest face's columns
        // are random colour mixes whose means sit as far apart as a straddling quad's two halves
        // (measured: honest 58–105 vs straddle 60–90), so any absolute cut rejects honest looks —
        // and every rejected look is a slower scan. Straddles are dealt with where evidence
        // accumulates: per-colour clustering, the consensus close-filter, and the retry ladder.
        pool.add(face);
        if (pool.size() > POOL_LIMIT) evict();
        int groups = distinctFaceCount();
        boolean grew = groups > lastGroupCount;
        lastGroupCount = groups;
        android.util.Log.d("CubeAsm", describeGroups());
        return grew;
    }

    /** One line per accepted look: group count and each group's size + centre, for logcat. */
    private String describeGroups() {
        int[] label = clusteredLabels();
        if (label == null) return "groups=? (lab incomplete) pool=" + pool.size();
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        java.util.Map<Integer, CubeColor> centre = new java.util.HashMap<>();
        for (int i = 0; i < label.length; i++) {
            int id = label[i];
            if (id < 0) continue;
            count.put(id, count.getOrDefault(id, 0) + 1);
            centre.putIfAbsent(id, pool.get(i).center());
        }
        StringBuilder out = new StringBuilder("groups=").append(count.size()).append(" [");
        for (java.util.Map.Entry<Integer, Integer> e : count.entrySet()) {
            out.append(centre.get(e.getKey()).toString().substring(0, 3))
                .append(':').append(e.getValue()).append(' ');
        }
        return out.append("] pool=").append(pool.size()).toString();
    }

    /**
     * Makes room by dropping the least valuable observation, not the oldest one.
     *
     * <p>Plain FIFO eviction was a trap: once the pool filled during a long scan it discarded the
     * first faces' only honest looks, their colours silently vanished, and the scan regressed to
     * five groups fed with whatever remained — including the one face whose every look was a
     * straddle. Surplus-group members go first (the clusterer already called them junk), then the
     * oldest member of the largest colour group, so thin groups always survive.
     */
    private void evict() {
        int[] label = clusteredLabels();
        int victim = 0;
        if (label == null) {
            victim = 0;
        } else {
            int surplus = -1;
            int largest = -1, largestSize = 0;
            java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
            for (int i = 0; i < label.length; i++) {
                int id = label[i];
                if (id < 0) { surplus = i; continue; }
                int size = count.getOrDefault(id, 0) + 1;
                count.put(id, size);
                if (size > largestSize) { largestSize = size; largest = id; }
            }
            if (surplus >= 0) {
                victim = surplus;
            } else {
                for (int i = 0; i < label.length; i++) {
                    if (label[i] == largest) { victim = i; break; }
                }
            }
        }
        pool.remove(victim);
    }

    public int size() { return lastGroupCount; }

    /**
     * True when six colour groups exist AND each holds at least two looks.
     *
     * <p>A colour seen exactly once is not a collected face: its single look may be a straddle
     * or a fluke, and one bad group is enough to fail every assembly. Requiring a second look
     * turns the failure mode from "solve fails, user has no idea why" into "that colour's dot
     * stays unlit" — the user knows which face to show again.
     */
    public boolean isComplete() {
        if (lastGroupCount != 6) return false;
        int[] label = clusteredLabels();
        if (label == null || countGroups(label) != 6) return false;
        return smallestGroup(label) >= MIN_LOOKS_PER_FACE;
    }

    /** Provisional centre colours of the groups that have the minimum evidence. */
    public Set<CubeColor> establishedColors() {
        Set<CubeColor> colors = EnumSet.noneOf(CubeColor.class);
        int[] label = clusteredLabels();
        if (label == null) return colors;
        // Majority non-UNKNOWN name per group: the first look of a group often classified its
        // centre UNKNOWN (shadow, glare), and an UNKNOWN here blanks the user's face dot even
        // though the group itself is fine.
        java.util.Map<Integer, java.util.Map<CubeColor, Integer>> names = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        for (int i = 0; i < label.length; i++) {
            int id = label[i];
            if (id < 0) continue;
            count.put(id, count.getOrDefault(id, 0) + 1);
            names.computeIfAbsent(id, k -> new EnumMap<>(CubeColor.class))
                .merge(pool.get(i).center(), 1, Integer::sum);
        }
        for (int id : count.keySet()) {
            if (count.get(id) < MIN_LOOKS_PER_FACE) continue;
            CubeColor best = null;
            int bestCount = 0;
            for (java.util.Map.Entry<CubeColor, Integer> e : names.get(id).entrySet()) {
                if (e.getKey() == CubeColor.UNKNOWN) continue;
                if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
            }
            if (best != null) colors.add(best);
        }
        return colors;
    }

    private int smallestGroup(int[] label) {
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        for (int id : label) {
            if (id < 0) continue;
            count.put(id, count.getOrDefault(id, 0) + 1);
        }
        int smallest = Integer.MAX_VALUE;
        for (int size : count.values()) smallest = Math.min(smallest, size);
        return smallest;
    }
    public String lastFailure() { return lastFailure; }
    public List<FaceSample> observations() { return new ArrayList<>(pool); }

    public void clear() {
        pool.clear();
        lastGroupCount = 0;
        palette = null;
        lastFailure = "";
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
     * whole pool's colour spread. Real scans have a widest gap around 140 (white vs red) and a
     * red–orange gap around 39; 0.30 of 140 is 42 and merged red with orange. A floor of 32 joins
     * lighting variants (~20–28) without bridging red and orange.
     */
    private static final double SAME_FACE_SHARE = 0.20;
    /**
     * Floor so a pool that only contains red and orange does not split each colour in two.
     * Calibrated on the device pool: one face's looks vary by 2-7 Lab units, while this cube's
     * red-orange centres sit 34 apart — the old floor of 32 was one warm room away from fusing
     * them into a single group, which stalls the scan at five colours forever.
     */
    private static final double SAME_FACE_FLOOR = 24.0;
    /**
     * A look whose left/right or top/bottom Lab means differ by roughly this is shaped like a quad
     * sitting on an edge. Never used to reject a look outright — honest scrambled faces reach the
     * same values — but it ranks groups when the assembler must decide which face to distrust.
     */
    static final float STRADDLE_SPLIT = 52f;

    /**
     * How many distinct face colours the pool supports.
     *
     * <p>Extra groups are dropped, not force-merged. Force-merging a seventh junk cluster into six
     * is what glued red to orange and then reported 6/6 on an illegal colouring.
     */
    private int distinctFaceCount() {
        int[] label = clusteredLabels();
        if (label == null) return Math.min(pool.size(), countByEnum());
        return countGroups(label);
    }

    /** Labels after relative merge and dropping surplus weak groups. Null if Lab is incomplete. */
    private int[] clusteredLabels() {
        List<float[]> centers = centersWithLab();
        if (centers.size() != pool.size() || centers.isEmpty()) return null;
        int[] label = mergeRelatively(centers);
        return dropExtraGroups(label);
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

    /** Joins groups that are close relative to the pool's overall spread. */
    private static int[] mergeRelatively(List<float[]> points) {
        double widest = 0;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                widest = Math.max(widest, Lab.distance(points.get(i), points.get(j)));
            }
        }
        double cut = Math.max(SAME_FACE_FLOOR, widest * SAME_FACE_SHARE);

        int[] label = new int[points.size()];
        for (int i = 0; i < label.length; i++) label[i] = i;
        int groups = points.size();
        while (groups > 1) {
            double best = Double.MAX_VALUE;
            int mergeA = -1, mergeB = -1;
            for (int a = 0; a < label.length; a++) {
                if (label[a] < 0) continue;
                for (int b = a + 1; b < label.length; b++) {
                    if (label[b] < 0 || label[a] == label[b]) continue;
                    double spread = linkage(points, label, label[a], label[b]);
                    if (spread < best) { best = spread; mergeA = label[a]; mergeB = label[b]; }
                }
            }
            if (mergeA < 0 || best > cut) break;
            for (int i = 0; i < label.length; i++) if (label[i] == mergeB) label[i] = mergeA;
            groups--;
        }
        return label;
    }

    /**
     * Surplus clusters are discarded rather than merged. Merging the extra one was gluing red to
     * orange whenever a junk grey centre made a seventh group.
     */
    private int[] dropExtraGroups(int[] label) {
        while (countGroups(label) > 6) {
            int weak = weakestGroup(label);
            if (weak < 0) break;
            for (int i = 0; i < label.length; i++) if (label[i] == weak) label[i] = -1;
        }
        return label;
    }

    private int weakestGroup(int[] label) {
        java.util.Map<Integer, Double> score = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        for (int i = 0; i < label.length; i++) {
            int id = label[i];
            if (id < 0) continue;
            FaceSample face = pool.get(i);
            double add = face.confidence + (Lab.isStickerCenter(face.centerLab()) ? 100.0 : 0.0);
            score.put(id, score.getOrDefault(id, 0.0) + add);
            count.put(id, count.getOrDefault(id, 0) + 1);
        }
        int weak = -1;
        double worst = Double.MAX_VALUE;
        for (int id : score.keySet()) {
            double mean = score.get(id) / Math.max(1, count.get(id));
            if (mean < worst) { worst = mean; weak = id; }
        }
        return weak;
    }

    private static int countGroups(int[] label) {
        List<Integer> seen = new ArrayList<>();
        for (int value : label) {
            if (value < 0) continue;
            if (!seen.contains(value)) seen.add(value);
        }
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

    /**
     * Best-effort assembly. Six grouped faces are tried first; if they cannot form a legal cube
     * — usually because a straddling quad contaminated one colour — the noisiest face is dropped
     * and rebuilt from the other five, the same way a five-face scan infers the missing side.
     */
    public String assemble() {
        if (isComplete()) {
            String six = assembleLegalState();
            if (six != null) return six;
            return assembleByDroppingAFace();
        }
        return assembleFromFiveFaces();
    }

    public String assembleLegalState() {
        // The retry ladders below are bounded by this budget; without a cap the cluster
        // combinations can stall the solver thread for minutes on a hopeless pool.
        searchBudget = 120;
        List<float[]> centers = centersWithLab();
        if (centers.size() != pool.size() || pool.size() < 6) return assembleWithoutReadings();

        // Use the same grouping that reports progress. Force-splitting five real faces into six
        // invents a duplicate that can still pass the solver's parity check — a wrong cube.
        List<List<FaceSample>> raw = groupsOf(6);
        if (raw == null) {
            lastFailure = "分组不是 6 个独立面";
            return null;
        }

        // Looks that share a centre colour are not all the same face: a quad sitting on an edge
        // has a plausible centre and lands in that colour's bucket, then poisons the median.
        List<List<FaceSample>> groups = new ArrayList<>();
        for (List<FaceSample> group : raw) groups.add(largestCluster(group));

        FaceSample[] chosen = new FaceSample[6];
        for (int i = 0; i < 6; i++) chosen[i] = consensus(groups.get(i), -1);
        String state = tryAssemble(chosen);
        if (state != null) return state;

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

        // The largest cluster of a face can still be a repeated straddle. Try the runner-up too.
        state = tryClusterCombinations(raw);
        if (state != null) return state;

        lastFailure = "六面色块对不上魔方结构";
        return null;
    }

    /**
     * Drops the most internally-inconsistent colour group and infers that face from the rest.
     *
     * <p>Once the pool reports six colours, {@link #assembleFromFiveFaces()} refuses to run
     * because it demands exactly five groups. That is what made 6/6 scans stick while 5/6 ones
     * sometimes proceeded: the extra group was often a contaminated look, not a better cube.
     */
    private String assembleByDroppingAFace() {
        searchBudget = 120;
        List<List<FaceSample>> raw = groupsOf(6);
        if (raw == null) return null;
        List<List<FaceSample>> options = clusterChoices(raw);
        Integer[] order = {0, 1, 2, 3, 4, 5};
        java.util.Arrays.sort(order, (a, b) -> {
            int split = Float.compare(meanSplit(raw.get(b)), meanSplit(raw.get(a)));
            if (split != 0) return split;
            int wasteA = raw.get(a).size() - largestCluster(raw.get(a)).size();
            int wasteB = raw.get(b).size() - largestCluster(raw.get(b)).size();
            if (wasteA != wasteB) return Integer.compare(wasteB, wasteA);
            return Integer.compare(raw.get(b).size(), raw.get(a).size());
        });
        for (int drop : order) {
            List<List<FaceSample>> fiveOptions = new ArrayList<>();
            for (int i = 0; i < 6; i++) if (i != drop) fiveOptions.add(options.get(i));
            String state = cartesianFive(fiveOptions, 0, new FaceSample[5]);
            if (state != null) return state;
        }
        return null;
    }

    private String cartesianFive(List<List<FaceSample>> options, int index, FaceSample[] chosen) {
        if (index == options.size()) {
            if (searchBudget-- <= 0) return null;
            SixthFaceSolver.Result result = SixthFaceSolver.solve(chosen);
            if (result == null) return null;
            palette = result.palette;
            lastFailure = "";
            return result.state;
        }
        for (FaceSample pick : options.get(index)) {
            chosen[index] = pick;
            String state = cartesianFive(options, index + 1, chosen);
            if (state != null) return state;
        }
        return null;
    }

    private String tryClusterCombinations(List<List<FaceSample>> raw) {
        return cartesianAssemble(clusterChoices(raw), 0, new FaceSample[6]);
    }

    private static float meanSplit(List<FaceSample> group) {
        float total = 0;
        for (FaceSample look : group) total += look.spatialSplit();
        return group.isEmpty() ? 0f : total / group.size();
    }

    /** One consensus reading per consistent cluster of each colour group. */
    private static List<List<FaceSample>> clusterChoices(List<List<FaceSample>> raw) {
        List<List<FaceSample>> options = new ArrayList<>();
        for (List<FaceSample> group : raw) {
            List<List<FaceSample>> parts = partitionLooks(group);
            parts.sort(CubeStateAssembler::compareClusters);
            List<FaceSample> choices = new ArrayList<>();
            int limit = Math.min(4, parts.size());
            for (int i = 0; i < limit; i++) choices.add(consensus(parts.get(i), -1));
            options.add(choices);
        }
        return options;
    }

    private String cartesianAssemble(List<List<FaceSample>> options, int index, FaceSample[] chosen) {
        if (index == options.size()) {
            if (searchBudget-- <= 0) return null;
            return tryAssemble(chosen);
        }
        for (FaceSample pick : options.get(index)) {
            chosen[index] = pick;
            String state = cartesianAssemble(options, index + 1, chosen);
            if (state != null) return state;
        }
        return null;
    }

    /**
     * Splits looks that share a centre colour into clusters of actually similar faces.
     *
     * <p>Complete linkage at the same 32-unit cut the consensus close-filter uses: lighting
     * variants of one face stay together (~18–28), a straddling quad does not.
     */
    private static final float SAME_LOOK_CUT = 24f;

    static List<List<FaceSample>> partitionLooks(List<FaceSample> group) {
        List<List<FaceSample>> parts = new ArrayList<>();
        int n = group.size();
        if (n == 0) return parts;
        if (n == 1) {
            parts.add(new ArrayList<>(group));
            return parts;
        }
        float[][] dist = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                float d = meanCellDistance(group.get(i), alignTo(group.get(i), group.get(j)));
                dist[i][j] = dist[j][i] = d;
            }
        }
        int[] label = new int[n];
        for (int i = 0; i < n; i++) label[i] = i;
        int clusters = n;
        while (clusters > 1) {
            float best = Float.MAX_VALUE;
            int mergeA = -1, mergeB = -1;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (label[i] == label[j]) continue;
                    float link = 0f;
                    for (int p = 0; p < n; p++) {
                        if (label[p] != label[i]) continue;
                        for (int q = 0; q < n; q++) {
                            if (label[q] != label[j]) continue;
                            link = Math.max(link, dist[p][q]);
                        }
                    }
                    if (link < best) { best = link; mergeA = label[i]; mergeB = label[j]; }
                }
            }
            if (mergeA < 0 || best > SAME_LOOK_CUT) break;
            for (int i = 0; i < n; i++) if (label[i] == mergeB) label[i] = mergeA;
            clusters--;
        }
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = seen.indexOf(label[i]);
            if (index < 0) {
                seen.add(label[i]);
                parts.add(new ArrayList<>());
                index = parts.size() - 1;
            }
            parts.get(index).add(group.get(i));
        }
        return parts;
    }

    static List<FaceSample> largestCluster(List<FaceSample> group) {
        List<List<FaceSample>> parts = partitionLooks(group);
        if (parts.isEmpty()) return group;
        parts.sort(CubeStateAssembler::compareClusters);
        return parts.get(0);
    }

    private static int compareClusters(List<FaceSample> a, List<FaceSample> b) {
        int bySize = Integer.compare(b.size(), a.size());
        if (bySize != 0) return bySize;
        return Integer.compare(reliableCells(b), reliableCells(a));
    }

    private static int reliableCells(List<FaceSample> looks) {
        int n = 0;
        for (FaceSample look : looks) n += 9 - look.unreliableCount();
        return n;
    }

    /**
     * Solves from five distinct faces by deriving the sixth from the cube's structure.
     *
     * <p>The face nobody scanned is not free-form: once the five centres name five colours, the
     * sixth colour is whatever is left, the nine-per-colour rule fixes how many of each colour
     * the unseen face holds, and piece uniqueness places them. This is what lets a scan succeed
     * when one face — usually the bottom — is too awkward to present cleanly.
     */
    public String assembleFromFiveFaces() {
        searchBudget = 120;
        List<List<FaceSample>> groups = groupsOf(5);
        if (groups == null) {
            lastFailure = "还没有 5 个独立面";
            return null;
        }
        for (int i = 0; i < groups.size(); i++) groups.set(i, largestCluster(groups.get(i)));

        FaceSample[] chosen = new FaceSample[5];
        for (int i = 0; i < 5; i++) chosen[i] = consensus(groups.get(i), -1);
        SixthFaceSolver.Result result = SixthFaceSolver.solve(chosen);
        if (result != null) {
            palette = result.palette;
            return result.state;
        }

        FaceSample[] trimmed = new FaceSample[5];
        for (int i = 0; i < 5; i++) {
            List<FaceSample> group = groups.get(i);
            trimmed[i] = group.size() < 2 ? chosen[i]
                : consensus(group, byDisagreement(group).get(0));
        }
        result = SixthFaceSolver.solve(trimmed);
        if (result != null) {
            palette = result.palette;
            return result.state;
        }

        for (int face = 0; face < 5; face++) {
            List<FaceSample> group = groups.get(face);
            if (group.size() < 2) continue;
            FaceSample original = chosen[face];
            List<Integer> worstFirst = byDisagreement(group);
            for (int attempt = 0; attempt < Math.min(2, worstFirst.size()); attempt++) {
                chosen[face] = consensus(group, worstFirst.get(attempt));
                result = SixthFaceSolver.solve(chosen);
                if (result != null) {
                    palette = result.palette;
                    lastFailure = "";
                    return result.state;
                }
            }
            chosen[face] = original;
        }
        lastFailure = "五面推算不出第六面，色块读数有歧义";
        return null;
    }

    /**
     * Observations clustered by the same relative merge that reports {@link #size()}.
     *
     * @return {@code expected} groups, or null when the pool does not support that many colours
     */
    private List<List<FaceSample>> groupsOf(int expected) {
        int[] label = clusteredLabels();
        if (label == null || countGroups(label) != expected) return null;
        List<List<FaceSample>> groups = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < label.length; i++) {
            if (label[i] < 0) continue;
            int index = seen.indexOf(label[i]);
            if (index < 0) {
                seen.add(label[i]);
                groups.add(new ArrayList<>());
                index = groups.size() - 1;
            }
            groups.get(index).add(pool.get(i));
        }
        return groups;
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
    static FaceSample consensus(List<FaceSample> group, int skip) {
        FaceSample reference = mostCentral(group, skip);
        if (reference == null) return null;

        List<FaceSample> aligned = new ArrayList<>();
        List<FaceSample> close = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            if (i == skip) continue;
            FaceSample candidate = alignTo(reference, group.get(i));
            aligned.add(candidate);
            if (meanCellDistance(reference, candidate) <= SAME_LOOK_CUT) close.add(candidate);
        }
        // A straddling quad scores high confidence, so it must not be allowed to outvote
        // several honest looks. Drop looks that do not match the group's centre of mass.
        if (close.size() >= 2 && close.size() < aligned.size()) aligned = close;
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
    /** The look nearest the others, not the highest-confidence one — straddles score 1.0. */
    private static FaceSample mostCentral(List<FaceSample> group, int skip) {
        FaceSample best = null;
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i < group.size(); i++) {
            if (i == skip) continue;
            double cost = 0;
            int n = 0;
            FaceSample pivot = group.get(i);
            for (int j = 0; j < group.size(); j++) {
                if (j == skip || j == i) continue;
                cost += meanCellDistance(pivot, alignTo(pivot, group.get(j)));
                n++;
            }
            double mean = n == 0 ? 0 : cost / n;
            if (mean < lowest) { lowest = mean; best = pivot; }
        }
        return best;
    }

    private static float meanCellDistance(FaceSample a, FaceSample b) {
        float total = 0;
        int n = 0;
        for (int cell = 0; cell < 9; cell++) {
            if (a.lab == null || b.lab == null) continue;
            if (!a.reliable[cell] || !b.reliable[cell]) continue;
            total += Lab.distance(a.lab[cell], b.lab[cell]);
            n++;
        }
        return n == 0 ? 999f : total / n;
    }

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
