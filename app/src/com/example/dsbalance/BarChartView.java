package com.example.dsbalance;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal bar chart. Hand-drawn on purpose — a charting library would be far
 * more code and a third-party dependency for thirty rectangles.
 *
 * Public with public constructors because the layout inflater instantiates it
 * reflectively from XML.
 *
 * Tapping a bar pins a readout to the top of the chart; tapping it again clears
 * it. The readout sits at the top rather than floating over the touched bar so it
 * never hides the value it is describing, and it is aligned to the bar's column
 * so the link between the two stays obvious.
 *
 * Touches are consumed on ACTION_DOWN so the tap can complete, but a drag is
 * still handed to the enclosing ScrollView: it intercepts once the finger passes
 * touch slop and this view receives ACTION_CANCEL, which leaves the selection
 * alone. That is what keeps the page scrollable from the chart area.
 */
public final class BarChartView extends View {

    public static final class Bar {
        public final String label;
        public final double value;
        /** Pre-formatted for the readout, e.g. "¥4.35". */
        public final String display;

        public Bar(String label, double value, String display) {
            this.label = label;
            this.value = value;
            this.display = display;
        }
    }

    private final List<Bar> bars = new ArrayList<Bar>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tipRect = new RectF();

    private double max;
    private int selected = -1;
    private float slotWidth;
    /** Height of the readout strip, reserved at the top so bars never sit under it. */
    private float readoutHeight;
    private float readoutPaddingH;
    private float readoutPaddingV;

    public BarChartView(Context context) {
        super(context);
        init();
    }

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BarChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        textPaint.setTextSize(dp(12));
        readoutPaddingH = dp(10);
        readoutPaddingV = dp(5);
        readoutHeight = textPaint.getTextSize() + readoutPaddingV * 2;
    }

    public void setBars(List<Bar> data) {
        bars.clear();
        bars.addAll(data);
        selected = -1;
        max = 0;
        for (Bar bar : bars) {
            max = Math.max(max, bar.value);
        }
        invalidate();
    }

    public double maxValue() {
        return max;
    }

    // ------------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bars.isEmpty() || max <= 0) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_UP: {
                int index = indexAt(event.getX());
                // Tapping the pinned bar again clears the readout.
                selected = (index == selected) ? -1 : index;
                performClick();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                // The ScrollView took over for a drag; leave the selection as it was.
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int indexAt(float x) {
        if (slotWidth <= 0) {
            return -1;
        }
        int index = (int) (x / slotWidth);
        if (index < 0) {
            index = 0;
        }
        if (index >= bars.size()) {
            index = bars.size() - 1;
        }
        return index;
    }

    // -------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.divider));
        float baseline = height - 1f;
        canvas.drawRect(0, baseline, width, height, paint);

        if (bars.isEmpty() || max <= 0) {
            return;
        }

        int accent = getContext().getColor(R.color.accent);
        int highlighted = getContext().getColor(R.color.text_primary);

        slotWidth = (float) width / bars.size();
        float barWidth = Math.max(2f, slotWidth * 0.62f);
        float radius = Math.min(barWidth / 2f, 6f);
        // Leave the readout strip free so a full-height bar cannot end up under it.
        float usable = baseline - 4f - readoutHeight - dp(4);

        for (int i = 0; i < bars.size(); i++) {
            double value = bars.get(i).value;
            if (value <= 0) {
                continue;
            }
            float barHeight = (float) (value / max * usable);
            if (barHeight < 3f) {
                // Keep a day with a tiny amount visible rather than invisible.
                barHeight = 3f;
            }
            float left = i * slotWidth + (slotWidth - barWidth) / 2f;
            rect.set(left, usable - barHeight, left + barWidth, usable);
            paint.setColor(i == selected ? highlighted : accent);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        if (selected >= 0 && selected < bars.size()) {
            drawReadout(canvas, width, bars.get(selected), selected);
        }
    }

    /** Pinned to the top edge, centred on its column and clamped inside the view. */
    private void drawReadout(Canvas canvas, int width, Bar bar, int index) {
        String text = bar.label + "   " + bar.display;
        float boxWidth = textPaint.measureText(text) + readoutPaddingH * 2;
        float boxHeight = readoutHeight;

        float center = (index + 0.5f) * slotWidth;
        float left = center - boxWidth / 2f;
        left = Math.max(0f, Math.min(left, width - boxWidth));

        tipRect.set(left, 0f, left + boxWidth, boxHeight);
        paint.setColor(getContext().getColor(R.color.text_primary));
        paint.setAlpha(242);
        canvas.drawRoundRect(tipRect, dp(7), dp(7), paint);
        paint.setAlpha(255);

        textPaint.setColor(getContext().getColor(R.color.bg_page));
        float baseline = boxHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(text, left + readoutPaddingH, baseline, textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
