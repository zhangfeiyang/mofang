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
    /**
     * Observations ever accepted, eviction notwithstanding. The pool's size stops at
     * {@link #POOL_LIMIT}, so it cannot tell a retry loop whether anything changed: once full, a
     * failed attempt was never repeated and the scan sat on its stale refusal for good.
     */
    private int accepted;
    private int lastGroupCount;
    private ScanPalette palette;
    private String lastFailure = "";
    /** See {@link #suspect()}. */
    private CubeColor suspect;
    /** Remaining attempts across the retry ladders of one {@link #assemble()} call. */
    private int searchBudget;
    /**
     * When the current {@link #assemble()} gives up, in {@link System#nanoTime()} terms. A pool
     * whose readings contradict each other used to grind for seconds per attempt — and the next,
     * better attempt waited behind it; attempts repeat as looks accumulate, so a bounded refusal
     * is cheap.
     */
    private long deadlineNanos = Long.MAX_VALUE;
    static final long SIX_FACE_BUDGET_NANOS = 1_500_000_000L;

    private boolean expired() { return System.nanoTime() > deadlineNanos; }

    private boolean timedOut;

    /** True when the last {@link #assemble()} ran out of time rather than proving a contradiction. */
    public boolean lastAttemptTimedOut() { return timedOut; }
    /**
     * Grouping of the current pool, or null when the pool changed since it was computed. The
     * clustering is cubic in the pool size and the UI asks for the established colours on every
     * frame, so recomputing it per call put tens of milliseconds on the main thread.
     */
    private int[] labelCache;

    /**
     * An independent assembler over the same observations.
     *
     * <p>Assembly runs for seconds on a worker thread while scanning keeps adding observations on
     * the main thread; it must work on a snapshot, never on the live pool.
     */
    public CubeStateAssembler copy() {
        CubeStateAssembler copy = new CubeStateAssembler();
        copy.pool.addAll(pool);
        copy.accepted = accepted;
        copy.lastGroupCount = lastGroupCount;
        copy.palette = palette;
        copy.lastFailure = lastFailure;
        return copy;
    }

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
        accepted++;
        labelCache = null;
        namedCache = null;
        if (pool.size() > POOL_LIMIT) evict();
        int groups = distinctFaceCount();
        boolean grew = groups > lastGroupCount;
        lastGroupCount = groups;
        return grew;
    }

    /**
     * Makes room by dropping the least valuable observation, not the oldest one.
     *
     * <p>Plain FIFO eviction was a trap: once the pool filled during a long scan it discarded the
     * first faces' only honest looks, their colours silently vanished, and the scan regressed to
     * five groups fed with whatever remained. Value order: looks the clusterer already dropped,
     * then unconfirmed single looks (a straddle seen once, never again), then the oldest look of
     * the largest group — so thin but real groups always survive. The newest look is never the
     * victim: it may be the first sight of the face the user is showing right now.
     */
    private void evict() {
        int[] label = clusteredLabels();
        int newest = pool.size() - 1;
        int victim = -1;
        if (label != null) {
            int[] size = groupSizes(label);
            for (int i = 0; i < newest && victim < 0; i++) if (label[i] < 0) victim = i;
            for (int i = 0; i < newest && victim < 0; i++) {
                if (size[label[i]] < MIN_LOOKS_PER_FACE) victim = i;
            }
            if (victim < 0) {
                int largest = -1;
                for (int id = 0; id < size.length; id++) {
                    if (largest < 0 || size[id] > size[largest]) largest = id;
                }
                for (int i = 0; i < newest && victim < 0; i++) if (label[i] == largest) victim = i;
            }
        }
        pool.remove(victim < 0 ? 0 : victim);
        labelCache = null;
        namedCache = null;
    }

    /** Members per group id; ids are point indices, so the array spans the pool. */
    private static int[] groupSizes(int[] label) {
        int[] size = new int[label.length];
        for (int id : label) if (id >= 0) size[id]++;
        return size;
    }

    /**
     * Faces collected so far: colour groups backed by at least two looks.
     *
     * <p>A colour seen exactly once is not a collected face: its single look may be a straddle
     * or a fluke. Counting it let one junk look occupy a face slot — the scan read "6 faces"
     * with a colour never shown, and the five-face inference refused to run because the pool
     * held six groups. Counting only confirmed groups keeps the number honest and turns the
     * failure mode into "that colour's dot stays unlit": the user knows which face to show again.
     */
    public int size() { return lastGroupCount; }

    /** True when six colour groups exist and each holds at least two looks. */
    public boolean isComplete() {
        return confirmedGroups() == 6;
    }

    /** Groups with enough looks to count as a collected face, capped at six by the clusterer. */
    private int confirmedGroups() {
        int[] label = clusteredLabels();
        if (label == null) return pool.isEmpty() ? 0 : Math.min(6, countByEnum());
        int confirmed = 0;
        for (int size : groupSizes(label)) if (size >= MIN_LOOKS_PER_FACE) confirmed++;
        return confirmed;
    }

    /** Colours of the collected faces, named one-to-one like the solver names them. */
    public Set<CubeColor> establishedColors() {
        Set<CubeColor> colors = EnumSet.noneOf(CubeColor.class);
        for (NamedGroup group : namedGroups()) colors.add(group.name);
        return colors;
    }

    /**
     * What each collected face looks like, for the scan map: one representative look per
     * collected face, every readable patch named against the collected centres.
     *
     * @return face letter (URFDLB) to nine ARGB colours, 0 where a patch is unreadable
     */
    public java.util.Map<Character, int[]> preview() {
        List<NamedGroup> groups = namedGroups();
        java.util.Map<Character, int[]> out = new java.util.HashMap<>();
        for (NamedGroup group : groups) {
            FaceSample look = group.representative;
            int[] argb = new int[9];
            for (int cell = 0; cell < 9; cell++) {
                if (look.lab == null || !look.reliable[cell]) continue;
                CubeColor best = null;
                double nearest = Double.MAX_VALUE;
                for (NamedGroup other : groups) {
                    double d = Lab.distance(look.lab[cell], other.representative.centerLab());
                    if (d < nearest) { nearest = d; best = other.name; }
                }
                // A colour whose face is not collected yet sits far from every centre: show the
                // provisional guess rather than forcing it onto a collected colour.
                if (nearest > 30 && look.stickers[cell] != CubeColor.UNKNOWN) best = look.stickers[cell];
                argb[cell] = best == null ? 0 : best.argb;
            }
            argb[4] = group.name.argb;
            out.put(group.name.face, argb);
        }
        return out;
    }

    private static final class NamedGroup {
        final CubeColor name;
        final FaceSample representative;

        NamedGroup(CubeColor name, FaceSample representative) {
            this.name = name;
            this.representative = representative;
        }
    }

    private List<NamedGroup> namedCache;

    /**
     * Collected faces with one-to-one colour names.
     *
     * <p>Naming each group by its looks' majority threshold colour could call two groups red —
     * red and orange differ by a few degrees of hue — so groups are named the way the solver
     * names prototypes: a minimum-cost assignment of distinct canonical colours.
     */
    private List<NamedGroup> namedGroups() {
        if (namedCache != null && labelCache != null) return namedCache;
        List<NamedGroup> named = new ArrayList<>();
        int[] label = clusteredLabels();
        if (label == null) return named;
        int[] size = groupSizes(label);
        List<FaceSample> representatives = new ArrayList<>();
        for (int id = 0; id < label.length; id++) {
            if (size[id] < MIN_LOOKS_PER_FACE) continue;
            List<FaceSample> group = new ArrayList<>();
            for (int i = 0; i < label.length; i++) if (label[i] == id) group.add(pool.get(i));
            FaceSample central = mostCentral(largestCluster(group), -1);
            if (central != null) representatives.add(central);
        }
        if (representatives.isEmpty()) {
            namedCache = named;
            return named;
        }
        float[][] prototypes = new float[representatives.size()][];
        for (int i = 0; i < prototypes.length; i++) prototypes[i] = representatives.get(i).centerLab();
        CubeColor[] names = ColorAssignment.namePrototypes(prototypes, 0);
        if (names == null) return named;
        for (int i = 0; i < names.length; i++) named.add(new NamedGroup(names[i], representatives.get(i)));
        namedCache = named;
        return named;
    }

    public String lastFailure() { return lastFailure; }
    public List<FaceSample> observations() { return new ArrayList<>(pool); }
    /** Grows with every accepted observation, even once the pool is full and evicting. */
    public int accepted() { return accepted; }

    public void clear() {
        pool.clear();
        accepted = 0;
        labelCache = null;
        namedCache = null;
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
        return confirmedGroups();
    }

    /**
     * Labels after relative merge and dropping surplus weak groups. Null if Lab is incomplete.
     * Cached until the pool changes; callers must treat the array as read-only.
     */
    private int[] clusteredLabels() {
        if (labelCache != null) return labelCache;
        List<float[]> centers = centersWithLab();
        if (centers.size() != pool.size() || centers.isEmpty()) return null;
        int[] label = mergeRelatively(centers);
        labelCache = dropExtraGroups(label);
        return labelCache;
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

    /**
     * Joins groups that are close relative to the pool's overall spread.
     *
     * <p>Complete linkage on a cluster distance matrix: merging A and B makes their distance to
     * every C the larger of the two, so each merge costs O(n) updates instead of a full rescan.
     * Labels are point indices of each cluster's lowest member, as before.
     */
    private static int[] mergeRelatively(List<float[]> points) {
        int n = points.size();
        double[][] distance = new double[n][n];
        double widest = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Lab.distance(points.get(i), points.get(j));
                distance[i][j] = distance[j][i] = d;
                widest = Math.max(widest, d);
            }
        }
        double cut = Math.max(SAME_FACE_FLOOR, widest * SAME_FACE_SHARE);
        int[] label = new int[n];
        boolean[] alive = new boolean[n];
        for (int i = 0; i < n; i++) { label[i] = i; alive[i] = true; }
        int groups = n;
        while (groups > 1) {
            double best = Double.MAX_VALUE;
            int a = -1, b = -1;
            for (int i = 0; i < n; i++) {
                if (!alive[i]) continue;
                for (int j = i + 1; j < n; j++) {
                    if (alive[j] && distance[i][j] < best) { best = distance[i][j]; a = i; b = j; }
                }
            }
            if (a < 0 || best > cut) break;
            alive[b] = false;
            for (int k = 0; k < n; k++) {
                if (!alive[k] || k == a) continue;
                double merged = Math.max(distance[a][k], distance[b][k]);
                distance[a][k] = distance[k][a] = merged;
            }
            for (int i = 0; i < n; i++) if (label[i] == b) label[i] = a;
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

    /**
     * The group to give up when there are more than six: the one with the fewest looks.
     *
     * <p>This used to rank by mean confidence, and a straddling quad reads nine flat stickers, so
     * it outscores honest looks: a single straddle survived while a real face with seven looks was
     * dropped and its colour vanished from the scan. How often a reading recurs is what separates
     * a face from a fluke; confidence only breaks ties, then recency.
     */
    private int weakestGroup(int[] label) {
        int[] size = groupSizes(label);
        double[] score = new double[label.length];
        int[] newest = new int[label.length];
        for (int i = 0; i < label.length; i++) {
            int id = label[i];
            if (id < 0) continue;
            FaceSample face = pool.get(i);
            score[id] += face.confidence + (Lab.isStickerCenter(face.centerLab()) ? 100.0 : 0.0);
            newest[id] = i;
        }
        int weak = -1;
        for (int id = 0; id < label.length; id++) {
            if (size[id] == 0) continue;
            if (weak < 0 || size[id] < size[weak]) { weak = id; continue; }
            if (size[id] > size[weak]) continue;
            double mean = score[id] / size[id], weakMean = score[weak] / size[weak];
            if (mean < weakMean || (mean == weakMean && newest[id] < newest[weak])) weak = id;
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

    /**
     * Assembles the cube from six scanned faces, or returns null with {@link #lastFailure()} set.
     *
     * <p>Five faces are never enough. Inferring the sixth used to rescue scans whose six faces
     * contradicted each other, but the contradiction is a misread sticker, and a misread face
     * passed to the inference produced a legal cube that was not the one in the hands: a phone
     * scan swapped one red and one orange sticker, the inference rebuilt yellow from the other
     * five, and the solve went wrong at step 15. A six-face scan whose readings cannot be
     * repaired asks for a face to be shown again instead ({@link #suspect()}).
     */
    public String assemble() {
        suspect = null;
        if (!isComplete()) {
            lastFailure = "还没有六个面";
            return null;
        }
        deadlineNanos = System.nanoTime() + SIX_FACE_BUDGET_NANOS;
        timedOut = false;
        try {
            String six = assembleLegalState();
            if (six == null && expired()) {
                timedOut = true;
                lastFailure = "核对超时，继续采集后会再试";
            }
            return six;
        } finally {
            deadlineNanos = Long.MAX_VALUE;
        }
    }

    /**
     * After a failed {@link #assemble()}: the colour of the face whose readings are most in doubt,
     * to be shown to the camera again, or null when no face stands out.
     */
    public CubeColor suspect() { return suspect; }

    /** Forgets every look at the face with this centre colour, so it is read afresh. */
    public void forget(CubeColor color) {
        int[] label = clusteredLabels();
        List<FaceSample> keep = new ArrayList<>();
        List<NamedGroup> named = namedGroups();
        FaceSample representative = null;
        for (NamedGroup group : named) if (group.name == color) representative = group.representative;
        for (int i = 0; i < pool.size(); i++) {
            boolean same = representative != null && label != null && label[i] >= 0
                && label[i] == labelOf(representative, label);
            if (!same) keep.add(pool.get(i));
        }
        if (keep.size() == pool.size()) return;
        pool.clear();
        pool.addAll(keep);
        labelCache = null;
        namedCache = null;
        lastGroupCount = distinctFaceCount();
    }

    private int labelOf(FaceSample look, int[] label) {
        for (int i = 0; i < pool.size(); i++) if (pool.get(i) == look) return label[i];
        return Integer.MIN_VALUE;
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

        for (int i = 0; i < 6; i++) chosen[i] = consensus(groups.get(i), -1);
        state = repairBySwaps(chosen);
        if (state != null) return state;

        lastFailure = suspect == null ? "六面色块对不上魔方结构"
            : "有色块读得不准，请把" + suspect.chinese + "色中心那面再正对镜头看一眼";
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
            if (searchBudget-- <= 0 || expired()) return null;
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
        SixthFaceSolver.Result result = SixthFaceSolver.solve(chosen, deadlineNanos);
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
        result = SixthFaceSolver.solve(trimmed, deadlineNanos);
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
                if (expired()) return null;
                chosen[face] = consensus(group, worstFirst.get(attempt));
                result = SixthFaceSolver.solve(chosen, deadlineNanos);
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
     * The {@code expected} largest colour groups.
     *
     * <p>Unconfirmed single looks beyond those are ignored rather than allowed to veto the
     * assembly — one stray look used to block the five-face inference outright. A confirmed
     * extra group is different: then the pool really shows more faces than asked for.
     *
     * @return {@code expected} groups, or null when the pool does not support that many colours
     */
    private List<List<FaceSample>> groupsOf(int expected) {
        int[] label = clusteredLabels();
        if (label == null) return null;
        int[] size = groupSizes(label);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < label.length; i++) {
            if (label[i] >= 0 && !ids.contains(label[i])) ids.add(label[i]);
        }
        if (ids.size() < expected) return null;
        // Stable: equal sizes keep first-seen order.
        ids.sort((a, b) -> Integer.compare(size[b], size[a]));
        for (int k = expected; k < ids.size(); k++) {
            if (size[ids.get(k)] >= MIN_LOOKS_PER_FACE) return null;
        }
        List<List<FaceSample>> groups = new ArrayList<>();
        for (int k = 0; k < expected; k++) {
            List<FaceSample> members = new ArrayList<>();
            for (int i = 0; i < label.length; i++) if (label[i] == ids.get(k)) members.add(pool.get(i));
            groups.add(members);
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
            if (expired()) return null;
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

    /** Single swaps tried, cheapest first, before pairs of swaps. */
    private static final int SWAP_CANDIDATES = 160;
    /** Cheapest swaps combined into pairs of swaps. */
    private static final int DOUBLE_SWAP_BASE = 40;
    /**
     * A repair is accepted only when the next legal one costs this many times more: two repairs
     * of similar cost mean the readings cannot tell the cubes apart, and guessing is how a wrong
     * cube reaches the solver. Exchanging two well-read stickers costs about twice what undoing a
     * red and an orange read fully the wrong way round does, so the margin must stay below two.
     */
    private static final double REPAIR_MARGIN = 1.8;

    /**
     * Six faces whose colouring is illegal, repaired by exchanging colours between stickers.
     *
     * <p>Nine stickers per colour is enforced, so a misread never comes alone: a red read as orange
     * pushes some orange onto red. Measured on the phone, red and orange swapped on two faces and
     * the cube failed its check with the counts intact. Exchanging the colours of the two
     * stickers that sit closest to each other's colour undoes exactly that, and a random exchange
     * almost never yields a legal cube, so a legal result that clearly beats every other is the
     * real one. When none does, {@link #suspect} names the face holding the least certain sticker.
     */
    private String repairBySwaps(FaceSample[] chosen) {
        ColorAssignment.Result result = ColorAssignment.assign(chosen, 0);
        if (result == null) return null;
        float[][] prototypeOf = new float[CubeColor.values().length][];
        for (int f = 0; f < 6; f++) prototypeOf[result.faceColors[f].ordinal()] = result.prototypes[f];

        // Cost of naming sticker i colour c, relative to its current colour.
        int[] cells = new int[48];
        int n = 0;
        for (int f = 0; f < 6; f++) for (int c = 0; c < 9; c++) if (c != 4) cells[n++] = f * 9 + c;
        double[][] extra = new double[48][CubeColor.values().length];
        double leastMargin = Double.MAX_VALUE;
        int leastCertain = -1;
        for (int i = 0; i < 48; i++) {
            int f = cells[i] / 9, c = cells[i] % 9;
            CubeColor own = result.colors[f][c];
            boolean evidence = chosen[f].reliable[c];
            double base = evidence ? Lab.distanceSquared(chosen[f].lab[c], prototypeOf[own.ordinal()]) : 0;
            for (int k = 0; k < 6; k++) {
                CubeColor other = result.faceColors[k];
                double cost = evidence ? Lab.distanceSquared(chosen[f].lab[c], result.prototypes[k]) : 0;
                extra[i][other.ordinal()] = cost - base;
                if (evidence && other != own && cost - base < leastMargin) {
                    leastMargin = cost - base;
                    leastCertain = f;
                }
            }
        }
        suspect = leastCertain < 0 ? null : result.faceColors[leastCertain];

        List<double[]> swaps = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            CubeColor a = result.colors[cells[i] / 9][cells[i] % 9];
            for (int j = i + 1; j < 48; j++) {
                CubeColor b = result.colors[cells[j] / 9][cells[j] % 9];
                if (a == b) continue;
                swaps.add(new double[]{extra[i][b.ordinal()] + extra[j][a.ordinal()], i, j});
            }
        }
        swaps.sort((x, y) -> Double.compare(x[0], y[0]));

        List<int[]> tries = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        for (int k = 0; k < Math.min(SWAP_CANDIDATES, swaps.size()); k++) {
            tries.add(new int[]{(int) swaps.get(k)[1], (int) swaps.get(k)[2]});
            costs.add(swaps.get(k)[0]);
        }
        String state = bestRepair(chosen, result, cells, tries, costs);
        // An ambiguous single swap must not fall through to pairs: a pair that is the only legal
        // one at its own cost is still a guess when a cheaper rival already exists.
        if (state != null || ambiguous || expired()) return state;

        // Two independent slips: pairs of the cheapest swaps that share no sticker.
        List<double[]> doubles = new ArrayList<>();
        int base = Math.min(DOUBLE_SWAP_BASE, swaps.size());
        for (int x = 0; x < base; x++) {
            for (int y = x + 1; y < base; y++) {
                double[] p = swaps.get(x), q = swaps.get(y);
                if (p[1] == q[1] || p[1] == q[2] || p[2] == q[1] || p[2] == q[2]) continue;
                doubles.add(new double[]{p[0] + q[0], x, y});
            }
        }
        doubles.sort((x, y) -> Double.compare(x[0], y[0]));
        tries.clear();
        costs.clear();
        for (double[] d : doubles) {
            double[] p = swaps.get((int) d[1]), q = swaps.get((int) d[2]);
            tries.add(new int[]{(int) p[1], (int) p[2], (int) q[1], (int) q[2]});
            costs.add(d[0]);
        }
        return bestRepair(chosen, result, cells, tries, costs);
    }

    /** Set by {@link #bestRepair} when two legal repairs cost about the same. */
    private boolean ambiguous;

    /** The cheapest legal repair among {@code tries}, if it clearly beats the next legal one. */
    private String bestRepair(FaceSample[] chosen, ColorAssignment.Result result, int[] cells,
                              List<int[]> tries, List<Double> costs) {
        ambiguous = false;
        String best = null;
        double bestCost = 0;
        for (int t = 0; t < tries.size(); t++) {
            if (expired()) return null;
            double cost = costs.get(t);
            if (best != null && cost > Math.max(bestCost, 1.0) * REPAIR_MARGIN) break;
            CubeColor[][] colors = new CubeColor[6][];
            for (int f = 0; f < 6; f++) colors[f] = result.colors[f].clone();
            int[] swap = tries.get(t);
            for (int k = 0; k < swap.length; k += 2) {
                int a = cells[swap[k]], b = cells[swap[k + 1]];
                CubeColor held = colors[a / 9][a % 9];
                colors[a / 9][a % 9] = colors[b / 9][b % 9];
                colors[b / 9][b % 9] = held;
            }
            FaceSample[] resolved = new FaceSample[6];
            for (int f = 0; f < 6; f++) {
                resolved[f] = new FaceSample(colors[f], chosen[f].lab, chosen[f].reliable,
                    chosen[f].confidence);
            }
            String state = orderAndSearch(resolved);
            if (state == null || state.equals(best)) continue;
            if (best != null) {
                ambiguous = true;
                return null;
            }
            best = state;
            bestCost = cost;
        }
        if (best != null) {
            palette = ScanPalette.from(result);
            suspect = null;
        }
        return best;
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
        return searchRotations(ordered, 0, new StringBuilder(54), new boolean[64], new boolean[64]);
    }

    /** Sorts the six resolved faces into URFDLB order, then searches each face's camera roll. */
    private String orderAndSearch(FaceSample[] resolved) {
        FaceSample[] ordered = new FaceSample[6];
        for (int i = 0; i < ORDER.length; i++) {
            for (FaceSample face : resolved) {
                if (face.center().face == ORDER[i]) { ordered[i] = face; break; }
            }
            if (ordered[i] == null) return null;
        }
        return searchRotations(ordered, 0, new StringBuilder(54), new boolean[64], new boolean[64]);
    }

    /** Pieces whose last facelet belongs to face k, so they are complete once k is placed. */
    private static final int[][] EDGES_DONE_AT = piecesDoneAt(CubeRules.EDGES);
    private static final int[][] CORNERS_DONE_AT = piecesDoneAt(CubeRules.CORNERS);

    private static int[][] piecesDoneAt(int[][] pieces) {
        List<List<Integer>> byFace = new ArrayList<>();
        for (int k = 0; k < 6; k++) byFace.add(new ArrayList<>());
        for (int p = 0; p < pieces.length; p++) {
            int last = 0;
            for (int facelet : pieces[p]) last = Math.max(last, facelet / 9);
            byFace.get(last).add(p);
        }
        int[][] out = new int[6][];
        for (int k = 0; k < 6; k++) {
            out[k] = new int[byFace.get(k).size()];
            for (int i = 0; i < out[k].length; i++) out[k][i] = byFace.get(k).get(i);
        }
        return out;
    }

    /**
     * Depth-first over the faces' rolls, rejecting a partial cube the moment a completed edge or
     * corner is impossible or repeats an earlier piece. A wrong roll almost always breaks the
     * first piece it completes, so this visits a handful of branches where brute force ran the
     * full verification on all 4^6 combinations.
     */
    private String searchRotations(FaceSample[] faces, int index, StringBuilder state,
                                   boolean[] usedEdges, boolean[] usedCorners) {
        if (index == faces.length) {
            String candidate = state.toString();
            if (!CubeRules.piecesArePlausible(candidate)) return null;
            return Tools.verify(candidate) == 0 ? candidate : null;
        }
        FaceSample rotated = faces[index];
        for (int turn = 0; turn < 4; turn++) {
            int oldLength = state.length();
            for (CubeColor color : rotated.stickers) state.append(color.face);
            int edgesTaken = claim(state, CubeRules.EDGES, EDGES_DONE_AT[index], usedEdges);
            int cornersTaken = edgesTaken < 0 ? -1
                : claim(state, CubeRules.CORNERS, CORNERS_DONE_AT[index], usedCorners);
            if (edgesTaken >= 0 && cornersTaken >= 0) {
                String found = searchRotations(faces, index + 1, state, usedEdges, usedCorners);
                if (found != null) return found;
            }
            release(state, CubeRules.EDGES, EDGES_DONE_AT[index], usedEdges, edgesTaken);
            release(state, CubeRules.CORNERS, CORNERS_DONE_AT[index], usedCorners, cornersTaken);
            state.setLength(oldLength);
            rotated = rotated.rotateClockwise();
        }
        return null;
    }

    /**
     * Marks the newly completed pieces as used. Returns how many were claimed, or -1 (with
     * nothing left claimed) when one of them is impossible or already taken.
     */
    private static int claim(CharSequence state, int[][] pieces, int[] completed, boolean[] used) {
        int claimed = 0;
        for (int p : completed) {
            int key = pieceKey(state, pieces[p]);
            if (key < 0 || used[key]) {
                for (int q = 0; q < claimed; q++) used[pieceKey(state, pieces[completed[q]])] = false;
                return -1;
            }
            used[key] = true;
            claimed++;
        }
        return claimed;
    }

    private static void release(CharSequence state, int[][] pieces, int[] completed, boolean[] used,
                                int claimed) {
        for (int q = 0; q < claimed; q++) used[pieceKey(state, pieces[completed[q]])] = false;
    }

    /** Bitmask of the piece's face letters, or -1 when no real piece shows those colours. */
    private static int pieceKey(CharSequence state, int[] facelets) {
        int mask = 0;
        for (int facelet : facelets) {
            int bit = "URFDLB".indexOf(state.charAt(facelet));
            if (bit < 0 || (mask & (1 << bit)) != 0) return -1;
            mask |= 1 << bit;
        }
        // Opposite faces (U/D, R/L, F/B) never share a piece.
        if ((mask & 0b001001) == 0b001001 || (mask & 0b010010) == 0b010010 || (mask & 0b100100) == 0b100100) {
            return -1;
        }
        return mask;
    }
}
