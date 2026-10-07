package com.mofang.cubear;

import java.util.ArrayList;
import java.util.List;

/**
 * One instruction as the user performs it: a layer named by where it sits in their hands
 * ("顶层向左拧"), not by its centre colour.
 *
 * <p>The solver speaks in centre colours — "turn the green face" — which made the user hunt for
 * the green face and point it at the camera before every move. Given the {@link CubeFrame} the
 * camera last saw, each move instead names the top, bottom, left, right, front or back layer of
 * the cube as it is held. Two other ways of doing the same thing are considered:
 * <ul>
 * <li>An outer pair that turns two opposite layers the same way (R L′) as one turn of the middle
 * layer between them — never the one standing between front and back, which is awkward to grip
 * and invisible to the camera.</li>
 * <li>A back-layer turn as the front two layers turning while the back is held: the cube ends up
 * the same, but the face towards the camera now turns, so the camera can confirm the step.</li>
 * </ul>
 * Both carry centres along, so they also change how the cube sits in the hands ({@link #after}),
 * and with it how every later move must be done — a middle half turn swaps front and back, and
 * every later front move would become a back move. A step that still leaves the face towards the
 * camera looking the same is shown together with the one after it ({@link #then}), so the pair
 * confirms itself instead of waiting for "下一步" — on the phone, people simply kept turning a
 * layer that did not advance. Which way each step goes is decided over the whole remaining
 * solution, by the least total {@link #effort()}.
 */
final class GuideStep {
    private static final String FACES = "URFDLB";
    static final int[] VIEW_RIGHT = {1, 0, 0};
    static final int[] VIEW_UP = {0, 1, 0};
    static final int[] VIEW_FRONT = {0, 0, 1};

    /** {@link #layer}: the middle layer. */
    static final int MIDDLE = 0;
    /** {@link #layer}: the outer layer on the +axis side. */
    static final int OUTER = 1;
    /** {@link #layer}: the two layers on the +axis side, everything but the opposite outer layer. */
    static final int WIDE = 2;

    /** Effort of one step the camera confirms by itself. */
    private static final float STEP = 1f;
    /** Extra for a step it cannot see: the user has to press "下一步". */
    private static final float UNSEEN = 1f;
    /**
     * Extra for a grip other than one outer layer, which beginners find harder: half a step, so
     * a middle turn must save a whole outer turn, and a front-two-layer turn a press of "下一步",
     * without costing more of the same later. At 0.3 a middle half turn won by a tenth of a step
     * at the price of two more unusual grips.
     */
    private static final float GRIP = 0.5f;
    /**
     * Extra for showing two moves as one instruction: more than an unusual grip, because a turn
     * the camera confirms by itself catches a slip at once, while a chained one shows it only
     * after the next; less than a press of "下一步", which is what chaining saves.
     */
    private static final float CHAIN = 0.6f;

    /** Index of the first solver move this step covers. */
    final int first;
    /**
     * Solver moves covered: 1, 2 for an outer pair performed as one middle-layer turn, and those
     * of {@link #then} on top.
     */
    final int count;
    /** How the cube is held before and after the step. */
    final CubeFrame frame, after;
    /** Centre colour (URFDLB) of the turned outer layer, or 0 when the turned block has no single one. */
    final char face;
    /** Cube-space axis the layer turns about. */
    final int[] axis;
    /** {@link #OUTER}, {@link #MIDDLE} or {@link #WIDE}. */
    final int layer;
    /** Signed right-handed quarter turns about {@link #axis}: -1, +1, or ±2. */
    final int quarters;
    /** Whether the face towards the camera looks different afterwards, so the step confirms itself. */
    final boolean seen;
    /**
     * What to do straight after this turn, which on its own changes nothing the camera can see;
     * null when this turn stands alone. The fields above describe this turn, except {@link #count},
     * {@link #after} and {@link #seen}, which cover both.
     */
    final GuideStep then;

    private GuideStep(int first, int count, CubeFrame frame, char face, int[] axis, int layer,
                      int quarters, Boolean seen) {
        this.first = first;
        this.count = count;
        this.frame = frame;
        this.face = face;
        this.axis = axis;
        this.layer = layer;
        this.quarters = quarters;
        this.after = layer == OUTER ? frame : frame.turned(frame.toView(axis), quarters);
        this.seen = seen != null ? seen : viewAxis()[2] == 0 || turnsFrontFace();
        this.then = null;
    }

    /** {@code part}, which the camera cannot see, followed by {@code then}. */
    private GuideStep(GuideStep part, GuideStep then, boolean seen) {
        first = part.first;
        count = part.count + then.count;
        frame = part.frame;
        face = part.face;
        axis = part.axis;
        layer = part.layer;
        quarters = part.quarters;
        after = then.after;
        this.seen = seen;
        this.then = then;
    }

    /** This turn on its own: the first part of a chained step, or the step itself. */
    GuideStep alone() {
        if (then == null) return this;
        return new GuideStep(first, count - then.count, frame, face, axis, layer, quarters, false);
    }

    private GuideStep seenAs(boolean visible) {
        return new GuideStep(first, count, frame, face, axis, layer, quarters, visible);
    }

    /**
     * The steps from solver move {@code from}, at most {@code limit} of them, as they will be
     * shown to someone holding the cube in {@code frame}.
     *
     * @param states the cube before each move ({@code moves.size() + 1} of them), so steps the
     *     camera cannot tell apart are known as such; null to judge by geometry alone
     */
    static List<GuideStep> plan(List<String> moves, String[] states, int from, CubeFrame frame,
                                int limit) {
        Planner planner = new Planner(moves, states);
        List<GuideStep> steps = new ArrayList<>();
        CubeFrame held = frame;
        int index = Math.max(0, from);
        while (index < moves.size() && steps.size() < limit) {
            GuideStep step = planner.best(index, held);
            steps.add(step);
            held = step.after;
            index += step.count;
        }
        return steps;
    }

    /** How much this step asks of the user, for choosing between equivalent plans. */
    float effort() {
        float hands = STEP + (layer == OUTER ? 0f : GRIP);
        if (then != null) hands += CHAIN + STEP + (then.layer == OUTER ? 0f : GRIP);
        return hands + (seen ? 0f : UNSEEN);
    }

    /**
     * Least-effort way through the remaining moves, by dynamic programming over (move index, how
     * the cube is held): 24 holds times a solution's twenty-odd moves.
     */
    private static final class Planner {
        private final List<String> moves;
        private final String[] states;
        private final float[] memo;

        Planner(List<String> moves, String[] states) {
            this.moves = moves;
            this.states = states != null && states.length > moves.size() ? states : null;
            memo = new float[(moves.size() + 1) * 24];
            java.util.Arrays.fill(memo, -1f);
        }

        GuideStep best(int index, CubeFrame held) {
            GuideStep best = null;
            float least = 0f;
            // Ties keep the earliest option, and the plain outer turn comes first.
            for (GuideStep option : options(index, held)) {
                float total = option.effort() + cost(index + option.count, option.after);
                if (best == null || total < least) {
                    least = total;
                    best = option;
                }
            }
            return best;
        }

        /** Every way to do the moves from {@code index}: single turns, then unseen ones chained on. */
        private List<GuideStep> options(int index, CubeFrame held) {
            List<GuideStep> singles = singles(index, held);
            List<GuideStep> all = new ArrayList<>(singles);
            for (GuideStep single : singles) {
                int next = index + single.count;
                if (single.seen || next >= moves.size()) continue;
                for (GuideStep follow : singles(next, single.after)) all.add(chained(single, follow));
            }
            return all;
        }

        private List<GuideStep> singles(int index, CubeFrame held) {
            List<GuideStep> out = new ArrayList<>(3);
            GuideStep outer = judged(outer(moves.get(index), index, held));
            out.add(outer);
            if (index + 1 < moves.size() && pairsIntoMiddle(moves.get(index), moves.get(index + 1))) {
                GuideStep middle = middle(moves.get(index), index, held);
                if (middle.viewAxis()[2] == 0) out.add(judged(middle));
            }
            if (outer.isBack()) out.add(judged(wide(moves.get(index), index, held)));
            return out;
        }

        private GuideStep chained(GuideStep part, GuideStep then) {
            boolean seen = states == null ? then.seen
                : !reading(states[part.first], part.frame)
                    .equals(reading(states[part.first + part.count + then.count], then.after));
            return new GuideStep(part, then, seen);
        }

        private float cost(int index, CubeFrame held) {
            if (index >= moves.size()) return 0f;
            int key = index * 24 + held.id();
            if (memo[key] >= 0f) return memo[key];
            GuideStep step = best(index, held);
            float total = step.effort() + cost(index + step.count, step.after);
            memo[key] = total;
            return total;
        }

        /**
         * The step, told whether the camera can see it happen: whether its picture of the front
         * face differs before and after. That also catches what geometry misses — a front face
         * of one colour turning, or a side layer bringing in stickers just like the ones it moves
         * out.
         */
        private GuideStep judged(GuideStep step) {
            if (states == null) return step;
            String before = reading(states[step.first], step.frame);
            String after = reading(states[step.first + step.count], step.after);
            return step.seenAs(!before.equals(after));
        }
    }

    /**
     * The front face as the camera reads it, row-major from the top-left of the picture: the
     * inverse of {@link CubeFrame#seen}, whose rotation turns a reading back into the layout.
     */
    static String reading(String state, CubeFrame held) {
        String layout = CubeMoves.face(state, held.front());
        char[] picture = new char[9];
        // Turning the picture clockwise `rotation` times gives the layout, so the picture is the
        // layout turned counter-clockwise that many times.
        int turns = held.rotation();
        for (int i = 0; i < 9; i++) {
            int row = i / 3, col = i % 3;
            for (int t = 0; t < turns; t++) {
                // A counter-clockwise turn shows the layout's top-right in the picture's top-left.
                int nextRow = col, nextCol = 2 - row;
                row = nextRow;
                col = nextCol;
            }
            picture[i] = layout.charAt(row * 3 + col);
        }
        return new String(picture);
    }

    /** A single solver move. */
    static GuideStep outer(String move, int index, CubeFrame frame) {
        char face = move.charAt(0);
        // Clockwise seen from outside is a negative right-handed turn about the outward normal.
        int quarters = amount(move) == 1 ? -1 : amount(move) == 3 ? 1 : -2;
        return new GuideStep(index, 1, frame, face, CubeFrame.normal(face), OUTER, quarters, null);
    }

    /**
     * An outer pair X^a Y^-a on opposite faces, performed as the middle layer turning the other
     * way: both outer layers move a quarter turn relative to the centres either way, so the
     * resulting state is the same, and the hands holding the outer layers keep their grip.
     */
    static GuideStep middle(String move, int index, CubeFrame frame) {
        char face = move.charAt(0);
        int quarters = amount(move) == 1 ? 1 : amount(move) == 3 ? -1 : 2;
        return new GuideStep(index, 2, frame, (char) 0, CubeFrame.normal(face), MIDDLE, quarters, null);
    }

    /**
     * A single move performed by holding its layer still and turning the other two: relative to
     * each other the two blocks move exactly as before, so the resulting state is the same — a
     * turn of θ about the layer's normal is a turn of θ about the opposite normal for the rest.
     */
    static GuideStep wide(String move, int index, CubeFrame frame) {
        char face = move.charAt(0);
        int[] normal = CubeFrame.normal(face);
        int quarters = amount(move) == 1 ? -1 : amount(move) == 3 ? 1 : -2;
        int[] opposite = {-normal[0], -normal[1], -normal[2]};
        return new GuideStep(index, 1, frame, (char) 0, opposite, WIDE, quarters, null);
    }

    /** True when two moves turn opposite layers through the same angle in the same direction. */
    static boolean pairsIntoMiddle(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        int fa = FACES.indexOf(a.charAt(0)), fb = FACES.indexOf(b.charAt(0));
        if (fa < 0 || fb < 0 || (fa + 3) % 6 != fb) return false;
        return (amount(a) + amount(b)) % 4 == 0;
    }

    /** Clockwise quarter turns of a solver move: 1, 2 or 3. */
    static int amount(String move) {
        return move.endsWith("2") ? 2 : move.endsWith("'") ? 3 : 1;
    }

    /** Whether a cubie at coordinate {@code along} on the axis is part of the turning block. */
    static boolean turns(int layer, int along) {
        return layer == WIDE ? along >= 0 : along == layer;
    }

    /** The turning axis in view space. */
    int[] viewAxis() { return frame.toView(axis); }

    boolean isMiddle() { return layer == MIDDLE; }

    boolean isWide() { return layer == WIDE; }

    boolean isHalfTurn() { return Math.abs(quarters) == 2; }

    /** The outer layer facing away from the viewer. */
    boolean isBack() { return layer == OUTER && viewAxis()[2] == -1; }

    /** The outer layer facing the viewer. */
    boolean isFront() { return layer == OUTER && viewAxis()[2] == 1; }

    /** The whole face towards the camera turns in place: the front layer, or the front two layers. */
    boolean turnsFrontFace() { return layer != MIDDLE && viewAxis()[2] == 1; }

    /**
     * View-space direction in which the layer's stickers on the face with view normal
     * {@code faceNormal} move, for a face the layer crosses; for a half turn, one of the two ways.
     */
    int[] motion(int[] faceNormal) {
        int[] across = CubeFrame.cross(viewAxis(), faceNormal);
        int sign = quarters > 0 ? 1 : -1;
        return new int[]{across[0] * sign, across[1] * sign, across[2] * sign};
    }

    /**
     * Standard notation relative to the cube as held: R L U D F B for the outer layers, M E S
     * between them, and a trailing w for two layers at once (Fw).
     */
    String notation() {
        return then == null ? ownNotation() : ownNotation() + " " + then.notation();
    }

    private String ownNotation() {
        int[] n = viewAxis();
        String letter;
        int clockwise;
        if (layer != MIDDLE) {
            letter = (n[0] == 1 ? "R" : n[0] == -1 ? "L" : n[1] == 1 ? "U"
                : n[1] == -1 ? "D" : n[2] == 1 ? "F" : "B") + (layer == WIDE ? "w" : "");
            clockwise = Math.floorMod(-quarters, 4);
        } else if (n[0] != 0) {
            // M turns like L: a positive turn about +x.
            letter = "M";
            clockwise = Math.floorMod(n[0] * quarters, 4);
        } else if (n[1] != 0) {
            // E turns like D: a positive turn about +y.
            letter = "E";
            clockwise = Math.floorMod(n[1] * quarters, 4);
        } else {
            // S turns like F: a negative turn about +z.
            letter = "S";
            clockwise = Math.floorMod(-n[2] * quarters, 4);
        }
        return clockwise == 2 ? letter + "2" : clockwise == 3 ? letter + "′" : letter;
    }

    /** Which layer, in the holder's words. */
    String place() {
        int[] n = viewAxis();
        if (layer == MIDDLE) return n[0] != 0 ? "中间竖层" : n[1] != 0 ? "中间横层" : "中间夹层";
        String side = n[0] != 0 ? (n[0] > 0 ? "右" : "左") : n[1] != 0 ? (n[1] > 0 ? "顶" : "底")
            : n[2] > 0 ? "前" : "后";
        if (layer == WIDE) return (side.equals("顶") ? "上" : side.equals("底") ? "下" : side) + "两层";
        return side + "层";
    }

    /**
     * Which way, in the holder's words: the turning sense when the whole front face turns, the
     * direction the layer's stickers travel on the front face when it crosses it, and otherwise
     * the direction its top row travels.
     */
    String direction() {
        if (isHalfTurn()) return "半圈";
        if (turnsFrontFace()) return quarters < 0 ? "顺时针" : "逆时针";
        int[] n = viewAxis();
        int[] moving = motion(n[2] == 0 ? VIEW_FRONT : VIEW_UP);
        if (moving[0] != 0) return moving[0] > 0 ? "向右" : "向左";
        return moving[1] > 0 ? "向上" : "向下";
    }

    /**
     * The whole instruction: "顶层向左拧", "前层顺时针拧", "前两层顺时针拧", "右层拧半圈", or two of
     * them joined by "再".
     */
    String caption() {
        String own = place() + (isHalfTurn() ? "拧半圈" : direction() + "拧");
        return then == null ? own : own + "，再" + then.caption();
    }
}
