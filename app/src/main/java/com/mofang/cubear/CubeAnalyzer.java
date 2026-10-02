package com.mofang.cubear;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;

/**
 * Turns CameraX frames into detected faces: coarse corners, lattice refinement, sticker readings.
 *
 * <p>The network (or, when it is blind, the anchor search) only says roughly where the face
 * is. {@link FaceRefiner} then snaps that estimate onto the stickers actually visible, and the
 * nine readings are taken from the refined lattice. Coarse corners are still reported when the
 * refiner cannot anchor, flagged as such, so tracking never goes blind while capture can choose
 * to wait for a trustworthy view.
 */
public final class CubeAnalyzer implements ImageAnalysis.Analyzer {
    public interface Listener { void onDetection(DetectedFace face); }

    /**
     * Shortest interval between analysed frames. Faster than the camera's own 30 fps buys
     * nothing; the floor exists so a slow device keeps the UI thread and the solver breathing.
     */
    private static final long MIN_FRAME_NANOS = 45_000_000L;
    /**
     * Consecutive frames the network has declined before the anchor search may run.
     *
     * <p>The search costs several refinements, so running it on every declined frame burns CPU
     * exactly when the device is already busy and buys nothing: {@link DetectionTracker} bridges
     * short gaps anyway. Three declines mean the network is genuinely blind to this view, which is
     * the case the search exists for.
     */
    private static final int FALLBACK_AFTER_DECLINES = 3;
    /** Frames a refined lattice stays usable as the starting point for the next refinement. */
    private static final int LATTICE_MEMORY = 2;

    private final Listener listener;
    /**
     * Null until loaded, or when it could not be, in which case the anchor search runs alone. Set
     * on the analysis thread itself, so no frame sees a half-initialised model.
     */
    private CubeFaceModel model;
    private final DetectionDump dump;
    private final CornerSmoother smoother = new CornerSmoother();
    private final FaceRefiner refiner = new FaceRefiner();
    private final AnchorSearch anchors = new AnchorSearch(refiner);
    private long lastAnalysisNanos;
    private byte[] packedRgba = new byte[0];
    private int modelDeclines;
    private boolean loggedSize;
    private int framesSinceLog;
    private long windowStartNanos;
    private long refineNanos;
    private int refined, coarse;
    /** Last refined lattice and how many analysed frames ago it was found. */
    private double[] lastLattice;
    private int latticeAge = Integer.MAX_VALUE;

    public CubeAnalyzer(Listener listener) { this(listener, null, null); }

    public CubeAnalyzer(Listener listener, CubeFaceModel model) {
        this(listener, model, null);
    }

    public CubeAnalyzer(Listener listener, CubeFaceModel model, DetectionDump dump) {
        this.listener = listener;
        this.model = model;
        this.dump = dump;
    }

    /** Must be called on the analysis executor. */
    void setModel(CubeFaceModel model) {
        this.model = model;
        modelDeclines = 0;
    }

    @Override public void analyze(@NonNull ImageProxy image) {
        try {
            long now = System.nanoTime();
            if (now - lastAnalysisNanos < MIN_FRAME_NANOS) return;
            if (image.getPlanes().length == 0) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            int width = image.getWidth(), height = image.getHeight();
            int required = width * height * 4;
            if (packedRgba.length != required) packedRgba = new byte[required];
            ByteBuffer source = plane.getBuffer().duplicate();
            if (plane.getPixelStride() == 4 && plane.getRowStride() == width * 4) {
                // Contiguous rows: the whole buffer can be lifted in one copy.
                source.get(packedRgba, 0, required);
            } else {
                packRows(plane.getBuffer(), packedRgba, width, height,
                    plane.getRowStride(), plane.getPixelStride());
            }
            process(packedRgba, width, height);
        } finally {
            image.close();
        }
    }

    /**
     * Analyses one packed RGBA frame. The camera path calls this for every analysed frame; the
     * debug replay feeds decoded video frames through it. Analysis thread only.
     */
    void process(byte[] rgba, int width, int height) {
        long now = System.nanoTime();
        heartbeat(now);
        lastAnalysisNanos = now;
        // OUTPUT_IMAGE_FORMAT_RGBA_8888 hands back R,G,B,A in that byte order, which is what
        // every stage here reads, so the buffer is used as-is. An earlier channel remap assumed
        // A,R,G,B and left the blue channel holding a constant alpha.
        packedRgba = rgba;
        if (!loggedSize) {
            loggedSize = true;
            if (dump != null) dump.log("analysis_frame " + width + "x" + height);
        }
        listener.onDetection(detect(width, height));
    }

    private DetectedFace detect(int width, int height) {
        byte[] rgba = packedRgba;
        float score;
        double[] coarseQuad;
        CubeFaceModel.DebugResult evaluated = model == null ? null : model.evaluate(rgba, width, height);
        CubeFaceModel.Result result = evaluated == null ? null : evaluated.toResult();
        if (result == null) {
            if (dump != null) {
                dump.recordCnn(rgba, width, height, evaluated, null,
                    evaluated == null ? "no_model" : evaluated.reject);
            }
            if (model != null && ++modelDeclines < FALLBACK_AFTER_DECLINES) {
                if (dump != null) dump.note("cnn_decline_" + modelDeclines, null);
                ageLattice();
                return null;
            }
            return searchAnchors(width, height);
        }
        modelDeclines = 0;
        // Smoothing the coarse corners gives refinement a steadier starting point; it never
        // delays the refined answer, which is measured afresh on every frame.
        coarseQuad = FaceRefiner.orderCorners(smoother.update(result.corners));
        score = result.presence;

        long start = System.nanoTime();
        FaceRefiner.Result lattice = refineTracked(coarseQuad, width, height);
        refineNanos += System.nanoTime() - start;
        boolean disputed = lattice != null && !FaceRefiner.agrees(lattice.quad, coarseQuad);
        if (disputed) {
            // Do not keep tracking from a lattice the detector disagrees with.
            lastLattice = null;
            latticeAge = Integer.MAX_VALUE;
            lattice = null;
        }
        double[] quad = lattice != null ? lattice.quad : coarseQuad;
        if (lattice != null) refined++; else coarse++;
        FaceSample sample = FaceSampler.sample(rgba, width, height, quad, lattice != null, score);
        if (sample == null) {
            if (dump != null) dump.recordCnn(rgba, width, height, evaluated, null, "sample_fail");
            return null;
        }
        float[] flat = new float[8];
        for (int i = 0; i < 8; i++) flat[i] = (float) quad[i];
        DetectedFace face = new DetectedFace(sample, flat, width, height, score,
            lattice != null, disputed);
        if (dump != null) dump.recordCnn(rgba, width, height, evaluated, face, null);
        return face;
    }

    /**
     * The network declined several frames running: look for the lattice directly, starting where
     * the face was last refined. There is no detector to cross-check against here, so this path
     * only runs when the network is blind, never in competition with it.
     */
    private DetectedFace searchAnchors(int width, int height) {
        int declines = modelDeclines;
        modelDeclines = 0;
        double[] hint = latticeAge <= 6 ? lastLattice : null;
        long start = System.nanoTime();
        FaceRefiner.Result lattice = anchors.find(packedRgba, width, height, hint);
        refineNanos += System.nanoTime() - start;
        if (lattice == null) {
            ageLattice();
            if (dump != null) dump.recordAnchors(packedRgba, width, height, null, declines);
            return null;
        }
        lastLattice = lattice.quad;
        latticeAge = 0;
        refined++;
        float score = (float) (lattice.support / 9.0);
        FaceSample sample = FaceSampler.sample(packedRgba, width, height, lattice.quad, true, score);
        if (sample == null) return null;
        float[] flat = new float[8];
        for (int i = 0; i < 8; i++) flat[i] = (float) lattice.quad[i];
        DetectedFace face = new DetectedFace(sample, flat, width, height, score, true, false);
        if (dump != null) dump.recordAnchors(packedRgba, width, height, face, declines);
        return face;
    }

    /**
     * Refines from the previous frame's lattice when the coarse quad still sits on the same face,
     * else from the coarse quad itself.
     *
     * <p>The previous lattice is a far better starting point than the network's loose corners —
     * it was already exact one frame ago — but only while the network agrees it is the same face:
     * once its centre moves more than a pitch away, the user has turned the cube.
     */
    private FaceRefiner.Result refineTracked(double[] coarseQuad, int width, int height) {
        FaceRefiner.Result result = null;
        if (lastLattice != null && latticeAge <= LATTICE_MEMORY && sameFace(lastLattice, coarseQuad)) {
            result = refiner.refine(packedRgba, width, height, lastLattice);
        }
        if (result == null) result = refiner.refine(packedRgba, width, height, coarseQuad);
        if (result != null) {
            lastLattice = result.quad;
            latticeAge = 0;
        } else {
            ageLattice();
        }
        return result;
    }

    private void ageLattice() {
        if (latticeAge < Integer.MAX_VALUE) latticeAge++;
    }

    static boolean sameFace(double[] lattice, double[] coarse) {
        double lp = meanSide(lattice) / 3, cp = meanSide(coarse) / 3;
        double dx = centre(lattice, 0) - centre(coarse, 0), dy = centre(lattice, 1) - centre(coarse, 1);
        double ratio = cp / lp;
        return Math.hypot(dx, dy) < 1.2 * lp && ratio > 0.65 && ratio < 1.55;
    }

    private static double centre(double[] quad, int axis) {
        return (quad[axis] + quad[2 + axis] + quad[4 + axis] + quad[6 + axis]) / 4;
    }

    private static double meanSide(double[] quad) {
        double total = 0;
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            total += Math.hypot(quad[n * 2] - quad[i * 2], quad[n * 2 + 1] - quad[i * 2 + 1]);
        }
        return total / 4;
    }

    /** Logcat-only heartbeat over 90 frames: how fast the pipeline really runs, without touching disk. */
    private void heartbeat(long now) {
        if (windowStartNanos == 0) windowStartNanos = now;
        if (++framesSinceLog < 90) return;
        int total = Math.max(1, refined + coarse);
        android.util.Log.i("CubeAnalyzer", String.format(java.util.Locale.US,
            "pace %.1f ms/frame, refine %.1f ms, refined %d%% of %d detections",
            (now - windowStartNanos) / 1e6f / framesSinceLog, refineNanos / 1e6f / total,
            100 * refined / total, refined + coarse));
        framesSinceLog = 0;
        windowStartNanos = now;
        refineNanos = 0;
        refined = coarse = 0;
    }

    static void packRows(ByteBuffer source, byte[] target, int width, int height,
                         int rowStride, int pixelStride) {
        ByteBuffer buffer = source.duplicate();
        int base = buffer.position();
        if (pixelStride == 4) {
            int rowBytes = width * 4;
            for (int row = 0; row < height; row++) {
                buffer.position(base + row * rowStride);
                buffer.get(target, row * rowBytes, rowBytes);
            }
            return;
        }
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int sourceOffset = base + row * rowStride + col * pixelStride;
                int targetOffset = (row * width + col) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    target[targetOffset + channel] = buffer.get(sourceOffset + channel);
                }
            }
        }
    }
}
