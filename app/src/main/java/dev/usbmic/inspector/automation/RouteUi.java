package dev.usbmic.inspector.automation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native design system shared by the route, connection and device screens. */
final class RouteUi {
    static final int BG = 0xff101019, PANEL = 0xff1b1b2a, INSET = 0xff14141f, BORDER = 0xff343145;
    static final int TEXT = 0xfff5f1ff, MUTED = 0xffaaa4bb, VIOLET = 0xffc0a3ff, MINT = 0xff8de0ce, AMBER = 0xffe5c590;
    private RouteUi() { }
    static int dp(Context c, float value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }
    static GradientDrawable surface(Context c, int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(c, radius));
        if (stroke != 0) shape.setStroke(dp(c, 1), stroke);
        return shape;
    }
    static void clickable(View view, int color, int stroke, int radius) {
        Context c = view.getContext();
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22c0a3ff), surface(c, color, stroke, radius), null));
        view.setClickable(true); view.setFocusable(true);
    }
    static TextView text(Context c, String value, int size, int color, boolean medium) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false); t.setLineSpacing(dp(c, 3), 1); return t;
    }
    static TextView button(Context c, String label, boolean primary, View.OnClickListener action) {
        TextView t = text(c, label, 16, primary ? BG : TEXT, true); t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 54)); t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        clickable(t, primary ? VIOLET : PANEL, primary ? 0 : BORDER, 17); t.setOnClickListener(action);
        buttonSemantics(t); return t;
    }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static void enabled(View view, boolean enabled) { if (view != null) { view.setEnabled(enabled); view.setAlpha(enabled ? 1 : .4f); } }
    static void buttonSemantics(View view) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
            }
        });
    }

    static final class Icon extends View {
        private final String kind; private final int tint; private final Paint p = new Paint(3); private final Path path = new Path();
        public Icon(Context c) { this(c, "route", VIOLET); }
        Icon(Context c, String kind, int tint) { super(c); this.kind = kind; this.tint = tint; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); float unit = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - 24 * unit) / 2, (getHeight() - 24 * unit) / 2); canvas.scale(unit, unit);
            p.setColor(tint); p.setStrokeWidth(1.7f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setStyle(Paint.Style.STROKE);
            path.reset();
            if ("mic".equals(kind)) {
                canvas.drawRoundRect(9, 3, 15, 15, 3, 3, p); path.moveTo(6, 11); path.lineTo(6, 13); path.cubicTo(6, 21, 18, 21, 18, 13); path.lineTo(18, 11); canvas.drawPath(path, p);
                canvas.drawLine(12, 19, 12, 23, p); canvas.drawLine(8, 23, 16, 23, p);
            } else if ("headphones".equals(kind)) {
                path.moveTo(4, 15); path.lineTo(4, 11); path.cubicTo(4, 1, 20, 1, 20, 11); path.lineTo(20, 15); canvas.drawPath(path, p);
                canvas.drawRoundRect(2, 12, 7, 21, 2, 2, p); canvas.drawRoundRect(17, 12, 22, 21, 2, 2, p);
            } else if ("link".equals(kind)) {
                canvas.rotate(-35, 12, 12); canvas.drawRoundRect(2, 8, 13, 16, 4, 4, p); canvas.drawRoundRect(11, 8, 22, 16, 4, 4, p); canvas.drawLine(8, 12, 16, 12, p);
            } else if ("devices".equals(kind)) {
                canvas.drawRoundRect(3, 5, 21, 19, 4, 4, p); canvas.drawLine(8, 9, 16, 9, p); canvas.drawLine(8, 14, 12, 14, p);
            } else if ("settings".equals(kind)) {
                canvas.drawLine(4, 6, 20, 6, p); canvas.drawLine(4, 12, 20, 12, p); canvas.drawLine(4, 18, 20, 18, p);
                p.setStyle(Paint.Style.FILL); canvas.drawCircle(9, 6, 2.6f, p); canvas.drawCircle(16, 12, 2.6f, p); canvas.drawCircle(8, 18, 2.6f, p);
            } else if ("back".equals(kind)) {
                path.moveTo(14, 5); path.lineTo(7, 12); path.lineTo(14, 19); canvas.drawPath(path, p);
            } else if ("arrow".equals(kind)) {
                path.moveTo(9, 5); path.lineTo(16, 12); path.lineTo(9, 19); canvas.drawPath(path, p);
            } else if ("check".equals(kind)) {
                path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(20, 6); canvas.drawPath(path, p);
            } else {
                path.moveTo(3, 14); path.lineTo(7, 14); path.lineTo(10, 5); path.lineTo(14, 20); path.lineTo(17, 10); path.lineTo(21, 10); canvas.drawPath(path, p);
            }
            canvas.restore();
        }
    }
}
