package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.noties.markwon.Markwon;

/**
 * Claude Code 在 headless 模式下通过 control_request(can_use_tool) 发来的交互：
 * 选择题（AskUserQuestion）、计划审批（ExitPlanMode）、工具权限确认。
 * 这里构建对应的卡片，用户操作后回调 can_use_tool 的响应（behavior allow / deny）。
 */
final class CcmInteraction {

    interface Callback {
        /**
         * @param response  can_use_tool 的响应对象
         * @param newMode   计划批准后要切换到的权限模式（null 表示不切换）
         */
        void onDecision(JSONObject response, String newMode);
    }

    private final Context mCtx;
    private final Markwon mMarkwon;
    private final int mDp;

    CcmInteraction(Context context, Markwon markwon) {
        mCtx = context;
        mMarkwon = markwon;
        mDp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());
    }

    /** 根据工具名构建合适的卡片。 */
    View build(String toolName, JSONObject input, JSONArray suggestions, String projectPath, Callback cb) {
        if ("AskUserQuestion".equals(toolName)) return question(input, cb);
        if ("ExitPlanMode".equals(toolName)) return plan(input, cb);
        return permission(toolName, input, suggestions, projectPath, cb);
    }

    // ======================= 选择题 =======================

    private View question(JSONObject input, Callback cb) {
        LinearLayout card = card(R.drawable.ccm_ic_chat, t("Claude 有问题想问你"));
        JSONArray questions = input.optJSONArray("questions");
        final List<String> qTexts = new ArrayList<>();
        final List<Set<String>> selections = new ArrayList<>();
        final List<EditText> others = new ArrayList<>();
        final List<Boolean> multis = new ArrayList<>();
        final MaterialButton submit = button(true, t("提交回答"));

        for (int qi = 0; questions != null && qi < questions.length(); qi++) {
            JSONObject q = questions.optJSONObject(qi);
            if (q == null) continue;
            final boolean multi = q.optBoolean("multiSelect");
            final Set<String> selected = new LinkedHashSet<>();
            final List<View> optionViews = new ArrayList<>();
            qTexts.add(q.optString("question"));
            selections.add(selected);
            multis.add(multi);

            if (!q.optString("header").isEmpty()) {
                TextView chip = text(q.optString("header"), 11, R.color.ccm_accent);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setBackground(round(R.color.ccm_accent_soft, 8, false));
                chip.setPadding(8 * mDp, 2 * mDp, 8 * mDp, 2 * mDp);
                LinearLayout.LayoutParams clp = wrap();
                clp.topMargin = (qi == 0 ? 8 : 16) * mDp;
                card.addView(chip, clp);
            }
            TextView qt = text(q.optString("question") + (multi ? t("（可多选）") : ""), 15, R.color.ccm_text);
            qt.setTypeface(Typeface.DEFAULT_BOLD);
            qt.setPadding(0, 6 * mDp, 0, 6 * mDp);
            card.addView(qt);

            final EditText other = new EditText(mCtx);
            JSONArray opts = q.optJSONArray("options");
            for (int oi = 0; opts != null && oi < opts.length(); oi++) {
                JSONObject o = opts.optJSONObject(oi);
                if (o == null) continue;
                final String label = o.optString("label");
                LinearLayout ov = option(label, o.optString("description"));
                optionViews.add(ov);
                ov.setOnClickListener(v -> {
                    if (!multi) {
                        selected.clear();
                        other.setText("");
                    }
                    if (!selected.remove(label)) selected.add(label);
                    for (View x : optionViews) setOptionSelected(x, selected.contains((String) x.getTag()));
                    submit.setEnabled(allAnswered(selections, others));
                });
                card.addView(ov, topMargin(6));
            }
            // 「其他」：自由输入
            other.setHint(t("其他（自己输入）"));
            other.setSingleLine(false);
            other.setMaxLines(4);
            other.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            other.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            other.setTextColor(ContextCompat.getColor(mCtx, R.color.ccm_text));
            other.setHintTextColor(ContextCompat.getColor(mCtx, R.color.ccm_text_tertiary));
            other.setBackground(round(R.color.ccm_surface, 10, true));
            other.setPadding(12 * mDp, 10 * mDp, 12 * mDp, 10 * mDp);
            other.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (s.length() > 0 && !multi && !selected.isEmpty()) {
                        selected.clear();
                        for (View x : optionViews) setOptionSelected(x, false);
                    }
                    submit.setEnabled(allAnswered(selections, others));
                }
            });
            others.add(other);
            card.addView(other, topMargin(6));
        }

        submit.setEnabled(false);
        MaterialButton skip = button(false, t("跳过"));
        LinearLayout row = buttonRow(skip, submit);
        card.addView(row, topMargin(12));

        submit.setOnClickListener(v -> {
            try {
                JSONObject answers = new JSONObject();
                List<String> summary = new ArrayList<>();
                for (int i = 0; i < qTexts.size(); i++) {
                    List<String> parts = new ArrayList<>(selections.get(i));
                    String o = others.get(i).getText().toString().trim();
                    if (!o.isEmpty()) parts.add(o);
                    String ans = android.text.TextUtils.join(", ", parts);
                    answers.put(qTexts.get(i), ans);
                    summary.add(ans);
                }
                JSONObject updated = new JSONObject(input.toString()).put("answers", answers);
                finish(card, t("已回答：") + android.text.TextUtils.join(t("；"), summary), true);
                cb.onDecision(new JSONObject().put("behavior", "allow").put("updatedInput", updated), null);
            } catch (Exception ignored) {}
        });
        skip.setOnClickListener(v -> {
            finish(card, t("已跳过"), false);
            cb.onDecision(deny(t("用户跳过了这个问题，请按你的判断继续，或换种方式询问。")), null);
        });
        return card;
    }

    private static boolean allAnswered(List<Set<String>> selections, List<EditText> others) {
        for (int i = 0; i < selections.size(); i++) {
            if (selections.get(i).isEmpty() && others.get(i).getText().toString().trim().isEmpty()) return false;
        }
        return true;
    }

    private LinearLayout option(String label, String desc) {
        LinearLayout o = new LinearLayout(mCtx);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setTag(label);
        o.setPadding(14 * mDp, 10 * mDp, 14 * mDp, 10 * mDp);
        TextView l = text(label, 14, R.color.ccm_text);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        o.addView(l);
        if (desc != null && !desc.isEmpty()) o.addView(text(desc, 12, R.color.ccm_text_secondary));
        setOptionSelected(o, false);
        return o;
    }

    private void setOptionSelected(View v, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(10 * mDp);
        g.setColor(ContextCompat.getColor(mCtx, selected ? R.color.ccm_accent_soft : R.color.ccm_surface));
        g.setStroke((selected ? 2 : 1) * mDp, ContextCompat.getColor(mCtx, selected ? R.color.ccm_accent : R.color.ccm_border));
        v.setBackground(g);
    }

    // ======================= 计划审批 =======================

    private View plan(JSONObject input, Callback cb) {
        LinearLayout card = card(R.drawable.ccm_ic_check, t("计划已就绪，是否开始执行？"));
        TextView body = text("", 14, R.color.ccm_text);
        body.setTextIsSelectable(true);
        mMarkwon.setMarkdown(body, input.optString("plan"));
        body.setBackground(round(R.color.ccm_code_bg, 10, false));
        body.setPadding(12 * mDp, 10 * mDp, 12 * mDp, 10 * mDp);
        card.addView(body, topMargin(8));

        MaterialButton auto = button(true, t("批准并自动执行"));
        MaterialButton manual = button(false, t("批准，逐项确认"));
        card.addView(auto, topMargin(12));
        card.addView(manual, topMargin(6));

        final EditText feedback = new EditText(mCtx);
        feedback.setHint(t("或者告诉 Claude 要怎么改…"));
        feedback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        feedback.setTextColor(ContextCompat.getColor(mCtx, R.color.ccm_text));
        feedback.setHintTextColor(ContextCompat.getColor(mCtx, R.color.ccm_text_tertiary));
        feedback.setBackground(round(R.color.ccm_surface, 10, true));
        feedback.setPadding(12 * mDp, 10 * mDp, 12 * mDp, 10 * mDp);
        card.addView(feedback, topMargin(10));
        MaterialButton revise = button(false, t("继续修改计划"));
        card.addView(revise, topMargin(6));

        auto.setOnClickListener(v -> {
            finish(card, t("已批准计划（自动执行）"), true);
            cb.onDecision(allow(input), "acceptEdits");
        });
        manual.setOnClickListener(v -> {
            finish(card, t("已批准计划（逐项确认）"), true);
            cb.onDecision(allow(input), "default");
        });
        revise.setOnClickListener(v -> {
            String fb = feedback.getText().toString().trim();
            finish(card, t("要求修改计划") + (fb.isEmpty() ? "" : t("：") + fb), false);
            cb.onDecision(deny(fb.isEmpty() ? t("用户希望继续完善计划，请再想想并重新提交。") : t("用户对计划的修改意见：") + fb), null);
        });
        return card;
    }

    // ======================= 工具权限 =======================

    private View permission(String toolName, JSONObject input, JSONArray suggestions, String projectPath, Callback cb) {
        String label = CcmChatRenderer.toolLabel(toolName);
        LinearLayout card = card(R.drawable.ccm_ic_terminal, t("允许 Claude 执行「") + label + t("」吗？"));
        String detail;
        if ("Bash".equals(toolName)) detail = "$ " + input.optString("command");
        else if (input.has("file_path")) detail = CcmProjects.displayPath(input.optString("file_path"));
        else detail = CcmChatRenderer.toolSummary(toolName, input, projectPath);
        if (detail.isEmpty()) detail = CcmChatRenderer.ellipsize(input.toString(), 300);
        TextView d = text(detail, 13, R.color.ccm_text);
        d.setTypeface(Typeface.MONOSPACE);
        d.setTextIsSelectable(true);
        d.setMaxLines(12);
        d.setBackground(round(R.color.ccm_code_bg, 10, false));
        d.setPadding(12 * mDp, 10 * mDp, 12 * mDp, 10 * mDp);
        card.addView(d, topMargin(8));
        if ("Bash".equals(toolName) && !input.optString("description").isEmpty()) {
            TextView why = text(input.optString("description"), 12, R.color.ccm_text_secondary);
            why.setPadding(2 * mDp, 6 * mDp, 0, 0);
            card.addView(why);
        }

        MaterialButton allow = button(true, t("允许"));
        MaterialButton deny = button(false, t("拒绝"));
        card.addView(buttonRow(deny, allow), topMargin(12));
        if (suggestions != null && suggestions.length() > 0) {
            MaterialButton always = button(false, t("始终允许（") + suggestionSummary(suggestions) + t("）"));
            always.setOnClickListener(v -> {
                try {
                    finish(card, t("已始终允许"), true);
                    cb.onDecision(allow(input).put("updatedPermissions", suggestions), null);
                } catch (Exception ignored) {}
            });
            card.addView(always, topMargin(6));
        }
        allow.setOnClickListener(v -> { finish(card, t("已允许"), true); cb.onDecision(allow(input), null); });
        deny.setOnClickListener(v -> {
            finish(card, t("已拒绝"), false);
            cb.onDecision(deny(t("用户拒绝了这个操作。")), null);
        });
        return card;
    }

    private static String suggestionSummary(JSONArray suggestions) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < suggestions.length(); i++) {
            JSONObject s = suggestions.optJSONObject(i);
            if (s == null) continue;
            if ("setMode".equals(s.optString("type"))) { parts.add(t("切换模式")); continue; }
            if ("addDirectories".equals(s.optString("type"))) { parts.add(t("访问该目录")); continue; }
            JSONArray rules = s.optJSONArray("rules");
            for (int k = 0; rules != null && k < rules.length(); k++) {
                JSONObject r = rules.optJSONObject(k);
                if (r != null) parts.add(r.optString("ruleContent", r.optString("toolName")));
            }
        }
        String joined = android.text.TextUtils.join(t("、"), parts);
        return CcmChatRenderer.ellipsize(joined.isEmpty() ? t("同类操作") : joined, 30);
    }

    // ======================= 公共 =======================

    private static JSONObject allow(JSONObject input) {
        try { return new JSONObject().put("behavior", "allow").put("updatedInput", input); } catch (Exception e) { return new JSONObject(); }
    }

    private static JSONObject deny(String message) {
        try { return new JSONObject().put("behavior", "deny").put("message", message); } catch (Exception e) { return new JSONObject(); }
    }

    /** 外部取消（任务被停止）时把卡片置为不可操作。 */
    void cancel(View card) {
        if (card instanceof LinearLayout) finish((LinearLayout) card, t("已取消"), false);
    }

    /** 操作完成：移除交互控件，只保留标题和一行结果。 */
    private void finish(LinearLayout card, String result, boolean positive) {
        if (card.getTag() != null) return;
        card.setTag("done");
        while (card.getChildCount() > 1) card.removeViewAt(1);
        TextView r = text((positive ? "✓ " : "✗ ") + result, 13, positive ? R.color.ccm_ok : R.color.ccm_text_secondary);
        r.setPadding(26 * mDp, 4 * mDp, 0, 0);
        card.addView(r);
        card.setBackground(round(R.color.ccm_surface, 14, true));
    }

    private LinearLayout card(int iconRes, String title) {
        LinearLayout c = new LinearLayout(mCtx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundStroke(R.color.ccm_surface, R.color.ccm_accent));
        c.setPadding(14 * mDp, 12 * mDp, 14 * mDp, 14 * mDp);
        LinearLayout head = new LinearLayout(mCtx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mCtx);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ContextCompat.getColorStateList(mCtx, R.color.ccm_accent));
        head.addView(icon, new LinearLayout.LayoutParams(18 * mDp, 18 * mDp));
        TextView t = text(title, 14, R.color.ccm_text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = 8 * mDp;
        head.addView(t, lp);
        c.addView(head);
        return c;
    }

    private LinearLayout buttonRow(MaterialButton left, MaterialButton right) {
        LinearLayout row = new LinearLayout(mCtx);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        r.leftMargin = 8 * mDp;
        row.addView(left, l);
        row.addView(right, r);
        return row;
    }

    private MaterialButton button(boolean primary, String label) {
        MaterialButton b = primary ? new MaterialButton(mCtx)
            : new MaterialButton(mCtx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setCornerRadius(10 * mDp);
        if (!primary) {
            b.setTextColor(ContextCompat.getColor(mCtx, R.color.ccm_text));
            b.setStrokeColor(ContextCompat.getColorStateList(mCtx, R.color.ccm_border));
        }
        return b;
    }

    private TextView text(String s, int sp, int colorRes) {
        TextView tv = new TextView(mCtx);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(mCtx, colorRes));
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    private GradientDrawable round(int colorRes, int radiusDp, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ContextCompat.getColor(mCtx, colorRes));
        g.setCornerRadius(radiusDp * mDp);
        if (border) g.setStroke(mDp, ContextCompat.getColor(mCtx, R.color.ccm_border));
        return g;
    }

    private GradientDrawable roundStroke(int colorRes, int strokeRes) {
        GradientDrawable g = round(colorRes, 14, false);
        g.setStroke(2 * mDp, ContextCompat.getColor(mCtx, strokeRes));
        return g;
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp * mDp;
        return lp;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

}
