#!/usr/bin/env node
// CCMobile 手机控制 MCP 服务（stdio，零依赖）。
// 核心思路：把 uiautomator 的几万 token XML 压缩成「编号 + 文字」列表，模型按编号点击；
// 每个动作执行后直接返回新屏幕，省掉一轮"再看一眼"的请求。
'use strict';

const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const HOME = process.env.HOME || os.homedir();
const CCM_DIR = path.join(HOME, '.ccmobile');
// 仅用于在 `ime list` 结果中匹配，实际使用的 ID 以系统返回值为准（见 resolveImeId）
const IME_ID_HINT = 'com.termux/.ccm.CcmIme';
const IME_ACTION = 'com.termux.ccm.IME_INPUT';
// 切换前记录用户原输入法，供 App 和下次输入在未能切回时恢复
const PREV_IME_FILE = path.join(CCM_DIR, 'prev_ime');
const MAX_ITEMS = 200;
const MAX_LABEL = 40;

// MCP 子进程未必继承完整环境变量，直接读 App 写的 config.env 补齐视觉模型等配置
try {
    for (const line of fs.readFileSync(path.join(CCM_DIR, 'config.env'), 'utf8').split('\n')) {
        const m = line.match(/^export (CCM_\w+)='(.*)'$/);
        if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/'\\''/g, "'");
    }
} catch (e) { /* 没有配置文件就用默认值 */ }

let serial = null;
let lastItems = new Map(); // id -> {cx, cy, b:[x1,y1,x2,y2], label}
let screenW = 1080, screenH = 2400;

// ---------- 工具函数 ----------

function sleep(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }

function adbRaw(args, opts = {}) {
    return execFileSync('adb', args, {
        encoding: opts.binary ? 'buffer' : 'utf8',
        timeout: opts.timeout || 20000,
        maxBuffer: 64 * 1024 * 1024,
        stdio: ['ignore', 'pipe', 'pipe'],
    });
}

function listDevices() {
    try {
        return adbRaw(['devices']).split('\n').slice(1)
            .map(l => l.trim().split(/\s+/)).filter(p => p[1] === 'device').map(p => p[0]);
    } catch (e) { return []; }
}

function ensureDevice() {
    let devs = listDevices();
    if (serial && devs.includes(serial)) return serial;
    if (devs.length === 0) {
        try { execFileSync('bash', [path.join(CCM_DIR, 'bin', 'ccm-connect'), '--quiet'], { timeout: 30000, stdio: 'ignore' }); } catch (e) { /* 下面统一报错 */ }
        devs = listDevices();
    }
    if (devs.length === 0) {
        throw new Error('没有连接到手机。请让用户：打开开发者选项的「无线调试」，在 CCMobile 配置里完成配对，或在终端执行 ccm-connect <端口>。');
    }
    serial = devs.find(d => d.startsWith('127.0.0.1') || d.startsWith('localhost')) || devs[0];
    try {
        const m = shell('wm size').match(/(\d+)x(\d+)\s*$/m);
        if (m) { screenW = +m[1]; screenH = +m[2]; }
    } catch (e) { /* 用默认尺寸 */ }
    return serial;
}

function adb(args, opts) { ensureDevice(); return adbRaw(['-s', serial, ...args], opts); }
function shell(cmd, opts) { return adbRaw(['-s', serial || ensureDevice(), 'shell', cmd], opts); }

function decodeXml(s) {
    return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n)).replace(/&amp;/g, '&');
}

function clip(s) {
    s = s.replace(/\s+/g, ' ').trim();
    return s.length > MAX_LABEL ? s.slice(0, MAX_LABEL) + '…' : s;
}

function foregroundApp() {
    try {
        const out = shell('dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2');
        const m = out.match(/\s([a-zA-Z0-9_.]+)\/[a-zA-Z0-9_.$]+/);
        return m ? m[1] : '?';
    } catch (e) { return '?'; }
}

// ---------- 读屏 ----------

function dumpUiXml() {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const out = adb(['exec-out', 'uiautomator', 'dump', '/dev/tty'], { timeout: 15000 });
            const s = out.indexOf('<?xml'), e = out.lastIndexOf('</hierarchy>');
            if (s >= 0 && e > s) return out.slice(s, e + 12);
            // 部分 ROM 不支持写 /dev/tty，退回写文件
            shell('uiautomator dump /sdcard/.ccm_ui.xml', { timeout: 15000 });
            const f = adb(['exec-out', 'cat', '/sdcard/.ccm_ui.xml']);
            if (f.includes('<hierarchy')) return f;
        } catch (e) { /* 动画中常见 "could not get idle state"，重试 */ }
        sleep(600);
    }
    throw new Error('读取界面失败（uiautomator dump）。界面可能在播放动画/视频，或是游戏等无控件界面，可改用 phone_screenshot。');
}

function parseUi(xml) {
    const nodes = [];
    const stack = [];
    const re = /<node\b([^>]*?)(\/?)>|<\/node>/g;
    let m;
    while ((m = re.exec(xml))) {
        if (!m[1] && m[0] === '</node>') { stack.pop(); continue; }
        const a = {};
        m[1].replace(/([\w-]+)="([^"]*)"/g, (_, k, v) => { a[k] = decodeXml(v); });
        const bm = (a.bounds || '').match(/\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
        const b = bm ? bm.slice(1).map(Number) : [0, 0, 0, 0];
        const node = {
            text: a.text || '', desc: a['content-desc'] || '', rid: (a['resource-id'] || '').split('/').pop(),
            cls: (a.class || '').split('.').pop(),
            clickable: a.clickable === 'true' || a['long-clickable'] === 'true',
            scrollable: a.scrollable === 'true', checkable: a.checkable === 'true', checked: a.checked === 'true',
            selected: a.selected === 'true', focused: a.focused === 'true', enabled: a.enabled !== 'false',
            b, parent: stack.length ? stack[stack.length - 1] : null, texts: [],
        };
        nodes.push(node);
        if (!m[2]) stack.push(node);
    }
    return nodes;
}

function buildScreen() {
    const nodes = parseUi(dumpUiXml());
    const area = n => Math.max(0, n.b[2] - n.b[0]) * Math.max(0, n.b[3] - n.b[1]);
    const screenArea = screenW * screenH;
    const visible = n => area(n) > 0 && n.b[2] > 0 && n.b[3] > 0 && n.b[0] < screenW && n.b[1] < screenH;
    const isEdit = n => /EditText|AutoCompleteTextView|SearchView/.test(n.cls);
    // 能"吸收"子节点文字的可点击容器：不能太大（整屏可点的根布局会把所有文字吞掉）
    const isTarget = n => (n.clickable || isEdit(n) || n.checkable) && area(n) < screenArea * 0.6;

    // 把无点击能力的文字归到最近的可点击祖先上
    const owned = new Set();
    for (const n of nodes) {
        if (!visible(n)) continue;
        const t = n.text || n.desc;
        if (!t || isTarget(n)) continue;
        for (let p = n.parent; p; p = p.parent) {
            if (isTarget(p) && visible(p)) { if (p.texts.length < 3) p.texts.push(t); owned.add(n); break; }
        }
    }

    const items = [];
    const seen = new Set();
    for (const n of nodes) {
        if (!visible(n) || owned.has(n)) continue;
        const own = n.text || n.desc;
        let kind, label;
        if (isEdit(n)) {
            kind = '输入框'; label = own || n.texts.join(' / ') || n.rid || '(空)';
        } else if (isTarget(n)) {
            kind = n.checkable ? (n.checked ? '开关:开' : '开关:关') : '按钮';
            label = [own, ...n.texts].filter(Boolean).join(' / ') || (n.rid ? 'id:' + n.rid : '无文字图标');
        } else if (n.scrollable && area(n) > screenArea * 0.1) {
            kind = '滚动区'; label = n.rid || n.cls;
        } else if (own) {
            kind = '文本'; label = own;
        } else continue;
        label = clip(label);
        const cx = (n.b[0] + n.b[2]) >> 1, cy = (n.b[1] + n.b[3]) >> 1;
        const key = kind + label + (cx >> 4) + ',' + (cy >> 4);
        if (seen.has(key)) continue;
        seen.add(key);
        let flags = '';
        if (n.selected) flags += ' *选中';
        if (n.focused && isEdit(n)) flags += ' *焦点';
        if (!n.enabled) flags += ' (不可用)';
        items.push({ kind, label, cx, cy, b: n.b, flags });
        if (items.length >= MAX_ITEMS) break;
    }

    lastItems = new Map();
    const lines = [`App: ${foregroundApp()}  屏幕 ${screenW}x${screenH}`];
    items.forEach((it, i) => {
        const id = i + 1;
        lastItems.set(id, it);
        lines.push(it.kind === '文本' ? `[${id}] 「${it.label}」${it.flags}` : `[${id}] ${it.kind}「${it.label}」${it.flags}`);
    });
    if (items.length === 0) lines.push('（没有可识别的控件：可能是游戏/视频/加载中，可等待后重试或用 phone_screenshot）');
    if (items.length >= MAX_ITEMS) lines.push(`（只列出前 ${MAX_ITEMS} 项）`);
    return lines.join('\n');
}

// ---------- 动作 ----------

function point(args) {
    if (args.id != null) {
        const it = lastItems.get(Number(args.id));
        if (!it) throw new Error(`编号 ${args.id} 不存在，请先调用 phone_screen 获取最新编号`);
        return [it.cx, it.cy, it];
    }
    if (args.x != null && args.y != null) return [Math.round(args.x), Math.round(args.y), null];
    throw new Error('需要 id，或者 x 和 y');
}

function tap(args) {
    const [x, y] = point(args);
    if (args.long) shell(`input swipe ${x} ${y} ${x} ${y} 800`);
    else shell(`input tap ${x} ${y}`);
}

function swipe(args) {
    let x1, y1, x2, y2;
    const d = Math.min(Math.max(Number(args.distance) || 0.5, 0.1), 0.9);
    let box = [0, 0, screenW, screenH];
    if (args.id != null) box = point(args)[2].b;
    const cx = (box[0] + box[2]) >> 1, cy = (box[1] + box[3]) >> 1;
    const w = box[2] - box[0], h = box[3] - box[1];
    switch (args.direction) {
        case 'up': x1 = x2 = cx; y1 = cy + h * d / 2; y2 = cy - h * d / 2; break;
        case 'down': x1 = x2 = cx; y1 = cy - h * d / 2; y2 = cy + h * d / 2; break;
        case 'left': y1 = y2 = cy; x1 = cx + w * d / 2; x2 = cx - w * d / 2; break;
        case 'right': y1 = y2 = cy; x1 = cx - w * d / 2; x2 = cx + w * d / 2; break;
        default: throw new Error('direction 只能是 up/down/left/right');
    }
    shell(`input swipe ${Math.round(x1)} ${Math.round(y1)} ${Math.round(x2)} ${Math.round(y2)} 300`);
}

const KEYS = {
    back: 4, home: 3, recent: 187, enter: 66, delete: 67, search: 84, menu: 82,
    volume_up: 24, volume_down: 25, power: 26, space: 62, tab: 61, paste: 279,
};

function key(args) {
    const k = String(args.key || '').toLowerCase();
    if (k === 'notifications') return shell('cmd statusbar expand-notifications');
    if (k === 'quick_settings') return shell('cmd statusbar expand-settings');
    if (!(k in KEYS)) throw new Error('不支持的按键：' + k + '，可用：' + Object.keys(KEYS).join(',') + ',notifications,quick_settings');
    shell(`input keyevent ${KEYS[k]}`);
}

// ---------- 输入法 ----------
// 原则：ID 以系统 `ime list` 返回的规范值为准；比较前两侧统一为简写；每一步检查结果，失败给出明确错误。

let imeIdCache = null;

/** "pkg/pkg.Cls" 与 "pkg/.Cls" 统一成系统规范的简写形式（settings 里存的就是简写）。 */
function normIme(id) {
    id = String(id || '').trim();
    const m = id.match(/^([^/\s]+)\/(\S+)$/);
    if (!m) return id;
    const [, pkg, cls] = m;
    return cls.startsWith(pkg + '.') ? `${pkg}/${cls.slice(pkg.length)}` : id;
}

/** 执行 shell 命令，非零退出码时抛出带命令、退出码和输出的错误。 */
function shellChecked(cmd, what) {
    ensureDevice();
    try {
        return adbRaw(['-s', serial, 'shell', cmd]);
    } catch (e) {
        const out = `${e.stdout || ''}${e.stderr || ''}`.trim();
        throw new Error(`${what}失败：\`${cmd}\` 退出码 ${e.status}${out ? '，输出：' + out : ''}`);
    }
}

/** 从系统输入法列表里取 CCMobile 输入法的规范 ID，不硬编码。 */
function resolveImeId() {
    if (imeIdCache) return imeIdCache;
    const list = shellChecked('ime list -s -a', '读取输入法列表').split('\n').map(s => s.trim()).filter(Boolean);
    const wanted = normIme(IME_ID_HINT);
    const id = list.find(l => normIme(l) === wanted) || list.find(l => l.startsWith('com.termux/') && /CcmIme$/.test(l));
    if (!id) {
        throw new Error(`系统输入法列表中找不到 CCMobile 输入法（期望 ${wanted}）。ime list -s -a 输出：${list.join(', ') || '(空)'}`);
    }
    imeIdCache = id;
    return id;
}

function currentIme() {
    return normIme(shellChecked('settings get secure default_input_method', '读取当前输入法'));
}

/** 切换输入法并核实结果，失败抛出带实际 ID 和 ime 输出的错误。 */
function setIme(id, what) {
    const out = shellChecked(`ime set ${id}`, what).trim();
    for (let i = 0; i < 10; i++) {
        if (currentIme() === normIme(id)) return;
        sleep(100);
    }
    throw new Error(`${what}失败：尝试的 ID 为 ${id}，当前输入法仍是 ${currentIme()}；ime 输出：${out || '(无)'}`);
}

function enableIme(id) {
    const out = shellChecked(`ime enable ${id}`, '启用 CCMobile 输入法').trim();
    if (/unknown|not found|error|exception|denied/i.test(out)) {
        throw new Error(`启用 CCMobile 输入法失败：尝试的 ID 为 ${id}；ime 输出：${out}`);
    }
}

function readPrevIme() {
    try { return normIme(fs.readFileSync(PREV_IME_FILE, 'utf8')); } catch (e) { return ''; }
}

function inputText(args) {
    const text = String(args.text ?? '');
    if (args.id != null || args.x != null) { tap(args); sleep(500); }
    const imeId = resolveImeId();
    const cur = currentIme();
    // 上次没切回去时（当前仍是 CCMobile 输入法），用记录下来的原输入法作为恢复目标
    const restoreTo = cur === normIme(imeId) ? readPrevIme() : (cur && cur !== 'null' ? cur : '');
    const switched = cur !== normIme(imeId);
    if (switched && restoreTo) fs.writeFileSync(PREV_IME_FILE, restoreTo);

    let ok = false, restoreError = null;
    try {
        if (switched) {
            enableIme(imeId);
            setIme(imeId, '切换到 CCMobile 输入法');
            sleep(400);
        }
        const b64 = Buffer.from(text, 'utf8').toString('base64');
        const extra = `${args.clear ? ' --ez clear true' : ''}${args.submit ? ' --ez submit true' : ''}`;
        const out = shellChecked(`am broadcast -a ${IME_ACTION} --es b64 ${b64}${extra}`, '发送输入内容');
        ok = /result=1\b/.test(out);
    } finally {
        if (restoreTo && restoreTo !== normIme(imeId)) {
            sleep(200);
            try {
                setIme(restoreTo, '恢复原输入法');
                try { fs.unlinkSync(PREV_IME_FILE); } catch (e) { /* 无记录可删 */ }
            } catch (e) {
                restoreError = e;
            }
        }
    }
    if (restoreError) {
        throw new Error(`${restoreError.message}。${ok ? '文字已输入成功，但' : ''}键盘没能切回，请回到 CCMobile 首页自动恢复，或在系统设置中手动切换输入法`);
    }
    if (!ok) {
        // 回退：系统 input text 只支持 ASCII
        if (/[^\x20-\x7e]/.test(text)) throw new Error('CCMobile 输入法未能输入（请确认输入框已获得焦点；小米需开启「USB调试（安全设置）」）');
        const esc = text.replace(/([\\'"`$&|;<>()*?!#~^{}\[\]])/g, '\\$1').replace(/ /g, '%s');
        shell(`input text '${esc.replace(/'/g, "'\\''")}'`);
        if (args.submit) key({ key: 'enter' });
    }
}

function loadApps() {
    try {
        return fs.readFileSync(path.join(CCM_DIR, 'apps.tsv'), 'utf8').split('\n')
            .map(l => l.split('\t')).filter(p => p.length >= 2 && p[1]).map(([label, pkg]) => ({ label, pkg }));
    } catch (e) { return []; }
}

function resolvePackage(name) {
    name = String(name || '').trim();
    if (!name) throw new Error('需要 app 名称或包名');
    const apps = loadApps();
    if (/^[a-zA-Z][\w]*(\.[\w]+)+$/.test(name)) return name;
    const lower = name.toLowerCase();
    const hit = apps.find(a => a.label.toLowerCase() === lower)
        || apps.find(a => a.label.toLowerCase().includes(lower))
        || apps.find(a => lower.includes(a.label.toLowerCase()) && a.label.length >= 2);
    if (hit) return hit.pkg;
    throw new Error(`找不到应用「${name}」。可用 phone_apps 搜索已安装应用`);
}

function launch(args) {
    const pkg = resolvePackage(args.app);
    let comp = '';
    try { comp = shell(`cmd package resolve-activity --brief ${pkg} | tail -1`).trim(); } catch (e) { /* 退回 monkey */ }
    if (comp.includes('/')) shell(`am start -n ${comp}`);
    else shell(`monkey -p ${pkg} -c android.intent.category.LAUNCHER 1`);
    return pkg;
}

// ---------- 截图（缩小后编码为 PNG，零依赖） ----------

const CRC_TABLE = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c;
    }
    return t;
})();

function crc32(buf) {
    let c = -1;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ -1) >>> 0;
}

function pngChunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
}

/** RGB 行数据（每行前置过滤字节 0）编码为 PNG。 */
function encodePng(w, h, rows) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(w, 0);
    ihdr.writeUInt32BE(h, 4);
    ihdr[8] = 8; ihdr[9] = 2; // 8 位 RGB
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        pngChunk('IHDR', ihdr),
        pngChunk('IDAT', require('zlib').deflateSync(rows, { level: 6 })),
        pngChunk('IEND', Buffer.alloc(0)),
    ]);
}

/**
 * 读取原始帧缓冲（screencap 不带 -p），按整数倍做方块平均缩小到长边 ≤ maxSide，再编码 PNG。
 * 原始格式：头部 12 或 16 字节（宽、高、像素格式[、色彩空间]），后接 RGBA 像素。
 * 解析失败时退回系统 PNG（不缩小）。
 */
function captureScreen(maxSide) {
    const raw = adb(['exec-out', 'screencap'], { binary: true, timeout: 20000 });
    const w = raw.readUInt32LE(0), h = raw.readUInt32LE(4), fmt = raw.readUInt32LE(8);
    const header = raw.length - w * h * 4;
    if (!(header === 12 || header === 16) || !(fmt === 1 || fmt === 2)) {
        const png = adb(['exec-out', 'screencap', '-p'], { binary: true, timeout: 20000 });
        return { png, width: w || screenW, height: h || screenH, scale: 1 };
    }
    const scale = Math.max(1, Math.ceil(Math.max(w, h) / maxSide));
    const ow = Math.floor(w / scale), oh = Math.floor(h / scale);
    const rowLen = ow * 3 + 1;
    const rows = Buffer.alloc(rowLen * oh);
    const n = scale * scale;
    for (let y = 0; y < oh; y++) {
        const o = y * rowLen;
        rows[o] = 0;
        for (let x = 0; x < ow; x++) {
            let r = 0, g = 0, b = 0;
            for (let dy = 0; dy < scale; dy++) {
                let p = header + ((y * scale + dy) * w + x * scale) * 4;
                for (let dx = 0; dx < scale; dx++, p += 4) { r += raw[p]; g += raw[p + 1]; b += raw[p + 2]; }
            }
            const q = o + 1 + x * 3;
            rows[q] = r / n; rows[q + 1] = g / n; rows[q + 2] = b / n;
        }
    }
    return { png: encodePng(ow, oh, rows), width: w, height: h, scale };
}

async function screenshot(args) {
    const shot = captureScreen(1280);
    const dir = path.join(CCM_DIR, 'workspace', 'shots');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `shot_${Date.now()}.png`);
    fs.writeFileSync(file, shot.png);
    const png = shot.png;
    const coordNote = `截图已缩小为原尺寸的 1/${shot.scale}（屏幕实际 ${shot.width}x${shot.height}），点击时坐标请乘以 ${shot.scale}。`;

    const base = process.env.CCM_VISION_BASE_URL, keyv = process.env.CCM_VISION_KEY, model = process.env.CCM_VISION_MODEL;
    if (!base || !keyv || !model) {
        // 默认：图片直接交给主模型（deepseek-flash 等支持图片输入的模型）
        if (process.env.CCM_MAIN_VISION === '0') {
            return `截图已保存：${file}\n（已在设置中关闭「主模型识图」且未配置视觉模型，无法查看图片内容）`;
        }
        return {
            content: [
                { type: 'text', text: `${coordNote}\n文件：${file}` },
                { type: 'image', data: png.toString('base64'), mimeType: 'image/png' },
            ],
        };
    }
    const question = args.question || '简洁描述这个手机屏幕：当前是什么界面、有哪些可操作元素及其大致位置(用屏幕坐标)。';
    const res = await fetch(base.replace(/\/+$/, '') + '/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + keyv },
        body: JSON.stringify({
            model, max_tokens: 800,
            messages: [{
                role: 'user', content: [
                    { type: 'image_url', image_url: { url: 'data:image/png;base64,' + png.toString('base64') } },
                    { type: 'text', text: `屏幕分辨率 ${screenW}x${screenH}。${question}` },
                ],
            }],
        }),
    });
    const j = await res.json();
    if (!res.ok) throw new Error('视觉模型调用失败：' + JSON.stringify(j).slice(0, 300));
    return `截图：${file}\n${j.choices?.[0]?.message?.content || '(无回答)'}`;
}

// ---------- 工具定义 ----------

const screenProp = { screen: { type: 'boolean', description: '动作后是否返回新屏幕，默认 true' } };
const target = {
    id: { type: 'integer', description: 'phone_screen 列出的编号' },
    x: { type: 'integer' }, y: { type: 'integer' },
};

const TOOLS = [
    {
        name: 'phone_screen',
        description: '读取当前屏幕，返回压缩后的控件列表：[编号] 类型「文字」。操作控件时用编号。',
        inputSchema: { type: 'object', properties: {} },
        run: () => buildScreen(),
    },
    {
        name: 'phone_tap',
        description: '点击控件（按编号，或 x/y 坐标）。long=true 为长按。',
        inputSchema: { type: 'object', properties: { ...target, long: { type: 'boolean' }, ...screenProp } },
        run: a => { tap(a); return null; },
    },
    {
        name: 'phone_input',
        description: '在输入框输入文字（支持中文）。传 id 会先点击该输入框；clear 先清空；submit 输入后回车/搜索/发送。',
        inputSchema: {
            type: 'object', required: ['text'],
            properties: { text: { type: 'string' }, ...target, clear: { type: 'boolean' }, submit: { type: 'boolean' }, ...screenProp },
        },
        run: a => { inputText(a); return null; },
    },
    {
        name: 'phone_swipe',
        description: '滑动。direction=up 表示手指上滑（查看下方内容）。可传滚动区编号 id；distance 为 0.1~0.9 的比例。',
        inputSchema: {
            type: 'object', required: ['direction'],
            properties: { direction: { type: 'string', enum: ['up', 'down', 'left', 'right'] }, id: { type: 'integer' }, distance: { type: 'number' }, ...screenProp },
        },
        run: a => { swipe(a); return null; },
    },
    {
        name: 'phone_key',
        description: '按键：back home recent enter delete search notifications quick_settings volume_up volume_down power',
        inputSchema: { type: 'object', required: ['key'], properties: { key: { type: 'string' }, ...screenProp } },
        run: a => { key(a); return null; },
    },
    {
        name: 'phone_launch',
        description: '打开应用，app 填中文名（如 微信）或包名。',
        inputSchema: { type: 'object', required: ['app'], properties: { app: { type: 'string' }, ...screenProp } },
        run: a => `已启动 ${launch(a)}`,
        wait: 2000,
    },
    {
        name: 'phone_batch',
        description: '一次执行多个动作，最后只返回一次屏幕，适合确定的连续操作以节省轮次。'
            + 'actions 元素示例：{"tap":3} {"tap":3,"long":true} {"input":"你好","id":5,"submit":true} {"key":"back"} {"swipe":"up"} {"launch":"微信"} {"wait":1.5}。'
            + '注意：编号来自执行前的屏幕，界面跳转后编号会失效。',
        inputSchema: {
            type: 'object', required: ['actions'],
            properties: { actions: { type: 'array', items: { type: 'object' } }, ...screenProp },
        },
        run: a => {
            const log = [];
            for (const [i, act] of (a.actions || []).entries()) {
                if (act.tap != null) tap({ id: act.tap, long: act.long });
                else if (act.x != null) tap(act);
                else if (act.input != null) inputText({ text: act.input, id: act.id, clear: act.clear, submit: act.submit });
                else if (act.key) key(act);
                else if (act.swipe) swipe({ direction: act.swipe, id: act.id, distance: act.distance });
                else if (act.launch) { launch({ app: act.launch }); sleep(1500); }
                else if (act.wait) { sleep(Math.min(Number(act.wait), 10) * 1000); continue; }
                else throw new Error(`第 ${i + 1} 个动作无法识别：${JSON.stringify(act)}`);
                log.push(Object.keys(act)[0]);
                sleep(act.wait_after ? act.wait_after * 1000 : 600);
            }
            return `已执行 ${log.length} 个动作`;
        },
    },
    {
        name: 'phone_apps',
        description: '搜索已安装应用，返回 名称=包名。',
        inputSchema: { type: 'object', properties: { filter: { type: 'string' } } },
        run: a => {
            const f = String(a.filter || '').toLowerCase();
            const list = loadApps().filter(x => !f || x.label.toLowerCase().includes(f) || x.pkg.includes(f));
            return list.slice(0, 80).map(x => `${x.label}=${x.pkg}`).join('\n') || '无匹配（apps.tsv 由 CCMobile App 启动时生成）';
        },
        noScreen: true,
    },
    {
        name: 'phone_screenshot',
        description: '截图并把图片交给你查看（已缩小，坐标需按返回的倍数换算）。仅在 phone_screen 拿不到控件（游戏/图片/视频）或需要看画面内容时使用。',
        inputSchema: { type: 'object', properties: { question: { type: 'string' } } },
        run: a => screenshot(a),
        noScreen: true,
    },
    {
        name: 'phone_shell',
        description: '执行 adb shell 命令（高级用法，如 am/pm/settings/dumpsys）。',
        inputSchema: { type: 'object', required: ['cmd'], properties: { cmd: { type: 'string' } } },
        run: a => shell(String(a.cmd), { timeout: 60000 }).slice(0, 8000) || '(无输出)',
        noScreen: true,
    },
];

async function callTool(name, args) {
    const tool = TOOLS.find(t => t.name === name);
    if (!tool) throw new Error('未知工具 ' + name);
    ensureDevice();
    const result = await tool.run(args || {});
    if (tool.name === 'phone_screen' || tool.noScreen) return result;
    if (args?.screen === false) return result || '完成';
    sleep(tool.wait || 900);
    return (result ? result + '\n\n' : '') + buildScreen();
}

// ---------- MCP stdio 协议 ----------

function send(msg) { process.stdout.write(JSON.stringify(msg) + '\n'); }

async function handle(msg) {
    const { id, method, params } = msg;
    if (id === undefined) return; // 通知，无需回复
    try {
        let result;
        if (method === 'initialize') {
            result = {
                protocolVersion: params?.protocolVersion || '2024-11-05',
                capabilities: { tools: {} },
                serverInfo: { name: 'ccmobile-phone', version: '0.1.0' },
            };
        } else if (method === 'tools/list') {
            result = { tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) };
        } else if (method === 'tools/call') {
            try {
                const out = await callTool(params.name, params.arguments);
                // 工具可直接返回 MCP content（如图片），否则当作文本
                result = out && Array.isArray(out.content) ? out : { content: [{ type: 'text', text: String(out) }] };
            } catch (e) {
                result = { content: [{ type: 'text', text: '错误：' + (e.stderr ? String(e.stderr).trim() : e.message) }], isError: true };
            }
        } else if (method === 'ping') {
            result = {};
        } else {
            return send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
        }
        send({ jsonrpc: '2.0', id, result });
    } catch (e) {
        send({ jsonrpc: '2.0', id, error: { code: -32603, message: e.message } });
    }
}

let buf = '';
let queue = Promise.resolve();
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
    buf += chunk;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch (e) { continue; }
        queue = queue.then(() => handle(msg));
    }
});
process.stdin.on('end', () => process.exit(0));
