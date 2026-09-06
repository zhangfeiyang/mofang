package com.mofang.cubear;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * A small isometric Rubik cube that loops the current guidance move.
 *
 * <p>The 3×3×3 cubies are coloured from the 54-facelet state. The turning layer rotates in 3D
 * with the same clockwise convention as {@link CubeMoves}, and the camera is re-aimed so the
 * face being twisted always sits in front (U/R still peek from the top and right).
 */
final class GuideCube {
    private static final long CYCLE_MS = 2000;
    private static final float CUBIE = 0.92f;
    private static final float STICKER = 0.78f;
    private static final int PLASTIC = 0xFF141414;
    private static final int PLASTIC_TURN = 0xFF2A2A2A;
    private static final int MINT = 0xFF74F5C5;
    private static final float YAW = 0.55f;
    private static final float PITCH = -0.46f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[][][] corners = new float[160][4][3];
    private final int[] colors = new int[160];
    private final float[] depth = new float[160];
    private final int[] order = new int[160];
    private final boolean[] turning = new boolean[160];
    private final float[] tmp = new float[3];
    private final float[] tmp2 = new float[3];
    private final float[] scr = new float[2];
    private char cameraFace = 'F';

    GuideCube() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    void draw(Canvas canvas, RectF box, String cubeState, String move, long now) {
        if (cubeState == null || cubeState.length() != 54) cubeState = solved();
        if (move == null || move.isEmpty()) move = "R";

        float cx = box.centerX();
        float cy = box.centerY() - box.height() * 0.08f;
        float scale = box.width() * 0.28f;

        char face = move.charAt(0);
        int turns = move.endsWith("2") ? 2 : move.endsWith("'") ? 3 : 1;
        float phase = (now % CYCLE_MS) / (float) CYCLE_MS;
        float twist = twistAngle(phase, turns);

        int count = collectFaces(cubeState, face, twist);
        sortByDepth(count);

        canvas.save();
        canvas.clipRect(box);
        drawShadow(canvas, cx, cy, scale);
        for (int i = 0; i < count; i++) {
            int id = order[i];
            projectQuad(corners[id], cx, cy, scale);
            fill.setColor(colors[id]);
            canvas.drawPath(path, fill);
            if (colors[id] != PLASTIC && colors[id] != PLASTIC_TURN) {
                stroke.setColor(turning[id] ? 0xCC74F5C5 : 0x33000000);
                stroke.setStrokeWidth(box.width() * (turning[id] ? 0.012f : 0.008f));
                canvas.drawPath(path, stroke);
            }
        }
        drawTwistArrow(canvas, cx, cy, scale, box.width(), turns, phase);
        canvas.restore();

        drawCaption(canvas, box, move);
    }

    private static float twistAngle(float phase, int turns) {
        if (phase <= 0.14f) return 0f;
        float target = (float) (-Math.PI / 2.0 * turns);
        if (phase >= 0.78f) return target;
        float t = (phase - 0.14f) / 0.64f;
        float ease = t * t * (3f - 2f * t);
        return target * ease;
    }

    private int collectFaces(String state, char moveFace, float twist) {
        cameraFace = moveFace;
        int n = 0;
        float[] axis = axisFor(moveFace);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    boolean layer = onLayer(x, y, z, moveFace);
                    n += emitCubie(n, state, x, y, z, layer ? twist : 0f, layer ? axis : null);
                }
            }
        }
        return n;
    }

    private int emitCubie(int n, String state, int x, int y, int z, float twist, float[] axis) {
        int[] sticker = cubieColors(state, x, y, z);
        n = emitShellAndSticker(n, x, y, z, 1, 0, 0, sticker[0], twist, axis);
        n = emitShellAndSticker(n, x, y, z, -1, 0, 0, sticker[1], twist, axis);
        n = emitShellAndSticker(n, x, y, z, 0, 1, 0, sticker[2], twist, axis);
        n = emitShellAndSticker(n, x, y, z, 0, -1, 0, sticker[3], twist, axis);
        n = emitShellAndSticker(n, x, y, z, 0, 0, 1, sticker[4], twist, axis);
        n = emitShellAndSticker(n, x, y, z, 0, 0, -1, sticker[5], twist, axis);
        return n;
    }

    private int emitShellAndSticker(int n, int x, int y, int z, int nx, int ny, int nz, int color,
                                    float twist, float[] axis) {
        if (x * nx + y * ny + z * nz <= 0) return n;
        n = emitFace(n, x, y, z, nx, ny, nz, 0, twist, axis);
        if (color != 0) n = emitFace(n, x, y, z, nx, ny, nz, color, twist, axis);
        return n;
    }

    private int emitFace(int n, int cx, int cy, int cz, int nx, int ny, int nz, int color,
                         float twist, float[] axis) {
        boolean plastic = color == 0;
        int paint = plastic ? (axis != null ? PLASTIC_TURN : PLASTIC) : color;
        float half = CUBIE * 0.5f;
        float sticker = (plastic ? CUBIE : STICKER) * 0.5f;
        float ox = nx * half, oy = ny * half, oz = nz * half;
        float ux, uy, uz, vx, vy, vz;
        if (nx != 0) { ux = 0; uy = sticker; uz = 0; vx = 0; vy = 0; vz = sticker; }
        else if (ny != 0) { ux = sticker; uy = 0; uz = 0; vx = 0; vy = 0; vz = sticker; }
        else { ux = sticker; uy = 0; uz = 0; vx = 0; vy = sticker; vz = 0; }
        float[][] quad = corners[n];
        setCorner(quad[0], cx + ox - ux - vx, cy + oy - uy - vy, cz + oz - uz - vz, twist, axis);
        setCorner(quad[1], cx + ox + ux - vx, cy + oy + uy - vy, cz + oz + uz - vz, twist, axis);
        setCorner(quad[2], cx + ox + ux + vx, cy + oy + uy + vy, cz + oz + uz + vz, twist, axis);
        setCorner(quad[3], cx + ox - ux + vx, cy + oy - uy + vy, cz + oz - uz + vz, twist, axis);
        float dz = 0;
        for (int i = 0; i < 4; i++) dz += quad[i][2];
        depth[n] = dz / 4f;
        colors[n] = paint;
        turning[n] = axis != null && !plastic;
        return n + 1;
    }

    private void setCorner(float[] out, float x, float y, float z, float twist, float[] axis) {
        tmp[0] = x; tmp[1] = y; tmp[2] = z;
        if (axis != null && twist != 0f) {
            rodrigues(tmp, axis, twist, tmp2);
            tmp[0] = tmp2[0]; tmp[1] = tmp2[1]; tmp[2] = tmp2[2];
        }
        orientFaceToFront(tmp, cameraFace, tmp2);
        rotateY(tmp2, YAW, tmp);
        rotateX(tmp, PITCH, out);
    }

    private void projectQuad(float[][] world, float cx, float cy, float scale) {
        path.reset();
        for (int i = 0; i < 4; i++) {
            float sx = cx + world[i][0] * scale;
            float sy = cy - world[i][1] * scale;
            if (i == 0) path.moveTo(sx, sy);
            else path.lineTo(sx, sy);
        }
        path.close();
    }

    private void sortByDepth(int count) {
        for (int i = 0; i < count; i++) order[i] = i;
        for (int i = 1; i < count; i++) {
            int key = order[i];
            float d = depth[key];
            int j = i - 1;
            while (j >= 0 && depth[order[j]] > d) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
    }

    private void drawShadow(Canvas canvas, float cx, float cy, float scale) {
        fill.setColor(0x55000000);
        canvas.drawOval(cx - scale * 1.55f, cy + scale * 1.05f,
            cx + scale * 1.55f, cy + scale * 1.42f, fill);
    }

    /**
     * Direction ring drawn in the remapped front plane so clockwise is the same as looking at
     * that face on a real cube.
     */
    private void drawTwistArrow(Canvas canvas, float cx, float cy, float scale, float boxW,
                                int turns, float phase) {
        boolean ccw = turns == 3;
        float dir = ccw ? -1f : 1f;
        float sweep = turns == 2 ? 3.2f : 2.5f;
        float start = ccw ? 0.9f : -0.7f;
        float radius = 1.22f;
        float z = 1.28f;
        int steps = 22;
        path.reset();
        for (int i = 0; i <= steps; i++) {
            float a = start + dir * sweep * (i / (float) steps);
            projectFront(radius * (float) Math.sin(a), radius * (float) Math.cos(a), z, cx, cy, scale);
            if (i == 0) path.moveTo(scr[0], scr[1]);
            else path.lineTo(scr[0], scr[1]);
        }
        stroke.setColor(MINT);
        stroke.setStrokeWidth(boxW * 0.028f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        float pulse = 0.55f + 0.45f * (float) Math.sin(Math.min(1f, Math.max(0f, (phase - 0.14f) / 0.64f)) * Math.PI);
        stroke.setAlpha((int) (90 + 150 * pulse));
        canvas.drawPath(path, stroke);
        stroke.setAlpha(255);

        float tip = start + dir * sweep;
        float back = tip - dir * 0.28f;
        projectFront(radius * (float) Math.sin(tip), radius * (float) Math.cos(tip), z, cx, cy, scale);
        float tx = scr[0], ty = scr[1];
        projectFront(radius * (float) Math.sin(back), radius * (float) Math.cos(back), z, cx, cy, scale);
        float bx = scr[0], by = scr[1];
        float dx = tx - bx, dy = ty - by;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        dx /= len; dy /= len;
        float px = -dy, py = dx;
        float head = boxW * 0.055f;
        path.reset();
        path.moveTo(tx, ty);
        path.lineTo(tx - dx * head + px * head * 0.55f, ty - dy * head + py * head * 0.55f);
        path.lineTo(tx - dx * head - px * head * 0.55f, ty - dy * head - py * head * 0.55f);
        path.close();
        fill.setColor(MINT);
        canvas.drawPath(path, fill);

        if (turns == 2) {
            fill.setColor(MINT);
            fill.setTextAlign(Paint.Align.CENTER);
            fill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            fill.setTextSize(boxW * 0.11f);
            projectFront(0f, 0f, z, cx, cy, scale);
            canvas.drawText("×2", scr[0], scr[1] + fill.getTextSize() * 0.35f, fill);
            fill.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** Front-plane point after the turning face has been mapped to +Z. */
    private void projectFront(float x, float y, float z, float cx, float cy, float scale) {
        tmp[0] = x; tmp[1] = y; tmp[2] = z;
        rotateY(tmp, YAW, tmp2);
        rotateX(tmp2, PITCH, tmp);
        scr[0] = cx + tmp[0] * scale;
        scr[1] = cy - tmp[1] * scale;
    }

    private void drawCaption(Canvas canvas, RectF box, String move) {
        CubeColor target = CubeColor.fromFace(move.charAt(0));
        String action = move.endsWith("'") ? "逆时针拧" : move.endsWith("2") ? "拧半圈" : "顺时针拧";
        String text = action + target.chinese + "面";
        fill.setColor(0xE6101C18);
        float pad = box.width() * 0.06f;
        RectF chip = new RectF(box.left + pad, box.bottom - box.height() * 0.20f,
            box.right - pad, box.bottom - pad * 0.35f);
        canvas.drawRoundRect(chip, chip.height() / 2f, chip.height() / 2f, fill);
        fill.setColor(target.argb);
        canvas.drawCircle(chip.left + chip.height() * 0.52f, chip.centerY(),
            chip.height() * 0.22f, fill);
        fill.setColor(Color.WHITE);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        fill.setTextSize(Math.min(chip.height() * 0.38f, chip.width() * 0.13f));
        canvas.drawText(text, chip.centerX() + chip.height() * 0.10f,
            chip.centerY() + fill.getTextSize() * 0.35f, fill);
        fill.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * Rotate the model so {@code face} becomes +Z (camera front). U stays roughly up after the
     * isometric tilt except when the move itself is U or D.
     */
    static void orientFaceToFront(float[] v, char face, float[] out) {
        switch (face) {
            case 'U': rotateX(v, (float) (Math.PI / 2.0), out); break;
            case 'D': rotateX(v, (float) (-Math.PI / 2.0), out); break;
            case 'R': rotateY(v, (float) (-Math.PI / 2.0), out); break;
            case 'L': rotateY(v, (float) (Math.PI / 2.0), out); break;
            case 'B': rotateY(v, (float) Math.PI, out); break;
            default:
                out[0] = v[0];
                out[1] = v[1];
                out[2] = v[2];
        }
    }

    static int[] cubieColors(String state, int x, int y, int z) {
        int[] faces = new int[6];
        for (int index = 0; index < 54; index++) {
            int[] pn = stickerPos(index);
            if (pn[0] != x || pn[1] != y || pn[2] != z) continue;
            int slot = faceSlot(pn[3], pn[4], pn[5]);
            if (slot >= 0) faces[slot] = CubeColor.fromFace(state.charAt(index)).argb;
        }
        return faces;
    }

    /** Position xyz + normal xyz for facelet index, matching {@link CubeMoves}. */
    static int[] stickerPos(int index) {
        int face = index / 9, local = index % 9, row = local / 3, col = local % 3;
        switch (face) {
            case 0: return new int[]{col - 1, 1, row - 1, 0, 1, 0};
            case 1: return new int[]{1, 1 - row, 1 - col, 1, 0, 0};
            case 2: return new int[]{col - 1, 1 - row, 1, 0, 0, 1};
            case 3: return new int[]{col - 1, -1, 1 - row, 0, -1, 0};
            case 4: return new int[]{-1, 1 - row, col - 1, -1, 0, 0};
            default: return new int[]{1 - col, 1 - row, -1, 0, 0, -1};
        }
    }

    private static int faceSlot(int nx, int ny, int nz) {
        if (nx == 1) return 0;
        if (nx == -1) return 1;
        if (ny == 1) return 2;
        if (ny == -1) return 3;
        if (nz == 1) return 4;
        if (nz == -1) return 5;
        return -1;
    }

    private static boolean onLayer(int x, int y, int z, char face) {
        switch (face) {
            case 'U': return y == 1;
            case 'D': return y == -1;
            case 'R': return x == 1;
            case 'L': return x == -1;
            case 'F': return z == 1;
            case 'B': return z == -1;
            default: return false;
        }
    }

    private static float[] axisFor(char face) {
        switch (face) {
            case 'U': return new float[]{0, 1, 0};
            case 'D': return new float[]{0, -1, 0};
            case 'R': return new float[]{1, 0, 0};
            case 'L': return new float[]{-1, 0, 0};
            case 'F': return new float[]{0, 0, 1};
            case 'B': return new float[]{0, 0, -1};
            default: return new float[]{1, 0, 0};
        }
    }

    private static void rodrigues(float[] v, float[] u, float a, float[] out) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float dot = v[0] * u[0] + v[1] * u[1] + v[2] * u[2];
        float cx = u[1] * v[2] - u[2] * v[1];
        float cy = u[2] * v[0] - u[0] * v[2];
        float cz = u[0] * v[1] - u[1] * v[0];
        out[0] = v[0] * c + cx * s + u[0] * dot * (1 - c);
        out[1] = v[1] * c + cy * s + u[1] * dot * (1 - c);
        out[2] = v[2] * c + cz * s + u[2] * dot * (1 - c);
    }

    static void rotateY(float[] v, float a, float[] out) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        out[0] = v[0] * c + v[2] * s;
        out[1] = v[1];
        out[2] = -v[0] * s + v[2] * c;
    }

    static void rotateX(float[] v, float a, float[] out) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        out[0] = v[0];
        out[1] = v[1] * c - v[2] * s;
        out[2] = v[1] * s + v[2] * c;
    }

    static String solved() {
        return "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    }
}
