package com.mofang.cubear;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class CubeUiState {
    public enum Phase { PERMISSION, SCANNING, SOLVING, GUIDING, SOLVED, ERROR }

    public final Phase phase;
    public final String title;
    public final String detail;
    public final FaceSample liveFace;
    public final DetectedFace detectedFace;
    public final Set<CubeColor> scanned;
    /** Faces actually captured. Can exceed {@link #scanned} when two centres share a provisional colour. */
    public final int scannedCount;
    public final List<String> moves;
    public final int moveIndex;
    /** 0..1 how far the current face's stability run has progressed, for the capture ring. */
    public final float stabilizeProgress;
    /** True when a face is tracked but sits too far away for its readings to be trusted. */
    public final boolean tooFarToCapture;
    /** Colour of the face inferred rather than scanned, or null when all six were seen. */
    public final CubeColor inferred;
    /** 54-facelet URFDLB string used by the 3D guide cube, or null before a solve. */
    public final String cubeState;

    public CubeUiState(Phase phase, String title, String detail, FaceSample liveFace,
                       DetectedFace detectedFace, Set<CubeColor> scanned, int scannedCount,
                       List<String> moves, int moveIndex) {
        this(phase, title, detail, liveFace, detectedFace, scanned, scannedCount,
            moves, moveIndex, 0f, false, null, null);
    }

    public CubeUiState(Phase phase, String title, String detail, FaceSample liveFace,
                       DetectedFace detectedFace, Set<CubeColor> scanned, int scannedCount,
                       List<String> moves, int moveIndex, float stabilizeProgress,
                       boolean tooFarToCapture) {
        this(phase, title, detail, liveFace, detectedFace, scanned, scannedCount,
            moves, moveIndex, stabilizeProgress, tooFarToCapture, null, null);
    }

    public CubeUiState(Phase phase, String title, String detail, FaceSample liveFace,
                       DetectedFace detectedFace, Set<CubeColor> scanned, int scannedCount,
                       List<String> moves, int moveIndex, float stabilizeProgress,
                       boolean tooFarToCapture, CubeColor inferred) {
        this(phase, title, detail, liveFace, detectedFace, scanned, scannedCount,
            moves, moveIndex, stabilizeProgress, tooFarToCapture, inferred, null);
    }

    public CubeUiState(Phase phase, String title, String detail, FaceSample liveFace,
                       DetectedFace detectedFace, Set<CubeColor> scanned, int scannedCount,
                       List<String> moves, int moveIndex, float stabilizeProgress,
                       boolean tooFarToCapture, CubeColor inferred, String cubeState) {
        this.scannedCount = scannedCount;
        this.phase = phase;
        this.title = title;
        this.detail = detail;
        this.liveFace = liveFace;
        this.detectedFace = detectedFace;
        this.scanned = scanned == null || scanned.isEmpty()
            ? Collections.emptySet() : EnumSet.copyOf(scanned);
        this.moves = moves == null ? Collections.emptyList() : moves;
        this.moveIndex = moveIndex;
        this.stabilizeProgress = stabilizeProgress;
        this.tooFarToCapture = tooFarToCapture;
        this.inferred = inferred;
        this.cubeState = cubeState;
    }

    public String currentMove() {
        return moveIndex >= 0 && moveIndex < moves.size() ? moves.get(moveIndex) : "";
    }
}
