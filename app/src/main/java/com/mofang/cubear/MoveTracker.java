package com.mofang.cubear;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Follows the user through a solution from what the camera sees.
 *
 * <p>The old check compared the turned face before and after its move while ignoring rotation —
 * but a face turn only rotates that face's own stickers, so "before" and "after" are the same
 * pattern and every steady look at the target face counted as a completed move. This tracker
 * uses evidence that actually distinguishes the states:
 * <ul>
 * <li>Any face whose content the move changes (its four neighbours) is matched against the
 * expected states at any rotation — a moved row or column is unambiguous.</li>
 * <li>For the turned face itself, the rotation at which it last matched is remembered per face:
 * a steady camera that sees the pattern a quarter-turn off is a turn, not a re-grip.</li>
 * </ul>
 * Matching also looks a few steps ahead, so a move the camera missed does not strand the user,
 * and every decision can be overridden by the manual controls.
 */
final class MoveTracker {
    /** How many moves past the current one an observation may confirm at once. */
    static final int LOOKAHEAD = 3;
    /** Readable stickers that must agree before a face counts as recognised. */
    static final int MIN_MATCHING = 7;

    enum Outcome { NONE, CONFIRMED, ADVANCED, MISMATCH }

    private final List<String> moves;
    private final String[] states;
    private int index;
    /** Per face letter (URFDLB), the rotation it last matched the current state at, or -1. */
    private final int[] reference = {-1, -1, -1, -1, -1, -1};
    /** The step at which each face's rotation was last confirmed. */
    private final int[] referenceStep = {-1, -1, -1, -1, -1, -1};
    private int mismatches;

    MoveTracker(String start, List<String> moves) {
        this.moves = Collections.unmodifiableList(new ArrayList<>(moves));
        states = new String[moves.size() + 1];
        states[0] = start;
        for (int i = 0; i < moves.size(); i++) states[i + 1] = CubeMoves.apply(states[i], moves.get(i));
    }

    int index() { return index; }

    int size() { return moves.size(); }

    boolean done() { return index >= moves.size(); }

    List<String> moves() { return moves; }

    String currentMove() { return done() ? "" : moves.get(index); }

    /** The cube as it should look before the current move. */
    String state() { return states[index]; }

    /** Consecutive observations that matched no expected state. */
    int mismatches() { return mismatches; }

    void next() { jump(index + 1); }

    void previous() { jump(index - 1); }

    void jump(int to) {
        index = Math.max(0, Math.min(moves.size(), to));
        mismatches = 0;
    }

    /**
     * @param named a steady face whose stickers are named against the scan palette
     * @param rollStable false when the face sits near 45 degrees of roll, where the corner order
     *     — and with it the reading's rotation — may flip between frames
     */
    Outcome observe(FaceSample named, boolean rollStable) {
        if (named == null || done()) return Outcome.NONE;
        char face = named.center().face;
        int slot = "URFDLB".indexOf(face);
        if (slot < 0) return Outcome.NONE;
        int last = Math.min(moves.size(), index + LOOKAHEAD);
        int[] masks = new int[last - index + 1];
        int firstFuture = -1;
        for (int k = index; k <= last; k++) {
            masks[k - index] = matchMask(CubeMoves.face(states[k], face), named);
            if (k > index && masks[k - index] != 0 && firstFuture < 0) firstFuture = k;
        }
        boolean current = masks[0] != 0;
        if (!current && firstFuture < 0) {
            mismatches++;
            return Outcome.MISMATCH;
        }
        mismatches = 0;
        if (!current) {
            // Only a later state explains what the camera sees: those moves were made.
            int mask = masks[firstFuture - index];
            index = firstFuture;
            remember(slot, mask, rollStable);
            return Outcome.ADVANCED;
        }
        if (firstFuture < 0) {
            // Unambiguously the current state; this is also where a re-grip is re-learned.
            remember(slot, masks[0], rollStable);
            return Outcome.CONFIRMED;
        }
        // The content cannot tell the states apart — typically the face being turned, whose
        // stickers only rotate. The rotation it was seen at during this very step decides; an
        // older one may predate a re-grip, which would look exactly like a quarter turn.
        int ref = rollStable && referenceStep[slot] == index ? reference[slot] : -1;
        if (ref < 0 || (masks[0] & (1 << ref)) != 0) {
            if (ref < 0) remember(slot, masks[0], rollStable);
            return Outcome.CONFIRMED;
        }
        for (int k = index + 1; k <= last; k++) {
            if ((masks[k - index] & (1 << ref)) != 0) {
                index = k;
                referenceStep[slot] = index;
                return Outcome.ADVANCED;
            }
        }
        return Outcome.NONE;
    }

    /** Remembers the rotation a face matched at, when that rotation is unambiguous. */
    private void remember(int slot, int mask, boolean rollStable) {
        if (rollStable && Integer.bitCount(mask) == 1) {
            reference[slot] = Integer.numberOfTrailingZeros(mask);
            referenceStep[slot] = index;
        }
    }

    /**
     * Bit r is set when rotating {@code observed} clockwise r times agrees with
     * {@code canonical} on at least {@link #MIN_MATCHING} readable stickers and conflicts on none.
     */
    static int matchMask(String canonical, FaceSample observed) {
        if (canonical == null || canonical.length() != 9 || observed == null) return 0;
        int mask = 0;
        FaceSample rotated = observed;
        for (int turn = 0; turn < 4; turn++) {
            int agreed = 0;
            boolean conflict = false;
            for (int i = 0; i < 9 && !conflict; i++) {
                CubeColor sticker = rotated.stickers[i];
                if (sticker == CubeColor.UNKNOWN) continue;
                if (sticker.face == canonical.charAt(i)) agreed++;
                else conflict = true;
            }
            if (!conflict && agreed >= MIN_MATCHING) mask |= 1 << turn;
            rotated = rotated.rotateClockwise();
        }
        return mask;
    }

    /**
     * Whether a detected quad sits far enough from 45 degrees of roll for its corner order to be
     * stable: the top-left-most corner is ambiguous only near the diagonal.
     */
    static boolean rollStable(float[] corners) {
        double angle = Math.toDegrees(Math.atan2(corners[3] - corners[1], corners[2] - corners[0]));
        return Math.abs(angle) < 30;
    }
}
