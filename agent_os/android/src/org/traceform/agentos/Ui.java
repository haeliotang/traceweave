package org.traceform.agentos;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared compact layout tokens; every screen should use these instead of ad-hoc dp values. */
final class Ui {
    static final int SPACE_XS = 2;
    static final int SPACE_S = 4;
    static final int SPACE_M = 8;
    static final int SPACE_L = 12;
    static final int SPACE_XL = 16;

    static final int TEXT_TITLE = 16;
    static final int TEXT_SECTION = 13;
    static final int TEXT_BODY = 12;
    static final int TEXT_META = 10;

    static final int COLOR_TEXT = 0xFF0F172A;
    static final int COLOR_MUTED = 0xFF64748B;
    static final int COLOR_CARD = 0xFFFFFFFF;
    static final int COLOR_STROKE = 0xFFE2E8F0;

    private Ui() {}

    static boolean dark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static int background(Context context) { return dark(context) ? 0xFF0B1220 : 0xFFFFFFFF; }
    static int card(Context context) { return dark(context) ? 0xFF16213A : 0xFFFFFFFF; }
    static int text(Context context) { return dark(context) ? 0xFFE2E8F0 : COLOR_TEXT; }
    static int muted(Context context) { return dark(context) ? 0xFF94A3B8 : COLOR_MUTED; }
    static int stroke(Context context) { return dark(context) ? 0xFF334155 : COLOR_STROKE; }

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            context.getResources().getDisplayMetrics()));
    }

    static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_TITLE);
        view.setTypeface(null, Typeface.BOLD);
        view.setTextColor(text(context));
        return view;
    }

    static TextView section(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_SECTION);
        view.setTypeface(null, Typeface.BOLD);
        view.setTextColor(text(context));
        view.setPadding(0, dp(context, SPACE_M), 0, dp(context, SPACE_S));
        return view;
    }

    static TextView meta(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_META);
        view.setTextColor(muted(context));
        return view;
    }

    /** Two-column action grid: buttons keep a 44dp touch target but lose the full-width bulk. */
    static LinearLayout actionGrid(Context context, View... buttons) {
        LinearLayout holder = new LinearLayout(context);
        holder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int index = 0; index < buttons.length; index++) {
            if (index % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                holder.addView(row);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 48), 1f);
            params.setMargins(index % 2 == 0 ? 0 : dp(context, SPACE_S), dp(context, SPACE_S), 0, 0);
            row.addView(buttons[index], params);
        }
        return holder;
    }

    static LinearLayout.LayoutParams fullRow(Context context, int heightDp) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp));
    }

    static TextView chip(Context context, String text, int background, int foreground) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TEXT_META);
        view.setTextColor(foreground);
        view.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(context, 6));
        view.setBackground(shape);
        view.setPadding(dp(context, SPACE_M), dp(context, SPACE_XS), dp(context, SPACE_M), dp(context, SPACE_XS));
        return view;
    }
}
