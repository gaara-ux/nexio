package com.gaara.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Round D-pad: a ring split in four (up, right, down, left) around an OK button.
 * Holding a direction repeats it.
 */
public class DpadView extends View {
    public interface Listener {
        void onKey(int keyCode);
    }

    private static final int NONE = 0;
    private static final long REPEAT_DELAY = 400, REPEAT_EVERY = 110;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint okText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path chevron = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private int active = NONE;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (active == NONE || active == KeyEvent.KEYCODE_DPAD_CENTER) return;
            fire(active);
            handler.postDelayed(this, REPEAT_EVERY);
        }
    };

    public DpadView(Context c, AttributeSet a) {
        super(c, a);
        ring.setColor(c.getColor(R.color.ring));
        pressed.setColor(c.getColor(R.color.surface_pressed));
        center.setColor(c.getColor(R.color.surface));
        centerRim.setColor(c.getColor(R.color.bg));
        arrow.setColor(c.getColor(R.color.text));
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
        okText.setColor(c.getColor(R.color.text));
        okText.setTextAlign(Paint.Align.CENTER);
        okText.setFakeBoldText(true);
        setHapticFeedbackEnabled(true);
        setContentDescription("Pavé directionnel");
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int w, int h) {
        // Given a fixed area (weighted layout): take it all and draw a centred circle that fits.
        if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            return;
        }
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - 4;
    }

    private float innerRadius() {
        return radius() * 0.40f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius(), ri = innerRadius();
        canvas.drawCircle(cx, cy, r, ring);

        if (active != NONE && active != KeyEvent.KEYCODE_DPAD_CENTER) {
            float start;
            switch (active) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: start = -45; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: start = 45; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: start = 135; break;
                default: start = 225; break;
            }
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, start, 90, true, pressed);
        }

        // Gap around the center button, then the button itself.
        canvas.drawCircle(cx, cy, ri + r * 0.04f, centerRim);
        canvas.drawCircle(cx, cy, ri, active == KeyEvent.KEYCODE_DPAD_CENTER ? pressed : center);
        okText.setTextSize(ri * 0.42f);
        canvas.drawText("OK", cx, cy + okText.getTextSize() * 0.36f, okText);

        float mid = (r + ri) / 2f, s = r * 0.07f;
        arrow.setStrokeWidth(r * 0.025f);
        drawChevron(canvas, cx, cy - mid, s, 0);
        drawChevron(canvas, cx + mid, cy, s, 90);
        drawChevron(canvas, cx, cy + mid, s, 180);
        drawChevron(canvas, cx - mid, cy, s, 270);
    }

    private void drawChevron(Canvas c, float x, float y, float s, float rotation) {
        c.save();
        c.rotate(rotation, x, y);
        chevron.reset();
        chevron.moveTo(x - s, y + s * 0.5f);
        chevron.lineTo(x, y - s * 0.5f);
        chevron.lineTo(x + s, y + s * 0.5f);
        c.drawPath(chevron, arrow);
        c.restore();
    }

    private int zoneAt(float x, float y) {
        float dx = x - getWidth() / 2f, dy = y - getHeight() / 2f;
        double d = Math.hypot(dx, dy);
        if (d > radius() * 1.05) return NONE;
        if (d < innerRadius()) return KeyEvent.KEYCODE_DPAD_CENTER;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        return dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int z = zoneAt(e.getX(), e.getY());
                if (z == NONE) return false;
                active = z;
                fire(z);
                if (z != KeyEvent.KEYCODE_DPAD_CENTER) handler.postDelayed(repeat, REPEAT_DELAY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                active = NONE;
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void fire(int key) {
        if (Prefs.haptics(getContext())) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onKey(key);
    }
}
