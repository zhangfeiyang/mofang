package com.mofang.cubear;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * A small 3D Rubik cube that loops the current guidance step, held the way the user holds theirs.
 *
 * <p>The 3×3×3 cubies are coloured from the 54-facelet state and placed in view space by the
 * step's {@link CubeFrame}: the face the camera sees is in front, the face above it on top. The
 * cube is seen from slightly right of and above the front — where a person holding a cube looks
 * from — so the front, top and right sides show, and every layer a step can turn crosses one of
 * them. The turning layer rotates in 3D with the same convention as {@link CubeMoves}, and an
 * arrow runs along the stickers it moves.
 */
final class GuideCube {
    private static final long CYCLE_MS = 2000;
    private static final float CUBIE = 0.96f;
    private static final float STICKER = 0.78f;
    private static final int PLASTIC = 0xFF141414;
    private static final int PLASTIC_TURN = 0xFF2A2A2A;
    private static final int MINT = 0xFF74F5C5;
    private static final float YAW = -0.55f;
    private static final float PITCH = 0.46f;
    /** Height of the arrows above the sticker plane, in cubie units from the centre. */
    private static final float LIFT = 1.62f;
    private static final int MAX_QUADS = 256;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[][][] corners = new float[MAX_QUADS][4][3];
    private final int[] colors = new int[MAX_QUADS];
    private final float[] depth = new float[MAX_QUADS];
    private final int[] order = new int[MAX_QUADS];
    private final boolean[] turning = new boolean[MAX_QUADS];
    private final boolean[] isPlastic = new boolean[MAX_QUADS];
    private final float[] tmp = new float[3];
    private final float[] tmp2 = new float[3];
    private final float[] scr = new float[2];
    private final float[] normal = new float[3];
    private CubeFrame frame = CubeFrame.DEFAULT;

    GuideCube() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * @param partway the cube after the first part of a chained step, or null
     */
    void draw(Canvas canvas, RectF box, String cubeState, String partway, GuideStep step, long now) {
        if (step == null) return;
        if (cubeState == null || cubeState.length() != 54) cubeState = solved();
        // A chained step plays its two turns one after the other, each from the cube it starts on.
        String label = "";
        if (step.then != null && partway != null && partway.length() == 54) {
            long local = now % (CYCLE_MS * 2);
            boolean second = local >= CYCLE_MS;
            label = second ? "② " : "① ";
            cubeState = second ? partway : cubeState;
            step = second ? step.then : step.alone();
            now = local % CYCLE_MS;
        }

        float cx = box.centerX();
        float cy = box.centerY() - box.height() * 0.09f;
        // At this yaw and pitch the cube's silhouette spans about 4.0 x 4.4 units; this keeps it
        // inside the box with the caption chip below it.
        float scale = Math.min(box.width(), box.height()) * 0.165f;

        float phase = (now % CYCLE_MS) / (float) CYCLE_MS;
        float twist = twistAngle(phase, step.quarters);
        int count = collectFaces(cubeState, step.frame, step.axis, step.layer, twist);
        sortByDepth(count);

        canvas.save();
        canvas.clipRect(box);
        drawShadow(canvas, cx, cy, scale);
        for (int i = 0; i < count; i++) {
            int id = order[i];
            projectQuad(corners[id], cx, cy, scale);
            fill.setColor(colors[id]);
            canvas.drawPath(path, fill);
            if (!isPlastic[id]) {
                stroke.setColor(turning[id] ? 0xCC74F5C5 : 0x33000000);
                stroke.setStrokeWidth(box.width() * (turning[id] ? 0.012f : 0.008f));
                canvas.drawPath(path, stroke);
            }
        }
        drawArrow(canvas, cx, cy, scale, box.width(), step, phase);
        canvas.restore();

        drawCaption(canvas, box, step, label);
    }

    /** Eased rotation of the turning layer: still, turning, then holding the result. */
    private static float twistAngle(float phase, int quarters) {
        if (phase <= 0.14f) return 0f;
        // Signed, so a counter-clockwise step turns a quarter the short way round instead of
        // three quarters clockwise.
        float target = (float) (Math.PI / 2.0 * quarters);
        if (phase >= 0.78f) return target;
        float t = (phase - 0.14f) / 0.64f;
        float ease = t * t * (3f - 2f * t);
        return target * ease;
    }

    /**
     * Fills the quad buffers for one frame of the animation.
     *
     * @param axis cube-space axis of the turning layer
     * @param layer which layers turn, as {@link GuideStep#layer}
     * @param twist right-handed rotation of that layer, radians
     * @return quads emitted
     */
    int collectFaces(String state, CubeFrame held, int[] axis, int layer, float twist) {
        frame = held;
        float[] turnAxis = {axis[0], axis[1], axis[2]};
        int n = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    int along = x * axis[0] + y * axis[1] + z * axis[2];
                    boolean moving = GuideStep.turns(layer, along);
                    boolean core = x == 0 && y == 0 && z == 0;
                    // emitCubie returns the next free slot, not a count.
                    if (!core) {
                        n = emitCubie(n, state, x, y, z, moving ? twist : 0f, moving ? turnAxis : null);
                    }
                    // The core has no stickers, but its sides close the middle of a cut.
                    if (twist != 0f) {
                        n = emitCut(n, x, y, z, axis, along, layer, moving ? twist : 0f,
                            moving ? turnAxis : null);
                    }
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

    /**
     * The plastic where the turning layer meets its neighbours. Inside a still cube those sides
     * are hidden; once the layer turns they show, and without them the cube looks hollow — most
     * of all when the middle layer turns.
     */
    private int emitCut(int n, int x, int y, int z, int[] axis, int along, int layer, float twist,
                        float[] turnAxis) {
        for (int side = -1; side <= 1; side += 2) {
            int neighbour = along + side;
            boolean faces = Math.abs(neighbour) <= 1
                && GuideStep.turns(layer, along) != GuideStep.turns(layer, neighbour);
            if (faces) {
                n = emitFace(n, x, y, z, axis[0] * side, axis[1] * side, axis[2] * side, 0,
                    twist, turnAxis);
            }
        }
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
        if (n >= MAX_QUADS) return n;
        // Faces turned away from the viewer are skipped: drawn first and painted over, they still
        // showed through the gaps between cubies as stray coloured lines.
        setCorner(normal, nx, ny, nz, twist, axis);
        if (normal[2] <= 0.04f) return n;
        boolean plastic = color == 0;
        int base = plastic ? (axis != null ? PLASTIC_TURN : PLASTIC) : color;
        // Soft light from the upper left so the three visible sides read as a solid.
        float light = normal[0] * -0.38f + normal[1] * 0.74f + normal[2] * 0.56f;
        int paint = shade(base, 0.74f + 0.26f * Math.max(0f, light));
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
        // A sticker shares its plane with the plastic shell under it; without a bias rounding
        // decides which is painted last and the shell blots out stickers.
        depth[n] = dz / 4f + (plastic ? 0f : 0.01f);
        colors[n] = paint;
        isPlastic[n] = plastic;
        turning[n] = axis != null && !plastic;
        return n + 1;
    }

    private static int shade(int argb, float k) {
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((argb & 0xFF) * k));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Cube space → turned layer → the holder's view → the slightly raised, rightward camera. */
    private void setCorner(float[] out, float x, float y, float z, float twist, float[] axis) {
        tmp[0] = x; tmp[1] = y; tmp[2] = z;
        if (axis != null && twist != 0f) {
            rodrigues(tmp, axis, twist, tmp2);
            tmp[0] = tmp2[0]; tmp[1] = tmp2[1]; tmp[2] = tmp2[2];
        }
        int[] r = frame.right, u = frame.up, f = frame.front;
        tmp2[0] = r[0] * tmp[0] + r[1] * tmp[1] + r[2] * tmp[2];
        tmp2[1] = u[0] * tmp[0] + u[1] * tmp[1] + u[2] * tmp[2];
        tmp2[2] = f[0] * tmp[0] + f[1] * tmp[1] + f[2] * tmp[2];
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
     * The direction cue: a ring on the front face when that whole face turns (the front layer, or
     * the front two layers), otherwise a straight arrow along the stickers the layer carries — on
     * the front face for every layer it crosses, on the top face for the back layer.
     */
    private void drawArrow(Canvas canvas, float cx, float cy, float scale, float boxW,
                           GuideStep step, float phase) {
        float pulse = 0.55f + 0.45f * (float) Math.sin(
            Math.min(1f, Math.max(0f, (phase - 0.14f) / 0.64f)) * Math.PI);
        if (step.turnsFrontFace()) {
            drawRing(canvas, cx, cy, scale, boxW, step.quarters > 0, step.isHalfTurn(), pulse);
            return;
        }
        int[] n = step.viewAxis();
        int[] face = n[2] == 0 ? GuideStep.VIEW_FRONT : GuideStep.VIEW_UP;
        int[] d = step.motion(face);
        float bx = face[0] * LIFT + n[0] * step.layer;
        float by = face[1] * LIFT + n[1] * step.layer;
        float bz = face[2] * LIFT + n[2] * step.layer;
        float reach = 1.3f;
        project(bx - d[0] * reach, by - d[1] * reach, bz - d[2] * reach, cx, cy, scale);
        float x0 = scr[0], y0 = scr[1];
        project(bx + d[0] * reach, by + d[1] * reach, bz + d[2] * reach, cx, cy, scale);
        float x1 = scr[0], y1 = scr[1];

        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(0x99000000);
        stroke.setStrokeWidth(boxW * 0.044f);
        canvas.drawLine(x0, y0, x1, y1, stroke);
        stroke.setColor(MINT);
        stroke.setStrokeWidth(boxW * 0.028f);
        stroke.setAlpha((int) (90 + 150 * pulse));
        canvas.drawLine(x0, y0, x1, y1, stroke);
        stroke.setAlpha(255);
        drawHead(canvas, x0, y0, x1, y1, boxW);

        if (step.isHalfTurn()) {
            project(bx, by, bz, cx, cy, scale);
            drawTwice(canvas, scr[0], scr[1], boxW);
        }
    }

    /** Direction ring on the front face, clockwise as seen from the front unless told otherwise. */
    private void drawRing(Canvas canvas, float cx, float cy, float scale, float boxW,
                          boolean counter, boolean twice, float pulse) {
        float dir = counter ? -1f : 1f;
        float sweep = twice ? 3.2f : 2.5f;
        float start = counter ? 0.9f : -0.7f;
        float radius = 1.22f;
        int steps = 22;
        path.reset();
        for (int i = 0; i <= steps; i++) {
            float a = start + dir * sweep * (i / (float) steps);
            project(radius * (float) Math.sin(a), radius * (float) Math.cos(a), LIFT, cx, cy, scale);
            if (i == 0) path.moveTo(scr[0], scr[1]);
            else path.lineTo(scr[0], scr[1]);
        }
        stroke.setColor(MINT);
        stroke.setStrokeWidth(boxW * 0.028f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setAlpha((int) (90 + 150 * pulse));
        canvas.drawPath(path, stroke);
        stroke.setAlpha(255);

        float tip = start + dir * sweep;
        float back = tip - dir * 0.28f;
        project(radius * (float) Math.sin(back), radius * (float) Math.cos(back), LIFT, cx, cy, scale);
        float bx = scr[0], by = scr[1];
        project(radius * (float) Math.sin(tip), radius * (float) Math.cos(tip), LIFT, cx, cy, scale);
        drawHead(canvas, bx, by, scr[0], scr[1], boxW);

        if (twice) {
            project(0f, 0f, LIFT, cx, cy, scale);
            drawTwice(canvas, scr[0], scr[1], boxW);
        }
    }

    /** A filled arrowhead at (tx, ty), pointing away from (fx, fy). */
    private void drawHead(Canvas canvas, float fx, float fy, float tx, float ty, float boxW) {
        float dx = tx - fx, dy = ty - fy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        dx /= len; dy /= len;
        float px = -dy, py = dx;
        float head = boxW * 0.055f;
        path.reset();
        path.moveTo(tx + dx * head * 0.35f, ty + dy * head * 0.35f);
        path.lineTo(tx - dx * head + px * head * 0.6f, ty - dy * head + py * head * 0.6f);
        path.lineTo(tx - dx * head - px * head * 0.6f, ty - dy * head - py * head * 0.6f);
        path.close();
        fill.setColor(MINT);
        canvas.drawPath(path, fill);
    }

    private void drawTwice(Canvas canvas, float x, float y, float boxW) {
        fill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTextSize(boxW * 0.10f);
        fill.setColor(0xCC000000);
        canvas.drawCircle(x, y, boxW * 0.085f, fill);
        fill.setColor(MINT);
        canvas.drawText("×2", x, y + fill.getTextSize() * 0.35f, fill);
        fill.setTextAlign(Paint.Align.LEFT);
    }

    /** View-space point to screen, through the same raised, rightward camera as the cubies. */
    private void project(float x, float y, float z, float cx, float cy, float scale) {
        tmp[0] = x; tmp[1] = y; tmp[2] = z;
        rotateY(tmp, YAW, tmp2);
        rotateX(tmp2, PITCH, tmp);
        scr[0] = cx + tmp[0] * scale;
        scr[1] = cy - tmp[1] * scale;
    }

    private void drawCaption(Canvas canvas, RectF box, GuideStep step, String label) {
        String text = label + step.caption();
        fill.setColor(0xE6101C18);
        float pad = box.width() * 0.06f;
        RectF chip = new RectF(box.left + pad, box.bottom - box.height() * 0.20f,
            box.right - pad, box.bottom - pad * 0.35f);
        canvas.drawRoundRect(chip, chip.height() / 2f, chip.height() / 2f, fill);
        boolean dot = step.face != 0;
        float textLeft = chip.left + chip.height() * (dot ? 0.95f : 0.45f);
        float textRight = chip.right - chip.height() * 0.45f;
        if (dot) {
            fill.setColor(CubeColor.fromFace(step.face).argb);
            canvas.drawCircle(chip.left + chip.height() * 0.52f, chip.centerY(),
                chip.height() * 0.22f, fill);
        }
        fill.setColor(Color.WHITE);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        float size = chip.height() * 0.40f;
        fill.setTextSize(size);
        float width = fill.measureText(text);
        if (width > textRight - textLeft) fill.setTextSize(size * (textRight - textLeft) / width);
        canvas.drawText(text, (textLeft + textRight) / 2f,
            chip.centerY() + fill.getTextSize() * 0.35f, fill);
        fill.setTextAlign(Paint.Align.LEFT);
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
