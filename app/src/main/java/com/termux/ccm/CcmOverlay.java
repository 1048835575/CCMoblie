package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.R;

import java.util.List;

/**
 * 后台任务悬浮窗：圆形按钮（可拖动，贴边）+ 当前动作文字；点击展开进度面板，
 * 可查看步骤、停止任务、打开对应会话的终端或回到 App。
 */
public final class CcmOverlay implements CcmTaskState.Listener {

    private static CcmOverlay sInstance;

    private final Context mCtx;
    private final WindowManager mWm;
    private final int mDp;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams mParams;
    private final FrameLayout mRoot;
    private LinearLayout mCollapsed, mPanel, mStepsBox;
    private TextView mLabel, mPanelTitle, mPanelStatus, mHeadline, mReply, mStopBtn;
    private ProgressBar mRing;
    private ImageView mBadge;
    private boolean mExpanded, mAttached;

    private final Runnable mTicker = new Runnable() {
        @Override public void run() {
            if (mExpanded) updatePanelStatus();
            if (CcmTaskState.isRunning()) mHandler.postDelayed(this, 1000);
        }
    };

    public static boolean canShow(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    /** 显示（已显示则刷新）。没有悬浮窗权限时什么也不做。 */
    public static void show(Context context) {
        if (!canShow(context)) return;
        if (sInstance == null) sInstance = new CcmOverlay(context.getApplicationContext());
        sInstance.attach();
    }

    public static void hide() {
        if (sInstance != null) sInstance.detach();
    }

    private CcmOverlay(Context app) {
        mCtx = new ContextThemeWrapper(app, R.style.Theme_Ccm);
        mWm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        mDp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, app.getResources().getDisplayMetrics());
        mParams = new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.TOP | Gravity.START;
        int screenW = app.getResources().getDisplayMetrics().widthPixels;
        int screenH = app.getResources().getDisplayMetrics().heightPixels;
        mParams.x = screenW - 64 * mDp;
        mParams.y = (int) (screenH * 0.32f);
        mRoot = new FrameLayout(mCtx);
        buildCollapsed();
        buildPanel();
        mRoot.addView(mCollapsed);
        mRoot.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && mExpanded) collapse();
            return false;
        });
    }

    private void attach() {
        if (!mAttached) {
            try {
                mWm.addView(mRoot, mParams);
                mAttached = true;
                CcmTaskState.addListener(this);
            } catch (Exception e) {
                return;
            }
        }
        onTaskChanged();
        mHandler.removeCallbacks(mTicker);
        mHandler.post(mTicker);
    }

    private void detach() {
        if (!mAttached) return;
        CcmTaskState.removeListener(this);
        mHandler.removeCallbacks(mTicker);
        try { mWm.removeView(mRoot); } catch (Exception ignored) {}
        mAttached = false;
        if (mExpanded) { mExpanded = false; mRoot.removeAllViews(); mRoot.addView(mCollapsed); }
    }

    // ======================= 收起态：圆形按钮 + 当前动作 =======================

    @SuppressLint("ClickableViewAccessibility")
    private void buildCollapsed() {
        mCollapsed = new LinearLayout(mCtx);
        mCollapsed.setGravity(Gravity.CENTER_VERTICAL);
        mCollapsed.setPadding(4 * mDp, 4 * mDp, 4 * mDp, 4 * mDp);

        FrameLayout bubble = new FrameLayout(mCtx);
        mRing = new ProgressBar(mCtx);
        mRing.setIndeterminateTintList(ContextCompat.getColorStateList(mCtx, R.color.ccm_accent));
        bubble.addView(mRing, new FrameLayout.LayoutParams(56 * mDp, 56 * mDp, Gravity.CENTER));
        View disc = new View(mCtx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ContextCompat.getColor(mCtx, R.color.ccm_accent));
        g.setStroke(2 * mDp, 0xFFFFFFFF);
        disc.setBackground(g);
        disc.setElevation(6 * mDp);
        bubble.addView(disc, new FrameLayout.LayoutParams(46 * mDp, 46 * mDp, Gravity.CENTER));
        ImageView icon = new ImageView(mCtx);
        icon.setImageResource(R.drawable.ccm_ic_logo);
        icon.setImageTintList(ContextCompat.getColorStateList(mCtx, R.color.ccm_on_accent));
        icon.setElevation(7 * mDp);
        bubble.addView(icon, new FrameLayout.LayoutParams(24 * mDp, 24 * mDp, Gravity.CENTER));
        mBadge = new ImageView(mCtx);
        mBadge.setElevation(8 * mDp);
        mBadge.setPadding(2 * mDp, 2 * mDp, 2 * mDp, 2 * mDp);
        bubble.addView(mBadge, new FrameLayout.LayoutParams(18 * mDp, 18 * mDp, Gravity.BOTTOM | Gravity.END));
        mCollapsed.addView(bubble, new LinearLayout.LayoutParams(56 * mDp, 56 * mDp));

        mLabel = new TextView(mCtx);
        mLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mLabel.setTextColor(ContextCompat.getColor(mCtx, R.color.ccm_text));
        mLabel.setMaxWidth(200 * mDp);
        mLabel.setMaxLines(2);
        mLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mLabel.setPadding(10 * mDp, 6 * mDp, 10 * mDp, 6 * mDp);
        mLabel.setBackground(round(R.color.ccm_surface, 12, true));
        mLabel.setElevation(4 * mDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 6 * mDp;
        lp.rightMargin = 6 * mDp;
        mCollapsed.addView(mLabel, lp);

        View.OnTouchListener drag = new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean moved;
            final int slop = ViewConfiguration.get(mCtx).getScaledTouchSlop();

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = mParams.x; startY = mParams.y;
                        downX = e.getRawX(); downY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        if (moved) {
                            mParams.x = startX + (int) dx;
                            mParams.y = startY + (int) dy;
                            updateLayout();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) snapToEdge(); else expand();
                        return true;
                }
                return false;
            }
        };
        bubble.setOnTouchListener(drag);
        mLabel.setOnTouchListener(drag);
    }

    private void snapToEdge() {
        int screenW = mCtx.getResources().getDisplayMetrics().widthPixels;
        int w = mCollapsed.getWidth();
        boolean left = mParams.x + 28 * mDp < screenW / 2;
        mParams.x = left ? 0 : screenW - w;
        // 贴右边时文字放到按钮左侧
        mCollapsed.setLayoutDirection(left ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
        updateLayout();
        mRoot.post(() -> {
            if (!left) { mParams.x = screenW - mCollapsed.getWidth(); updateLayout(); }
        });
    }

    // ======================= 展开态：进度面板 =======================

    private void buildPanel() {
        mPanel = new LinearLayout(mCtx);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackground(round(R.color.ccm_bg, 18, true));
        mPanel.setElevation(10 * mDp);
        mPanel.setPadding(16 * mDp, 14 * mDp, 16 * mDp, 12 * mDp);

        LinearLayout head = new LinearLayout(mCtx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView spark = new ImageView(mCtx);
        spark.setImageResource(R.drawable.ccm_ic_logo);
        spark.setImageTintList(ContextCompat.getColorStateList(mCtx, R.color.ccm_accent));
        head.addView(spark, new LinearLayout.LayoutParams(20 * mDp, 20 * mDp));
        mPanelTitle = text(15, R.color.ccm_text);
        mPanelTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mPanelTitle.setSingleLine(true);
        mPanelTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = 8 * mDp;
        head.addView(mPanelTitle, tlp);
        ImageView close = new ImageView(mCtx);
        close.setImageResource(R.drawable.ccm_ic_close);
        close.setImageTintList(ContextCompat.getColorStateList(mCtx, R.color.ccm_text_secondary));
        close.setPadding(4 * mDp, 4 * mDp, 4 * mDp, 4 * mDp);
        close.setOnClickListener(v -> collapse());
        head.addView(close, new LinearLayout.LayoutParams(28 * mDp, 28 * mDp));
        mPanel.addView(head);

        mPanelStatus = text(12, R.color.ccm_text_tertiary);
        mPanelStatus.setPadding(28 * mDp, 0, 0, 8 * mDp);
        mPanel.addView(mPanelStatus);

        mHeadline = text(13, R.color.ccm_accent);
        mHeadline.setMaxLines(3);
        mHeadline.setBackground(round(R.color.ccm_accent_soft, 10, false));
        mHeadline.setPadding(10 * mDp, 8 * mDp, 10 * mDp, 8 * mDp);
        mPanel.addView(mHeadline);

        ScrollView sv = new ScrollView(mCtx);
        mStepsBox = new LinearLayout(mCtx);
        mStepsBox.setOrientation(LinearLayout.VERTICAL);
        mStepsBox.setPadding(0, 6 * mDp, 0, 6 * mDp);
        sv.addView(mStepsBox);
        mPanel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200 * mDp));

        mReply = text(13, R.color.ccm_text);
        mReply.setMaxLines(4);
        mReply.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mReply.setPadding(0, 4 * mDp, 0, 8 * mDp);
        mPanel.addView(mReply);

        LinearLayout buttons = new LinearLayout(mCtx);
        mStopBtn = button(t("停止"), true);
        mStopBtn.setOnClickListener(v -> CcmTaskState.stop());
        TextView term = button(t("终端"), false);
        term.setOnClickListener(v -> {
            collapse();
            CcmTerminal.open(mCtx, CcmTaskState.projectName(), CcmTaskState.projectPath(),
                CcmTaskState.sessionId(), CcmTaskState.isRunning());
        });
        TextView open = button(t("打开 App"), false);
        open.setOnClickListener(v -> {
            collapse();
            mCtx.startActivity(new Intent(mCtx, CcmHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        TextView dismiss = button(t("关闭"), false);
        dismiss.setOnClickListener(v -> detach());
        for (TextView b : new TextView[]{mStopBtn, term, open, dismiss}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 36 * mDp, 1);
            if (buttons.getChildCount() > 0) lp.leftMargin = 6 * mDp;
            buttons.addView(b, lp);
        }
        mPanel.addView(buttons);
    }

    private void expand() {
        if (mExpanded) return;
        mExpanded = true;
        int screenW = mCtx.getResources().getDisplayMetrics().widthPixels;
        mRoot.removeAllViews();
        int w = Math.min(screenW - 24 * mDp, 340 * mDp);
        mRoot.addView(mPanel, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        mSavedX = mParams.x;
        mParams.x = (screenW - w) / 2;
        updateLayout();
        onTaskChanged();
    }

    private int mSavedX;

    private void collapse() {
        if (!mExpanded) return;
        mExpanded = false;
        mRoot.removeAllViews();
        mRoot.addView(mCollapsed);
        mParams.x = mSavedX;
        updateLayout();
    }

    // ======================= 刷新 =======================

    @Override
    public void onTaskChanged() {
        if (!mAttached) return;
        boolean running = CcmTaskState.isRunning();
        mRing.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        if (running) {
            mBadge.setVisibility(View.GONE);
        } else {
            mBadge.setVisibility(CcmTaskState.hasTask() ? View.VISIBLE : View.GONE);
            mBadge.setImageResource(CcmTaskState.isError() ? R.drawable.ccm_ic_close : R.drawable.ccm_ic_check);
            mBadge.setImageTintList(ContextCompat.getColorStateList(mCtx, R.color.ccm_on_accent));
            GradientDrawable b = new GradientDrawable();
            b.setShape(GradientDrawable.OVAL);
            b.setColor(ContextCompat.getColor(mCtx, CcmTaskState.isError() ? R.color.ccm_error : R.color.ccm_ok));
            mBadge.setBackground(b);
        }
        String h = CcmTaskState.headline();
        mLabel.setText(h);
        mLabel.setVisibility(h.isEmpty() ? View.GONE : View.VISIBLE);
        if (mExpanded) updatePanel();
    }

    private void updatePanel() {
        mPanelTitle.setText(CcmTaskState.projectName() + " · " + CcmChatRenderer.ellipsize(CcmTaskState.prompt().replace('\n', ' '), 30));
        updatePanelStatus();
        mHeadline.setText(CcmTaskState.headline());
        mStopBtn.setEnabled(CcmTaskState.isRunning());
        mStopBtn.setAlpha(CcmTaskState.isRunning() ? 1f : 0.4f);

        mStepsBox.removeAllViews();
        List<CcmTaskState.Step> steps = CcmTaskState.steps();
        if (steps.isEmpty()) {
            TextView none = text(12, R.color.ccm_text_tertiary);
            none.setText(t("还没有执行操作"));
            mStepsBox.addView(none);
        }
        for (CcmTaskState.Step s : steps) {
            TextView row = text(13, s.state == CcmTaskState.STEP_ERROR ? R.color.ccm_error : R.color.ccm_text);
            String mark = s.state == CcmTaskState.STEP_RUNNING ? "◌ " : s.state == CcmTaskState.STEP_OK ? "✓ " : "✗ ";
            row.setText(mark + s.label + (s.detail.isEmpty() ? "" : "  " + CcmChatRenderer.ellipsize(s.detail, 40)));
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(0, 3 * mDp, 0, 3 * mDp);
            if (s.state == CcmTaskState.STEP_RUNNING) row.setTextColor(ContextCompat.getColor(mCtx, R.color.ccm_accent));
            mStepsBox.addView(row);
        }
        ((ScrollView) mStepsBox.getParent()).post(() -> ((ScrollView) mStepsBox.getParent()).fullScroll(View.FOCUS_DOWN));
        String reply = CcmTaskState.reply();
        mReply.setVisibility(reply.isEmpty() ? View.GONE : View.VISIBLE);
        mReply.setText(reply);
        updateLayout();
    }

    private void updatePanelStatus() {
        long s = CcmTaskState.elapsedSeconds();
        String t = s >= 60 ? (s / 60) + t(" 分 ") + (s % 60) + t(" 秒") : s + t(" 秒");
        String state = CcmTaskState.isRunning() ? t("进行中") : CcmTaskState.isError() ? t("出错") : t("已完成");
        mPanelStatus.setText(state + " · " + t + " · " + CcmTaskState.steps().size() + t(" 步"));
    }

    private void updateLayout() {
        if (!mAttached) return;
        try { mWm.updateViewLayout(mRoot, mParams); } catch (Exception ignored) {}
    }

    // ======================= 小工具 =======================

    private TextView text(int sp, int colorRes) {
        TextView tv = new TextView(mCtx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(mCtx, colorRes));
        return tv;
    }

    private TextView button(String label, boolean primary) {
        TextView b = text(13, primary ? R.color.ccm_on_accent : R.color.ccm_text);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(primary ? round(R.color.ccm_accent, 10, false) : round(R.color.ccm_surface_alt, 10, false));
        return b;
    }

    private GradientDrawable round(int colorRes, int radiusDp, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ContextCompat.getColor(mCtx, colorRes));
        g.setCornerRadius(radiusDp * mDp);
        if (border) g.setStroke(mDp, ContextCompat.getColor(mCtx, R.color.ccm_border));
        return g;
    }
}
