package com.mofang.cubear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Hosts the 3D {@link GuideCube} and loops the current step while visible. */
public final class GuideCubeView extends View {
    private final GuideCube cube = new GuideCube();
    private final RectF box = new RectF();
    private String state, partway;
    private GuideStep step;
    private long startedAt;

    public GuideCubeView(Context context) { this(context, null); }

    public GuideCubeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Restarts the loop whenever the step, or the way the cube is held, changes, so every
     * instruction begins from its first frame.
     *
     * @param partwayState the cube after the first part of a chained step, or null
     */
    public void show(String cubeState, String partwayState, GuideStep next) {
        boolean same = next != null && step != null && next.first == step.first
            && next.count == step.count && next.frame.equals(step.frame)
            && cubeState != null && cubeState.equals(state);
        if (!same) {
            startedAt = SystemClock.uptimeMillis();
            if (next != null) setContentDescription(next.caption());
        }
        state = cubeState;
        partway = partwayState;
        step = next;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (state == null || step == null) return;
        float pad = getWidth() * 0.04f;
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);
        cube.draw(canvas, box, state, partway, step, SystemClock.uptimeMillis() - startedAt);
        if (getVisibility() == VISIBLE && isShown()) postInvalidateOnAnimation();
    }
}
