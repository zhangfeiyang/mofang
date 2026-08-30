package com.mofang.cubear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;

/** Camera overlay: scan grid, status HUD and live turn arrow. */
public final class CubeOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private CubeUiState state = new CubeUiState(CubeUiState.Phase.SCANNING,
        "寻找魔方", "将一个完整面放入框内", null, Collections.emptySet(), Collections.emptyList(), -1);
    private Runnable onReset = () -> {};
    private RectF resetBounds = new RectF();

    public CubeOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("魔方 AR 实时识别和还原指引");
    }

    public void setState(CubeUiState state) { this.state = state; invalidate(); }
    public void setOnReset(Runnable onReset) { this.onReset = onReset; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth(), height = getHeight();
        drawTopStatus(canvas, density, width);
        float[] detected = mapDetectedCorners(width, height);
        if (detected != null) {
            RectF bounds = drawDetectedGrid(canvas, density, detected);
            if (state.phase == CubeUiState.Phase.GUIDING) {
                drawTurnArrow(canvas, density, bounds, state.currentMove());
            }
        } else {
            drawSearching(canvas, density, width, height);
        }
        drawBottomCard(canvas, density, width, height);
    }

    private void drawTopStatus(Canvas canvas, float d, float width) {
        paint.setColor(0xC914201D);
        canvas.drawRoundRect(new RectF(18*d, 18*d, width - 18*d, 76*d), 22*d, 22*d, paint);
        paint.setColor(0xFF74F5C5);
        canvas.drawCircle(42*d, 47*d, 5*d, paint);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        paint.setTextSize(16*d);
        canvas.drawText(state.title, 58*d, 45*d, paint);
        paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        paint.setTextSize(11*d);
        paint.setColor(0xFFB8C9C3);
        canvas.drawText(state.detail, 58*d, 62*d, paint);

        resetBounds.set(width - 78*d, 29*d, width - 30*d, 65*d);
        paint.setColor(0xFF25342F);
        canvas.drawRoundRect(resetBounds, 14*d, 14*d, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(12*d);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("重扫", resetBounds.centerX(), resetBounds.centerY() + 4*d, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private RectF drawDetectedGrid(Canvas canvas, float d, float[] corners) {
        if (state.liveFace != null) {
            for (int i = 0; i < 9; i++) {
                int row = i / 3, col = i % 3;
                paint.setColor((state.liveFace.stickers[i].argb & 0x00FFFFFF) | 0x55000000);
                drawCell(canvas, corners, col / 3f, row / 3f, (col + 1) / 3f, (row + 1) / 3f, 0.08f);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2*d);
        paint.setColor(0x99FFFFFF);
        for (int i = 1; i < 3; i++) {
            float[] top = bilinear(corners, i / 3f, 0);
            float[] bottom = bilinear(corners, i / 3f, 1);
            float[] left = bilinear(corners, 0, i / 3f);
            float[] right = bilinear(corners, 1, i / 3f);
            canvas.drawLine(top[0], top[1], bottom[0], bottom[1], paint);
            canvas.drawLine(left[0], left[1], right[0], right[1], paint);
        }
        paint.setStrokeWidth(4*d);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(state.liveFace != null && !state.liveFace.containsUnknown() ? 0xFF74F5C5 : Color.WHITE);
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
            minX = Math.min(minX, corners[i*2]); maxX = Math.max(maxX, corners[i*2]);
            minY = Math.min(minY, corners[i*2+1]); maxY = Math.max(maxY, corners[i*2+1]);
        }
        return new RectF(minX, minY, maxX, maxY);
    }

    private void drawCell(Canvas canvas, float[] corners, float u0, float v0, float u1, float v1, float inset) {
        float du = (u1 - u0) * inset, dv = (v1 - v0) * inset;
        float[] a = bilinear(corners, u0 + du, v0 + dv);
        float[] b = bilinear(corners, u1 - du, v0 + dv);
        float[] c = bilinear(corners, u1 - du, v1 - dv);
        float[] e = bilinear(corners, u0 + du, v1 - dv);
        path.reset(); path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]);
        path.lineTo(c[0], c[1]); path.lineTo(e[0], e[1]); path.close();
        canvas.drawPath(path, paint);
    }

    private static float[] bilinear(float[] q, float u, float v) {
        float topX = q[0] + (q[2] - q[0]) * u;
        float topY = q[1] + (q[3] - q[1]) * u;
        float bottomX = q[6] + (q[4] - q[6]) * u;
        float bottomY = q[7] + (q[5] - q[7]) * u;
        return new float[]{topX + (bottomX - topX) * v, topY + (bottomY - topY) * v};
    }

    private float[] mapDetectedCorners(float viewWidth, float viewHeight) {
        DetectedFace detection = state.detectedFace;
        if (detection == null || detection.imageWidth <= 0 || detection.imageHeight <= 0) return null;
        float scale = Math.max(viewWidth / detection.imageWidth, viewHeight / detection.imageHeight);
        float offsetX = (viewWidth - detection.imageWidth * scale) / 2f;
        float offsetY = (viewHeight - detection.imageHeight * scale) / 2f;
        float[] mapped = new float[8];
        for (int i = 0; i < 4; i++) {
            mapped[i*2] = offsetX + detection.corners[i*2] * scale;
            mapped[i*2+1] = offsetY + detection.corners[i*2+1] * scale;
        }
        return mapped;
    }

    private void drawSearching(Canvas canvas, float d, float width, float height) {
        float x = width / 2f, y = height * 0.43f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3*d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xBB74F5C5);
        RectF radar = new RectF(x - 25*d, y - 25*d, x + 25*d, y + 25*d);
        canvas.drawArc(radar, -70, 250, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(13*d);
        canvas.drawText("正在全画面寻找魔方", x, y + 52*d, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawTurnArrow(Canvas canvas, float d, RectF grid, String move) {
        if (move.isEmpty()) return;
        boolean counter = move.endsWith("'");
        boolean twice = move.endsWith("2");
        RectF arc = new RectF(grid.left - 18*d, grid.top - 18*d, grid.right + 18*d, grid.bottom + 18*d);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10*d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF74F5C5);
        float start = counter ? 205 : -25;
        float sweep = counter ? -230 : 230;
        canvas.drawArc(arc, start, sweep, false, paint);
        float angle = (float) Math.toRadians(start + sweep);
        float x = arc.centerX() + arc.width()/2f * (float)Math.cos(angle);
        float y = arc.centerY() + arc.height()/2f * (float)Math.sin(angle);
        float direction = counter ? -1 : 1;
        path.reset();
        path.moveTo(x, y);
        path.lineTo(x - 22*d*direction, y - 4*d);
        path.lineTo(x - 5*d*direction, y - 20*d);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        if (twice) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(24*d);
            canvas.drawText("×2", grid.centerX(), grid.top - 28*d, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawBottomCard(Canvas canvas, float d, float width, float height) {
        float cardTop = height - 178*d;
        paint.setColor(0xE90B1714);
        canvas.drawRoundRect(new RectF(14*d, cardTop, width - 14*d, height - 16*d), 28*d, 28*d, paint);

        String[] faceOrder = {"U", "R", "F", "D", "L", "B"};
        for (int i = 0; i < faceOrder.length; i++) {
            CubeColor color = CubeColor.fromFace(faceOrder[i].charAt(0));
            float x = 34*d + i*32*d;
            paint.setColor(color.argb);
            canvas.drawCircle(x, cardTop + 28*d, 8*d, paint);
            if (!state.scanned.contains(color)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2*d);
                paint.setColor(0xFF67736F);
                canvas.drawCircle(x, cardTop + 28*d, 11*d, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(12*d);
        paint.setColor(0xFF9EB0AA);
        String progress = state.phase == CubeUiState.Phase.GUIDING
            ? (state.moveIndex + 1) + " / " + state.moves.size()
            : state.scannedCount + " / 6 面";
        canvas.drawText(progress, width - 34*d, cardTop + 33*d, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        if (state.phase == CubeUiState.Phase.GUIDING) {
            String move = state.currentMove();
            CubeColor target = CubeColor.fromFace(move.charAt(0));
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(47*d);
            canvas.drawText(prettyMove(move), 32*d, cardTop + 99*d, paint);
            paint.setTextSize(15*d);
            paint.setColor(0xFF74F5C5);
            canvas.drawText("将" + target.chinese + "色中心面对镜头", 32*d, cardTop + 127*d, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(12*d);
            paint.setColor(0xFF9EB0AA);
            canvas.drawText("按箭头转动，识别完成后自动进入下一步", 32*d, cardTop + 150*d, paint);
        } else {
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(23*d);
            canvas.drawText(state.title, 32*d, cardTop + 83*d, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(14*d);
            paint.setColor(0xFFB8C9C3);
            canvas.drawText(state.detail, 32*d, cardTop + 113*d, paint);
            paint.setTextSize(12*d);
            paint.setColor(0xFF7F928B);
            canvas.drawText("无需对准固定框 · 自动跟踪 · 数据仅在本机处理", 32*d, cardTop + 143*d, paint);
        }
    }

    private static String prettyMove(String move) {
        if (move.endsWith("'")) return move.substring(0, 1) + " 逆时针";
        if (move.endsWith("2")) return move.substring(0, 1) + " 转两次";
        return move.substring(0, 1) + " 顺时针";
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
