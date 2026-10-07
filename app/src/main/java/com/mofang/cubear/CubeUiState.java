package com.mofang.cubear;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the screen shows for one moment, built by {@link MainActivity}. */
public final class CubeUiState {
    public enum Phase { PERMISSION, SCANNING, READY, GUIDING, SOLVED, ERROR }

    public final Phase phase;
    public final String title;
    public final String detail;
    /** A short, situational nudge ("再靠近一点"), or null. */
    public final String hint;
    /** The tracked face, possibly bridged over a dropped frame, or null. */
    public final DetectedFace detectedFace;
    /** The live reading with display names, or null. */
    public final FaceSample liveFace;
    /** 0..1 how far the current steady run is towards a capture. */
    public final float stabilizeProgress;
    /** True when a face is tracked but too distant for its readings to be trusted. */
    public final boolean tooFarToCapture;
    /** Colours whose face has been collected. */
    public final Set<CubeColor> scanned;
    /** Collected faces, 0..6. */
    public final int scannedCount;
    /** Per face letter (URFDLB), nine ARGB colours previewing what was scanned, 0 = unread. */
    public final Map<Character, int[]> preview;
    /** Colour of the face inferred rather than scanned, or null. */
    public final CubeColor inferred;
    /** 54-facelet URFDLB string once the cube is known, else null. */
    public final String cubeState;
    public final List<String> moves;
    public final int moveIndex;
    /** True while a background assembly is running. */
    public final boolean busy;
    /** Guidance: the current step, named from how the cube is held, or null. */
    public final GuideStep guideStep;
    /** Guidance: notation of the steps after the current one. */
    public final List<String> upcoming;
    /**
     * Guidance: the face in view is the front of {@link #guideStep}'s frame, read upright, so its
     * lattice can carry the step's arrow.
     */
    public final boolean stepOnLiveFace;

    private CubeUiState(Builder b) {
        phase = b.phase;
        title = b.title;
        detail = b.detail;
        hint = b.hint;
        detectedFace = b.detectedFace;
        liveFace = b.liveFace;
        stabilizeProgress = b.stabilizeProgress;
        tooFarToCapture = b.tooFar;
        scanned = b.scanned == null || b.scanned.isEmpty()
            ? Collections.emptySet() : EnumSet.copyOf(b.scanned);
        scannedCount = b.scannedCount;
        preview = b.preview == null ? Collections.emptyMap() : b.preview;
        inferred = b.inferred;
        cubeState = b.cubeState;
        moves = b.moves == null ? Collections.emptyList() : b.moves;
        moveIndex = b.moveIndex;
        busy = b.busy;
        guideStep = b.guideStep;
        upcoming = b.upcoming == null ? Collections.emptyList() : b.upcoming;
        stepOnLiveFace = b.stepOnLiveFace;
    }

    public static Builder builder(Phase phase) { return new Builder(phase); }

    public static final class Builder {
        private final Phase phase;
        private String title = "", detail = "", hint;
        private DetectedFace detectedFace;
        private FaceSample liveFace;
        private float stabilizeProgress;
        private boolean tooFar, busy, stepOnLiveFace;
        private GuideStep guideStep;
        private List<String> upcoming;
        private Set<CubeColor> scanned;
        private int scannedCount;
        private Map<Character, int[]> preview;
        private CubeColor inferred;
        private String cubeState;
        private List<String> moves;
        private int moveIndex = -1;

        private Builder(Phase phase) { this.phase = phase; }

        public Builder text(String title, String detail) {
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            return this;
        }
        public Builder hint(String hint) { this.hint = hint; return this; }
        public Builder detection(DetectedFace face, FaceSample live) {
            detectedFace = face;
            liveFace = live;
            return this;
        }
        public Builder progress(float progress, boolean tooFar) {
            stabilizeProgress = progress;
            this.tooFar = tooFar;
            return this;
        }
        public Builder scan(Set<CubeColor> scanned, int count, Map<Character, int[]> preview) {
            this.scanned = scanned;
            scannedCount = count;
            this.preview = preview;
            return this;
        }
        public Builder cube(String state, CubeColor inferred) {
            cubeState = state;
            this.inferred = inferred;
            return this;
        }
        public Builder moves(List<String> moves, int index) {
            this.moves = moves;
            moveIndex = index;
            return this;
        }
        public Builder guide(GuideStep step, List<String> upcoming, boolean onLiveFace) {
            guideStep = step;
            this.upcoming = upcoming;
            stepOnLiveFace = onLiveFace;
            return this;
        }
        public Builder busy(boolean busy) { this.busy = busy; return this; }
        public CubeUiState build() { return new CubeUiState(this); }
    }
}
