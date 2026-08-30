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

    public CubeUiState(Phase phase, String title, String detail, FaceSample liveFace,
                       Set<CubeColor> scanned, List<String> moves, int moveIndex) {
        this(phase, title, detail, liveFace, null, scanned,
            scanned == null ? 0 : scanned.size(), moves, moveIndex);
    }

    public CubeUiState(Phase phase, String title, String detail, FaceSample liveFace,
                       DetectedFace detectedFace, Set<CubeColor> scanned, int scannedCount,
                       List<String> moves, int moveIndex) {
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
    }

    public String currentMove() {
        return moveIndex >= 0 && moveIndex < moves.size() ? moves.get(moveIndex) : "";
    }
}
