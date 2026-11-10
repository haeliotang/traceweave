// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

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
    /** Body text inside cards; the light value is too dark against a dark card. */
    /** Single accent for the home cards: one hue instead of six competing ones. */
    static int cardAccent(Context context) { return dark(context) ? 0xFFCBD5E1 : 0xFF334155; }
    static int body(Context context) { return dark(context) ? 0xFFCBD5E1 : 0xFF475569; }
    /** Secondary (outlined) buttons keep a light chip in light mode and a dark one in dark mode. */
    static int secondaryBg(Context context) { return dark(context) ? 0xFF1E293B : 0xFFF1F5F9; }
    static int secondaryFg(Context context) { return dark(context) ? 0xFFE2E8F0 : 0xFF0F172A; }

    /** Recolors text and input fields inside a dark dialog. */
    static void styleContent(Context context, android.view.View view) {
        if (!dark(context) || view == null) return;
        if (view instanceof android.widget.EditText) {
            android.widget.EditText field = (android.widget.EditText) view;
            field.setTextColor(text(context));
            field.setHintTextColor(muted(context));
            if (android.os.Build.VERSION.SDK_INT >= 21 && field.getBackground() != null) {
                field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF475569));
            }
        } else if (view instanceof android.widget.Button) {
            // buttons keep their own styling
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(text(context));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                styleContent(context, group.getChildAt(index));
            }
        }
    }

    /** Dark-mode dialog surface: window, title, message and action buttons. */
    static void themeDialog(android.app.AlertDialog dialog, Context context) {
        if (dialog == null || !dark(context) || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background(context)));
        TextView title = dialog.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(text(context));
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(text(context));
        int[] buttons = {android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE,
            android.app.AlertDialog.BUTTON_NEUTRAL};
        for (int which : buttons) {
            android.widget.Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(0xFF7DD3FC);
        }
        styleContent(context, dialog.getWindow().getDecorView());
    }

    /**
     * targetSdk 35 draws edge-to-edge whether or not the app asks: without insets the gesture
     * bar covers the last row of content and the status bar icons take the wrong colour.
     */
    static void edgeToEdge(android.app.Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        setBarIconAppearance(activity);
    }

    /** Keeps system-bar icons readable against our own light/dark surfaces. */
    static void setBarIconAppearance(android.app.Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        android.view.View decor = activity.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark(activity)) {
            flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** Applies system-bar, cutout and keyboard insets on top of the view's own padding. */
    static void padForInsets(final android.view.View view, final int baseLeft, final int baseTop,
                             final int baseRight, final int baseBottom) {
        if (view == null) return;
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left = baseLeft, top = baseTop, right = baseRight, bottom = baseBottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                left += bars.left;
                top += bars.top;
                right += bars.right;
                bottom += Math.max(bars.bottom, ime.bottom);
            } else {
                left += insets.getSystemWindowInsetLeft();
                top += insets.getSystemWindowInsetTop();
                right += insets.getSystemWindowInsetRight();
                bottom += insets.getSystemWindowInsetBottom();
            }
            target.setPadding(left, top, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    /** Text field that follows the theme, including inside dialogs. */
    static android.widget.EditText input(Context context, String hint) {
        android.widget.EditText field = new android.widget.EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(13);
        field.setTextColor(text(context));
        field.setHintTextColor(muted(context));
        field.setMinHeight(dp(context, 48));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(dark(context) ? 0xFF0F172A : 0xFFF8FAFC);
        background.setCornerRadius(dp(context, 8));
        background.setStroke(dp(context, 1), stroke(context));
        field.setBackground(background);
        field.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        return field;
    }

    /** Builds a dialog body with our own title and message so dark mode stays readable. */
    static android.widget.LinearLayout dialogBody(Context context, String title, String message, int messageSize) {
        android.widget.LinearLayout body = new android.widget.LinearLayout(context);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        body.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), 0);
        if (title != null && !title.isEmpty()) {
            TextView heading = title(context, title);
            heading.setTextSize(14);
            body.addView(heading);
        }
        if (message != null && !message.isEmpty()) {
            TextView text = new TextView(context);
            text.setText(message);
            text.setTextSize(messageSize);
            text.setTextColor(text(context));
            text.setPadding(0, dp(context, 4), 0, 0);
            body.addView(text);
        }
        return body;
    }

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
