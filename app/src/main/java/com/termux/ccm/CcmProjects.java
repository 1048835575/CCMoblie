package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目（工作目录）与会话管理。
 * 会话直接读取 Claude Code 自己保存的 ~/.claude/projects/&lt;编码后的路径&gt;/&lt;session&gt;.jsonl，
 * 因此与终端里 `claude --resume` 看到的是同一份历史。
 */
public final class CcmProjects {

    private static final String PREF_PROJECTS = "projects";
    private static final String PREF_CURRENT = "current_project";

    public static final File PROJECTS_ROOT = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "projects");
    public static final File PHONE_WORKSPACE = new File(CcmConfig.CCM_DIR, "workspace");

    private CcmProjects() {}

    public static final class Project {
        public final String name;
        public final String path;

        Project(String name, String path) {
            this.name = name;
            this.path = path;
        }

        public boolean isPhoneWorkspace() {
            return PHONE_WORKSPACE.getAbsolutePath().equals(path);
        }
    }

    public static final class Session {
        public final String id;
        public final String title;
        public final long modified;
        public final File file;

        Session(String id, String title, long modified, File file) {
            this.id = id;
            this.title = title;
            this.modified = modified;
            this.file = file;
        }
    }

    // ---------------- 项目 ----------------

    public static List<Project> list(Context context) {
        List<Project> out = new ArrayList<>();
        out.add(new Project(t("手机助手"), PHONE_WORKSPACE.getAbsolutePath()));
        try {
            JSONArray arr = new JSONArray(CcmConfig.prefs(context).getString(PREF_PROJECTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String path = o.getString("path");
                if (!path.equals(PHONE_WORKSPACE.getAbsolutePath())) out.add(new Project(o.optString("name", new File(path).getName()), path));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static Project current(Context context) {
        String path = CcmConfig.prefs(context).getString(PREF_CURRENT, PHONE_WORKSPACE.getAbsolutePath());
        for (Project p : list(context)) if (p.path.equals(path)) return p;
        return list(context).get(0);
    }

    public static void setCurrent(Context context, Project p) {
        CcmConfig.prefs(context).edit().putString(PREF_CURRENT, p.path).apply();
    }

    /** 添加（或移到最前）一个项目并设为当前项目。 */
    public static Project add(Context context, String name, String path) {
        List<Project> all = list(context);
        JSONArray arr = new JSONArray();
        try {
            arr.put(new JSONObject().put("name", name).put("path", path));
            for (Project p : all) {
                if (p.isPhoneWorkspace() || p.path.equals(path)) continue;
                arr.put(new JSONObject().put("name", p.name).put("path", p.path));
            }
        } catch (Exception ignored) {}
        CcmConfig.prefs(context).edit().putString(PREF_PROJECTS, arr.toString()).apply();
        Project p = new Project(name, path);
        setCurrent(context, p);
        return p;
    }

    public static void remove(Context context, Project target) {
        JSONArray arr = new JSONArray();
        try {
            for (Project p : list(context)) {
                if (p.isPhoneWorkspace() || p.path.equals(target.path)) continue;
                arr.put(new JSONObject().put("name", p.name).put("path", p.path));
            }
        } catch (Exception ignored) {}
        CcmConfig.prefs(context).edit().putString(PREF_PROJECTS, arr.toString()).apply();
        if (current(context).path.equals(target.path)) setCurrent(context, list(context).get(0));
    }

    // ---------------- 彻底删除项目文件夹 ----------------

    /** 一级目录名：内部存储根目录下的这些系统文件夹本身不允许整体删除（其中的子文件夹可以）。 */
    private static final String[] SHARED_TOP_DIRS = {"Android", "DCIM", "Download", "Documents", "Pictures", "Movies",
        "Music", "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings", "MIUI"};

    /**
     * 删除前的安全检查：可删除返回 null，否则返回拒绝原因。
     * 受保护：根目录、App 自身的各级目录及其上级、内部存储根目录和其中的系统文件夹。
     */
    public static String deleteRefusal(Project p) {
        if (p.isPhoneWorkspace()) return t("手机助手工作区不能删除");
        File dir = new File(p.path);
        if (!dir.isDirectory()) return t("文件夹不存在或已被删除");
        String target;
        try {
            target = dir.getCanonicalPath();
        } catch (Exception e) {
            return t("无法解析文件夹路径");
        }
        if (target.split("/").length < 4) return t("路径层级太浅，可能是系统目录，不允许删除");
        List<String> guarded = new ArrayList<>();
        for (String s : new String[]{"/", "/data", "/data/data", "/data/user/0", "/storage", "/storage/emulated",
            "/storage/emulated/0", "/sdcard", TermuxConstants.TERMUX_FILES_DIR_PATH, TermuxConstants.TERMUX_HOME_DIR_PATH,
            TermuxConstants.TERMUX_PREFIX_DIR_PATH, CcmConfig.CCM_DIR.getPath(), PROJECTS_ROOT.getPath(),
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.claude", TermuxConstants.TERMUX_HOME_DIR_PATH + "/storage"}) {
            guarded.add(s);
        }
        for (String d : SHARED_TOP_DIRS) guarded.add("/storage/emulated/0/" + d);
        for (String g : guarded) {
            String c;
            try { c = new File(g).getCanonicalPath(); } catch (Exception e) { c = g; }
            // 目标是受保护目录本身，或是它的上级目录
            if (c.equals(target) || c.startsWith(target.endsWith("/") ? target : target + "/")) {
                return t("这是受保护的目录，不允许整体删除：") + displayPath(target);
            }
        }
        return null;
    }

    /** 统计文件数和总大小（不跟随符号链接，最多统计 20 万个）。返回 {文件数, 字节数}。 */
    public static long[] measure(File dir) {
        long[] r = {0, 0};
        List<File> stack = new ArrayList<>();
        stack.add(dir);
        while (!stack.isEmpty() && r[0] < 200_000) {
            File f = stack.remove(stack.size() - 1);
            if (isSymlink(f)) { r[0]++; continue; }
            if (f.isDirectory()) {
                File[] cs = f.listFiles();
                if (cs != null) stack.addAll(Arrays.asList(cs));
            } else {
                r[0]++;
                r[1] += f.length();
            }
        }
        return r;
    }

    private static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            File inCanonicalParent = new File(parent.getCanonicalFile(), f.getName());
            return !inCanonicalParent.getCanonicalFile().equals(inCanonicalParent.getAbsoluteFile());
        } catch (Exception e) {
            return true; // 无法判断时按链接处理：只删链接本身，绝不进入
        }
    }

    /** 递归删除；符号链接只删除链接本身，不进入其指向的目录。返回删除失败的数量。 */
    private static int deleteTree(File f) {
        int failed = 0;
        if (!isSymlink(f) && f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) failed += deleteTree(c);
        }
        if (!f.delete() && f.exists()) failed++;
        return failed;
    }

    /**
     * 彻底删除项目：从列表移除并删除整个文件夹（不可恢复），可选同时删除该项目的对话记录。
     * 调用前必须先通过 {@link #deleteRefusal}。返回删除失败的文件数。
     */
    public static int deleteProject(Context context, Project p, boolean withSessions) {
        if (deleteRefusal(p) != null) return -1;
        remove(context, p);
        int failed = deleteTree(new File(p.path));
        if (withSessions) for (File d : sessionDirs(p.path)) failed += deleteTree(d);
        return failed;
    }

    /** 在 ~/projects 下新建项目目录。 */
    public static Project create(Context context, String name) {
        File dir = new File(PROJECTS_ROOT, name.trim().replaceAll("[/\\\\:*?\"<>|]", "_"));
        dir.mkdirs();
        return add(context, name.trim(), dir.getAbsolutePath());
    }

    public static String displayPath(String path) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (path.startsWith(home)) return "~" + path.substring(home.length());
        if (path.startsWith("/storage/emulated/0")) return t("内部存储") + path.substring("/storage/emulated/0".length());
        return path;
    }

    // ---------------- 会话 ----------------

    /** Claude Code 用路径中非字母数字字符替换为 '-' 作为会话目录名。 */
    private static List<File> sessionDirs(String projectPath) {
        File root = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".claude/projects");
        List<File> dirs = new ArrayList<>();
        for (String p : new String[]{projectPath, projectPath.replaceFirst("^/data/data/", "/data/user/0/")}) {
            File d = new File(root, p.replaceAll("[^a-zA-Z0-9]", "-"));
            if (d.isDirectory() && !dirs.contains(d)) dirs.add(d);
        }
        return dirs;
    }

    public static List<Session> sessions(String projectPath, int limit) {
        List<File> files = new ArrayList<>();
        for (File d : sessionDirs(projectPath)) {
            File[] fs = d.listFiles((dir, n) -> n.endsWith(".jsonl"));
            if (fs != null) files.addAll(Arrays.asList(fs));
        }
        java.util.Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<Session> out = new ArrayList<>();
        for (File f : files) {
            if (out.size() >= limit) break;
            String title = sessionTitle(f);
            if (title == null) continue; // 没有用户消息的空会话
            out.add(new Session(f.getName().replace(".jsonl", ""), title, f.lastModified(), f));
        }
        return out;
    }

    private static String sessionTitle(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 200) {
                if (!line.contains("\"type\":\"user\"")) continue;
                JSONObject o = new JSONObject(line);
                if (o.optBoolean("isMeta")) continue;
                String text = userText(o.optJSONObject("message"));
                if (text != null && !text.trim().isEmpty() && !text.startsWith("<")) {
                    text = text.trim().replace('\n', ' ');
                    return text.length() > 40 ? text.substring(0, 40) + "…" : text;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 提取用户消息中的文字（跳过 tool_result）。 */
    static String userText(JSONObject message) {
        if (message == null) return null;
        Object c = message.opt("content");
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            JSONArray arr = (JSONArray) c;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text"));
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    public static File sessionFile(String projectPath, String sessionId) {
        for (File d : sessionDirs(projectPath)) {
            File f = new File(d, sessionId + ".jsonl");
            if (f.exists()) return f;
        }
        return null;
    }

    private static File trashDir() {
        File d = new File(CcmConfig.CCM_DIR, "trash");
        d.mkdirs();
        return d;
    }

    /** 删除会话：移到回收站，返回回收站里的文件（用于撤销），失败返回 null。 */
    public static File deleteSession(File sessionFile) {
        File dest = new File(trashDir(), System.currentTimeMillis() + "_" + sessionFile.getName());
        return sessionFile.renameTo(dest) ? dest : null;
    }

    /** 撤销删除 / 撤销回退：把备份文件放回原位置。 */
    public static boolean restore(File backup, File original) {
        original.getParentFile().mkdirs();
        if (original.exists()) original.delete();
        return backup.renameTo(original);
    }

    /** 回退结果：被撤回的用户消息文字 + 回退前的完整备份。 */
    public static final class Rewind {
        public final String userText;
        public final File backup;

        Rewind(String userText, File backup) {
            this.userText = userText;
            this.backup = backup;
        }
    }

    /**
     * 撤销最后一轮：截断到最后一条真实用户输入之前（之后的 AI 回复、工具调用一并去掉）。
     * 只回退对话记录，AI 已修改的文件不会恢复。没有可撤销的内容时返回 null。
     */
    public static Rewind rewindLastTurn(File sessionFile) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(sessionFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        int cut = -1;
        String text = null;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (!line.contains("\"type\":\"user\"")) continue;
            JSONObject o;
            try { o = new JSONObject(line); } catch (Exception e) { continue; }
            if (o.optBoolean("isMeta") || o.optBoolean("isSidechain")) continue;
            String t = userText(o.optJSONObject("message"));
            boolean hasImage = line.contains("\"type\":\"image\"");
            if ((t == null || t.trim().isEmpty() || t.startsWith("<")) && !hasImage) continue;
            cut = i;
            text = t == null ? "" : t;
            break;
        }
        if (cut < 0) return null;
        File backup = new File(trashDir(), System.currentTimeMillis() + "_rewind_" + sessionFile.getName());
        if (!sessionFile.renameTo(backup)) throw new Exception(t("无法备份会话文件"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cut; i++) sb.append(lines.get(i)).append('\n');
        CcmConfig.writeString(sessionFile, sb.toString());
        return new Rewind(text, backup);
    }

    /** 读出会话里的 user / assistant 记录，供聊天界面重放。 */
    public static List<JSONObject> loadSession(File file) {
        List<JSONObject> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\":\"assistant\"")) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    String t = o.optString("type");
                    if (("user".equals(t) || "assistant".equals(t)) && !o.optBoolean("isMeta") && !o.optBoolean("isSidechain")) out.add(o);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }
}
