package com.inceptless.traceweave;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/** Full-screen memory vault: a page instead of a dialog that runs out of room. */
public final class MemoryVaultActivity extends Activity implements MemoryHost {
    private AgentStore store;
    private MemoryDetailPanel panel;
    private LinearLayout list;
    private EditText search;
    private EditText input;
    private Spinner kind;
    private MemoryIndex index;
    private boolean en;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AgentStore(this);
        panel = new MemoryDetailPanel(this);
        en = english();
        build();
        render();
    }

    private void build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.dark(this) ? 0xFF0B1220 : 0xFFF8FAFC);
        page.setPadding(dpRef(16), dpRef(8), dpRef(16), dpRef(12));
        Ui.padForInsets(page, dpRef(16), dpRef(8), dpRef(16), dpRef(12));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = Ui.title(this, T.s("ui.encrypted_memory_vault", en));
        title.setTextSize(18);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = secondaryButton(T.s("common.close", en), view -> finish());
        topRow.addView(close, new LinearLayout.LayoutParams(dpRef(96), dpRef(48)));
        page.addView(topRow);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dpRef(8), 0, dpRef(6));

        kind = new Spinner(this);
        kind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{T.s("memory.kind_all", en), T.s("kind.preference", en),
                    T.s("kind.fact", en), T.s("kind.workflow", en)}));
        kind.setSelection(0);
        kind.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                render();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        controls.addView(kind, new LinearLayout.LayoutParams(dpRef(140), ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(this);
        search.setHint(T.s("ui.search_memories", en));
        search.setTextSize(13);
        search.setBackground(field(dpRef(44)));
        search.setPadding(dpRef(8), dpRef(4), dpRef(8), dpRef(4));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (index != null) renderResults(index.search(text.toString()));
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, dpRef(48), 1f);
        searchLp.setMargins(dpRef(6), 0, 0, 0);
        controls.addView(search, searchLp);
        page.addView(controls);

        input = new EditText(this);
        input.setHint(T.s("ui.record_a_user_preference_fact_or_workflo", en));
        input.setTextSize(13);
        input.setMinLines(2);
        input.setMaxLines(3);
        input.setBackground(field(dpRef(96)));
        input.setPadding(dpRef(10), dpRef(8), dpRef(10), dpRef(8));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, dpRef(4), 0, dpRef(6));
        page.addView(input, inputLp);

        Button save = new Button(this);
        save.setText(T.s("ui.save_encrypted_memory", en));
        save.setAllCaps(false);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setBackgroundColor(0xFF0F172A);
        save.setTextColor(0xFFFFFFFF);
        save.setOnClickListener(view -> saveMemory());
        page.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpRef(48)));

        Button review = secondaryButton(T.s("ui.review_deletion_by_source", en), view -> panel.reviewBySource());
        page.addView(review, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpRef(48)));

        Button pending = secondaryButton(pendingLabel(), view -> showPending());
        page.addView(pending, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpRef(48)));

        ScrollView scroller = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(list);
        page.addView(scroller, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Ui.edgeToEdge(this);
        setContentView(page);
    }

    private Button secondaryButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setBackgroundColor(Ui.secondaryBg(this));
        button.setTextColor(Ui.secondaryFg(this));
        button.setOnClickListener(listener);
        return button;
    }

    private String pendingLabel() {
        int pending = 0;
        try {
            for (AgentStore.Memory memory : store.listMemories()) if (store.hasMemoryCandidate(memory.id)) pending++;
        } catch (Exception ignored) {
        }
        return T.format("memory.pending_button", en, pending);
    }

    private void showPending() {
        int pending = 0;
        try {
            for (AgentStore.Memory memory : store.listMemories()) if (store.hasMemoryCandidate(memory.id)) pending++;
        } catch (Exception ignored) {
        }
        toast(pending == 0 ? T.s("memory.no_pending_versions", en) : T.format("memory.pending_button", en, pending));
    }

    private GradientDrawable field(int height) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.dark(this) ? 0xFF0F172A : 0xFFFFFFFF);
        background.setCornerRadius(dpRef(8));
        background.setStroke(dpRef(1), Ui.stroke(this));
        return background;
    }

    private void saveMemory() {
        String content = input.getText().toString().trim();
        if (content.isEmpty()) return;
        String[] kinds = {AgentStore.MEMORY_PREFERENCE, AgentStore.MEMORY_FACT, AgentStore.MEMORY_WORKFLOW};
        int selected = Math.max(0, kind.getSelectedItemPosition() - 1);
        try {
            store.createMemory(kinds[Math.min(selected, kinds.length - 1)], content);
            input.setText("");
            render();
        } catch (Exception error) {
            failWith(error);
        }
    }

    private void render() {
        try {
            index = new MemoryIndex(store.listMemories());
        } catch (Exception error) {
            index = null;
            failWith(error);
            return;
        }
        String query = search == null ? "" : search.getText().toString();
        List<AgentStore.Memory> found = index.search(query);
        int selected = kind == null ? 0 : kind.getSelectedItemPosition();
        String wanted = selected <= 0 ? null
            : selected == 1 ? AgentStore.MEMORY_PREFERENCE
            : selected == 2 ? AgentStore.MEMORY_FACT : AgentStore.MEMORY_WORKFLOW;
        if (wanted != null) {
            List<AgentStore.Memory> filtered = new java.util.ArrayList<>();
            for (AgentStore.Memory memory : found) if (wanted.equals(memory.kind)) filtered.add(memory);
            found = filtered;
        }
        renderResults(found);
    }

    private void renderResults(List<AgentStore.Memory> memories) {
        if (list == null) return;
        list.removeAllViews();
        if (memories.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(T.s("memory.none_yet", en));
            empty.setTextColor(Ui.muted(this));
            empty.setPadding(0, dpRef(12), 0, dpRef(12));
            list.addView(empty);
            return;
        }
        String query = search == null ? "" : search.getText().toString().trim();
        String currentKind = null;
        for (AgentStore.Memory memory : memories) {
            if (!memory.kind.equals(currentKind)) {
                currentKind = memory.kind;
                list.addView(groupHeader(currentKind, memories));
            }
            list.addView(row(memory, query));
        }
    }

    private TextView groupHeader(String kind, List<AgentStore.Memory> memories) {
        int count = 0;
        for (AgentStore.Memory memory : memories) if (kind.equals(memory.kind)) count++;
        TextView header = new TextView(this);
        header.setText(T.format("memory.group_header", en, kindLabel(kind), count));
        header.setTextSize(12);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(Ui.muted(this));
        header.setPadding(0, dpRef(10), 0, dpRef(2));
        return header;
    }

    private String kindLabel(String kind) {
        if (AgentStore.MEMORY_PREFERENCE.equals(kind)) return T.s("memory.kind_preference", en);
        if (AgentStore.MEMORY_FACT.equals(kind)) return T.s("memory.kind_fact", en);
        if (AgentStore.MEMORY_WORKFLOW.equals(kind)) return T.s("memory.kind_workflow", en);
        return kind;
    }

    private View row(AgentStore.Memory memory, String query) {
        TextView row = new TextView(this);
        String text = memory.content;
        int match = query.isEmpty() ? -1
            : text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (match >= 0) {
            android.text.SpannableString spanned = new android.text.SpannableString(text);
            spanned.setSpan(new android.text.style.BackgroundColorSpan(0xFFFDE68A),
                match, match + query.length(), 0);
            row.setText(spanned);
        } else {
            row.setText(text);
        }
        row.setTextSize(14);
        row.setTextColor(Ui.text(this));
        row.setPadding(dpRef(4), dpRef(12), dpRef(4), dpRef(12));
        row.setOnClickListener(view -> panel.showDetail(memory));
        return row;
    }

    @Override
    public AgentStore storeRef() { return store; }

    @Override
    public boolean english() {
        return "en".equals(getSharedPreferences("agent_os_prefs", MODE_PRIVATE).getString("app_lang", "zh"));
    }

    @Override
    public int dpRef(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void failWith(Exception error) {
        toast(error.getMessage() == null ? T.s("common.operation_failed", en) : error.getMessage());
    }

    @Override
    public void refreshAfterSync() {
        render();
    }
}
