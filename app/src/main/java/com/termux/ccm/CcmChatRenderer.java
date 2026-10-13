package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;

/**
 * 把 Claude Code 的 stream-json 事件（或会话 jsonl 历史）渲染成桌面端风格的消息流：
 * 用户气泡、可折叠的思考过程、工具调用卡片（带状态与结果）、Markdown 正文、用量脚注。
 */
public class CcmChatRenderer {

    private final Context mContext;
    private final LinearLayout mContainer;
    private final Markwon mMarkwon;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int mDp;
    private String mProjectPath = "";

    /** 当前流式消息中按 content block index 记录的视图。 */
    private final Map<Integer, Block> mStreaming = new HashMap<>();
    /** 已收到 stream 增量、等待 assistant 完整事件定稿的块。 */
    private final List<Block> mPendingFinal = new ArrayList<>();
    private final Map<String, ToolCard> mTools = new HashMap<>();
    private boolean mAssistantHeaderShown = false;
    private Runnable mOnContentChanged;

    public CcmChatRenderer(Context context, LinearLayout container) {
        mContext = context;
        mContainer = container;
        mDp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());
        final int codeBg = color(R.color.ccm_code_bg);
        final int accent = color(R.color.ccm_accent);
        mMarkwon = Markwon.builder(context).usePlugin(new AbstractMarkwonPlugin() {
            @Override
            public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                builder.codeBackgroundColor(codeBg).codeBlockBackgroundColor(codeBg)
                    .linkColor(accent).blockMargin(16 * mDp).headingBreakHeight(0)
                    .codeTextSize((int) (13 * context.getResources().getDisplayMetrics().scaledDensity));
            }
        }).build();
    }

    public void setProjectPath(String path) {
        mProjectPath = path == null ? "" : path;
    }

    public void setOnContentChanged(Runnable r) {
        mOnContentChanged = r;
    }

    public void clear() {
        mContainer.removeAllViews();
        mStreaming.clear();
        mPendingFinal.clear();
        mTools.clear();
        mAssistantHeaderShown = false;
    }

    // ======================= 输入事件 =======================

    /** 用户新发的一条消息。 */
    public void addUserMessage(String text, List<Bitmap> images) {
        mAssistantHeaderShown = false;
        LinearLayout col = new LinearLayout(mContext);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        if (images != null && !images.isEmpty()) {
            LinearLayout row = new LinearLayout(mContext);
            row.setGravity(Gravity.END);
            for (Bitmap b : images) {
                ImageView iv = new ImageView(mContext);
                iv.setImageBitmap(b);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.ccm_bg_thumb);
                iv.setClipToOutline(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(96 * mDp, 96 * mDp);
                lp.leftMargin = 6 * mDp;
                row.addView(iv, lp);
            }
            col.addView(row);
        }
        if (text != null && !text.trim().isEmpty()) {
            TextView tv = textView(15, R.color.ccm_text);
            tv.setText(text.trim());
            tv.setTextIsSelectable(true);
            tv.setBackgroundResource(R.drawable.ccm_bg_user_bubble);
            tv.setPadding(14 * mDp, 10 * mDp, 14 * mDp, 10 * mDp);
            tv.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = 6 * mDp;
            col.addView(tv, lp);
        }
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 18 * mDp;
        lp.leftMargin = 40 * mDp;
        mContainer.addView(col, lp);
        changed();
    }

    /** 处理一行 stream-json 事件。返回 true 表示是 result（本轮结束）。 */
    public boolean onEvent(JSONObject ev, long turnStartMs) {
        switch (ev.optString("type")) {
            case "stream_event": onStreamEvent(ev.optJSONObject("event")); break;
            case "assistant": onAssistant(ev.optJSONObject("message"), true); break;
            case "user": onUser(ev.optJSONObject("message"), false); break;
            case "result": onResult(ev, turnStartMs); return true;
            case "system":
                if ("compact_boundary".equals(ev.optString("subtype"))) addNote(t("— 上下文已自动压缩 —"));
                break;
        }
        return false;
    }

    /** 回放会话历史中的一条记录。 */
    public void onHistory(JSONObject rec) {
        JSONObject msg = rec.optJSONObject("message");
        if ("assistant".equals(rec.optString("type"))) {
            onAssistant(msg, false);
        } else {
            onUser(msg, true);
        }
    }

    /** 插入交互卡片（选择题 / 权限 / 计划审批）。 */
    public void addInteraction(View card) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 10 * mDp;
        mContainer.addView(card, lp);
        changed();
    }

    public Markwon markwon() {
        return mMarkwon;
    }

    public String projectPath() {
        return mProjectPath;
    }

    public void addError(String text) {
        TextView tv = textView(13, R.color.ccm_error);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setBackgroundResource(R.drawable.ccm_bg_error);
        tv.setPadding(12 * mDp, 10 * mDp, 12 * mDp, 10 * mDp);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 10 * mDp;
        mContainer.addView(tv, lp);
        changed();
    }

    public TextView addNote(String text) {
        TextView tv = textView(12, R.color.ccm_text_tertiary);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 10 * mDp;
        mContainer.addView(tv, lp);
        changed();
        return tv;
    }

    /** 等待首个输出时的「思考中」指示。 */
    public View addWorkingIndicator() {
        ensureAssistantHeader();
        LinearLayout row = new LinearLayout(mContext);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar pb = new ProgressBar(mContext, null, android.R.attr.progressBarStyleSmall);
        pb.setIndeterminateTintList(ContextCompat.getColorStateList(mContext, R.color.ccm_accent));
        row.addView(pb, new LinearLayout.LayoutParams(16 * mDp, 16 * mDp));
        TextView tv = textView(13, R.color.ccm_text_secondary);
        tv.setText(t("  正在处理…"));
        row.addView(tv);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 8 * mDp;
        mContainer.addView(row, lp);
        changed();
        return row;
    }

    // ======================= 流式事件 =======================

    private void onStreamEvent(JSONObject e) {
        if (e == null) return;
        switch (e.optString("type")) {
            case "message_start":
                mStreaming.clear();
                break;
            case "content_block_start": {
                JSONObject cb = e.optJSONObject("content_block");
                if (cb == null) break;
                Block b = createBlock(cb.optString("type"), cb);
                if (b != null) {
                    mStreaming.put(e.optInt("index"), b);
                    mPendingFinal.add(b);
                }
                break;
            }
            case "content_block_delta": {
                Block b = mStreaming.get(e.optInt("index"));
                JSONObject d = e.optJSONObject("delta");
                if (b == null || d == null) break;
                switch (d.optString("type")) {
                    case "text_delta": b.append(d.optString("text")); break;
                    case "thinking_delta": b.append(d.optString("thinking")); break;
                    case "input_json_delta": b.append(d.optString("partial_json")); break;
                }
                break;
            }
            case "content_block_stop": {
                Block b = mStreaming.get(e.optInt("index"));
                if (b != null) b.flush();
                break;
            }
        }
    }

    private void onAssistant(JSONObject message, boolean live) {
        if (message == null) return;
        JSONArray content = message.optJSONArray("content");
        if (content == null) return;
        for (int i = 0; i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            String type = c.optString("type");
            // 先找同类型、已经流式显示过的块来定稿，避免重复
            Block target = null;
            if (live) {
                for (int k = 0; k < mPendingFinal.size(); k++) {
                    if (mPendingFinal.get(k).type.equals(type)) { target = mPendingFinal.remove(k); break; }
                }
            }
            if (target == null) target = createBlock(type, c);
            if (target != null) target.finish(c);
        }
    }

    private void onUser(JSONObject message, boolean history) {
        if (message == null) return;
        Object content = message.opt("content");
        if (history) {
            // 历史中的用户输入：文字 + 图片
            String text = CcmProjects.userText(message);
            List<Bitmap> imgs = new ArrayList<>();
            if (content instanceof JSONArray) {
                JSONArray arr = (JSONArray) content;
                boolean onlyToolResults = true;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String t = b.optString("type");
                    if (!"tool_result".equals(t)) onlyToolResults = false;
                    if ("image".equals(t)) {
                        Bitmap bm = decodeImage(b.optJSONObject("source"));
                        if (bm != null) imgs.add(bm);
                    }
                }
                if (onlyToolResults) { applyToolResults(arr); return; }
            }
            if (text != null && text.startsWith("<")) return; // 命令输出等内部消息
            if ((text != null && !text.trim().isEmpty()) || !imgs.isEmpty()) addUserMessage(text, imgs);
            return;
        }
        if (content instanceof JSONArray) applyToolResults((JSONArray) content);
    }

    private void applyToolResults(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null || !"tool_result".equals(c.optString("type"))) continue;
            ToolCard card = mTools.get(c.optString("tool_use_id"));
            if (card != null) card.setResult(toolResultText(c.opt("content")), c.optBoolean("is_error"));
        }
    }

    private void onResult(JSONObject ev, long turnStartMs) {
        for (ToolCard t : mTools.values()) t.stopSpinner();
        if (ev.optBoolean("is_error") && ev.has("result")) addError(ev.optString("result"));
        JSONObject u = ev.optJSONObject("usage");
        long in = 0, cache = 0, out = 0;
        if (u != null) {
            in = u.optLong("input_tokens") + u.optLong("cache_creation_input_tokens") + u.optLong("cache_read_input_tokens");
            cache = u.optLong("cache_read_input_tokens");
            out = u.optLong("output_tokens");
        }
        double secs = (System.currentTimeMillis() - turnStartMs) / 1000.0;
        TextView tv = textView(11, R.color.ccm_text_tertiary);
        tv.setText(String.format(Locale.ROOT, t("%s 输入 · %s 缓存 · %s 输出 · %.1fs"), k(in), k(cache), k(out), secs));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 8 * mDp;
        mContainer.addView(tv, lp);
        mAssistantHeaderShown = false;
        changed();
    }

    // ======================= 块 =======================

    private Block createBlock(String type, JSONObject initial) {
        switch (type) {
            case "text": ensureAssistantHeader(); return new TextBlock();
            case "thinking": ensureAssistantHeader(); return new ThinkingBlock();
            case "tool_use": {
                ensureAssistantHeader();
                ToolCard card = new ToolCard(initial.optString("id"), initial.optString("name"));
                mTools.put(initial.optString("id"), card);
                return card;
            }
            default: return null;
        }
    }

    private void ensureAssistantHeader() {
        if (mAssistantHeaderShown) return;
        mAssistantHeaderShown = true;
        ImageView iv = new ImageView(mContext);
        iv.setImageResource(R.drawable.ccm_ic_logo);
        iv.setImageTintList(ContextCompat.getColorStateList(mContext, R.color.ccm_accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(20 * mDp, 20 * mDp);
        lp.topMargin = 18 * mDp;
        mContainer.addView(iv, lp);
    }

    private abstract class Block {
        final String type;
        final StringBuilder buf = new StringBuilder();

        Block(String type) { this.type = type; }

        void append(String s) {
            buf.append(s);
            scheduleRender();
        }

        private boolean mScheduled;

        void scheduleRender() {
            if (mScheduled) return;
            mScheduled = true;
            mHandler.postDelayed(() -> { mScheduled = false; render(); changed(); }, 80);
        }

        void flush() { render(); changed(); }

        abstract void render();

        abstract void finish(JSONObject full);
    }

    private class TextBlock extends Block {
        final TextView tv;

        TextBlock() {
            super("text");
            tv = textView(15, R.color.ccm_text);
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = 8 * mDp;
            mContainer.addView(tv, lp);
        }

        @Override void render() { mMarkwon.setMarkdown(tv, buf.toString()); }

        @Override void finish(JSONObject full) {
            buf.setLength(0);
            buf.append(full.optString("text"));
            if (buf.toString().trim().isEmpty()) tv.setVisibility(View.GONE);
            flush();
        }
    }

    private class ThinkingBlock extends Block {
        final TextView head, body;
        final long start = System.currentTimeMillis();
        boolean done;

        ThinkingBlock() {
            super("thinking");
            LinearLayout box = new LinearLayout(mContext);
            box.setOrientation(LinearLayout.VERTICAL);
            head = textView(13, R.color.ccm_text_secondary);
            head.setText(t("思考中…"));
            head.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ccm_ic_chevron_right, R.color.ccm_text_tertiary, 16), null, null, null);
            head.setCompoundDrawablePadding(4 * mDp);
            head.setGravity(Gravity.CENTER_VERTICAL);
            body = textView(13, R.color.ccm_text_secondary);
            body.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
            body.setLineSpacing(0, 1.2f);
            body.setTextIsSelectable(true);
            body.setPadding(20 * mDp, 4 * mDp, 0, 4 * mDp);
            body.setVisibility(View.GONE);
            box.addView(head);
            box.addView(body);
            head.setOnClickListener(v -> {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                head.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    tinted(show ? R.drawable.ccm_ic_chevron_down : R.drawable.ccm_ic_chevron_right, R.color.ccm_text_tertiary, 16), null, null, null);
            });
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = 8 * mDp;
            mContainer.addView(box, lp);
        }

        @Override void render() {
            body.setText(buf.toString().trim());
            if (!done) {
                String t = buf.toString().trim().replace('\n', ' ');
                head.setText(t.isEmpty() ? t("思考中…") : t("思考中… ") + (t.length() > 40 ? "…" + t.substring(t.length() - 40) : t));
            }
        }

        @Override void finish(JSONObject full) {
            done = true;
            String t = full.optString("thinking");
            if (!t.isEmpty()) { buf.setLength(0); buf.append(t); }
            long secs = Math.max(1, (System.currentTimeMillis() - start) / 1000);
            if (buf.toString().trim().isEmpty()) {
                ((View) head.getParent()).setVisibility(View.GONE);
                return;
            }
            render();
            head.setText(mStreaming.isEmpty() ? t("思考过程") : t("思考了 ") + secs + t(" 秒"));
            changed();
        }
    }

    /** 工具调用卡片：图标 + 名称 + 摘要 + 状态，点开查看输入与结果。 */
    private class ToolCard extends Block {
        final String id, name;
        final LinearLayout box, detail;
        final TextView title, subtitle, resultView;
        final ProgressBar spinner;
        final ImageView status;
        JSONObject input = new JSONObject();

        ToolCard(String id, String name) {
            super("tool_use");
            this.id = id;
            this.name = name;
            box = new LinearLayout(mContext);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.ccm_bg_tool);
            box.setPadding(12 * mDp, 9 * mDp, 12 * mDp, 9 * mDp);

            LinearLayout head = new LinearLayout(mContext);
            head.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(mContext);
            icon.setImageDrawable(tinted(toolIcon(name), R.color.ccm_text_secondary, 16));
            head.addView(icon, new LinearLayout.LayoutParams(16 * mDp, 16 * mDp));
            title = textView(13, R.color.ccm_text);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setText(toolLabel(name));
            LinearLayout.LayoutParams tlp = wrap();
            tlp.leftMargin = 8 * mDp;
            head.addView(title, tlp);
            subtitle = textView(13, R.color.ccm_text_secondary);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            slp.leftMargin = 6 * mDp;
            head.addView(subtitle, slp);
            spinner = new ProgressBar(mContext, null, android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminateTintList(ContextCompat.getColorStateList(mContext, R.color.ccm_accent));
            head.addView(spinner, new LinearLayout.LayoutParams(14 * mDp, 14 * mDp));
            status = new ImageView(mContext);
            status.setVisibility(View.GONE);
            head.addView(status, new LinearLayout.LayoutParams(16 * mDp, 16 * mDp));
            box.addView(head);

            detail = new LinearLayout(mContext);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setVisibility(View.GONE);
            box.addView(detail);
            resultView = codeView();
            resultView.setVisibility(View.GONE);

            head.setOnClickListener(v -> detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = 6 * mDp;
            mContainer.addView(box, lp);
        }

        @Override void render() {
            // 输入参数还在流式生成：尝试从不完整 JSON 中提取摘要
            subtitle.setText(partialSummary(buf.toString()));
        }

        @Override void finish(JSONObject full) {
            JSONObject in = full.optJSONObject("input");
            if (in != null) input = in;
            subtitle.setText(toolSummary(name, input, mProjectPath));
            buildDetail();
            changed();
        }

        void buildDetail() {
            detail.removeAllViews();
            String n = shortName(name);
            switch (n) {
                case "Bash": {
                    TextView cmd = codeView();
                    cmd.setText("$ " + input.optString("command"));
                    detail.addView(cmd, codeLp());
                    break;
                }
                case "Edit":
                    detail.addView(diffView(input.optString("old_string"), input.optString("new_string")), codeLp());
                    detail.setVisibility(View.VISIBLE);
                    break;
                case "MultiEdit": {
                    JSONArray edits = input.optJSONArray("edits");
                    for (int i = 0; edits != null && i < edits.length(); i++) {
                        JSONObject e = edits.optJSONObject(i);
                        if (e != null) detail.addView(diffView(e.optString("old_string"), e.optString("new_string")), codeLp());
                    }
                    detail.setVisibility(View.VISIBLE);
                    break;
                }
                case "Write":
                    detail.addView(diffView("", ellipsize(input.optString("content"), 3000)), codeLp());
                    break;
                case "TodoWrite":
                    detail.addView(todoView(input.optJSONArray("todos")));
                    detail.setVisibility(View.VISIBLE);
                    spinner.setVisibility(View.GONE);
                    break;
                default:
                    if (!n.startsWith("phone_") || input.length() > 1) {
                        TextView args = codeView();
                        args.setText(prettyJson(input));
                        detail.addView(args, codeLp());
                    }
            }
            detail.addView(resultView, codeLp());
        }

        void setResult(String text, boolean isError) {
            stopSpinner();
            status.setVisibility(View.VISIBLE);
            status.setImageDrawable(tinted(isError ? R.drawable.ccm_ic_close : R.drawable.ccm_ic_check,
                isError ? R.color.ccm_error : R.color.ccm_ok, 16));
            if ("TodoWrite".equals(shortName(name))) return;
            String t = text == null ? "" : text.trim();
            if (!t.isEmpty()) {
                resultView.setText(ellipsize(t, 6000));
                resultView.setTextColor(color(isError ? R.color.ccm_error : R.color.ccm_text_secondary));
                resultView.setVisibility(View.VISIBLE);
            }
            if (isError) detail.setVisibility(View.VISIBLE);
            changed();
        }

        void stopSpinner() { spinner.setVisibility(View.GONE); }
    }

    // ======================= 工具展示 =======================

    private static String shortName(String name) {
        return name.startsWith("mcp__phone__") ? name.substring("mcp__phone__".length()) : name;
    }

    static String toolLabel(String name) {
        String n = shortName(name);
        switch (n) {
            case "phone_screen": return t("读取屏幕");
            case "phone_tap": return t("点击");
            case "phone_input": return t("输入");
            case "phone_swipe": return t("滑动");
            case "phone_key": return t("按键");
            case "phone_launch": return t("打开应用");
            case "phone_batch": return t("批量操作");
            case "phone_apps": return t("查找应用");
            case "phone_screenshot": return t("截图");
            case "phone_shell": return "adb shell";
            case "TodoWrite": return t("待办");
            case "ToolSearch": return t("加载工具");
            case "AskUserQuestion": return t("提问");
            case "ExitPlanMode": return t("提交计划");
            case "EnterPlanMode": return t("进入规划");
            case "Task": case "Agent": return t("子任务");
            default: return n.startsWith("mcp__") ? n.replaceFirst("^mcp__([^_]+)__", "$1 · ") : n;
        }
    }

    private static int toolIcon(String name) {
        String n = shortName(name);
        if (n.startsWith("phone_")) return R.drawable.ccm_ic_phone;
        switch (n) {
            case "Bash": return R.drawable.ccm_ic_terminal;
            case "Edit": case "MultiEdit": case "Write": case "NotebookEdit": return R.drawable.ccm_ic_edit_note;
            case "Read": case "Glob": case "Grep": case "LS": return R.drawable.ccm_ic_folder;
            case "WebFetch": case "WebSearch": return R.drawable.ccm_ic_link;
            case "TodoWrite": return R.drawable.ccm_ic_check;
            default: return R.drawable.ccm_ic_logo;
        }
    }

    static String toolSummary(String name, JSONObject in, String projectPath) {
        String n = shortName(name);
        switch (n) {
            case "Bash": return in.has("description") ? in.optString("description") : in.optString("command");
            case "Read": case "Edit": case "MultiEdit": case "Write": case "NotebookEdit": return relPath(projectPath, in.optString("file_path", in.optString("notebook_path")));
            case "Glob": return in.optString("pattern");
            case "Grep": return in.optString("pattern") + (in.has("path") ? "  " + relPath(projectPath, in.optString("path")) : "");
            case "WebFetch": return in.optString("url");
            case "WebSearch": return in.optString("query");
            case "Task": case "Agent": return in.optString("description");
            case "TodoWrite": {
                JSONArray t = in.optJSONArray("todos");
                int done = 0, total = t == null ? 0 : t.length();
                for (int i = 0; i < total; i++) if ("completed".equals(t.optJSONObject(i).optString("status"))) done++;
                return done + "/" + total + t(" 已完成");
            }
            case "phone_tap": return (in.optBoolean("long") ? t("长按 ") : "") + (in.has("id") ? "[" + in.optInt("id") + "]" : "(" + in.optInt("x") + ", " + in.optInt("y") + ")");
            case "phone_input": return t("「") + ellipsize(in.optString("text"), 40) + t("」") + (in.optBoolean("submit") ? t(" 并发送") : "");
            case "phone_swipe": return in.optString("direction");
            case "phone_key": return in.optString("key");
            case "phone_launch": return in.optString("app");
            case "phone_batch": return (in.optJSONArray("actions") == null ? 0 : in.optJSONArray("actions").length()) + t(" 个动作");
            case "phone_apps": return in.optString("filter");
            case "phone_screenshot": return in.optString("question");
            case "phone_shell": return in.optString("cmd");
            case "phone_screen": return "";
            case "AskUserQuestion": {
                JSONArray qs = in.optJSONArray("questions");
                JSONObject q0 = qs == null ? null : qs.optJSONObject(0);
                return q0 == null ? "" : q0.optString("question");
            }
            case "ExitPlanMode": return "";
            case "ToolSearch": {
                // "select:mcp__phone__phone_screen,..." → "读取屏幕、点击"
                String q = in.optString("query").replaceFirst("^select:", "");
                StringBuilder sb = new StringBuilder();
                for (String t : q.split(",")) {
                    if (t.trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append(t("、"));
                    sb.append(toolLabel(t.trim()));
                }
                return sb.toString();
            }
            default: return ellipsize(in.toString(), 80);
        }
    }

    private static String partialSummary(String partialJson) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"(command|file_path|pattern|description|url|app|text|query)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").matcher(partialJson);
        return m.find() ? m.group(2).replace("\\n", " ").replace("\\\"", "\"") : "";
    }

    private static String relPath(String projectPath, String p) {
        if (p == null) return "";
        if (projectPath != null && !projectPath.isEmpty() && p.startsWith(projectPath + "/")) return p.substring(projectPath.length() + 1);
        return CcmProjects.displayPath(p);
    }

    private View todoView(JSONArray todos) {
        LinearLayout col = new LinearLayout(mContext);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 6 * mDp, 0, 0);
        for (int i = 0; todos != null && i < todos.length(); i++) {
            JSONObject t = todos.optJSONObject(i);
            if (t == null) continue;
            String st = t.optString("status");
            TextView tv = textView(13, "completed".equals(st) ? R.color.ccm_text_tertiary : R.color.ccm_text);
            String mark = "completed".equals(st) ? "☑ " : "in_progress".equals(st) ? "◐ " : "☐ ";
            tv.setText(mark + t.optString("content"));
            if ("completed".equals(st)) tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            if ("in_progress".equals(st)) tv.setTextColor(color(R.color.ccm_accent));
            tv.setPadding(0, 2 * mDp, 0, 2 * mDp);
            col.addView(tv);
        }
        return col;
    }

    private View diffView(String oldText, String newText) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendDiffLines(sb, oldText, "- ", R.color.ccm_diff_del, R.color.ccm_error);
        appendDiffLines(sb, newText, "+ ", R.color.ccm_diff_add, R.color.ccm_ok);
        TextView tv = codeView();
        tv.setText(sb);
        tv.setMaxLines(24);
        return wrapHorizontal(tv);
    }

    private void appendDiffLines(SpannableStringBuilder sb, String text, String prefix, int bg, int fg) {
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\n", -1)) {
            int start = sb.length();
            sb.append(prefix).append(line).append('\n');
            sb.setSpan(new BackgroundColorSpan(color(bg)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(color(fg)), start, start + prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private View wrapHorizontal(TextView tv) {
        HorizontalScrollView hs = new HorizontalScrollView(mContext);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tv);
        hs.setBackgroundResource(R.drawable.ccm_bg_code);
        tv.setBackground(null);
        return hs;
    }

    private TextView codeView() {
        TextView tv = textView(12, R.color.ccm_text_secondary);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setBackgroundResource(R.drawable.ccm_bg_code);
        tv.setPadding(10 * mDp, 8 * mDp, 10 * mDp, 8 * mDp);
        tv.setMaxLines(40);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    private LinearLayout.LayoutParams codeLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = 8 * mDp;
        return lp;
    }

    // ======================= 小工具 =======================

    static String toolResultText(Object content) {
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if ("text".equals(o.optString("type"))) sb.append(o.optString("text"));
                else sb.append("[").append(o.optString("type")).append("]");
            }
            return sb.toString();
        }
        return content == null ? "" : String.valueOf(content);
    }

    private static Bitmap decodeImage(JSONObject source) {
        if (source == null || !"base64".equals(source.optString("type"))) return null;
        try {
            byte[] data = Base64.decode(source.optString("data"), Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String prettyJson(JSONObject o) {
        try { return o.toString(2).replace("\\/", "/"); } catch (Exception e) { return o.toString(); }
    }

    static String ellipsize(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String k(long n) {
        return n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    private android.graphics.drawable.Drawable tinted(int res, int colorRes, int sizeDp) {
        android.graphics.drawable.Drawable d = ContextCompat.getDrawable(mContext, res).mutate();
        d.setTint(color(colorRes));
        d.setBounds(0, 0, sizeDp * mDp, sizeDp * mDp);
        return d;
    }

    private int color(int res) {
        return ContextCompat.getColor(mContext, res);
    }

    private TextView textView(int sp, int colorRes) {
        TextView tv = new TextView(mContext);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color(colorRes));
        return tv;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void changed() {
        if (mOnContentChanged != null) mOnContentChanged.run();
    }
}
