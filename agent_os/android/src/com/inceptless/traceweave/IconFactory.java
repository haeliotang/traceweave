package com.inceptless.traceweave;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class IconFactory {

    public enum IconType {
        CHIP,
        BRAIN,
        SCREEN,
        SHIELD,
        MIC,
        SYNC,
        TASK,
        CALENDAR,
        BELL,
        STATUS_DOT_GREEN,
        STATUS_DOT_BLUE,
        STATUS_DOT_AMBER,
        STATUS_DOT_GRAY,
        CHECK,
        ALERT,
        SERVER,
        SENSOR,
        EYE,
        HARDWARE
    }

    public static Drawable getIcon(Context context, IconType type, int color, int dpSize) {
        float density = context.getResources().getDisplayMetrics().density;
        int sizePx = Math.max(16, (int) (dpSize * density + 0.5f));
        VectorDrawable d = new VectorDrawable(type, color, sizePx);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    public static ImageView createIconView(Context context, IconType type, int color, int dpSize) {
        ImageView iv = new ImageView(context);
        iv.setImageDrawable(getIcon(context, type, color, dpSize));
        float density = context.getResources().getDisplayMetrics().density;
        int sizePx = (int) (dpSize * density + 0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.gravity = Gravity.CENTER_VERTICAL;
        iv.setLayoutParams(lp);
        return iv;
    }

    public static LinearLayout createHeaderWithIcon(Context context, IconType type, String title, int color, int dpIconSize) {
        return createHeaderWithIcon(context, type, title, color, dpIconSize, 15);
    }

    public static LinearLayout createHeaderWithIcon(Context context, IconType type, String title, int color, int dpIconSize, int titleTextSizeSp) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iv = createIconView(context, type, color, dpIconSize);
        row.addView(iv);

        TextView tv = new TextView(context);
        tv.setText(" " + title);
        tv.setTextColor(color);
        tv.setTextSize(titleTextSizeSp);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding((int) (4 * density), 0, 0, 0);
        row.addView(tv);

        return row;
    }

    public static TextView createBadge(Context context, String text, int bgColor, int textColor) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView badge = new TextView(context);
        badge.setText(text);
        badge.setTextSize(11);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setTextColor(textColor);
        badge.setPadding((int) (8 * density), (int) (3 * density), (int) (8 * density), (int) (3 * density));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(16 * density);
        badge.setBackground(bg);

        return badge;
    }

    private static final class VectorDrawable extends Drawable {
        private final IconType type;
        private final Paint paint;
        private final Paint fillPaint;
        private final int sizePx;

        VectorDrawable(IconType type, int color, int sizePx) {
            this.type = type;
            this.sizePx = sizePx;

            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(1.5f, sizePx * 0.08f));

            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(color);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h);
            float ox = b.left + (w - s) / 2f;
            float oy = b.top + (h - s) / 2f;

            canvas.save();
            canvas.translate(ox, oy);

            switch (type) {
                case CHIP:
                    drawChip(canvas, s);
                    break;
                case BRAIN:
                    drawBrain(canvas, s);
                    break;
                case SCREEN:
                    drawScreen(canvas, s);
                    break;
                case SHIELD:
                    drawShield(canvas, s);
                    break;
                case MIC:
                    drawMic(canvas, s);
                    break;
                case SYNC:
                    drawSync(canvas, s);
                    break;
                case TASK:
                    drawTask(canvas, s);
                    break;
                case CALENDAR:
                    drawCalendar(canvas, s);
                    break;
                case BELL:
                    drawBell(canvas, s);
                    break;
                case STATUS_DOT_GREEN:
                    drawStatusDot(canvas, s, 0xFF00E676);
                    break;
                case STATUS_DOT_BLUE:
                    drawStatusDot(canvas, s, 0xFF29B6F6);
                    break;
                case STATUS_DOT_AMBER:
                    drawStatusDot(canvas, s, 0xFFFFB300);
                    break;
                case STATUS_DOT_GRAY:
                    drawStatusDot(canvas, s, 0xFF9E9E9E);
                    break;
                case CHECK:
                    drawCheck(canvas, s);
                    break;
                case ALERT:
                    drawAlert(canvas, s);
                    break;
                case SERVER:
                    drawServer(canvas, s);
                    break;
                case SENSOR:
                    drawSensor(canvas, s);
                    break;
                case EYE:
                    drawEye(canvas, s);
                    break;
                case HARDWARE:
                    drawHardware(canvas, s);
                    break;
            }

            canvas.restore();
        }

        private void drawChip(Canvas canvas, float s) {
            RectF body = new RectF(s * 0.24f, s * 0.24f, s * 0.76f, s * 0.76f);
            canvas.drawRoundRect(body, s * 0.06f, s * 0.06f, paint);

            // Center core
            RectF core = new RectF(s * 0.40f, s * 0.40f, s * 0.60f, s * 0.60f);
            canvas.drawRect(core, fillPaint);

            // Pins (top & bottom)
            for (int i = 0; i < 3; i++) {
                float x = s * (0.36f + i * 0.14f);
                canvas.drawLine(x, s * 0.12f, x, s * 0.24f, paint);
                canvas.drawLine(x, s * 0.76f, x, s * 0.88f, paint);
            }
            // Pins (left & right)
            for (int i = 0; i < 3; i++) {
                float y = s * (0.36f + i * 0.14f);
                canvas.drawLine(s * 0.12f, y, s * 0.24f, y, paint);
                canvas.drawLine(s * 0.76f, y, s * 0.88f, y, paint);
            }
        }

        private void drawBrain(Canvas canvas, float s) {
            // Neural nodes
            float[][] nodes = {
                    {s * 0.24f, s * 0.28f}, {s * 0.24f, s * 0.50f}, {s * 0.24f, s * 0.72f},
                    {s * 0.50f, s * 0.36f}, {s * 0.50f, s * 0.64f},
                    {s * 0.76f, s * 0.50f}
            };
            // Synapse lines
            int[][] connections = {
                    {0, 3}, {1, 3}, {1, 4}, {2, 4},
                    {3, 5}, {4, 5}
            };
            for (int[] c : connections) {
                canvas.drawLine(nodes[c[0]][0], nodes[c[0]][1], nodes[c[1]][0], nodes[c[1]][1], paint);
            }
            for (float[] node : nodes) {
                canvas.drawCircle(node[0], node[1], s * 0.06f, fillPaint);
            }
        }

        private void drawScreen(Canvas canvas, float s) {
            RectF screen = new RectF(s * 0.24f, s * 0.12f, s * 0.76f, s * 0.88f);
            canvas.drawRoundRect(screen, s * 0.08f, s * 0.08f, paint);
            // Speaker bar
            canvas.drawLine(s * 0.44f, s * 0.20f, s * 0.56f, s * 0.20f, paint);
            // Bottom gesture bar
            canvas.drawLine(s * 0.42f, s * 0.80f, s * 0.58f, s * 0.80f, paint);
        }

        private void drawShield(Canvas canvas, float s) {
            Path p = new Path();
            p.moveTo(s * 0.22f, s * 0.20f);
            p.lineTo(s * 0.78f, s * 0.20f);
            p.quadTo(s * 0.78f, s * 0.56f, s * 0.50f, s * 0.85f);
            p.quadTo(s * 0.22f, s * 0.56f, s * 0.22f, s * 0.20f);
            p.close();
            canvas.drawPath(p, paint);

            // Center check inside shield
            Path ck = new Path();
            ck.moveTo(s * 0.38f, s * 0.48f);
            ck.lineTo(s * 0.47f, s * 0.57f);
            ck.lineTo(s * 0.62f, s * 0.40f);
            canvas.drawPath(ck, paint);
        }

        private void drawMic(Canvas canvas, float s) {
            // Capsule
            RectF capsule = new RectF(s * 0.38f, s * 0.16f, s * 0.62f, s * 0.56f);
            canvas.drawRoundRect(capsule, s * 0.12f, s * 0.12f, paint);

            // Cradle U-arc
            RectF cradle = new RectF(s * 0.26f, s * 0.28f, s * 0.74f, s * 0.68f);
            canvas.drawArc(cradle, 0, 180, false, paint);

            // Stem and base
            canvas.drawLine(s * 0.50f, s * 0.68f, s * 0.50f, s * 0.82f, paint);
            canvas.drawLine(s * 0.34f, s * 0.82f, s * 0.66f, s * 0.82f, paint);
        }

        private void drawSync(Canvas canvas, float s) {
            RectF oval = new RectF(s * 0.22f, s * 0.22f, s * 0.78f, s * 0.78f);
            canvas.drawArc(oval, 30, 120, false, paint);
            canvas.drawArc(oval, 210, 120, false, paint);

            // Top arrow head
            Path arrow1 = new Path();
            arrow1.moveTo(s * 0.48f, s * 0.14f);
            arrow1.lineTo(s * 0.62f, s * 0.22f);
            arrow1.lineTo(s * 0.48f, s * 0.30f);
            canvas.drawPath(arrow1, paint);

            // Bottom arrow head
            Path arrow2 = new Path();
            arrow2.moveTo(s * 0.52f, s * 0.86f);
            arrow2.lineTo(s * 0.38f, s * 0.78f);
            arrow2.lineTo(s * 0.52f, s * 0.70f);
            canvas.drawPath(arrow2, paint);
        }

        private void drawTask(Canvas canvas, float s) {
            RectF doc = new RectF(s * 0.25f, s * 0.16f, s * 0.75f, s * 0.84f);
            canvas.drawRoundRect(doc, s * 0.04f, s * 0.04f, paint);

            // Horizontal content lines
            canvas.drawLine(s * 0.36f, s * 0.35f, s * 0.64f, s * 0.35f, paint);
            canvas.drawLine(s * 0.36f, s * 0.50f, s * 0.64f, s * 0.50f, paint);
            canvas.drawLine(s * 0.36f, s * 0.65f, s * 0.54f, s * 0.65f, paint);
        }

        private void drawCalendar(Canvas canvas, float s) {
            RectF cal = new RectF(s * 0.20f, s * 0.22f, s * 0.80f, s * 0.82f);
            canvas.drawRoundRect(cal, s * 0.06f, s * 0.06f, paint);

            // Top divider
            canvas.drawLine(s * 0.20f, s * 0.38f, s * 0.80f, s * 0.38f, paint);

            // Binder rings
            canvas.drawLine(s * 0.36f, s * 0.14f, s * 0.36f, s * 0.26f, paint);
            canvas.drawLine(s * 0.64f, s * 0.14f, s * 0.64f, s * 0.26f, paint);

            // Grid dots
            canvas.drawCircle(s * 0.36f, s * 0.52f, s * 0.035f, fillPaint);
            canvas.drawCircle(s * 0.50f, s * 0.52f, s * 0.035f, fillPaint);
            canvas.drawCircle(s * 0.64f, s * 0.52f, s * 0.035f, fillPaint);
            canvas.drawCircle(s * 0.36f, s * 0.66f, s * 0.035f, fillPaint);
            canvas.drawCircle(s * 0.50f, s * 0.66f, s * 0.035f, fillPaint);
        }

        private void drawBell(Canvas canvas, float s) {
            Path b = new Path();
            b.moveTo(s * 0.50f, s * 0.20f);
            b.quadTo(s * 0.32f, s * 0.38f, s * 0.28f, s * 0.68f);
            b.lineTo(s * 0.72f, s * 0.68f);
            b.quadTo(s * 0.68f, s * 0.38f, s * 0.50f, s * 0.20f);
            b.close();
            canvas.drawPath(b, paint);

            // Bottom clapper
            canvas.drawCircle(s * 0.50f, s * 0.76f, s * 0.05f, fillPaint);
        }

        private void drawStatusDot(Canvas canvas, float s, int dotColor) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(dotColor);
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(s * 0.50f, s * 0.50f, s * 0.35f, p);

            // Subtle outer glow ring
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(dotColor);
            ring.setStyle(Paint.Style.STROKE);
            ring.setAlpha(80);
            ring.setStrokeWidth(s * 0.10f);
            canvas.drawCircle(s * 0.50f, s * 0.50f, s * 0.42f, ring);
        }

        private void drawCheck(Canvas canvas, float s) {
            Path p = new Path();
            p.moveTo(s * 0.24f, s * 0.52f);
            p.lineTo(s * 0.44f, s * 0.72f);
            p.lineTo(s * 0.76f, s * 0.30f);
            canvas.drawPath(p, paint);
        }

        private void drawAlert(Canvas canvas, float s) {
            Path p = new Path();
            p.moveTo(s * 0.50f, s * 0.16f);
            p.lineTo(s * 0.84f, s * 0.80f);
            p.lineTo(s * 0.16f, s * 0.80f);
            p.close();
            canvas.drawPath(p, paint);

            canvas.drawLine(s * 0.50f, s * 0.40f, s * 0.50f, s * 0.60f, paint);
            canvas.drawCircle(s * 0.50f, s * 0.70f, s * 0.04f, fillPaint);
        }

        private void drawServer(Canvas canvas, float s) {
            // Upper rack blade
            RectF r1 = new RectF(s * 0.20f, s * 0.22f, s * 0.80f, s * 0.46f);
            canvas.drawRoundRect(r1, s * 0.04f, s * 0.04f, paint);
            canvas.drawCircle(s * 0.32f, s * 0.34f, s * 0.035f, fillPaint);
            canvas.drawLine(s * 0.46f, s * 0.34f, s * 0.70f, s * 0.34f, paint);

            // Lower rack blade
            RectF r2 = new RectF(s * 0.20f, s * 0.54f, s * 0.80f, s * 0.78f);
            canvas.drawRoundRect(r2, s * 0.04f, s * 0.04f, paint);
            canvas.drawCircle(s * 0.32f, s * 0.66f, s * 0.035f, fillPaint);
            canvas.drawLine(s * 0.46f, s * 0.66f, s * 0.70f, s * 0.66f, paint);
        }

        private void drawSensor(Canvas canvas, float s) {
            // Outer compass/motion ring
            canvas.drawCircle(s * 0.50f, s * 0.50f, s * 0.28f, paint);
            // Cardinal axis ticks
            canvas.drawLine(s * 0.50f, s * 0.14f, s * 0.50f, s * 0.22f, paint);
            canvas.drawLine(s * 0.50f, s * 0.78f, s * 0.50f, s * 0.86f, paint);
            canvas.drawLine(s * 0.14f, s * 0.50f, s * 0.22f, s * 0.50f, paint);
            canvas.drawLine(s * 0.78f, s * 0.50f, s * 0.86f, s * 0.50f, paint);
            // Center inertial mass
            canvas.drawCircle(s * 0.50f, s * 0.50f, s * 0.08f, fillPaint);
        }

        private void drawEye(Canvas canvas, float s) {
            // Outer eye curve
            Path p = new Path();
            p.moveTo(s * 0.18f, s * 0.50f);
            p.quadTo(s * 0.50f, s * 0.20f, s * 0.82f, s * 0.50f);
            p.quadTo(s * 0.50f, s * 0.80f, s * 0.18f, s * 0.50f);
            p.close();
            canvas.drawPath(p, paint);
            // Iris / pupil
            canvas.drawCircle(s * 0.50f, s * 0.50f, s * 0.10f, fillPaint);
        }

        private void drawHardware(Canvas canvas, float s) {
            // Smart ring / wearable hardware loop
            canvas.drawCircle(s * 0.44f, s * 0.50f, s * 0.24f, paint);
            // PTT push button trigger on the side
            RectF ptt = new RectF(s * 0.64f, s * 0.40f, s * 0.76f, s * 0.60f);
            canvas.drawRoundRect(ptt, s * 0.03f, s * 0.03f, fillPaint);
            // Wireless wave arcs
            RectF wave = new RectF(s * 0.74f, s * 0.32f, s * 0.90f, s * 0.68f);
            canvas.drawArc(wave, -45, 90, false, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            fillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            fillPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
    }
}
