package org.traceform.agentos;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class MemorySelectionView extends LinearLayout {
    private final AgentStore store;
    private final MemorySelection selection;
    private final LinearLayout rows;
    private final TextView summary;
    private final EditText query;
    private final EditText goal;
    private final Spinner source;
    private final boolean en;
    private boolean suggestions;

    MemorySelectionView(Context context, AgentStore store, EditText goal, boolean en, List<String> restored) throws Exception {
        super(context);
        this.store = store; this.goal = goal; this.en = en;
        selection = new MemorySelection(store.listPlanningMemories(), restored);
        setOrientation(VERTICAL);
        query = new EditText(context); query.setHint(en ? "Search memory text, kind or source" : "搜索记忆内容、类型或来源"); addView(query);
        source = new Spinner(context);
        source.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
            en ? new String[]{"All sources", "User", "Traceform", "Confirmed suggestions"}
                : new String[]{"全部来源", "你提供", "Traceform", "已确认建议"}));
        addView(source);
        Button suggest = new Button(context); suggest.setAllCaps(false);
        suggest.setText(en ? "Show suggestions · select manually" : "显示相关建议 · 仍需手动勾选");
        suggest.setOnClickListener(v -> { suggestions = !suggestions; render(); }); addView(suggest);
        Button clear = new Button(context); clear.setAllCaps(false); clear.setText(en ? "Clear selected memories" : "清除已选记忆");
        clear.setOnClickListener(v -> { selection.clear(); render(); }); addView(clear);
        summary = new TextView(context); addView(summary);
        rows = new LinearLayout(context); rows.setOrientation(VERTICAL); addView(rows);
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable value) {}
        });
        source.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) { render(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        render();
    }
    private void render() {
        if (rows == null) return;
        String[] sources = {null, AgentStore.MEMORY_SOURCE_USER, AgentStore.MEMORY_SOURCE_TRACEFORM, AgentStore.MEMORY_SOURCE_CONSOLIDATION};
        rows.removeAllViews();
        for (AgentStore.Memory memory : selection.visible(query.getText().toString(), sources[Math.max(0, source.getSelectedItemPosition())], suggestions, goal.getText().toString())) {
            CheckBox box = new CheckBox(getContext()); box.setText(memory.content + "\n" + memory.source + " · " + memory.id);
            box.setChecked(selection.isSelected(memory.id));
            box.setOnCheckedChangeListener((view, checked) -> { selection.choose(memory.id, checked); updateSummary(); }); rows.addView(box);
        }
        updateSummary();
    }
    private void updateSummary() {
        summary.setText((en ? "Selected: " : "已选：") + selection.ids().size() + (suggestions
            ? (en ? " · suggestions shown; no automatic selection" : " · 正在显示建议，不会自动选中") : ""));
    }
    ArrayList<String> selectedIds() { return selection.ids(); }
    List<AgentStore.Memory> chosen() throws Exception { return selection.chosen(store.listPlanningMemories()); }
}
