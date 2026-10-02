package com.mofang.cubear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Hosts the isometric {@link GuideCube} and loops the current move while visible. */
public final class GuideCubeView extends View {
    private final GuideCube cube = new GuideCube();
    private final RectF box = new RectF();
    private String state;
    private String move = "";
    private long startedAt;

    public GuideCubeView(Context context) { this(context, null); }

    public GuideCubeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Restarts the loop whenever the move changes, so every step begins from its first frame. */
    public void show(String cubeState, String nextMove) {
        String m = nextMove == null ? "" : nextMove;
        if (!m.equals(move) || (cubeState != null && !cubeState.equals(state))) {
            startedAt = SystemClock.uptimeMillis();
        }
        state = cubeState;
        move = m;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (state == null || move.isEmpty()) return;
        float pad = getWidth() * 0.04f;
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);
        cube.draw(canvas, box, state, move, SystemClock.uptimeMillis() - startedAt);
        if (getVisibility() == VISIBLE && isShown()) postInvalidateOnAnimation();
    }
}
