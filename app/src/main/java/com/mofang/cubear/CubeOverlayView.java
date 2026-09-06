package com.mofang.cubear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;

/**
 * Camera overlay: scan grid, stability ring, capture feedback and the guidance card.
 *
 * <p>Everything the user needs to act on is drawn here, so the overlay carries the three signals
 * that decide how a scan feels: where the tracked face is, how close the current reading is to
 * being captured, and what the next move wants. Animations run only while something is actually
 * moving — the radar, the solving spinner, a capture flash, the celebration, or a stability run
 * closing — and the view idles otherwise.
 */
public final class CubeOverlayView extends View {
    private static final int MINT = 0xFF74F5C5;
    private static final int CARD_BG = 0xF30B1714;
    private static final int TEXT_DIM = 0xFF9EB0AA;

    private static final long FLASH_MS = 480;
    private static final long CELEBRATE_MS = 1400;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private CubeUiState state = new CubeUiState(CubeUiState.Phase.SCANNING,
        "寻找魔方", "将一个完整面放入框内", null, null,
        Collections.emptySet(), 0, Collections.emptyList(), -1);
    private Runnable onReset = () -> {};
    private final RectF resetBounds = new RectF();
    private final RectF guideCubeBounds = new RectF();
    private final GuideCube guideCube = new GuideCube();
    private String lastGuideMove = "";
    private long guideAnimAt;

    /** Smoothly-chased copy of {@code state.stabilizeProgress}, so the ring never jumps. */
    private float shownProgress;
    private long flashStartedAt;
    private long celebrateStartedAt;

    public CubeOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("魔方 AR 实时识别和还原指引");
    }

    public void setState(CubeUiState state) {
        this.state = state;
        invalidate();
    }

    public void setOnReset(Runnable onReset) { this.onReset = onReset; }

    /** A brief flash over the quad after a face is captured. */
    public void onFaceCaptured() {
        flashStartedAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Expanding rings and a check for the restored cube. */
    public void celebrate() {
        celebrateStartedAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float width = getWidth(), height = getHeight();

        drawTopStatus(canvas, d, width);
        float[] detected = mapDetectedCorners(width, height);
        if (detected != null) {
            RectF bounds = drawDetectedGrid(canvas, d, detected);
            drawStabilityRing(canvas, d, height, bounds);
            drawCaptureFlash(canvas, d, detected, bounds);
            if (state.phase == CubeUiState.Phase.GUIDING && state.cubeState == null) {
                drawTurnArrow(canvas, d, bounds, state.currentMove());
            }
        } else if (state.phase == CubeUiState.Phase.SOLVING) {
            drawSolvingSpinner(canvas, d, width, height);
        } else if (state.phase != CubeUiState.Phase.PERMISSION
                && state.phase != CubeUiState.Phase.ERROR) {
            drawSearching(canvas, d, width, height);
        }
        drawCelebration(canvas, d, width, height);
        drawGuideCube(canvas, d, width);
        drawBottomCard(canvas, d, width, height);
        drawDumpBadge(canvas, d, width, height);
        if (animating()) postInvalidateOnAnimation();
    }

    /** True while any animation still owes frames to the user. */
    private boolean animating() {
        if (SystemClock.uptimeMillis() - flashStartedAt < FLASH_MS) return true;
        if (SystemClock.uptimeMillis() - celebrateStartedAt < CELEBRATE_MS) return true;
        if (state.phase == CubeUiState.Phase.SOLVING) return true;
        if (state.detectedFace == null
                && (state.phase == CubeUiState.Phase.SCANNING
                    || state.phase == CubeUiState.Phase.GUIDING)) {
            return true;
        }
        if (Math.abs(shownProgress - state.stabilizeProgress) > 0.004f) return true;
        if (state.phase == CubeUiState.Phase.GUIDING && state.cubeState != null) return true;
        return DetectionDump.active() != null;
    }

    private void drawGuideCube(Canvas canvas, float d, float width) {
        if (state.phase != CubeUiState.Phase.GUIDING || state.cubeState == null) return;
        String move = state.currentMove();
        if (move.isEmpty()) return;
        if (!move.equals(lastGuideMove)) {
            lastGuideMove = move;
            guideAnimAt = SystemClock.uptimeMillis();
        }
        float size = 164 * d;
        guideCubeBounds.set(width - 14 * d - size, 74 * d, width - 14 * d, 74 * d + size);
        paint.setColor(0xE80B1714);
        canvas.drawRoundRect(guideCubeBounds, 22 * d, 22 * d, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * d);
        paint.setColor(0x5574F5C5);
        canvas.drawRoundRect(guideCubeBounds, 22 * d, 22 * d, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF9FE8CC);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(11 * d);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("这一步", guideCubeBounds.centerX(), guideCubeBounds.top + 18 * d, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        RectF inner = new RectF(guideCubeBounds.left + 8 * d, guideCubeBounds.top + 20 * d,
            guideCubeBounds.right - 8 * d, guideCubeBounds.bottom - 6 * d);
        guideCube.draw(canvas, inner, state.cubeState, move, SystemClock.uptimeMillis() - guideAnimAt);
    }

    private void drawTopStatus(Canvas canvas, float d, float width) {
        paint.setColor(0xB30D1A16);
        rect.set(16 * d, 16 * d, width - 16 * d, 66 * d);
        canvas.drawRoundRect(rect, 20 * d, 20 * d, paint);
        paint.setColor(statusDotColor());
        canvas.drawCircle(38 * d, 41 * d, 4.5f * d, paint);
        resetBounds.set(width - 86 * d, 26 * d, width - 30 * d, 56 * d);
        float textMax = resetBounds.left - 62 * d;
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(15 * d);
        drawFittedText(canvas, state.title, 54 * d, 40 * d, textMax, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(11 * d);
        paint.setColor(0xFFA9BBB4);
        drawFittedText(canvas, state.detail, 54 * d, 57 * d, textMax, paint);
        paint.setColor(0x3374F5C5);
        canvas.drawRoundRect(resetBounds, 15 * d, 15 * d, paint);
        paint.setColor(MINT);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(12.5f * d);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("重扫", resetBounds.centerX(), resetBounds.centerY() + 4.5f * d, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDumpBadge(Canvas canvas, float d, float width, float height) {
        DetectionDump dump = DetectionDump.active();
        if (dump == null) return;
        String text = String.format(java.util.Locale.US, "DEBUG  p=%.2f  %s/%s  dump=%d",
            dump.lastPresence, dump.lastSource, dump.lastReject, dump.imagesWritten());
        paint.setColor(0xCC1A0A08);
        rect.set(16 * d, height - 118 * d, width - 16 * d, height - 92 * d);
        canvas.drawRoundRect(rect, 10 * d, 10 * d, paint);
        paint.setColor(0xFFFFB4A2);
        paint.setTextSize(11 * d);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(text, 26 * d, height - 100 * d, paint);
    }

    private int statusDotColor() {
        switch (state.phase) {
            case SOLVING: return 0xFFFFD66B;
            case SOLVED: return MINT;
            case ERROR: return 0xFFFF6B5E;
            case GUIDING: return MINT;
            default: return state.detectedFace != null ? MINT : 0xFF6FA8FF;
        }
    }

    private RectF drawDetectedGrid(Canvas canvas, float d, float[] corners) {
        if (state.liveFace != null) {
            for (int i = 0; i < 9; i++) {
                int row = i / 3, col = i % 3;
                paint.setColor((state.liveFace.stickers[i].argb & 0x00FFFFFF) | 0x5A000000);
                drawCell(canvas, corners, col / 3f, row / 3f, (col + 1) / 3f, (row + 1) / 3f, 0.08f);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * d);
        paint.setColor(0x55FFFFFF);
        for (int i = 1; i < 3; i++) {
            float[] top = bilinear(corners, i / 3f, 0);
            float[] bottom = bilinear(corners, i / 3f, 1);
            float[] left = bilinear(corners, 0, i / 3f);
            float[] right = bilinear(corners, 1, i / 3f);
            canvas.drawLine(top[0], top[1], bottom[0], bottom[1], paint);
            canvas.drawLine(left[0], left[1], right[0], right[1], paint);
        }
        paint.setStrokeWidth(3.5f * d);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(state.liveFace != null && !state.liveFace.containsUnknown() ? MINT : Color.WHITE);
        path.reset();
        path.moveTo(corners[0], corners[1]);
        path.lineTo(corners[2], corners[3]);
        path.lineTo(corners[4], corners[5]);
        path.lineTo(corners[6], corners[7]);
        path.close();
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeJoin(Paint.Join.MITER);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, corners[i * 2]); maxX = Math.max(maxX, corners[i * 2]);
            minY = Math.min(minY, corners[i * 2 + 1]); maxY = Math.max(maxY, corners[i * 2 + 1]);
        }
        return new RectF(minX, minY, maxX, maxY);
    }

    /**
     * Ring around the tracked quad showing how close the steady run is to a capture.
     *
     * <p>Held-still frames are what the whole capture hinges on, and without this the user only
     * finds out afterwards whether they waited long enough.
     */
    private void drawStabilityRing(Canvas canvas, float d, float height, RectF bounds) {
        float target = state.stabilizeProgress;
        shownProgress += (target - shownProgress) * 0.3f;
        if (Math.abs(target - shownProgress) < 0.004f) shownProgress = target;
        if (shownProgress <= 0.004f || shownProgress >= 0.996f) return;

        float cx = bounds.centerX(), cy = bounds.centerY();
        float radius = Math.max(bounds.width(), bounds.height()) / 2f + 16 * d;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4 * d);
        paint.setColor(0x30FFFFFF);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(MINT);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(rect, -90, 360 * shownProgress, false, paint);

        float captionY = bounds.bottom + radius + 24 * d;
        if (captionY < height - 200 * d) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            paint.setTextSize(12.5f * d);
            paint.setColor(MINT);
            canvas.drawText(state.phase == CubeUiState.Phase.GUIDING
                ? "保持对准目标面" : "保持稳定，即将采集", cx, captionY, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Expanding mint frame and a quick white pulse over a freshly captured face. */
    private void drawCaptureFlash(Canvas canvas, float d, float[] corners, RectF bounds) {
        long elapsed = SystemClock.uptimeMillis() - flashStartedAt;
        if (elapsed < 0 || elapsed > FLASH_MS) return;
        float t = elapsed / (float) FLASH_MS;
        float ease = 1f - t * t;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha((int) (70 * ease));
        path.reset();
        path.moveTo(corners[0], corners[1]);
        path.lineTo(corners[2], corners[3]);
        path.lineTo(corners[4], corners[5]);
        path.lineTo(corners[6], corners[7]);
        path.close();
        canvas.drawPath(path, paint);

        float pad = 14 * d + 54 * d * t;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * d);
        paint.setColor(MINT);
        paint.setAlpha((int) (220 * ease));
        rect.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad);
        canvas.drawRoundRect(rect, 18 * d, 18 * d, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha((int) (255 * ease));
        float cx = bounds.centerX();
        float cy = bounds.top - pad - 6 * d;
        canvas.drawCircle(cx, cy, 13 * d, paint);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        path.reset();
        path.moveTo(cx - 5.5f * d, cy + 0.5f * d);
        path.lineTo(cx - 1.5f * d, cy + 4.5f * d);
        path.lineTo(cx + 5.5f * d, cy - 4 * d);
        canvas.drawPath(path, paint);
        paint.setAlpha(255);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSearching(Canvas canvas, float d, float width, float height) {
        float x = width / 2f, y = height * 0.42f;
        float angle = (SystemClock.uptimeMillis() % 1600) / 1600f * 360f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x2274F5C5);
        canvas.drawCircle(x, y, 26 * d, paint);
        paint.setColor(0xEE74F5C5);
        rect.set(x - 26 * d, y - 26 * d, x + 26 * d, y + 26 * d);
        canvas.drawArc(rect, angle, 110, false, paint);
        canvas.drawArc(rect, angle + 180, 110, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(13 * d);
        paint.setColor(0xB3FFFFFF);
        canvas.drawText(state.phase == CubeUiState.Phase.GUIDING
            ? "转动魔方，让目标面对准镜头" : "正在全画面寻找魔方", x, y + 52 * d, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSolvingSpinner(Canvas canvas, float d, float width, float height) {
        float x = width / 2f, y = height * 0.42f;
        float angle = (SystemClock.uptimeMillis() % 900) / 900f * 360f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(MINT);
        rect.set(x - 22 * d, y - 22 * d, x + 22 * d, y + 22 * d);
        canvas.drawArc(rect, angle, 80, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Rings and a check marking the restored cube, drawn over everything but the card. */
    private void drawCelebration(Canvas canvas, float d, float width, float height) {
        long elapsed = SystemClock.uptimeMillis() - celebrateStartedAt;
        if (elapsed < 0 || elapsed > CELEBRATE_MS) return;
        float t = elapsed / (float) CELEBRATE_MS;
        float x = width / 2f, y = height * 0.42f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * d);
        for (int i = 0; i < 2; i++) {
            float ringT = Math.max(0f, t - i * 0.18f) / (1f - i * 0.18f);
            paint.setColor(MINT);
            paint.setAlpha((int) (170 * (1 - ringT)));
            canvas.drawCircle(x, y, (30 + 150 * ringT + i * 26) * d, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MINT);
        paint.setAlpha((int) (255 * Math.min(1f, t * 6) * (t > 0.85f ? (1 - t) / 0.15f : 1)));
        canvas.drawCircle(x, y, 30 * d, paint);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.5f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAlpha((int) (255 * Math.min(1f, t * 6) * (t > 0.85f ? (1 - t) / 0.15f : 1)));
        path.reset();
        path.moveTo(x - 12 * d, y + 1 * d);
        path.lineTo(x - 3 * d, y + 10 * d);
        path.lineTo(x + 13 * d, y - 9 * d);
        canvas.drawPath(path, paint);
        paint.setAlpha(255);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawTurnArrow(Canvas canvas, float d, RectF grid, String move) {
        if (move.isEmpty()) return;
        boolean counter = move.endsWith("'");
        boolean twice = move.endsWith("2");
        RectF arc = new RectF(grid.left - 18 * d, grid.top - 18 * d,
            grid.right + 18 * d, grid.bottom + 18 * d);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8 * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(MINT);
        float start = counter ? 205 : -25;
        float sweep = counter ? -230 : 230;
        canvas.drawArc(arc, start, sweep, false, paint);
        float angle = (float) Math.toRadians(start + sweep);
        float x = arc.centerX() + arc.width() / 2f * (float) Math.cos(angle);
        float y = arc.centerY() + arc.height() / 2f * (float) Math.sin(angle);
        float direction = counter ? -1 : 1;
        path.reset();
        path.moveTo(x, y);
        path.lineTo(x - 22 * d * direction, y - 4 * d);
        path.lineTo(x - 5 * d * direction, y - 20 * d);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        if (twice) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            paint.setTextSize(24 * d);
            paint.setColor(MINT);
            canvas.drawText("×2", grid.centerX(), grid.top - 30 * d, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawBottomCard(Canvas canvas, float d, float width, float height) {
        float cardTop = height - 192 * d;
        paint.setColor(CARD_BG);
        rect.set(14 * d, cardTop, width - 14 * d, height - 14 * d);
        canvas.drawRoundRect(rect, 26 * d, 26 * d, paint);

        drawFaceDots(canvas, d, width, cardTop);

        if (state.phase == CubeUiState.Phase.GUIDING && !state.moves.isEmpty()) {
            drawGuidingCard(canvas, d, width, cardTop);
        } else {
            drawInfoCard(canvas, d, width, cardTop);
        }
    }

    /** Six centre-colour dots; a mint halo marks the face the current move wants during guidance. */
    private void drawFaceDots(Canvas canvas, float d, float width, float cardTop) {
        String[] faceOrder = {"U", "R", "F", "D", "L", "B"};
        char targetFace = state.phase == CubeUiState.Phase.GUIDING && !state.currentMove().isEmpty()
            ? state.currentMove().charAt(0) : '?';
        float y = cardTop + 32 * d;
        for (int i = 0; i < faceOrder.length; i++) {
            CubeColor color = CubeColor.fromFace(faceOrder[i].charAt(0));
            float x = 38 * d + i * 34 * d;
            boolean scanned = state.scanned.contains(color);
            boolean inferred = state.inferred == color;
            paint.setColor((scanned || inferred) ? color.argb : (color.argb & 0x00FFFFFF) | 0x38000000);
            canvas.drawCircle(x, y, scanned || inferred ? 7 * d : 5 * d, paint);
            if (inferred && !scanned) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * d);
                paint.setColor(MINT);
                paint.setPathEffect(new DashPathEffect(new float[]{4 * d, 3 * d}, 0));
                canvas.drawCircle(x, y, 11 * d, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
            } else if (!scanned) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * d);
                paint.setColor(0x66FFFFFF);
                canvas.drawCircle(x, y, 10 * d, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (color.face == targetFace) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.4f * d);
                paint.setColor(MINT);
                canvas.drawCircle(x, y, 13 * d, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(13 * d);
        paint.setColor(0xFFC7D6D0);
        String progress = state.phase == CubeUiState.Phase.GUIDING
            ? (state.moveIndex + 1) + " / " + state.moves.size() + " 步"
            : state.scannedCount + " / 6 面";
        canvas.drawText(progress, width - 34 * d, y + 5 * d, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    /** Move notation, a target swatch, the step bar, and a peek at the next moves. */
    private void drawGuidingCard(Canvas canvas, float d, float width, float cardTop) {
        String move = state.currentMove();
        CubeColor target = CubeColor.fromFace(move.charAt(0));

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x22FFFFFF);
        rect.set(34 * d, cardTop + 52 * d, width - 34 * d, cardTop + 56 * d);
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);
        paint.setColor(MINT);
        float fraction = (float) state.moveIndex / Math.max(1, state.moves.size());
        rect.set(34 * d, cardTop + 52 * d,
            34 * d + (width - 68 * d) * Math.max(0.03f, fraction), cardTop + 56 * d);
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);

        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(44 * d);
        String label = prettyMove(move);
        canvas.drawText(label, 34 * d, cardTop + 112 * d, paint);
        float swatchX = 34 * d + paint.measureText(label) + 20 * d;
        paint.setColor(target.argb);
        canvas.drawCircle(swatchX, cardTop + 98 * d, 11 * d, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * d);
        paint.setColor(0x66FFFFFF);
        canvas.drawCircle(swatchX, cardTop + 98 * d, 14 * d, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float chipX = swatchX + 30 * d;
        float chipY = cardTop + 87 * d;
        paint.setTextSize(11 * d);
        for (int i = state.moveIndex + 1; i < Math.min(state.moveIndex + 4, state.moves.size()); i++) {
            String next = plainMove(state.moves.get(i));
            paint.setColor(0x2474F5C5);
            rect.set(chipX, chipY, chipX + 36 * d, chipY + 24 * d);
            canvas.drawRoundRect(rect, 8 * d, 8 * d, paint);
            paint.setColor(0xFF9FE8CC);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(next, chipX + 18 * d, chipY + 16 * d, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            chipX += 42 * d;
        }

        paint.setTextSize(12.5f * d);
        paint.setColor(TEXT_DIM);
        canvas.drawText(state.detail, 34 * d, cardTop + 148 * d, paint);
        paint.setTextSize(11 * d);
        paint.setColor(0xFF6E8079);
        canvas.drawText("看右上角 3D 动画，层怎么转你就怎么拧", 34 * d, cardTop + 168 * d, paint);
    }

    private void drawInfoCard(Canvas canvas, float d, float width, float cardTop) {
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(21 * d);
        canvas.drawText(state.title, 34 * d, cardTop + 84 * d, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(13.5f * d);
        paint.setColor(0xFFB8C9C3);
        canvas.drawText(state.detail, 34 * d, cardTop + 114 * d, paint);
        paint.setTextSize(11 * d);
        paint.setColor(0xFF6E8079);
        canvas.drawText("无需对准固定框 · 自动跟踪 · 数据仅在本机处理", 34 * d, cardTop + 146 * d, paint);
    }

    private static void drawFittedText(Canvas canvas, String text, float x, float y,
                                       float maxWidth, Paint paint) {
        if (text == null || text.isEmpty()) return;
        if (paint.measureText(text) <= maxWidth) {
            canvas.drawText(text, x, y, paint);
            return;
        }
        String ellipsis = "…";
        float ellipsisWidth = paint.measureText(ellipsis);
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--;
        canvas.drawText(end <= 0 ? ellipsis : text.substring(0, end) + ellipsis, x, y, paint);
    }

    private static String prettyMove(String move) {
        if (move.endsWith("'")) return move.substring(0, 1) + " 逆时针";
        if (move.endsWith("2")) return move.substring(0, 1) + " 转两次";
        return move.substring(0, 1) + " 顺时针";
    }

    private static String plainMove(String move) {
        if (move.endsWith("'")) return move.substring(0, 1) + "′";
        return move;
    }

    private static float[] bilinear(float[] q, float u, float v) {
        float topX = q[0] + (q[2] - q[0]) * u;
        float topY = q[1] + (q[3] - q[1]) * u;
        float bottomX = q[6] + (q[4] - q[6]) * u;
        float bottomY = q[7] + (q[5] - q[7]) * u;
        return new float[]{topX + (bottomX - topX) * v, topY + (bottomY - topY) * v};
    }

    private void drawCell(Canvas canvas, float[] corners, float u0, float v0, float u1, float v1,
                          float inset) {
        float du = (u1 - u0) * inset, dv = (v1 - v0) * inset;
        float[] a = bilinear(corners, u0 + du, v0 + dv);
        float[] b = bilinear(corners, u1 - du, v0 + dv);
        float[] c = bilinear(corners, u1 - du, v1 - dv);
        float[] e = bilinear(corners, u0 + du, v1 - dv);
        path.reset(); path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]);
        path.lineTo(c[0], c[1]); path.lineTo(e[0], e[1]); path.close();
        canvas.drawPath(path, paint);
    }

    private float[] mapDetectedCorners(float viewWidth, float viewHeight) {
        DetectedFace detection = state.detectedFace;
        if (detection == null || detection.imageWidth <= 0 || detection.imageHeight <= 0) return null;
        float scale = Math.max(viewWidth / detection.imageWidth, viewHeight / detection.imageHeight);
        float offsetX = (viewWidth - detection.imageWidth * scale) / 2f;
        float offsetY = (viewHeight - detection.imageHeight * scale) / 2f;
        float[] mapped = new float[8];
        for (int i = 0; i < 4; i++) {
            mapped[i * 2] = offsetX + detection.corners[i * 2] * scale;
            mapped[i * 2 + 1] = offsetY + detection.corners[i * 2 + 1] * scale;
        }
        return mapped;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && resetBounds.contains(event.getX(), event.getY())) {
            performClick();
            onReset.run();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
