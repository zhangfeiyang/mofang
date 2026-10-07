package com.mofang.cubear;

import java.util.Arrays;

/**
 * How the cube sits in front of the camera: which of its faces looks at the viewer, which one is
 * up and which one is on the right.
 *
 * <p>Faces are named by their centre colour, the way the solver names them (URFDLB). The frame
 * stores the cube-space unit vectors that point to the viewer's right, up and towards the camera.
 * One steady look at a face pins it down completely: the centre says which face is in front, and
 * the rotation at which its stickers match the expected state says which neighbour is on top.
 *
 * <p>View space is the camera picture's: +x right, +y up, +z towards the camera. The rear camera
 * does not mirror, so it is also the space of the person holding the cube behind the phone.
 */
final class CubeFrame {
    private static final String FACES = "URFDLB";
    private static final int[] X = {1, 0, 0};
    private static final int[] Y = {0, 1, 0};
    private static final int[] Z = {0, 0, 1};

    /** Green towards the viewer, white on top: the assumption before the camera has seen the cube. */
    static final CubeFrame DEFAULT = seen('F', 0);

    /** Cube-space unit vectors pointing to the viewer's right, up and towards the camera. */
    final int[] right, up, front;

    private CubeFrame(int[] right, int[] up, int[] front) {
        this.right = right;
        this.up = up;
        this.front = front;
    }

    /**
     * The frame in which {@code face} looks at the camera and its reading, turned clockwise
     * {@code rotation} times, matches the face's layout in the facelet string — that is, bit
     * {@code rotation} of {@link MoveTracker#matchMask}.
     */
    static CubeFrame seen(char face, int rotation) {
        int[] front = normal(face);
        int[] layoutUp = layoutUp(face);
        int[] layoutRight = cross(layoutUp, front);
        int[] up;
        switch (rotation & 3) {
            case 0: up = layoutUp; break;
            // A reading that must turn clockwise once to match was taken with the layout's left
            // edge at the top of the picture: the cube is rolled a quarter turn clockwise.
            case 1: up = layoutRight; break;
            case 2: up = negate(layoutUp); break;
            default: up = negate(layoutRight); break;
        }
        return new CubeFrame(cross(up, front), up, front);
    }

    /**
     * The frame a steady look implies, or null when the look matches no rotation.
     *
     * <p>A face with repeated stickers matches at several rotations, and a reading near 45° of
     * roll may have its corners shifted by one; either way the candidate nearest the previous
     * frame wins, because people re-grip a cube a quarter turn at a time.
     *
     * @param mask {@link MoveTracker#matchMask} of the look against its expected face
     * @param rollStable false when the reading's rotation itself cannot be trusted
     * @param previous the frame so far, or null when none is known
     */
    static CubeFrame fromLook(char face, int mask, boolean rollStable, CubeFrame previous) {
        if (FACES.indexOf(face) < 0 || (mask & 0xF) == 0) return null;
        if (previous != null && previous.front() == face) {
            // Same face still in front: a roll is only believed when the reading is trustworthy
            // and the old roll no longer fits.
            if (!rollStable || (mask & (1 << previous.rotation())) != 0) return previous;
        }
        CubeFrame best = null;
        int bestAgreement = Integer.MIN_VALUE, bestUp = Integer.MIN_VALUE;
        for (int rotation = 0; rotation < 4; rotation++) {
            if ((mask & (1 << rotation)) == 0) continue;
            CubeFrame candidate = seen(face, rotation);
            if (previous == null) return candidate;
            int agreement = candidate.agreement(previous);
            int keepsUp = dot(candidate.up, previous.up);
            if (agreement > bestAgreement || (agreement == bestAgreement && keepsUp > bestUp)) {
                best = candidate;
                bestAgreement = agreement;
                bestUp = keepsUp;
            }
        }
        return best;
    }

    /** The face looking at the camera. */
    char front() { return faceOf(front); }

    /** The face pointing along a view-space axis direction. */
    char faceAt(int[] view) { return faceOf(toCube(view)); }

    /** View-space coordinates of a cube-space vector. */
    int[] toView(int[] cube) {
        return new int[]{dot(right, cube), dot(up, cube), dot(front, cube)};
    }

    /** Cube-space coordinates of a view-space vector. */
    int[] toCube(int[] view) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) out[i] = right[i] * view[0] + up[i] * view[1] + front[i] * view[2];
        return out;
    }

    /** The rotation bit at which the front face reads in this frame; the inverse of {@link #seen}. */
    int rotation() {
        char face = front();
        for (int rotation = 0; rotation < 4; rotation++) {
            if (Arrays.equals(seen(face, rotation).up, up)) return rotation;
        }
        throw new IllegalStateException("not a rotation");
    }

    /**
     * This frame after the centres themselves turned {@code quarters} right-handed quarter turns
     * about the view-space {@code viewAxis} — what a middle-layer turn does while the hands hold
     * the outer layers still.
     */
    CubeFrame turned(int[] viewAxis, int quarters) {
        // Whatever now points at view direction v pointed at R⁻¹·v before the turn.
        return new CubeFrame(
            toCube(rotate(X, viewAxis, -quarters)),
            toCube(rotate(Y, viewAxis, -quarters)),
            toCube(rotate(Z, viewAxis, -quarters)));
    }

    /** A dense index 0..23, one per way of holding the cube. */
    int id() { return FACES.indexOf(front()) * 4 + rotation(); }

    /** Trace of the rotation between two frames: 3 identical, 1 a quarter turn apart, -1 a half. */
    int agreement(CubeFrame other) {
        return dot(right, other.right) + dot(up, other.up) + dot(front, other.front);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CubeFrame)) return false;
        CubeFrame frame = (CubeFrame) other;
        return Arrays.equals(right, frame.right) && Arrays.equals(up, frame.up)
            && Arrays.equals(front, frame.front);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(right) * 961 + Arrays.hashCode(up) * 31 + Arrays.hashCode(front);
    }

    @Override public String toString() {
        return "front " + front() + ", up " + faceOf(up) + ", right " + faceOf(right);
    }

    /** Outward normal of a face, in the coordinates of {@link CubeMoves}. */
    static int[] normal(char face) {
        switch (face) {
            case 'U': return new int[]{0, 1, 0};
            case 'R': return new int[]{1, 0, 0};
            case 'F': return new int[]{0, 0, 1};
            case 'D': return new int[]{0, -1, 0};
            case 'L': return new int[]{-1, 0, 0};
            case 'B': return new int[]{0, 0, -1};
            default: throw new IllegalArgumentException("no face " + face);
        }
    }

    static char faceOf(int[] v) {
        if (v[0] == 1) return 'R';
        if (v[0] == -1) return 'L';
        if (v[1] == 1) return 'U';
        if (v[1] == -1) return 'D';
        if (v[2] == 1) return 'F';
        if (v[2] == -1) return 'B';
        throw new IllegalArgumentException(Arrays.toString(v));
    }

    /** Direction of row 0 of a face's 3×3 block in the facelet string, as {@link CubeMoves} lays it out. */
    private static int[] layoutUp(char face) {
        switch (face) {
            case 'U': return new int[]{0, 0, -1};
            case 'D': return new int[]{0, 0, 1};
            default: return new int[]{0, 1, 0};
        }
    }

    /** {@code v} turned {@code quarters} right-handed quarter turns about the unit {@code axis}. */
    static int[] rotate(int[] v, int[] axis, int quarters) {
        int[] out = v.clone();
        for (int i = 0; i < Math.floorMod(quarters, 4); i++) {
            int[] across = cross(axis, out);
            int along = dot(axis, out);
            for (int k = 0; k < 3; k++) out[k] = across[k] + axis[k] * along;
        }
        return out;
    }

    static int dot(int[] a, int[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static int[] cross(int[] a, int[] b) {
        return new int[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static int[] negate(int[] v) { return new int[]{-v[0], -v[1], -v[2]}; }
}
