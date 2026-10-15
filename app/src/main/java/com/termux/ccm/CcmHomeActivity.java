package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.NestedScrollView;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CCMobile 主界面：仿 Claude Code 桌面端。
 * 侧边栏（新对话 / 项目 / 最近对话）+ 项目标题 + 消息流 + 输入框（图片、权限模式、模型、手机连接状态）。
 * 每轮对话在后台运行 ccm-chat（claude -p，stream-json 输入输出）。
 */
public class CcmHomeActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGES = 11;
    private static final int REQ_STORAGE = 12;
    private static final int MAX_IMAGE_SIDE = 1568;

    private DrawerLayout mDrawer;
    private LinearLayout mMessages, mSuggestions, mAttachments, mDrawerSessions;
    private NestedScrollView mChatScroll;
    private ScrollView mEmptyState;
    private View mAttachmentsScroll;
    private EditText mInput;
    private ImageButton mSendButton;
    private TextView mProjectName, mProjectPath, mChipMode, mChipModel, mPhonePill, mEmptySubtitle;
    private int mDp;

    private CcmChatRenderer mRenderer;
    private CcmInteraction mInteraction;
    /** 等待用户处理的 control_request：request_id → 卡片 */
    private final Map<String, View> mPendingRequests = new HashMap<>();
    private boolean mVisible;
    private CcmProjects.Project mProject;
    private String mSessionId;
    private CcmProcess mChatProcess;
    private View mWorking;
    private long mTurnStart;
    private boolean mGotResult;
    private final StringBuilder mStderr = new StringBuilder();
    private volatile boolean mPhoneConnected;

    /** 待发送的图片：base64 JPEG + 缩略图 */
    private final List<String> mPendingImages = new ArrayList<>();
    private final List<Bitmap> mPendingThumbs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 未完成 Claude Code 安装或 API 配置：直接进引导，不显示主界面
        if (!isReady()) {
            startActivity(new Intent(this, CcmSetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.ccm_activity_home);
        mDp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());

        // 前台服务保活，长任务时避免进程被回收
        try { startService(new Intent(this, TermuxService.class)); } catch (Exception ignored) {}
        if (CcmSetupActivity.bootstrapInstalled()) {
            CcmConfig.installAssets(this);
            CcmProjects.PROJECTS_ROOT.mkdirs();
            maybeAutoUpdateNative();
            // 提前授权共享存储（等同 termux-setup-storage），老用户升级后也只问一次
            CcmStorage.requestOnce(this);
        }

        mDrawer = findViewById(R.id.drawer);
        mMessages = findViewById(R.id.messages);
        mSuggestions = findViewById(R.id.suggestions);
        mAttachments = findViewById(R.id.attachments);
        mAttachmentsScroll = findViewById(R.id.attachments_scroll);
        mDrawerSessions = findViewById(R.id.drawer_sessions);
        mChatScroll = findViewById(R.id.chat_scroll);
        mEmptyState = findViewById(R.id.empty_state);
        mInput = findViewById(R.id.input);
        mSendButton = findViewById(R.id.btn_send);
        mProjectName = findViewById(R.id.project_name);
        mProjectPath = findViewById(R.id.project_path);
        mChipMode = findViewById(R.id.chip_mode);
        mChipModel = findViewById(R.id.chip_model);
        mPhonePill = findViewById(R.id.phone_pill);
        mEmptySubtitle = findViewById(R.id.empty_subtitle);

        mRenderer = new CcmChatRenderer(this, mMessages);
        mRenderer.setOnContentChanged(this::onChatContentChanged);
        mInteraction = new CcmInteraction(this, mRenderer.markwon());

        findViewById(R.id.btn_menu).setOnClickListener(v -> mDrawer.openDrawer(GravityCompat.START));
        findViewById(R.id.btn_new_chat).setOnClickListener(v -> newChat());
        findViewById(R.id.btn_undo).setOnClickListener(v -> confirmRewind());
        findViewById(R.id.project_title).setOnClickListener(v -> showProjectSheet());
        findViewById(R.id.btn_attach).setOnClickListener(v -> pickImages());
        mSendButton.setOnClickListener(v -> onSendOrStop());
        mChipMode.setOnClickListener(v -> showModeSheet());
        mChipModel.setOnClickListener(v -> showModelSheet());
        mPhonePill.setOnClickListener(v -> startActivity(new Intent(this, CcmPairActivity.class)));

        findViewById(R.id.drawer_new_chat).setOnClickListener(v -> { mDrawer.closeDrawers(); newChat(); });
        findViewById(R.id.drawer_projects).setOnClickListener(v -> { mDrawer.closeDrawers(); showProjectSheet(); });
        findViewById(R.id.drawer_settings).setOnClickListener(v -> startActivity(new Intent(this, CcmConfigActivity.class)));
        findViewById(R.id.drawer_phone).setOnClickListener(v -> startActivity(new Intent(this, CcmSetupActivity.class)));
        // 终端：打开当前项目 / 当前会话对应的 Claude Code；长按进入普通终端
        findViewById(R.id.drawer_terminal).setOnClickListener(v -> {
            mDrawer.closeDrawers();
            CcmTerminal.open(this, mProject.name, mProject.path, mSessionId, isRunning());
        });
        findViewById(R.id.drawer_terminal).setOnLongClickListener(v -> {
            startActivity(new Intent(this, TermuxActivity.class));
            return true;
        });
        mDrawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawerView) { loadSessionList(); }
        });

        findViewById(R.id.setup_banner).setOnClickListener(v -> startActivity(new Intent(this, CcmSetupActivity.class)));

        switchProject(CcmProjects.current(this), true);
    }

    /** 当前实际使用的 Claude Code 版本（与 ccm-env 的选择逻辑一致）。 */
    private String runtimeVersion() {
        String nv = CcmConfig.nativeVersion();
        return CcmConfig.runsLatest(this) ? nv : CcmConfig.get(this, CcmConfig.KEY_CC_VERSION);
    }

    /** 最新版通道：每 12 小时在后台检查一次更新；新版本下载并验证通过后才切换，下一轮对话生效。 */
    private void maybeAutoUpdateNative() {
        if (!CcmConfig.isLatestChannel(this) || !CcmConfig.isEnvironmentInstalled() || !CcmConfig.nativeSupported()) return;
        long last = CcmConfig.prefs(this).getLong("native_check_at", 0);
        // 已安装：每 12 小时检查更新；还没装成功（安装时网络或镜像出错）：最多每 30 分钟重试一次
        long interval = CcmConfig.nativeVersion() == null ? 30 * 60 * 1000L : 12 * 3600 * 1000L;
        if (System.currentTimeMillis() - last < interval) return;
        CcmConfig.prefs(this).edit().putLong("native_check_at", System.currentTimeMillis()).apply();
        final String[] updated = {null};
        final boolean hadNative = CcmConfig.nativeVersion() != null;
        try {
            CcmProcess.start(getApplicationContext(), null, null, new CcmProcess.Listener() {
                @Override public void onLine(String line) {
                    if (line.startsWith("CCM_NATIVE_UPDATED=")) updated[0] = line.substring(19);
                }

                @Override public void onExit(int code) {
                    if (updated[0] == null && !hadNative && CcmConfig.nativeVersion() != null) updated[0] = CcmConfig.nativeVersion();
                    if (updated[0] == null) return;
                    runOnUiThread(() -> {
                        updateChips();
                        toast(t("Claude Code 已自动更新到 ") + updated[0] + t("，下一轮对话生效"));
                        updateEmptyState();
                    });
                }
            }, new File(CcmConfig.CCM_DIR, "bin/ccm-native").getAbsolutePath(), "update");
        } catch (Exception ignored) {}
    }

    private boolean isReady() {
        return CcmConfig.isEnvironmentInstalled() && CcmConfig.isConfigured(this);
    }

    /** 主界面中途变为未就绪（如重装环境、清空 Key）时常驻提示返回引导。 */
    private void updateSetupBanner() {
        TextView banner = findViewById(R.id.setup_banner);
        if (isReady()) {
            banner.setVisibility(View.GONE);
            return;
        }
        String missing = !CcmConfig.isEnvironmentInstalled()
            ? (CcmSetupActivity.isInstalling() ? t("Claude Code 正在安装中") : t("Claude Code 还没安装"))
            : t("还没配置模型 API Key");
        banner.setText(missing + t("，暂时无法对话。点此返回引导完成设置"));
        banner.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mVisible = true;
        CcmTaskState.setFollowUp(mFollowUp);
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFY_ATTENTION);
        CcmOverlay.hide();
    }

    /** 进入后台时若任务仍在运行，显示悬浮窗 */
    @Override
    protected void onStop() {
        super.onStop();
        mVisible = false;
        if (CcmTaskState.isRunning() && CcmConfig.getBool(this, CcmConfig.KEY_OVERLAY)) CcmOverlay.show(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetupBanner();
        updateChips();
        restoreKeyboardIfStuck();
        checkPhone();
    }

    /** 悬浮窗里委派的下一步任务：空闲时直接在当前会话发送，任务进行中则排队 */
    private final CcmTaskState.FollowUp mFollowUp = text -> runOnUiThread(() -> {
        if (!isReady() || mProject == null) {
            toast(t("请先完成引导：安装 Claude Code 并配置 API Key"));
            return;
        }
        if (isRunning()) {
            CcmTaskState.enqueue(text);
        } else {
            send(text, false);
        }
    });

    @Override
    protected void onDestroy() {
        CcmTaskState.clearFollowUp(mFollowUp);
        super.onDestroy();
        if (mChatProcess != null && mChatProcess.isRunning()) mChatProcess.stop();
    }

    @Override
    public void onBackPressed() {
        if (mDrawer == null) { super.onBackPressed(); return; }
        if (mDrawer.isDrawerOpen(GravityCompat.START)) mDrawer.closeDrawers();
        else super.onBackPressed();
    }

    // ======================= 项目与会话 =======================

    private String sessionPrefKey() {
        return "session:" + mProject.path;
    }

    private void switchProject(CcmProjects.Project p, boolean restoreSession) {
        if (isRunning()) { toast(t("请先停止当前任务")); return; }
        mProject = p;
        CcmProjects.setCurrent(this, p);
        new File(p.path).mkdirs();
        mProjectName.setText(p.name);
        mProjectPath.setText(CcmProjects.displayPath(p.path));
        mRenderer.setProjectPath(p.path);
        mRenderer.clear();
        mSessionId = restoreSession ? CcmConfig.prefs(this).getString(sessionPrefKey(), null) : null;
        if (mSessionId != null) openSession(mSessionId);
        else updateEmptyState();
    }

    private void newChat() {
        if (isRunning()) { toast(t("请先停止当前任务")); return; }
        mSessionId = null;
        CcmConfig.prefs(this).edit().remove(sessionPrefKey()).apply();
        mRenderer.clear();
        updateEmptyState();
        mInput.requestFocus();
    }

    /** 读取 jsonl 并重放到消息区。 */
    private void openSession(String sessionId) {
        mRenderer.clear();
        File file = null;
        for (CcmProjects.Session s : CcmProjects.sessions(mProject.path, 200)) if (s.id.equals(sessionId)) file = s.file;
        mSessionId = sessionId;
        CcmConfig.prefs(this).edit().putString(sessionPrefKey(), sessionId).apply();
        if (file == null) { updateEmptyState(); return; }
        final File f = file;
        new Thread(() -> {
            List<JSONObject> recs = CcmProjects.loadSession(f);
            runOnUiThread(() -> {
                for (JSONObject r : recs) mRenderer.onHistory(r);
                updateEmptyState();
                scrollToBottom();
            });
        }).start();
    }

    private void loadSessionList() {
        final String path = mProject.path;
        new Thread(() -> {
            List<CcmProjects.Session> list = CcmProjects.sessions(path, 50);
            runOnUiThread(() -> renderSessionList(list));
        }).start();
    }

    private void renderSessionList(List<CcmProjects.Session> list) {
        mDrawerSessions.removeAllViews();
        if (list.isEmpty()) {
            TextView tv = text(t("「") + mProject.name + t("」还没有对话"), 13, R.color.ccm_text_tertiary);
            tv.setPadding(10 * mDp, 8 * mDp, 10 * mDp, 8 * mDp);
            mDrawerSessions.addView(tv);
            return;
        }
        for (CcmProjects.Session s : list) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(10 * mDp, 8 * mDp, 10 * mDp, 8 * mDp);
            if (s.id.equals(mSessionId)) item.setBackgroundResource(R.drawable.ccm_bg_drawer_item_selected);
            else item.setBackgroundResource(selectableBg());
            TextView title = text(s.title, 14, R.color.ccm_text);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(title);
            item.addView(text(DateUtils.getRelativeTimeSpanString(s.modified).toString(), 11, R.color.ccm_text_tertiary));
            item.setOnClickListener(v -> {
                mDrawer.closeDrawers();
                if (isRunning()) { toast(t("请先停止当前任务")); return; }
                openSession(s.id);
            });
            item.setOnLongClickListener(v -> { showSessionActions(s); return true; });
            mDrawerSessions.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * 自愈：没有任务在跑、默认输入法却仍是 CCMobile 输入法时（上次没能切回），
     * 用 MCP 记录的原输入法通过 adb 恢复；恢复不了就弹出系统输入法选择框。
     */
    private void restoreKeyboardIfStuck() {
        if (CcmTaskState.isRunning()) return;
        String cur;
        try {
            cur = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (Exception e) {
            return;
        }
        if (cur == null || !cur.startsWith(getPackageName() + "/")) return;
        File prevFile = new File(CcmConfig.CCM_DIR, "prev_ime");
        new Thread(() -> {
            boolean ok = false;
            String detail = "";
            try {
                if (prevFile.exists() && CcmAdb.adbInstalled()) {
                    String prev = CcmConfig.readString(prevFile).trim();
                    detail = CcmAdb.runAdb(8000, "shell", "ime", "set", prev).trim();
                    String now = android.provider.Settings.Secure.getString(getContentResolver(),
                        android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
                    ok = now != null && !now.startsWith(getPackageName() + "/");
                    if (ok) prevFile.delete();
                }
            } catch (Exception e) {
                detail = e.getMessage();
            }
            final boolean restored = ok;
            final String info = detail;
            runOnUiThread(() -> {
                if (restored) {
                    toast(t("已把键盘恢复为原来的输入法"));
                } else {
                    toast(t("键盘停留在 CCMobile 输入法，请选择你的键盘") + (info.isEmpty() ? "" : t("（") + info + t("）")));
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showInputMethodPicker();
                }
            });
        }).start();
    }

    // ---------------- 会话操作：删除 / 撤销 ----------------

    private void showSessionActions(CcmProjects.Session s) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout col = sheetColumn(CcmChatRenderer.ellipsize(s.title, 24));
        View term = sheetItem(col, R.drawable.ccm_ic_terminal, t("在终端中打开"), t("用交互式 Claude Code 继续这个对话"), false);
        term.setOnClickListener(v -> {
            sheet.dismiss();
            mDrawer.closeDrawers();
            CcmTerminal.open(this, mProject.name, mProject.path, s.id, isRunning() && s.id.equals(mSessionId));
        });
        View del = sheetItem(col, R.drawable.ccm_ic_delete, t("删除对话"), t("删除后可在底部提示条中撤销"), false);
        ((ImageView) del.findViewWithTag("icon")).setImageTintList(ContextCompat.getColorStateList(this, R.color.ccm_error));
        del.setOnClickListener(v -> { sheet.dismiss(); deleteSession(s); });
        sheet.setContentView(wrapSheet(col));
        sheet.show();
    }

    private void deleteSession(CcmProjects.Session s) {
        if (isRunning() && s.id.equals(mSessionId)) { toast(t("请先停止当前任务")); return; }
        File trashed = CcmProjects.deleteSession(s.file);
        if (trashed == null) { toast(t("删除失败")); return; }
        boolean wasCurrent = s.id.equals(mSessionId);
        if (wasCurrent) newChat();
        loadSessionList();
        Snackbar.make(findViewById(R.id.composer), t("已删除「") + CcmChatRenderer.ellipsize(s.title, 16) + t("」"), Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.composer)
            .setAction(t("撤销"), v -> {
                if (!CcmProjects.restore(trashed, s.file)) { toast(t("恢复失败")); return; }
                loadSessionList();
                if (wasCurrent) openSession(s.id);
            }).show();
    }

    private void confirmRewind() {
        if (isRunning()) { toast(t("请先停止当前任务")); return; }
        File file = mSessionId == null ? null : CcmProjects.sessionFile(mProject.path, mSessionId);
        if (file == null) { toast(t("当前没有可撤销的对话")); return; }
        new MaterialAlertDialogBuilder(this).setTitle(t("撤销上一轮对话？"))
            .setMessage(t("对话会回到你上一条消息之前，那条消息会放回输入框。\n注意：AI 已经修改的文件、已在手机上执行的操作不会被撤回。"))
            .setPositiveButton(t("撤销"), (d, w) -> rewind(file))
            .setNegativeButton(t("取消"), null).show();
    }

    private void rewind(File file) {
        CcmProjects.Rewind r;
        try {
            r = CcmProjects.rewindLastTurn(file);
        } catch (Exception e) {
            toast(t("撤销失败：") + e.getMessage());
            return;
        }
        if (r == null) { toast(t("当前没有可撤销的对话")); return; }
        final String sid = mSessionId;
        final String previousInput = mInput.getText().toString();
        // 整个会话都被撤回时当作新对话
        if (CcmProjects.loadSession(file).isEmpty()) newChat(); else openSession(sid);
        mInput.setText(r.userText);
        mInput.setSelection(mInput.getText().length());
        Snackbar.make(findViewById(R.id.composer), t("已撤销上一轮"), Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.composer)
            .setAction(t("恢复"), v -> {
                if (!CcmProjects.restore(r.backup, file)) { toast(t("恢复失败")); return; }
                mInput.setText(previousInput);
                openSession(sid);
            }).show();
    }

    private void maybeAskOverlayPermission() {
        if (CcmOverlay.canShow(this) || !CcmConfig.getBool(this, CcmConfig.KEY_OVERLAY)) return;
        if (CcmConfig.prefs(this).getBoolean("overlay_asked", false)) return;
        CcmConfig.prefs(this).edit().putBoolean("overlay_asked", true).apply();
        new MaterialAlertDialogBuilder(this).setTitle(t("开启悬浮窗？"))
            .setMessage(t("AI 在后台操作手机时，屏幕边缘会显示悬浮按钮，实时显示进度；点开可查看步骤、停止任务或打开终端。\n小米手机请在权限页打开「显示悬浮窗」。"))
            .setPositiveButton(t("去开启"), (d, w) -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    toast(t("请在系统设置中允许 CCMobile 显示悬浮窗"));
                }
            }).setNegativeButton(t("暂不"), null).show();
    }

    private void showProjectSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout col = sheetColumn(t("选择项目"));
        for (CcmProjects.Project p : CcmProjects.list(this)) {
            View item = sheetItem(col, p.isPhoneWorkspace() ? R.drawable.ccm_ic_phone : R.drawable.ccm_ic_folder,
                p.name, CcmProjects.displayPath(p.path), p.path.equals(mProject.path));
            item.setOnClickListener(v -> { sheet.dismiss(); switchProject(p, true); });
            if (!p.isPhoneWorkspace()) item.setOnLongClickListener(v -> {
                sheet.dismiss();
                showProjectActions(p);
                return true;
            });
        }
        View create = sheetItem(col, R.drawable.ccm_ic_add, t("新建项目"), t("在 ~/projects 下创建文件夹"), false);
        create.setOnClickListener(v -> { sheet.dismiss(); createProjectDialog(); });
        View open = sheetItem(col, R.drawable.ccm_ic_folder, t("打开文件夹…"), t("选择手机上任意目录作为项目"), false);
        open.setOnClickListener(v -> { sheet.dismiss(); openFolderPicker(); });
        sheet.setContentView(wrapSheet(col));
        sheet.show();
    }

    // ---------------- 项目操作：移除 / 彻底删除 ----------------

    private void showProjectActions(CcmProjects.Project p) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout col = sheetColumn(p.name);
        View remove = sheetItem(col, R.drawable.ccm_ic_close, t("从列表移除"), t("文件夹保留在手机上，可随时重新打开"), false);
        remove.setOnClickListener(v -> { sheet.dismiss(); removeProject(p); });
        View del = sheetItem(col, R.drawable.ccm_ic_delete, t("彻底删除文件夹"),
            CcmProjects.displayPath(p.path) + t("  ·  不可恢复"), false);
        ((ImageView) del.findViewWithTag("icon")).setImageTintList(ContextCompat.getColorStateList(this, R.color.ccm_error));
        del.setOnClickListener(v -> { sheet.dismiss(); confirmDeleteProject(p); });
        sheet.setContentView(wrapSheet(col));
        sheet.show();
    }

    private void removeProject(CcmProjects.Project p) {
        if (isRunning() && p.path.equals(mProject.path)) { toast(t("请先停止当前任务")); return; }
        boolean wasCurrent = p.path.equals(mProject.path);
        CcmProjects.remove(this, p);
        if (wasCurrent) switchProject(CcmProjects.current(this), true);
        Snackbar.make(findViewById(R.id.composer), t("已从列表移除「") + p.name + t("」"), Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.composer)
            .setAction(t("撤销"), v -> {
                CcmProjects.add(this, p.name, p.path);
                if (wasCurrent) switchProject(CcmProjects.current(this), true);
            }).show();
    }

    /** 彻底删除：先做安全检查和统计，再要求输入文件夹名确认。 */
    private void confirmDeleteProject(CcmProjects.Project p) {
        if (isRunning() && p.path.equals(mProject.path)) { toast(t("请先停止当前任务")); return; }
        String refusal = CcmProjects.deleteRefusal(p);
        if (refusal != null) {
            new MaterialAlertDialogBuilder(this).setTitle(t("不能删除")).setMessage(refusal)
                .setPositiveButton(t("知道了"), null).show();
            return;
        }
        File dir = new File(p.path);
        new Thread(() -> {
            long[] m = CcmProjects.measure(dir);
            runOnUiThread(() -> showDeleteProjectDialog(p, dir, m));
        }).start();
    }

    private void showDeleteProjectDialog(CcmProjects.Project p, File dir, long[] m) {
        String folder = dir.getName();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24 * mDp, 8 * mDp, 24 * mDp, 0);
        EditText confirm = new EditText(this);
        confirm.setSingleLine(true);
        confirm.setHint(folder);
        box.addView(confirm);
        android.widget.CheckBox sessions = new android.widget.CheckBox(this);
        sessions.setText(t("同时删除这个项目的对话记录"));
        sessions.setChecked(true);
        box.addView(sessions);
        String size = m[1] >= 1 << 20 ? String.format(java.util.Locale.ROOT, "%.1f MB", m[1] / 1048576.0)
            : String.format(java.util.Locale.ROOT, "%.1f KB", m[1] / 1024.0);
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
            .setTitle(t("彻底删除「") + p.name + t("」？"))
            .setMessage(CcmProjects.displayPath(p.path) + "\n"
                + String.format(t("共 %1$d 个文件，%2$s。"), m[0], size)
                + t("\n文件夹会被永久删除，无法恢复。\n\n请输入文件夹名「") + folder + t("」确认："))
            .setView(box)
            .setNegativeButton(t("取消"), null)
            .setPositiveButton(t("彻底删除"), null)
            .create();
        d.setOnShowListener(x -> {
            android.widget.Button ok = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            ok.setEnabled(false);
            ok.setTextColor(ContextCompat.getColor(this, R.color.ccm_error));
            confirm.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    ok.setEnabled(s.toString().trim().equals(folder));
                }
            });
            ok.setOnClickListener(v -> {
                d.dismiss();
                deleteProject(p, sessions.isChecked());
            });
        });
        d.show();
    }

    private void deleteProject(CcmProjects.Project p, boolean withSessions) {
        boolean wasCurrent = p.path.equals(mProject.path);
        if (wasCurrent) newChat();
        toast(t("正在删除…"));
        new Thread(() -> {
            int failed = CcmProjects.deleteProject(this, p, withSessions);
            runOnUiThread(() -> {
                if (wasCurrent) switchProject(CcmProjects.current(this), true);
                else loadSessionList();
                toast(failed == 0 ? t("已彻底删除「") + p.name + t("」")
                    : String.format(t("删除完成，但有 %d 个文件删不掉（可能没有权限）"), failed));
            });
        }).start();
    }

    private void createProjectDialog() {
        EditText et = new EditText(this);
        et.setHint(t("项目名称，例如 my-app"));
        et.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(22 * mDp, 8 * mDp, 22 * mDp, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(this).setTitle(t("新建项目")).setView(box)
            .setPositiveButton(t("创建"), (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) return;
                switchProject(CcmProjects.create(this, name), false);
            }).setNegativeButton(t("取消"), null).show();
    }

    // ---------------- 文件夹选择 ----------------

    private void openFolderPicker() {
        File start = CcmProjects.PROJECTS_ROOT.exists() ? CcmProjects.PROJECTS_ROOT : new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        showFolder(start);
    }

    private void showFolder(File dir) {
        File[] children = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        List<File> dirs = children == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(children));
        java.util.Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout col = sheetColumn(t("选择文件夹"));
        TextView path = text(CcmProjects.displayPath(dir.getAbsolutePath()), 12, R.color.ccm_text_secondary);
        path.setPadding(20 * mDp, 0, 20 * mDp, 8 * mDp);
        col.addView(path);

        // 快捷位置
        LinearLayout quick = new LinearLayout(this);
        quick.setPadding(16 * mDp, 0, 16 * mDp, 8 * mDp);
        addQuickChip(quick, t("主目录"), new File(TermuxConstants.TERMUX_HOME_DIR_PATH), sheet);
        addQuickChip(quick, "projects", CcmProjects.PROJECTS_ROOT, sheet);
        addQuickChip(quick, t("内部存储"), Environment.getExternalStorageDirectory(), sheet);
        col.addView(quick);

        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            View up = sheetItem(col, R.drawable.ccm_ic_chevron_right, "..", t("上一级"), false);
            ((ImageView) up.findViewWithTag("icon")).setRotation(180);
            up.setOnClickListener(v -> { sheet.dismiss(); showFolder(parent); });
        }
        if (dirs.isEmpty()) {
            TextView none = text(dir.canRead() ? t("（没有子文件夹）") : t("（没有读取权限）"), 13, R.color.ccm_text_tertiary);
            none.setPadding(20 * mDp, 10 * mDp, 20 * mDp, 10 * mDp);
            col.addView(none);
        }
        for (File d : dirs) {
            View item = sheetItem(col, R.drawable.ccm_ic_folder, d.getName(), null, false);
            item.setOnClickListener(v -> { sheet.dismiss(); showFolder(d); });
        }
        TextView choose = text(t("使用此文件夹作为项目"), 15, R.color.ccm_on_accent);
        choose.setGravity(Gravity.CENTER);
        choose.setTypeface(Typeface.DEFAULT_BOLD);
        choose.setBackground(roundAccent());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48 * mDp);
        lp.setMargins(16 * mDp, 12 * mDp, 16 * mDp, 8 * mDp);
        col.addView(choose, lp);
        choose.setOnClickListener(v -> {
            sheet.dismiss();
            switchProject(CcmProjects.add(this, dir.getName(), dir.getAbsolutePath()), true);
        });
        sheet.setContentView(wrapSheet(col));
        sheet.show();
    }

    private void addQuickChip(LinearLayout row, String label, File target, BottomSheetDialog sheet) {
        TextView chip = text(label, 12, R.color.ccm_text_secondary);
        chip.setBackgroundResource(R.drawable.ccm_bg_chip);
        chip.setPadding(12 * mDp, 6 * mDp, 12 * mDp, 6 * mDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 8 * mDp;
        row.addView(chip, lp);
        chip.setOnClickListener(v -> {
            if (target.getAbsolutePath().startsWith("/storage") && !hasStoragePermission()) {
                sheet.dismiss();
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
            target.mkdirs();
            sheet.dismiss();
            showFolder(target);
        });
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        CcmStorage.onPermissionResult(this, requestCode);
        if (requestCode == REQ_STORAGE) {
            if (hasStoragePermission()) CcmStorage.ensureLinks(this);
            if (hasStoragePermission()) showFolder(Environment.getExternalStorageDirectory());
            else toast(t("未获得存储权限，无法浏览内部存储"));
        }
    }

    // ======================= 输入框 =======================

    private void updateChips() {
        String mode = CcmConfig.prefs(this).getString("chat_mode", CcmConfig.get(this, CcmConfig.KEY_PERMISSION_MODE));
        String effective = CcmConfig.effectivePermissionMode(this, mode);
        // 最新版不可用、回退 2.1.112 时自动模式降为完全放行，标签显示实际模式（版本号见空白页的版本信息）
        mChipMode.setText(modeLabel(effective) + " ▾");
        String model = currentModel();
        mChipModel.setText(model.replace("[1m]", "") + " ▾");
        updateEmptyState();
    }

    private String currentModel() {
        String m = CcmConfig.prefs(this).getString("chat_model", null);
        List<String> choices = CcmConfig.modelChoices(this);
        return m != null && choices.contains(m) ? m : choices.get(0);
    }

    private static String modeLabel(String mode) {
        for (int i = 0; i < CcmConfig.PERMISSION_MODES.length; i++) if (CcmConfig.PERMISSION_MODES[i].equals(mode)) return CcmConfig.PERMISSION_MODE_LABELS[i];
        return mode;
    }

    private void showModeSheet() {
        String[] desc = {
            t("AI 自行判断哪些操作安全，危险操作才拦截（推荐）"),
            t("自动修改文件，命令需在规则内"),
            t("不做任何确认，直接执行所有操作"),
            t("只读分析并给出方案，不改动任何文件"),
            t("非白名单操作会被拒绝"),
        };
        String cur = CcmConfig.prefs(this).getString("chat_mode", CcmConfig.get(this, CcmConfig.KEY_PERMISSION_MODE));
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout col = sheetColumn(t("权限模式"));
        for (int i = 0; i < CcmConfig.PERMISSION_MODES.length; i++) {
            final String m = CcmConfig.PERMISSION_MODES[i];
            View item = sheetItem(col, R.drawable.ccm_ic_check, CcmConfig.PERMISSION_MODE_LABELS[i], desc[i], m.equals(cur));
            item.setOnClickListener(v -> {
                CcmConfig.prefs(this).edit().putString("chat_mode", m).apply();
                sheet.dismiss();
                updateChips();
            });
        }
        sheet.setContentView(wrapSheet(col));
        sheet.show();
    }

    private void showModelSheet() {
        String cur = currentModel();
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout col = sheetColumn(t("模型"));
        for (String m : CcmConfig.modelChoices(this)) {
            View item = sheetItem(col, R.drawable.ccm_ic_logo, m.replace("[1m]", ""), m.endsWith("[1m]") ? t("1M 上下文") : null, m.equals(cur));
            item.setOnClickListener(v -> {
                CcmConfig.prefs(this).edit().putString("chat_model", m).apply();
                sheet.dismiss();
                updateChips();
            });
        }
        View manage = sheetItem(col, R.drawable.ccm_ic_settings, t("管理模型与 API…"), CcmConfig.get(this, CcmConfig.KEY_BASE_URL), false);
        manage.setOnClickListener(v -> { sheet.dismiss(); startActivity(new Intent(this, CcmConfigActivity.class)); });
        sheet.setContentView(wrapSheet(col));
        sheet.show();
    }

    /** 手机控制状态：无障碍服务在运行即可用；高级模式（无线调试）开启时才检查并静默重连 adb。 */
    private void checkPhone() {
        boolean a11y = CcmA11yService.isRunning();
        setPhonePill(a11y, t("手机"));
        if (a11y || !CcmConfig.isAdbEnabled(this)) return;
        if (!CcmConfig.isEnvironmentInstalled() || !CcmAdb.adbInstalled()) return;
        new Thread(() -> {
            boolean ok = CcmAdb.isConnected();
            if (!ok && new File(CcmConfig.CCM_DIR, "adb_port").exists()) {
                // 配对过：静默重连一次
                runOnUiThread(() -> CcmAdb.reconnect(this, (r, msg) -> setPhonePill(r, t("手机"))));
                return;
            }
            runOnUiThread(() -> setPhonePill(ok, t("手机")));
        }).start();
    }

    private void setPhonePill(boolean ok, String label) {
        mPhoneConnected = ok;
        android.graphics.drawable.Drawable dot = ContextCompat.getDrawable(this, R.drawable.ccm_dot).mutate();
        dot.setTint(ContextCompat.getColor(this, ok ? R.color.ccm_ok : R.color.ccm_text_tertiary));
        dot.setBounds(0, 0, 7 * mDp, 7 * mDp);
        mPhonePill.setCompoundDrawablesRelative(dot, null, null, null);
        mPhonePill.setText(label);
        mPhonePill.setTextColor(ContextCompat.getColor(this, ok ? R.color.ccm_ok : R.color.ccm_text_tertiary));
    }

    // ---------------- 图片附件 ----------------

    private void pickImages() {
        // 不经过 createChooser：部分系统上经 Chooser 转发会丢失返回结果
        Intent i;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                .putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 5);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, REQ_PICK_IMAGES);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK_IMAGES);
            } catch (Exception e2) {
                toast(t("没有可用的图片选择器"));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        CcmStorage.onPermissionResult(this, requestCode);
        if (requestCode != REQ_PICK_IMAGES || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        new Thread(() -> {
            for (Uri u : uris) {
                try {
                    Object[] r = loadImage(u);
                    runOnUiThread(() -> addAttachment((String) r[0], (Bitmap) r[1]));
                } catch (Exception e) {
                    runOnUiThread(() -> toast(t("读取图片失败：") + e.getMessage()));
                }
            }
        }).start();
    }

    /** 缩放到长边 ≤1568（模型推荐尺寸，省 token）并压成 JPEG。返回 {base64, 缩略图}。 */
    private Object[] loadImage(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o); }
        if (bm == null) throw new Exception(t("无法解码"));
        float scale = Math.min(1f, (float) MAX_IMAGE_SIDE / Math.max(bm.getWidth(), bm.getHeight()));
        if (scale < 1f) bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * scale), Math.round(bm.getHeight() * scale), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
        float ts = 256f / Math.max(bm.getWidth(), bm.getHeight());
        Bitmap thumb = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * ts)), Math.max(1, Math.round(bm.getHeight() * ts)), true);
        return new Object[]{Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb};
    }

    private void addAttachment(String b64, Bitmap thumb) {
        mPendingImages.add(b64);
        mPendingThumbs.add(thumb);
        renderAttachments();
    }

    private void renderAttachments() {
        mAttachments.removeAllViews();
        mAttachmentsScroll.setVisibility(mPendingThumbs.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < mPendingThumbs.size(); i++) {
            final int idx = i;
            FrameLayout f = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(mPendingThumbs.get(i));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.ccm_bg_thumb);
            iv.setClipToOutline(true);
            f.addView(iv, new FrameLayout.LayoutParams(60 * mDp, 60 * mDp));
            ImageView x = new ImageView(this);
            x.setImageResource(R.drawable.ccm_ic_close);
            x.setImageTintList(ContextCompat.getColorStateList(this, R.color.ccm_on_accent));
            x.setBackgroundResource(R.drawable.ccm_bg_send);
            x.getBackground().mutate().setTint(0xAA000000);
            x.setPadding(3 * mDp, 3 * mDp, 3 * mDp, 3 * mDp);
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(20 * mDp, 20 * mDp, Gravity.TOP | Gravity.END);
            xl.setMargins(0, 3 * mDp, 3 * mDp, 0);
            f.addView(x, xl);
            x.setOnClickListener(v -> {
                mPendingImages.remove(idx);
                mPendingThumbs.remove(idx);
                renderAttachments();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 8 * mDp;
            mAttachments.addView(f, lp);
        }
    }

    // ======================= 对话 =======================

    private boolean isRunning() {
        return mChatProcess != null && mChatProcess.isRunning();
    }

    private void onSendOrStop() {
        if (isRunning()) {
            mChatProcess.stop();
            return;
        }
        String text = mInput.getText().toString().trim();
        if (text.isEmpty() && mPendingImages.isEmpty()) return;
        if (!isReady()) {
            updateSetupBanner();
            toast(t("请先完成引导：安装 Claude Code 并配置 API Key"));
            startActivity(new Intent(this, CcmSetupActivity.class));
            return;
        }
        send(text, true);
    }

    /** @param fromComposer 来自输入框（带上待发送图片并清空输入框）；悬浮窗委派的任务不动输入框 */
    private void send(String text, boolean fromComposer) {
        JSONObject userMsg;
        try {
            JSONArray content = new JSONArray();
            if (fromComposer) for (String b64 : mPendingImages) {
                content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)));
            }
            if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
            userMsg = new JSONObject().put("type", "user")
                .put("message", new JSONObject().put("role", "user").put("content", content));
        } catch (Exception e) {
            toast(t("消息构建失败：") + e.getMessage());
            return;
        }

        if (fromComposer) {
            mRenderer.addUserMessage(text, new ArrayList<>(mPendingThumbs));
            mPendingImages.clear();
            mPendingThumbs.clear();
            renderAttachments();
            mInput.setText("");
        } else {
            mRenderer.addUserMessage(text, new ArrayList<>());
        }
        updateEmptyState();

        Map<String, String> env = new HashMap<>();
        env.put("CCM_CWD", mProject.path);
        env.put("CCM_MODEL", currentModel());
        env.put("CCM_PERMISSION_MODE", CcmConfig.prefs(this).getString("chat_mode", CcmConfig.get(this, CcmConfig.KEY_PERMISSION_MODE)));
        String script = new File(CcmConfig.CCM_DIR, "bin/ccm-chat").getAbsolutePath();
        String[] cmd = mSessionId == null ? new String[]{script} : new String[]{script, "--resume", mSessionId};

        CcmTaskState.begin(mProject.name, mProject.path, mSessionId, text.isEmpty() ? t("[图片]") : text,
            () -> { if (mChatProcess != null) mChatProcess.stop(); });
        maybeAskOverlayPermission();
        mGotResult = false;
        mStderr.setLength(0);
        mTurnStart = System.currentTimeMillis();
        mWorking = mRenderer.addWorkingIndicator();
        try {
            mPendingRequests.clear();
            mChatProcess = CcmProcess.start(getApplicationContext(), env, userMsg.toString() + "\n", true, new CcmProcess.Listener() {
                @Override public void onLine(String line) { runOnUiThread(() -> onChatLine(line)); }
                @Override public void onExit(int code) { runOnUiThread(() -> onChatExit(code)); }
            }, cmd);
            setSendState(true);
        } catch (Exception e) {
            removeWorking();
            mRenderer.addError(t("启动 Claude Code 失败：") + e.getMessage());
        }
    }

    private void onChatLine(String line) {
        if (!line.startsWith("{")) {
            if (mStderr.length() < 8000) mStderr.append(line).append('\n');
            return;
        }
        JSONObject ev;
        try { ev = new JSONObject(line); } catch (Exception e) { return; }
        String sid = ev.optString("session_id", "");
        if (!sid.isEmpty() && !sid.equals(mSessionId)) {
            mSessionId = sid;
            CcmTaskState.setSessionId(sid);
            CcmConfig.prefs(this).edit().putString(sessionPrefKey(), sid).apply();
        }
        String type = ev.optString("type");
        if ("control_request".equals(type)) { removeWorking(); onControlRequest(ev); return; }
        if ("control_cancel_request".equals(type)) {
            View card = mPendingRequests.remove(ev.optString("request_id"));
            if (card != null) mInteraction.cancel(card);
            return;
        }
        if ("control_response".equals(type)) return;
        if (!"system".equals(type)) removeWorking();
        if ("result".equals(type)) {
            mGotResult = true;
            // 本轮结束：关闭 stdin，进程随之退出
            if (mChatProcess != null) mChatProcess.closeStdin();
        }
        CcmTaskState.onEvent(ev);
        mRenderer.onEvent(ev, mTurnStart);
    }

    // ---------------- Claude Code 控制协议 ----------------

    private static final int NOTIFY_ATTENTION = 0xCC02;

    /** 处理 control_request：can_use_tool 显示交互卡片，其他类型回复不支持，避免 CLI 一直等待。 */
    private void onControlRequest(JSONObject ev) {
        String requestId = ev.optString("request_id");
        JSONObject req = ev.optJSONObject("request");
        if (req == null || !"can_use_tool".equals(req.optString("subtype"))) {
            sendControlError(requestId, "unsupported control request: " + (req == null ? "null" : req.optString("subtype")));
            return;
        }
        String tool = req.optString("tool_name");
        JSONObject input = req.optJSONObject("input");
        if (input == null) input = new JSONObject();
        View card = mInteraction.build(tool, input, req.optJSONArray("permission_suggestions"), mProject.path,
            (response, newMode) -> onDecision(requestId, response, newMode));
        mPendingRequests.put(requestId, card);
        mRenderer.addInteraction(card);
        String what = "AskUserQuestion".equals(tool) ? t("Claude 在问你问题")
            : "ExitPlanMode".equals(tool) ? t("Claude 的计划等待你批准")
            : t("等待你确认：") + CcmChatRenderer.toolLabel(tool);
        CcmTaskState.waitingForUser(what);
        if (!mVisible) notifyAttention(what);
    }

    private void onDecision(String requestId, JSONObject response, String newMode) {
        mPendingRequests.remove(requestId);
        if (mChatProcess == null || !mChatProcess.isRunning()) return;
        try {
            JSONObject msg = new JSONObject().put("type", "control_response").put("response",
                new JSONObject().put("subtype", "success").put("request_id", requestId).put("response", response));
            mChatProcess.writeLine(msg.toString());
            if (newMode != null) {
                // 计划批准后退出规划模式：通知当前进程，并让后续对话沿用该模式
                JSONObject setMode = new JSONObject().put("type", "control_request")
                    .put("request_id", "ccm_mode_" + System.currentTimeMillis())
                    .put("request", new JSONObject().put("subtype", "set_permission_mode").put("mode", newMode));
                mChatProcess.writeLine(setMode.toString());
                CcmConfig.prefs(this).edit().putString("chat_mode", newMode).apply();
                updateChips();
            }
        } catch (Exception ignored) {}
        CcmTaskState.resumeWorking();
        mWorking = mRenderer.addWorkingIndicator();
    }

    private void sendControlError(String requestId, String error) {
        if (mChatProcess == null) return;
        try {
            mChatProcess.writeLine(new JSONObject().put("type", "control_response").put("response",
                new JSONObject().put("subtype", "error").put("request_id", requestId).put("error", error)).toString());
        } catch (Exception ignored) {}
    }

    /** App 在后台时提醒用户回来处理确认 / 选择。 */
    private void notifyAttention(String text) {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new android.app.NotificationChannel("ccmobile_attention", t("需要你确认"),
                android.app.NotificationManager.IMPORTANCE_HIGH));
        }
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
            | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 2,
            new Intent(this, CcmHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, "ccmobile_attention")
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(mProject.name)
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true);
        try { nm.notify(NOTIFY_ATTENTION, b.build()); } catch (Exception ignored) {}
    }

    private void onChatExit(int code) {
        for (View card : mPendingRequests.values()) mInteraction.cancel(card);
        mPendingRequests.clear();
        removeWorking();
        setSendState(false);
        boolean stopped = code == 143 || code == 137;
        if (CcmTaskState.isRunning()) {
            CcmTaskState.end(!stopped, stopped ? t("已停止") : t("异常退出（") + code + t("）"));
        }
        // 悬浮窗排队的下一步任务：本轮正常结束后接着发送
        if (!stopped && mGotResult) {
            String next = CcmTaskState.pollQueued();
            if (next != null) mInput.post(() -> send(next, false));
        }
        if (!mGotResult) {
            String err = mStderr.toString().trim();
            if (code == 143 || code == 137) mRenderer.addNote(t("已停止"));
            else mRenderer.addError(t("Claude Code 异常退出（") + code + t("）") + (err.isEmpty() ? "" : "\n" + tail(err, 1500)));
        }
    }

    private void removeWorking() {
        if (mWorking != null && mWorking.getParent() != null) ((ViewGroup) mWorking.getParent()).removeView(mWorking);
        mWorking = null;
    }

    private void setSendState(boolean running) {
        mSendButton.setImageResource(running ? R.drawable.ccm_ic_stop : R.drawable.ccm_ic_send);
        mSendButton.setContentDescription(running ? t("停止") : t("发送"));
    }

    private void onChatContentChanged() {
        updateEmptyState();
        scrollToBottom();
    }

    private void scrollToBottom() {
        mChatScroll.post(() -> mChatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateEmptyState() {
        boolean empty = mMessages.getChildCount() == 0;
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        mChatScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty || mProject == null) return;
        mEmptySubtitle.setText(mProject.name + " · " + currentModel().replace("[1m]", "") + " · Claude Code " + runtimeVersion());
        mSuggestions.removeAllViews();
        String[][] items = mProject.isPhoneWorkspace() ? new String[][]{
            {t("看看屏幕"), t("现在手机屏幕上显示的是什么？")},
            {t("发微信"), t("打开微信，给文件传输助手发一句：你好")},
            {t("查设置"), t("打开设置，告诉我手机型号和安卓版本")},
            {t("搜天气"), t("打开浏览器搜索今天的天气")},
        } : new String[][]{
            {t("了解项目"), t("介绍一下这个项目的结构和主要功能")},
            {t("新建应用"), t("在当前目录用 HTML+JS 写一个待办清单网页")},
            {t("查找问题"), t("检查代码里可能存在的 bug")},
            {t("写 README"), t("为这个项目生成 README.md")},
        };
        for (String[] it : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.ccm_bg_card);
            card.setPadding(16 * mDp, 12 * mDp, 16 * mDp, 12 * mDp);
            TextView t = text(it[0], 14, R.color.ccm_text);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(t);
            card.addView(text(it[1], 13, R.color.ccm_text_secondary));
            card.setOnClickListener(v -> { mInput.setText(it[1]); mInput.setSelection(it[1].length()); mInput.requestFocus(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8 * mDp;
            mSuggestions.addView(card, lp);
        }
    }

    // ======================= 视图工具 =======================

    private LinearLayout sheetColumn(String title) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 10 * mDp, 0, 16 * mDp);
        View handle = new View(this);
        handle.setBackgroundResource(R.drawable.ccm_bg_chip);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(36 * mDp, 4 * mDp);
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = 10 * mDp;
        col.addView(handle, hl);
        TextView t = text(title, 17, R.color.ccm_text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(20 * mDp, 4 * mDp, 20 * mDp, 10 * mDp);
        col.addView(t);
        return col;
    }

    private View wrapSheet(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        return sv;
    }

    private View sheetItem(LinearLayout parent, int iconRes, String title, String subtitle, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(20 * mDp, 10 * mDp, 20 * mDp, 10 * mDp);
        row.setBackgroundResource(selectableBg());
        ImageView icon = new ImageView(this);
        icon.setTag("icon");
        icon.setImageResource(iconRes);
        icon.setImageTintList(ContextCompat.getColorStateList(this, selected ? R.color.ccm_accent : R.color.ccm_text_secondary));
        row.addView(icon, new LinearLayout.LayoutParams(20 * mDp, 20 * mDp));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, R.color.ccm_text);
        if (selected) t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 12, R.color.ccm_text_tertiary);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            texts.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = 14 * mDp;
        row.addView(texts, lp);
        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ccm_ic_check);
            check.setImageTintList(ContextCompat.getColorStateList(this, R.color.ccm_accent));
            row.addView(check, new LinearLayout.LayoutParams(18 * mDp, 18 * mDp));
        }
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private android.graphics.drawable.Drawable roundAccent() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ContextCompat.getColor(this, R.color.ccm_accent));
        g.setCornerRadius(12 * mDp);
        return g;
    }

    private int selectableBg() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private TextView text(String s, int sp, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        return tv;
    }

    private static String tail(String s, int max) {
        return s.length() > max ? "…" + s.substring(s.length() - max) : s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
