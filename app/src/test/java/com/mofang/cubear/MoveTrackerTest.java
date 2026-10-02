package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MoveTrackerTest {
    private static final String SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    private static String scrambled() {
        String state = SOLVED;
        for (String move : "R U2 F' L D B2 R' F U'".split(" ")) state = CubeMoves.apply(state, move);
        return state;
    }

    /** Face {@code face} of {@code state} as the camera reads it at roll {@code rotation}. */
    private static FaceSample seen(String state, char face, int rotation) {
        String canonical = CubeMoves.face(state, face);
        CubeColor[] stickers = new CubeColor[9];
        for (int i = 0; i < 9; i++) stickers[i] = CubeColor.fromFace(canonical.charAt(i));
        FaceSample sample = new FaceSample(stickers, 1f);
        for (int i = 0; i < (4 - rotation) % 4; i++) sample = sample.rotateClockwise();
        assertTrue((MoveTracker.matchMask(canonical, sample) & (1 << rotation)) != 0);
        return sample;
    }

    @Test public void holdingTheTurnedFaceStillIsNotAMove() {
        String start = scrambled();
        MoveTracker tracker = new MoveTracker(start, Arrays.asList("R", "U"));
        // The bug this replaces: two steady looks at the unturned red face completed the step.
        for (int i = 0; i < 4; i++) {
            assertEquals(MoveTracker.Outcome.CONFIRMED, tracker.observe(seen(start, 'R', 1), true));
            assertEquals(0, tracker.index());
        }
    }

    @Test public void aQuarterTurnOfTheFacingSideAdvances() {
        String start = scrambled();
        List<String> moves = Arrays.asList("R", "U");
        MoveTracker tracker = new MoveTracker(start, moves);
        assertEquals(MoveTracker.Outcome.CONFIRMED, tracker.observe(seen(start, 'R', 2), true));
        String after = CubeMoves.apply(start, "R");
        assertEquals(MoveTracker.Outcome.ADVANCED, tracker.observe(seen(after, 'R', 2), true));
        assertEquals(1, tracker.index());
        assertEquals("U", tracker.currentMove());
    }

    @Test public void theTurnedFaceProvesNothingWithoutASteadyReference() {
        String start = scrambled();
        MoveTracker tracker = new MoveTracker(start, Arrays.asList("R", "U"));
        String after = CubeMoves.apply(start, "R");
        // No earlier look at red in this step: the rotated pattern could be a re-grip.
        assertEquals(MoveTracker.Outcome.CONFIRMED, tracker.observe(seen(after, 'R', 0), true));
        assertEquals(0, tracker.index());
        // Near 45 degrees of roll the reading's rotation is not trusted either.
        MoveTracker tilted = new MoveTracker(start, Arrays.asList("R", "U"));
        tilted.observe(seen(start, 'R', 1), false);
        tilted.observe(seen(after, 'R', 1), false);
        assertEquals(0, tilted.index());
    }

    @Test public void aNeighbouringFaceShowsTheMoveAtAnyRotation() {
        String start = scrambled();
        MoveTracker tracker = new MoveTracker(start, Arrays.asList("R", "U", "F2"));
        String after = CubeMoves.apply(start, "R");
        assertEquals(MoveTracker.Outcome.ADVANCED, tracker.observe(seen(after, 'F', 3), true));
        assertEquals(1, tracker.index());
        // The opposite face is untouched by the next move and confirms rather than advances.
        assertEquals(MoveTracker.Outcome.CONFIRMED, tracker.observe(seen(after, 'D', 1), true));
        assertEquals(1, tracker.index());
    }

    @Test public void missedMovesAreCaughtUpByLookingAhead() {
        String start = scrambled();
        List<String> moves = Arrays.asList("R", "U", "F2", "L'");
        MoveTracker tracker = new MoveTracker(start, moves);
        String state = start;
        for (int i = 0; i < 3; i++) state = CubeMoves.apply(state, moves.get(i));
        assertEquals(MoveTracker.Outcome.ADVANCED, tracker.observe(seen(state, 'U', 0), true));
        assertEquals(3, tracker.index());
    }

    @Test public void aFaceMatchingNoExpectedStateIsAMismatch() {
        String start = scrambled();
        MoveTracker tracker = new MoveTracker(start, Arrays.asList("R", "U"));
        String wrong = CubeMoves.apply(start, "L");
        FaceSample odd = seen(wrong, 'F', 0);
        assertEquals(0, MoveTracker.matchMask(CubeMoves.face(start, 'F'), odd));
        assertEquals(MoveTracker.Outcome.MISMATCH, tracker.observe(odd, true));
        assertEquals(1, tracker.mismatches());
        assertEquals(0, tracker.index());
    }

    @Test public void manualControlsMoveWithinTheSolution() {
        MoveTracker tracker = new MoveTracker(scrambled(), Arrays.asList("R", "U"));
        tracker.previous();
        assertEquals(0, tracker.index());
        tracker.next();
        tracker.next();
        assertTrue(tracker.done());
        tracker.next();
        assertEquals(2, tracker.index());
        tracker.previous();
        assertEquals("U", tracker.currentMove());
    }

    @Test public void rollStabilityFollowsTheTopEdge() {
        assertTrue(MoveTracker.rollStable(new float[]{0, 0, 100, 10, 90, 110, -10, 100}));
        assertFalse(MoveTracker.rollStable(new float[]{0, 0, 70, 70, 0, 140, -70, 70}));
    }
}
