package com.mofang.cubear;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The guided solve as the camera follows it: which step is next, and how the cube is held.
 *
 * <p>Every instruction is phrased relative to {@link #frame()}, learned from steady looks that
 * match an expected state, so the user keeps whichever face they like towards the camera. Steps
 * that move centres (middle layers, the front two layers) also move the frame; it is carried
 * through them, and the look that confirms a step corrects it if the hands did something else.
 */
final class GuideSession {
    enum Result { NONE, CONFIRMED, ADVANCED, MISMATCH }

    private final MoveTracker tracker;
    private CubeFrame frame;
    private boolean frameKnown;
    /** The frame at the start of each step walked through, so {@link #previous()} can restore it. */
    private final TreeMap<Integer, CubeFrame> frameAt = new TreeMap<>();
    private String lastMismatch;
    private int settledMismatches;

    /**
     * @param frame how the cube is held at the start, as far as is known
     * @param frameKnown whether {@code frame} comes from a look at this cube rather than a guess
     */
    GuideSession(String start, List<String> moves, CubeFrame frame, boolean frameKnown) {
        tracker = new MoveTracker(start, moves);
        this.frame = frame == null ? CubeFrame.DEFAULT : frame;
        this.frameKnown = frame != null && frameKnown;
    }

    MoveTracker tracker() { return tracker; }

    CubeFrame frame() { return frame; }

    boolean frameKnown() { return frameKnown; }

    boolean done() { return tracker.done(); }

    /**
     * Consecutive looks that matched no expected state and all read the same: the cube has come
     * to rest somewhere unexpected. Looks taken mid-turn mismatch too, but they differ from one
     * another, and a half turn with a pause in it was enough to raise the alarm by plain count.
     */
    int settledMismatches() { return settledMismatches; }

    /** The step to show now, or null once the cube is solved. */
    GuideStep currentStep() {
        List<GuideStep> plan = plan(1);
        return plan.isEmpty() ? null : plan.get(0);
    }

    /** The current step and up to {@code limit - 1} after it. */
    List<GuideStep> plan(int limit) {
        return GuideStep.plan(tracker.moves(), tracker.states(), tracker.index(), frame, limit);
    }

    /**
     * Takes one steady look at the cube.
     *
     * @param named the look with stickers named against the scan palette
     * @param upright false near 45° of roll, where the reading's rotation may be off by one
     */
    Result observe(FaceSample named, boolean upright) {
        if (named == null || tracker.done()) return Result.NONE;
        int before = tracker.index();
        // The step as instructed comes first: from the front, the front two layers turning half
        // way look exactly like the back and front layers each turning half way, and the
        // tracker's look-ahead would credit the next move too.
        GuideStep step = currentStep();
        if (showsDone(step, named, upright)) {
            tracker.jump(before + step.count);
            follow(before, before + step.count);
            learn(named, tracker.state(), upright);
            settle(null);
            return Result.ADVANCED;
        }
        MoveTracker.Outcome outcome = tracker.observe(named, upright);
        if (outcome == MoveTracker.Outcome.MISMATCH) settle(named.signature());
        else if (outcome != MoveTracker.Outcome.NONE) settle(null);
        if (outcome == MoveTracker.Outcome.ADVANCED) follow(before, tracker.index());
        if (outcome == MoveTracker.Outcome.ADVANCED || outcome == MoveTracker.Outcome.CONFIRMED) {
            // The tracker has decided which state the cube is in; the look says how it is held.
            learn(named, tracker.state(), upright);
        }
        switch (outcome) {
            case ADVANCED: return Result.ADVANCED;
            case CONFIRMED: return Result.CONFIRMED;
            case MISMATCH: return Result.MISMATCH;
            default: return Result.NONE;
        }
    }

    private void settle(String mismatch) {
        settledMismatches = mismatch == null ? 0
            : mismatch.equals(lastMismatch) ? settledMismatches + 1 : 1;
        lastMismatch = mismatch;
    }

    /** The user says the current step is done. */
    void next() {
        settle(null);
        int before = tracker.index();
        GuideStep step = currentStep();
        tracker.jump(before + (step == null ? 1 : step.count));
        follow(before, tracker.index());
    }

    /** Back to where the previous step started, held the way it was then. */
    void previous() {
        settle(null);
        int before = tracker.index();
        Map.Entry<Integer, CubeFrame> earlier = frameAt.lowerEntry(before);
        int target = earlier == null ? Math.max(0, before - 1) : earlier.getKey();
        if (earlier != null) frame = earlier.getValue();
        frameAt.tailMap(target, true).clear();
        tracker.jump(target);
    }

    /**
     * Updates the frame from a steady look at a cube in {@code state}.
     *
     * @return false when the look fits no rotation of the expected face
     */
    boolean learn(FaceSample named, String state, boolean upright) {
        CubeFrame next = fromLook(named, state, upright, frameKnown ? frame : null);
        if (next == null) return false;
        frame = next;
        frameKnown = true;
        return true;
    }

    /** The frame a steady look at a cube in {@code state} implies, or null when it does not fit. */
    static CubeFrame fromLook(FaceSample named, String state, boolean upright, CubeFrame previous) {
        if (named == null || state == null) return null;
        char face = named.center().face;
        if ("URFDLB".indexOf(face) < 0) return null;
        int mask = MoveTracker.matchMask(CubeMoves.face(state, face), named);
        return CubeFrame.fromLook(face, mask, upright, previous);
    }

    /**
     * Whether a look shows {@code step} done, judged by how the cube is held: the face towards
     * the camera reads as the step's result at the roll the step predicts, and no longer as the
     * cube before it. The tracker alone misses these cases. A front face that only turns in place
     * — the front layer, or the front two layers while the back is held — needs its old roll as
     * a reference, and the tracker learns that from a look this step only when the face reads at
     * a single rotation, which a face with half-turn symmetry never does. And the front two
     * layers leave the stickers unchanged: to the tracker they look like the cube merely held at
     * another angle. The hold knows both.
     */
    private boolean showsDone(GuideStep step, FaceSample named, boolean upright) {
        if (step == null || !step.seen || !upright || !frameKnown) return false;
        char face = named.center().face;
        if (face != step.after.front()) return false;
        String[] states = tracker.states();
        String done = states[step.first + step.count];
        if ((MoveTracker.matchMask(CubeMoves.face(done, face), named)
                & (1 << step.after.rotation())) == 0) {
            return false;
        }
        if (step.frame.front() != face) return true;
        return (MoveTracker.matchMask(CubeMoves.face(states[step.first], face), named)
            & (1 << step.frame.rotation())) == 0;
    }

    /**
     * Carries the frame over the steps done between solver moves {@code from} and {@code to}: a
     * turn that moves centres changes the colour facing the camera.
     */
    private void follow(int from, int to) {
        if (to <= from) return;
        for (GuideStep step : GuideStep.plan(tracker.moves(), tracker.states(), from, frame, to - from)) {
            if (step.first >= to) break;
            frameAt.put(step.first, step.frame);
            // Stopping inside a middle-layer pair means its first outer turn was made on its own.
            if (step.first + step.count > to) break;
            frame = step.after;
        }
    }
}
