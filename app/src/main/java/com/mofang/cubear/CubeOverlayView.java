package com.mofang.cubear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

/**
 * The AR layer over the camera: the tracked face lattice, the capture ring, capture feedback,
 * guidance arrows and the finish confetti. Text and controls live in the HUD views above it.
 *
 * <p>Detections arrive at the analysis rate, 10-20 times a second, so drawing them directly made
 * the lattice jump. The drawn quad instead glides toward the latest detection every display
 * frame, and its corner order is matched cyclically to the previous one so the cells never spin
 * when the detector's top-left choice flips.
 */
public final class CubeOverlayView extends View {
    private static final int MINT = 0xFF6FF2C2;
    private static final int AMBER = 0xFFFFC857;
    private static final long FLASH_MS = 520;
    private static final long CELEBRATE_MS = 2600;
    /** Time constant of the glide toward a new detection. */
    private static final float GLIDE_MS = 55f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] shown = new float[8];
    private final float[] target = new float[8];
    private final double[] quad = new double[8];
    private final double[] point = new double[2];
    private final DashPathEffect[] flow = new DashPathEffect[12];
    private CubeUiState state = CubeUiState.builder(CubeUiState.Phase.SCANNING).build();
    private boolean hasShown;
    private int shownShift;
    private float visibility;
    private float shownProgress;
    private long lastFrame;
    private long flashAt = -100_000;
    private long celebrateAt = -100_000;
    private final float[][] confetti = new float[90][7];
    private final int[] confettiColor = new int[90];

    public CubeOverlayView(Context context) { this(context, null); }

    public CubeOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < flow.length; i++) {
            flow[i] = new DashPathEffect(new float[]{14 * d, 9 * d}, -i * 23 * d / flow.length);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setState(CubeUiState state) {
        this.state = state;
        postInvalidateOnAnimation();
    }

    /** A brief flash over the quad after a face is captured. */
    public void onFaceCaptured() {
        flashAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Confetti for the restored cube. */
    public void celebrate() {
        celebrateAt = SystemClock.uptimeMillis();
        Random random = new Random();
        int[] palette = {CubeColor.WHITE.argb, CubeColor.RED.argb, CubeColor.GREEN.argb,
            CubeColor.YELLOW.argb, CubeColor.ORANGE.argb, CubeColor.BLUE.argb, MINT};
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        for (int i = 0; i < confetti.length; i++) {
            float[] c = confetti[i];
            c[0] = w * (0.1f + 0.8f * random.nextFloat());   // x
            c[1] = h * (0.25f + 0.2f * random.nextFloat());  // y
            c[2] = (random.nextFloat() - 0.5f) * w * 1.4f;   // vx px/s
            c[3] = -h * (0.55f + 0.5f * random.nextFloat()); // vy px/s
            c[4] = random.nextFloat() * 360f;                // angle
            c[5] = (random.nextFloat() - 0.5f) * 720f;       // spin deg/s
            c[6] = 0.6f + random.nextFloat() * 0.8f;         // size
            confettiColor[i] = palette[random.nextInt(palette.length)];
        }
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.min(60f, now - lastFrame);
        lastFrame = now;
        float d = getResources().getDisplayMetrics().density;
        boolean animating = false;

        boolean tracked = mapDetection(getWidth(), getHeight());
        if (tracked) {
            int shift = hasShown ? bestShift(shown, target) : 0;
            float jump = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + shift) % 4;
                jump = Math.max(jump, (float) Math.hypot(target[j * 2] - shown[i * 2],
                    target[j * 2 + 1] - shown[i * 2 + 1]));
            }
            float diagonal = (float) Math.hypot(target[0] - target[4], target[1] - target[5]);
            if (!hasShown || visibility < 0.05f || jump > diagonal * 0.6f) {
                shift = 0;
                System.arraycopy(target, 0, shown, 0, 8);
            } else {
                float k = 1f - (float) Math.exp(-dt / GLIDE_MS);
                for (int i = 0; i < 4; i++) {
                    int j = (i + shift) % 4;
                    shown[i * 2] += (target[j * 2] - shown[i * 2]) * k;
                    shown[i * 2 + 1] += (target[j * 2 + 1] - shown[i * 2 + 1]) * k;
                }
                if (jump > 0.5f) animating = true;
            }
            shownShift = shift;
            hasShown = true;
            visibility = Math.min(1f, visibility + dt / 110f);
        } else {
            visibility = Math.max(0f, visibility - dt / 200f);
        }
        if (visibility > 0f && visibility < 1f) animating = true;

        if (hasShown && visibility > 0.01f) {
            drawLattice(canvas, d);
            animating |= drawRing(canvas, d, dt);
            animating |= drawFlash(canvas, d, now);
            if (state.phase == CubeUiState.Phase.GUIDING && state.guideStep != null
                    && state.stepOnLiveFace && tracked) {
                drawStepArrow(canvas, d, now);
                animating = true;
            }
        }
        if (!tracked && (state.phase == CubeUiState.Phase.SCANNING
                || state.phase == CubeUiState.Phase.GUIDING)) {
            drawSearching(canvas, d, now);
            animating = true;
        }
        animating |= drawConfetti(canvas, d, now, dt);
        drawDumpBadge(canvas, d);
        if (animating || DetectionDump.active() != null) postInvalidateOnAnimation();
    }

    /** Maps the detection into view pixels; FILL_CENTER crops the analysis frame the same way. */
    private boolean mapDetection(float viewWidth, float viewHeight) {
        DetectedFace detection = state.detectedFace;
        if (detection == null || detection.imageWidth <= 0 || detection.imageHeight <= 0) return false;
        float scale = Math.max(viewWidth / detection.imageWidth, viewHeight / detection.imageHeight);
        float offsetX = (viewWidth - detection.imageWidth * scale) / 2f;
        float offsetY = (viewHeight - detection.imageHeight * scale) / 2f;
        for (int i = 0; i < 4; i++) {
            target[i * 2] = offsetX + detection.corners[i * 2] * scale;
            target[i * 2 + 1] = offsetY + detection.corners[i * 2 + 1] * scale;
        }
        return true;
    }

    /** The cyclic shift of {@code to}'s corners that lies closest to {@code from}'s. */
    private static int bestShift(float[] from, float[] to) {
        int best = 0;
        double lowest = Double.MAX_VALUE;
        for (int shift = 0; shift < 4; shift++) {
            double cost = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + shift) % 4;
                cost += Math.hypot(to[j * 2] - from[i * 2], to[j * 2 + 1] - from[i * 2 + 1]);
            }
            if (cost < lowest) { lowest = cost; best = shift; }
        }
        return best;
    }

    private void drawLattice(Canvas canvas, float d) {
        for (int i = 0; i < 8; i++) quad[i] = shown[i];
        double[] toView = Homography.fromSquare(3, quad);
        if (toView == null) return;
        int alpha = (int) (255 * visibility);
        FaceSample live = state.liveFace;
        // The drawn quad may start from a different corner than the detection's; turn the
        // readings by the same amount so each colour sits on its own sticker.
        if (live != null) for (int t = 0; t < (4 - shownShift) % 4; t++) live = live.rotateClockwise();
        boolean refined = state.detectedFace != null && state.detectedFace.refined;
        boolean disputed = state.detectedFace != null && state.detectedFace.disputed;
        boolean readable = live != null && live.unreliableCount() == 0;
        int edge = state.tooFarToCapture ? AMBER : readable && refined ? 0xFFDFFFF3 : 0xFFFFFFFF;

        paint.setStyle(Paint.Style.FILL);
        if (live != null && !state.tooFarToCapture) {
            for (int cell = 0; cell < 9; cell++) {
                if (!live.reliable[cell] || live.stickers[cell] == CubeColor.UNKNOWN) continue;
                paint.setColor(live.stickers[cell].argb);
                paint.setAlpha((int) (alpha * 0.58f));
                cellPath(toView, cell % 3, cell / 3, 0.14);
                canvas.drawPath(path, paint);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.2f * d);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (alpha * 0.45f));
        for (int k = 1; k < 3; k++) {
            line(canvas, toView, k, 0, k, 3);
            line(canvas, toView, 0, k, 3, k);
        }
        paint.setStrokeWidth((refined ? 3.2f : 2.4f) * d);
        paint.setColor(edge);
        paint.setAlpha(disputed ? alpha / 2 : alpha);
        if (disputed) paint.setPathEffect(flow[0]);
        path.reset();
        path.moveTo(shown[0], shown[1]);
        path.lineTo(shown[2], shown[3]);
        path.lineTo(shown[4], shown[5]);
        path.lineTo(shown[6], shown[7]);
        path.close();
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        // Corner ticks make the frame read as "locked on" rather than as a plain outline.
        paint.setStrokeWidth(5f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 4; i++) {
            float x = shown[i * 2], y = shown[i * 2 + 1];
            int prev = (i + 3) % 4, next = (i + 1) % 4;
            canvas.drawLine(x, y, x + (shown[next * 2] - x) * 0.16f, y + (shown[next * 2 + 1] - y) * 0.16f, paint);
            canvas.drawLine(x, y, x + (shown[prev * 2] - x) * 0.16f, y + (shown[prev * 2 + 1] - y) * 0.16f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void cellPath(double[] toView, int col, int row, double inset) {
        path.reset();
        double[][] corners = {{col + inset, row + inset}, {col + 1 - inset, row + inset},
            {col + 1 - inset, row + 1 - inset}, {col + inset, row + 1 - inset}};
        for (int i = 0; i < 4; i++) {
            Homography.apply(toView, corners[i][0], corners[i][1], point);
            if (i == 0) path.moveTo((float) point[0], (float) point[1]);
            else path.lineTo((float) point[0], (float) point[1]);
        }
        path.close();
    }

    private void line(Canvas canvas, double[] toView, double x0, double y0, double x1, double y1) {
        Homography.apply(toView, x0, y0, point);
        float ax = (float) point[0], ay = (float) point[1];
        Homography.apply(toView, x1, y1, point);
        canvas.drawLine(ax, ay, (float) point[0], (float) point[1], paint);
    }

    /**
     * Capture progress traced along the face's own outline, starting at its top-left corner.
     * A separate ring around the face read as a second, larger target and fought the lattice for
     * attention; drawing progress on the frame the user is already watching says "hold still"
     * where they are looking.
     */
    private boolean drawRing(Canvas canvas, float d, float dt) {
        float goal = state.phase == CubeUiState.Phase.SCANNING || state.phase == CubeUiState.Phase.GUIDING
            ? state.stabilizeProgress : 0f;
        float k = 1f - (float) Math.exp(-dt / 70f);
        shownProgress += (goal - shownProgress) * k;
        if (Math.abs(goal - shownProgress) < 0.003f) shownProgress = goal;
        boolean animating = Math.abs(goal - shownProgress) > 0.003f;
        if (shownProgress <= 0.01f || state.tooFarToCapture) return animating;
        // Start from whichever drawn corner is top-left-most, so progress always begins there.
        int start = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float score = shown[i * 2] + shown[i * 2 + 1];
            if (score < best) { best = score; start = i; }
        }
        float[] side = new float[4];
        float total = 0;
        for (int i = 0; i < 4; i++) {
            int a = (start + i) % 4, b = (start + i + 1) % 4;
            side[i] = (float) Math.hypot(shown[b * 2] - shown[a * 2], shown[b * 2 + 1] - shown[a * 2 + 1]);
            total += side[i];
        }
        float remaining = total * Math.min(1f, shownProgress);
        path.reset();
        path.moveTo(shown[start * 2], shown[start * 2 + 1]);
        for (int i = 0; i < 4 && remaining > 0; i++) {
            int a = (start + i) % 4, b = (start + i + 1) % 4;
            float t = Math.min(1f, remaining / Math.max(side[i], 1e-3f));
            path.lineTo(shown[a * 2] + (shown[b * 2] - shown[a * 2]) * t,
                shown[a * 2 + 1] + (shown[b * 2 + 1] - shown[a * 2 + 1]) * t);
            remaining -= side[i];
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(6 * d);
        paint.setColor(MINT);
        paint.setAlpha((int) (255 * visibility));
        canvas.drawPath(path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        return animating;
    }

    /** White pulse over the face, an expanding mint frame, and a check badge. */
    private boolean drawFlash(Canvas canvas, float d, long now) {
        long elapsed = now - flashAt;
        if (elapsed < 0 || elapsed > FLASH_MS) return false;
        float t = elapsed / (float) FLASH_MS;
        float ease = 1f - t * t;
        path.reset();
        path.moveTo(shown[0], shown[1]);
        path.lineTo(shown[2], shown[3]);
        path.lineTo(shown[4], shown[5]);
        path.lineTo(shown[6], shown[7]);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (90 * ease));
        canvas.drawPath(path, paint);

        float cx = (shown[0] + shown[2] + shown[4] + shown[6]) / 4f;
        float cy = (shown[1] + shown[3] + shown[5] + shown[7]) / 4f;
        float grow = 1f + 0.18f * t;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * d);
        paint.setColor(MINT);
        paint.setAlpha((int) (230 * ease));
        path.reset();
        for (int i = 0; i < 4; i++) {
            float x = cx + (shown[i * 2] - cx) * grow, y = cy + (shown[i * 2 + 1] - cy) * grow;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, paint);

        float badge = 17 * d * (0.7f + 0.3f * Math.min(1f, t * 4));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MINT);
        paint.setAlpha((int) (255 * ease));
        canvas.drawCircle(cx, cy, badge, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.2f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xFF05261D);
        paint.setAlpha((int) (255 * ease));
        path.reset();
        path.moveTo(cx - badge * 0.42f, cy + badge * 0.02f);
        path.lineTo(cx - badge * 0.10f, cy + badge * 0.34f);
        path.lineTo(cx + badge * 0.45f, cy - badge * 0.30f);
        canvas.drawPath(path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        return true;
    }

    /**
     * The current step's direction cue, on the face the user is holding up: a ring when that whole
     * face turns, an arrow along the row or column a side or middle layer carries across it.
     * The back layer gets none: an arrow above the face landed on the top bar whenever the cube
     * was held high, and the guide card already shows that layer's top row moving.
     */
    private void drawStepArrow(Canvas canvas, float d, long now) {
        GuideStep step = state.guideStep;
        if (step.turnsFrontFace()) {
            drawRingArrow(canvas, d, now, step.quarters > 0, step.isHalfTurn());
            return;
        }
        int[] axis = step.viewAxis();
        if (axis[2] != 0) return;
        // The frame was read in the detection's own corner order; the drawn quad may start from
        // another corner, so map the detection's grid rather than the drawn one.
        for (int j = 0; j < 4; j++) {
            int i = (j - shownShift + 4) % 4;
            quad[j * 2] = shown[i * 2];
            quad[j * 2 + 1] = shown[i * 2 + 1];
        }
        double[] toView = Homography.fromSquare(3, quad);
        if (toView == null) return;
        // Grid units: u to the right, v down, one sticker each; view +y is grid -v.
        int[] dir = step.motion(GuideStep.VIEW_FRONT);
        double cu = 1.5 + axis[0] * step.layer, cv = 1.5 - axis[1] * step.layer;
        double du = dir[0], dv = -dir[1], reach = 1.3;
        Homography.apply(toView, cu - du * reach, cv - dv * reach, point);
        float x0 = (float) point[0], y0 = (float) point[1];
        Homography.apply(toView, cu + du * reach, cv + dv * reach, point);
        float x1 = (float) point[0], y1 = (float) point[1];

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(10 * d);
        paint.setColor(0x66000000);
        canvas.drawLine(x0, y0, x1, y1, paint);
        paint.setStrokeWidth(6 * d);
        paint.setColor(MINT);
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        paint.setPathEffect(flow[(int) ((now / 45) % flow.length)]);
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        paint.setStrokeCap(Paint.Cap.BUTT);

        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        if (len < 1f) return;
        float ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float head = 16 * d;
        path.reset();
        path.moveTo(x1 + ux * head * 0.5f, y1 + uy * head * 0.5f);
        path.lineTo(x1 - ux * head * 0.6f - uy * head * 0.75f, y1 - uy * head * 0.6f + ux * head * 0.75f);
        path.lineTo(x1 - ux * head * 0.6f + uy * head * 0.75f, y1 - uy * head * 0.6f - ux * head * 0.75f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);

        if (step.isHalfTurn()) drawTwiceBadge(canvas, d, (x0 + x1) / 2f, (y0 + y1) / 2f);
    }

    private void drawTwiceBadge(Canvas canvas, float d, float x, float y) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(26 * d);
        paint.setFakeBoldText(true);
        paint.setColor(0xCC000000);
        canvas.drawCircle(x, y, 22 * d, paint);
        paint.setColor(MINT);
        canvas.drawText("×2", x, y + 9 * d, paint);
        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * A circular arrow around the face to turn, flowing in the turn's direction. The camera looks
     * at that face from outside, which is the viewpoint the move notation is defined from, so
     * clockwise on screen is clockwise on the cube.
     */
    private void drawRingArrow(Canvas canvas, float d, long now, boolean counter, boolean twice) {
        float cx = (shown[0] + shown[2] + shown[4] + shown[6]) / 4f;
        float cy = (shown[1] + shown[3] + shown[5] + shown[7]) / 4f;
        float radius = 0;
        for (int i = 0; i < 4; i++) radius = Math.max(radius, (float) Math.hypot(shown[i * 2] - cx, shown[i * 2 + 1] - cy));
        radius *= 0.72f;
        float start = counter ? 120f : 60f;
        float sweep = (counter ? -1 : 1) * (twice ? 300f : 240f);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(9 * d);
        paint.setColor(0x66000000);
        canvas.drawArc(rect, start, sweep, false, paint);
        paint.setStrokeWidth(6 * d);
        paint.setColor(MINT);
        // The arc is drawn in the turn's own direction, so moving the dash phase forward along
        // the path always flows the way the layer should go.
        paint.setPathEffect(flow[(int) ((now / 45) % flow.length)]);
        canvas.drawArc(rect, start, sweep, false, paint);
        paint.setPathEffect(null);

        double tip = Math.toRadians(start + sweep);
        float tx = cx + radius * (float) Math.cos(tip), ty = cy + radius * (float) Math.sin(tip);
        // Tangent in the direction of travel.
        float dir = counter ? -1 : 1;
        float ux = -(float) Math.sin(tip) * dir, uy = (float) Math.cos(tip) * dir;
        float head = 16 * d;
        path.reset();
        path.moveTo(tx + ux * head, ty + uy * head);
        path.lineTo(tx - uy * head * 0.75f, ty + ux * head * 0.75f);
        path.lineTo(tx + uy * head * 0.75f, ty - ux * head * 0.75f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        if (twice) drawTwiceBadge(canvas, d, cx, cy);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Four corner brackets breathing in the middle of the frame while nothing is tracked. */
    private void drawSearching(Canvas canvas, float d, long now) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.40f;
        float breath = (float) (0.5 + 0.5 * Math.sin(now / 520.0));
        float half = Math.min(w, h) * (0.30f + 0.02f * breath);
        float arm = half * 0.28f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4 * d);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (110 + 90 * breath));
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] c : corners) {
            float x = cx + c[0] * half, y = cy + c[1] * half;
            canvas.drawLine(x, y, x - c[0] * arm, y, paint);
            canvas.drawLine(x, y, x, y - c[1] * arm, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private boolean drawConfetti(Canvas canvas, float d, long now, float dt) {
        long elapsed = now - celebrateAt;
        if (elapsed < 0 || elapsed > CELEBRATE_MS) return false;
        float fade = elapsed > CELEBRATE_MS - 500 ? (CELEBRATE_MS - elapsed) / 500f : 1f;
        float g = getHeight() * 1.6f;
        float s = dt / 1000f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < confetti.length; i++) {
            float[] c = confetti[i];
            c[3] += g * s;
            c[2] *= 0.985f;
            c[0] += c[2] * s;
            c[1] += c[3] * s;
            c[4] += c[5] * s;
            canvas.save();
            canvas.rotate(c[4], c[0], c[1]);
            paint.setColor(confettiColor[i]);
            paint.setAlpha((int) (255 * fade));
            float size = 7 * d * c[6];
            rect.set(c[0] - size, c[1] - size * 0.45f, c[0] + size, c[1] + size * 0.45f);
            canvas.drawRoundRect(rect, size * 0.3f, size * 0.3f, paint);
            canvas.restore();
        }
        return true;
    }

    private void drawDumpBadge(Canvas canvas, float d) {
        DetectionDump dump = DetectionDump.active();
        if (dump == null) return;
        String text = String.format(java.util.Locale.US, "DEBUG  p=%.2f  %s/%s  dump=%d",
            dump.lastPresence, dump.lastSource, dump.lastReject, dump.imagesWritten());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC1A0A08);
        rect.set(16 * d, getHeight() * 0.5f, getWidth() - 16 * d, getHeight() * 0.5f + 26 * d);
        canvas.drawRoundRect(rect, 10 * d, 10 * d, paint);
        paint.setColor(0xFFFFB4A2);
        paint.setTextSize(11 * d);
        canvas.drawText(text, 26 * d, getHeight() * 0.5f + 17 * d, paint);
    }
}
