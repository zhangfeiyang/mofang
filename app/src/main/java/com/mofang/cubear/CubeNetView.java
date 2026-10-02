package com.mofang.cubear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Collections;
import java.util.Map;

/**
 * The cube unfolded into a cross — U on top, L F R B across, D below — filling in as faces are
 * collected.
 *
 * <p>Six dots said how many faces were done; this says which, and what was read. An empty slot
 * still shows its centre colour, so the user knows which colour to turn to the camera next.
 * Once the whole cube is known the map shows every facelet, which is the moment to check the
 * colours before following a solution.
 */
public final class CubeNetView extends View {
    /** Face slots in the 4 x 3 cross, as {letter, column, row}. */
    private static final Object[][] SLOTS = {
        {'U', 1, 0}, {'L', 0, 1}, {'F', 1, 1}, {'R', 2, 1}, {'B', 3, 1}, {'D', 1, 2}};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final DashPathEffect dash;
    private Map<Character, int[]> faces = Collections.emptyMap();
    private char highlight = '?';
    private char inferred = '?';
    private long highlightSince;

    public CubeNetView(Context context) { this(context, null); }

    public CubeNetView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        dash = new DashPathEffect(new float[]{3 * d, 2.5f * d}, 0);
    }

    /** Scan previews per face letter; missing letters draw as empty slots. */
    public void setFaces(Map<Character, int[]> faces) {
        this.faces = faces == null ? Collections.emptyMap() : faces;
        invalidate();
    }

    /** Every facelet of a known cube, in URFDLB order. */
    public void setState(String state) {
        if (state == null || state.length() != 54) return;
        java.util.Map<Character, int[]> full = new java.util.HashMap<>();
        String order = "URFDLB";
        for (int f = 0; f < 6; f++) {
            int[] argb = new int[9];
            for (int i = 0; i < 9; i++) argb[i] = CubeColor.fromFace(state.charAt(f * 9 + i)).argb;
            full.put(order.charAt(f), argb);
        }
        setFaces(full);
    }

    /** The face the camera is looking at right now, pulsed; '?' for none. */
    public void setHighlight(char face) {
        if (face != highlight) highlightSince = SystemClock.uptimeMillis();
        highlight = face;
        invalidate();
    }

    public void setInferred(char face) {
        inferred = face;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float faceSize = Math.min(w / 4f, h / 3f);
        float gap = Math.max(1.5f * d, faceSize * 0.06f);
        float ox = getPaddingLeft() + (w - faceSize * 4) / 2f;
        float oy = getPaddingTop() + (h - faceSize * 3) / 2f;
        float cell = (faceSize - gap * 2) / 3f;
        float inner = cell * 0.12f;
        float radius = cell * 0.22f;

        for (Object[] slot : SLOTS) {
            char face = (Character) slot[0];
            float left = ox + (Integer) slot[1] * faceSize + gap * 0.5f;
            float top = oy + (Integer) slot[2] * faceSize + gap * 0.5f;
            float size = faceSize - gap;
            int[] colors = faces.get(face);
            int centre = CubeColor.fromFace(face).argb;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors != null ? 0xFF050A09 : 0x14FFFFFF);
            rect.set(left, top, left + size, top + size);
            canvas.drawRoundRect(rect, radius * 1.4f, radius * 1.4f, paint);

            float step = size / 3f;
            for (int i = 0; i < 9; i++) {
                float cl = left + (i % 3) * step + inner * 0.5f;
                float ct = top + (i / 3) * step + inner * 0.5f;
                rect.set(cl, ct, cl + step - inner, ct + step - inner);
                int color = colors == null ? (i == 4 ? withAlpha(centre, 0x99) : 0x1FFFFFFF)
                    : (colors[i] == 0 ? 0x33FFFFFF : colors[i]);
                paint.setColor(color);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }

            if (face == inferred && colors != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.4f * d);
                paint.setColor(0xFF6FF2C2);
                paint.setPathEffect(dash);
                rect.set(left - gap * 0.3f, top - gap * 0.3f, left + size + gap * 0.3f, top + size + gap * 0.3f);
                canvas.drawRoundRect(rect, radius * 1.6f, radius * 1.6f, paint);
                paint.setPathEffect(null);
            }
            if (face == highlight) {
                long t = SystemClock.uptimeMillis() - highlightSince;
                float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(t / 420.0));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * d);
                paint.setColor(withAlpha(0xFF6FF2C2, (int) (255 * pulse)));
                rect.set(left - gap * 0.45f, top - gap * 0.45f, left + size + gap * 0.45f, top + size + gap * 0.45f);
                canvas.drawRoundRect(rect, radius * 1.7f, radius * 1.7f, paint);
                postInvalidateOnAnimation();
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
