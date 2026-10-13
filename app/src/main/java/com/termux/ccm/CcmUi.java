package com.termux.ccm;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

/** 设置/向导/配对等次级页面共用的界面构建工具，统一 Theme.Ccm 的视觉。 */
final class CcmUi {

    final Activity a;
    final int dp;
    /** 页面内容容器（在 ScrollView 内） */
    final LinearLayout content;

    private CcmUi(Activity activity, LinearLayout content) {
        a = activity;
        dp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, activity.getResources().getDisplayMetrics());
        this.content = content;
    }

    /** 创建「顶栏 + 可滚动内容」的页面骨架并 setContentView。 */
    static CcmUi page(Activity activity, String title) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.ccm_bg));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        CcmUi ui = new CcmUi(activity, content);
        content.setPadding(20 * ui.dp, 4 * ui.dp, 20 * ui.dp, 32 * ui.dp);

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(6 * ui.dp, 0, 6 * ui.dp, 0);
        ImageButton back = new ImageButton(activity);
        back.setImageResource(R.drawable.ccm_ic_chevron_right);
        back.setRotation(180);
        back.setImageTintList(ContextCompat.getColorStateList(activity, R.color.ccm_text));
        back.setBackgroundResource(android.R.color.transparent);
        back.setPadding(8 * ui.dp, 8 * ui.dp, 8 * ui.dp, 8 * ui.dp);
        back.setOnClickListener(v -> activity.finish());
        bar.addView(back, new LinearLayout.LayoutParams(44 * ui.dp, 44 * ui.dp));
        TextView t = ui.text(title, 17, R.color.ccm_text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(t);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56 * ui.dp));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        activity.setContentView(root);
        return ui;
    }

    TextView text(String s, int sp, int colorRes) {
        TextView tv = new TextView(a);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(a, colorRes));
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    TextView heading(LinearLayout parent, String s) {
        TextView tv = text(s, 26, R.color.ccm_text);
        tv.setTypeface(Typeface.SERIF);
        tv.setPadding(0, 8 * dp, 0, 4 * dp);
        parent.addView(tv);
        return tv;
    }

    TextView section(LinearLayout parent, String s) {
        TextView tv = text(s, 12, R.color.ccm_text_tertiary);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.04f);
        tv.setPadding(4 * dp, 22 * dp, 0, 8 * dp);
        parent.addView(tv);
        return tv;
    }

    TextView hint(LinearLayout parent, String s) {
        TextView tv = text(s, 13, R.color.ccm_text_secondary);
        tv.setPadding(0, 2 * dp, 0, 6 * dp);
        parent.addView(tv);
        return tv;
    }

    /** 圆角卡片 */
    LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.ccm_bg_card);
        c.setPadding(16 * dp, 14 * dp, 16 * dp, 14 * dp);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 10 * dp;
        parent.addView(c, lp);
        return c;
    }

    /** 带序号圆点的步骤卡片标题行，返回右侧状态文字 */
    TextView stepHeader(LinearLayout card, int index, String title) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = text(String.valueOf(index), 13, R.color.ccm_on_accent);
        num.setGravity(Gravity.CENTER);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setBackgroundResource(R.drawable.ccm_bg_send);
        row.addView(num, new LinearLayout.LayoutParams(24 * dp, 24 * dp));
        TextView t = text(title, 16, R.color.ccm_text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = 10 * dp;
        row.addView(t, lp);
        TextView status = text("", 13, R.color.ccm_text_secondary);
        row.addView(status);
        card.addView(row);
        return status;
    }

    /** 状态行：圆点 + 标签 + 右侧值 */
    TextView[] statusRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 7 * dp, 0, 7 * dp);
        TextView dot = new TextView(a);
        dot.setBackgroundResource(R.drawable.ccm_dot);
        row.addView(dot, new LinearLayout.LayoutParams(8 * dp, 8 * dp));
        TextView l = text(label, 14, R.color.ccm_text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = 10 * dp;
        row.addView(l, lp);
        TextView v = text("", 13, R.color.ccm_text_secondary);
        row.addView(v);
        parent.addView(row);
        return new TextView[]{dot, v};
    }

    void setStatus(TextView[] row, boolean ok, String value) {
        setStatus(row, ok ? R.color.ccm_ok : R.color.ccm_warn, value);
    }

    void setStatus(TextView[] row, int colorRes, String value) {
        row[0].getBackground().mutate().setTint(ContextCompat.getColor(a, colorRes));
        row[1].setText(value);
    }

    MaterialButton primary(LinearLayout parent, String s, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(a);
        b.setText(s);
        b.setOnClickListener(l);
        addButton(parent, b);
        return b;
    }

    MaterialButton outlined(LinearLayout parent, String s, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(ContextCompat.getColor(a, R.color.ccm_text));
        b.setStrokeColor(ContextCompat.getColorStateList(a, R.color.ccm_border));
        b.setCornerRadius(10 * dp);
        b.setOnClickListener(l);
        addButton(parent, b);
        return b;
    }

    private void addButton(LinearLayout parent, MaterialButton b) {
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (parent.getChildCount() > 0) lp.leftMargin = 8 * dp;
            parent.addView(b, lp);
        } else {
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = 8 * dp;
            parent.addView(b, lp);
        }
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 4 * dp;
        parent.addView(r, lp);
        return r;
    }

    EditText field(LinearLayout parent, String label, String value, boolean secret) {
        if (label != null) {
            TextView l = text(label, 13, R.color.ccm_text_secondary);
            l.setPadding(2 * dp, 12 * dp, 0, 6 * dp);
            parent.addView(l);
        }
        EditText et = new EditText(a);
        et.setBackgroundResource(R.drawable.ccm_bg_field);
        et.setPadding(14 * dp, 11 * dp, 14 * dp, 11 * dp);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(ContextCompat.getColor(a, R.color.ccm_text));
        et.setHintTextColor(ContextCompat.getColor(a, R.color.ccm_text_tertiary));
        et.setSingleLine(true);
        et.setText(value);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        parent.addView(et, matchWrap());
        return et;
    }

    /** 日志区（等宽小字） */
    TextView logView(LinearLayout parent) {
        TextView tv = text("", 11, R.color.ccm_text_secondary);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setBackgroundResource(R.drawable.ccm_bg_code);
        tv.setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 8 * dp;
        parent.addView(tv, lp);
        return tv;
    }

    ImageView icon(int res, int colorRes, int sizeDp) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(res);
        iv.setImageTintList(ContextCompat.getColorStateList(a, colorRes));
        iv.setLayoutParams(new LinearLayout.LayoutParams(sizeDp * dp, sizeDp * dp));
        return iv;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
