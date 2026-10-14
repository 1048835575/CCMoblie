#!/usr/bin/env node
// CCMobile 手机控制 MCP 服务（stdio，零依赖）。
// 核心思路：把控件树压缩成「编号 + 文字」列表，模型按编号点击；
// 每个动作执行后直接返回新屏幕，省掉一轮"再看一眼"的请求。
//
// 两个后端：
//   无障碍（默认）：App 内的 CcmA11yService，经抽象 Unix socket 通信，只接受本 App 的进程；
//                   敏感应用拒绝操作，敏感按钮（支付、转账、删除等）由服务弹框请用户确认。
//   adb（高级模式）：用户在「手机控制」里手动开启无线调试后才可用（CCM_ADB_ENABLED=1）。
'use strict';

const { execFileSync } = require('child_process');
const net = require('net');
const fs = require('fs');
const path = require('path');
const os = require('os');

const HOME = process.env.HOME || os.homedir();
const CCM_DIR = path.join(HOME, '.ccmobile');
const A11Y_SOCKET = '\0ccmobile.a11y';
// 仅用于在 `ime list` 结果中匹配，实际使用的 ID 以系统返回值为准（见 resolveImeId）
const IME_ID_HINT = 'com.termux/.ccm.CcmIme';
const IME_ACTION = 'com.termux.ccm.IME_INPUT';
// 切换前记录用户原输入法，供 App 和下次输入在未能切回时恢复
const PREV_IME_FILE = path.join(CCM_DIR, 'prev_ime');
const MAX_ITEMS = 200;
const MAX_LABEL = 40;
// 与 CcmA11yService.SENSITIVE_LABEL 保持一致
const SENSITIVE_LABEL = /支付|付款|付钱|转账|汇款|提现|收款码|红包|充值|购买|下单|提交订单|结算|删除|清空|注销|卸载|格式化|恢复出厂|解绑|\b(pay|payment|transfer|send money|withdraw|purchase|buy|checkout|place order|delete|erase|uninstall|factory reset|remove account|unbind)\b/i;

// MCP 子进程未必继承完整环境变量，直接读 App 写的 config.env 补齐配置
try {
    for (const line of fs.readFileSync(path.join(CCM_DIR, 'config.env'), 'utf8').split('\n')) {
        const m = line.match(/^export (CCM_\w+)='(.*)'$/);
        if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/'\\''/g, "'");
    }
} catch (e) { /* 没有配置文件就用默认值 */ }

// 高级模式开启后 24 小时到期（App 写入到期时间），到期即视为关闭
const ADB_ENABLED = process.env.CCM_ADB_ENABLED === '1' && Date.now() < Number(process.env.CCM_ADB_UNTIL || 0);
const SENSITIVE_APPS = new Set(String(process.env.CCM_SENSITIVE_APPS || '').split(/[,，\s]+/).filter(Boolean));

let lastItems = new Map(); // id -> {kind, label, cx, cy, b, ref}
let screenW = 1080, screenH = 2400;

// ---------- 工具函数 ----------

function sleep(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }
function wait(ms) { return new Promise(r => setTimeout(r, ms)); }

function clip(s) {
    s = s.replace(/\s+/g, ' ').trim();
    return s.length > MAX_LABEL ? s.slice(0, MAX_LABEL) + '…' : s;
}

function blockedMessage(pkg) {
    return `当前应用 ${pkg} 在敏感应用名单中（银行、支付等），CCMobile 不读取也不操作它。`
        + '请告诉用户自己完成这一步，完成后让用户回复你再继续；也可以用 phone_key back/home 离开。'
        + '（名单可在 CCMobile「手机控制」页修改）';
}

const NO_BACKEND = ADB_ENABLED
    ? '无法控制手机：无障碍服务未开启，无线调试也未连接。请让用户打开 CCMobile 侧边栏「手机控制」，开启「CCMobile 手机控制」无障碍服务（推荐），或重新连接无线调试。'
    : '无法控制手机：无障碍服务未开启。请让用户打开 CCMobile 侧边栏「手机控制」，按提示在系统设置中开启「CCMobile 手机控制」无障碍服务。';

// ======================= 无障碍后端 =======================

function a11yCall(req, timeout = 20000) {
    return new Promise((resolve, reject) => {
        const sock = net.createConnection({ path: A11Y_SOCKET });
        let data = '';
        const timer = setTimeout(() => { sock.destroy(); reject(new Error('无障碍服务响应超时')); }, timeout);
        sock.setEncoding('utf8');
        sock.on('connect', () => sock.write(JSON.stringify(req) + '\n'));
        sock.on('data', c => { data += c; });
        sock.on('end', () => {
            clearTimeout(timer);
            try { resolve(JSON.parse(data)); } catch (e) { reject(new Error('无障碍服务返回无效数据')); }
        });
        sock.on('error', e => { clearTimeout(timer); reject(e); });
    });
}

const A11Y_KEYS = { back: 'back', home: 'home', recent: 'recent', notifications: 'notifications',
    quick_settings: 'quick_settings', power: 'power', lock: 'lock', enter: 'enter', search: 'enter' };

const a11y = {
    name: '无障碍',
    info: null,

    async available() {
        try {
            const s = await a11yCall({ cmd: 'status' }, 3000);
            if (!s.ok) return false;
            this.info = s;
            screenW = s.w; screenH = s.h;
            return true;
        } catch (e) { return false; }
    },

    async call(req, timeout) {
        const r = await a11yCall(req, timeout);
        if (r.blocked) throw new Error(blockedMessage(r.package));
        if (r.declined) throw new Error(`用户拒绝了点击敏感按钮「${r.label}」。不要重试，请询问用户下一步怎么做。`);
        if (!r.ok) throw new Error(r.error || '无障碍操作失败');
        return r;
    },

    async dump() {
        const r = await a11yCall({ cmd: 'dump' });
        if (r.blocked) return { blocked: r.package };
        if (!r.ok) throw new Error('读取界面失败：' + (r.error || '未知错误'));
        screenW = r.w; screenH = r.h;
        const nodes = r.nodes.map((o, i) => {
            const f = o.f || '';
            return {
                text: o.t || '', desc: o.d || '', hint: o.h || '', rid: (o.id || '').split('/').pop(),
                cls: (o.c || '').split('.').pop(), edit: f.includes('E'),
                clickable: /[CL]/.test(f), scrollable: f.includes('S'), checkable: f.includes('K'), checked: f.includes('k'),
                selected: f.includes('s'), focused: f.includes('F'), enabled: !f.includes('D'),
                b: o.b, p: o.p, texts: [], ref: { gen: r.gen, i },
            };
        });
        for (const n of nodes) n.parent = n.p >= 0 ? nodes[n.p] : null;
        return { pkg: r.package, nodes };
    },

    // 敏感按钮的确认由服务端完成（弹框等待用户），这里给足超时
    tap(it, x, y, long, reason) {
        if (it && it.ref) return this.call({ cmd: 'click', ...it.ref, long: !!long, reason }, 75000);
        return this.call({ cmd: 'tap', x, y, long: !!long, reason }, 75000);
    },

    swipe(x1, y1, x2, y2) {
        return this.call({ cmd: 'swipe', x1, y1, x2, y2, ms: 300 });
    },

    // 直接设置输入框内容，不切换输入法
    input(it, text, clear, submit) {
        const req = { cmd: 'input', text, clear: !!clear, submit: !!submit };
        if (it && it.ref) Object.assign(req, it.ref);
        return this.call(req);
    },

    key(k) {
        const action = A11Y_KEYS[k];
        if (!action) throw new Error(`无障碍模式不支持按键 ${k}，可用：${Object.keys(A11Y_KEYS).join(',')}`);
        return this.call({ cmd: 'global', action });
    },

    launch(pkg) {
        return this.call({ cmd: 'launch', package: pkg });
    },

    canScreenshot() { return !!(this.info && this.info.screenshot); },

    async screenshot(maxSide) {
        const r = await this.call({ cmd: 'screenshot', maxSide }, 15000);
        return { png: Buffer.from(r.png, 'base64'), width: r.w, height: r.h, scale: r.scale };
    },
};

// ======================= adb 后端（高级模式） =======================

let serial = null;

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
    if (devs.length === 0) throw new Error(NO_BACKEND);
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

function foregroundApp() {
    try {
        const out = shell('dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2');
        const m = out.match(/\s([a-zA-Z0-9_.]+)\/[a-zA-Z0-9_.$]+/);
        return m ? m[1] : '?';
    } catch (e) { return '?'; }
}

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
            text: a.text || '', desc: a['content-desc'] || '', hint: '', rid: (a['resource-id'] || '').split('/').pop(),
            cls: (a.class || '').split('.').pop(), edit: false,
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

const ADB_KEYS = {
    back: 4, home: 3, recent: 187, enter: 66, delete: 67, search: 84, menu: 82,
    volume_up: 24, volume_down: 25, power: 26, space: 62, tab: 61, paste: 279,
};

/** adb 模式下的敏感检查：前台应用在名单中则拒绝；敏感按钮借无障碍服务的确认框，服务未开启则交给用户。 */
async function adbGuard(label, reason) {
    const pkg = foregroundApp();
    if (SENSITIVE_APPS.has(pkg)) throw new Error(blockedMessage(pkg));
    if (!label || !SENSITIVE_LABEL.test(label)) return;
    let r;
    try {
        r = await a11yCall({ cmd: 'confirm', label, reason }, 75000);
    } catch (e) {
        throw new Error(`「${label}」是敏感按钮（付款、转账、删除等），需要用户确认，但无障碍服务未开启无法弹出确认框。请让用户自己点击。`);
    }
    if (!r.allowed) throw new Error(`用户拒绝了点击敏感按钮「${label}」。不要重试，请询问用户下一步怎么做。`);
}

const adbBackend = {
    name: 'adb',

    async available() {
        if (!ADB_ENABLED || Date.now() >= Number(process.env.CCM_ADB_UNTIL || 0)) return false;
        try { ensureDevice(); return true; } catch (e) { return false; }
    },

    async dump() {
        const pkg = foregroundApp();
        if (SENSITIVE_APPS.has(pkg)) return { blocked: pkg };
        return { pkg, nodes: parseUi(dumpUiXml()) };
    },

    async tap(it, x, y, long, reason) {
        await adbGuard(it ? it.label : '', reason);
        if (long) shell(`input swipe ${x} ${y} ${x} ${y} 800`);
        else shell(`input tap ${x} ${y}`);
    },

    async swipe(x1, y1, x2, y2) {
        shell(`input swipe ${x1} ${y1} ${x2} ${y2} 300`);
    },

    async input(it, text, clear, submit) {
        if (SENSITIVE_APPS.has(foregroundApp())) throw new Error(blockedMessage(foregroundApp()));
        if (it) { shell(`input tap ${it.cx} ${it.cy}`); await wait(500); }
        imeInput(text, clear, submit);
    },

    async key(k) {
        if (k === 'notifications') return shell('cmd statusbar expand-notifications');
        if (k === 'quick_settings') return shell('cmd statusbar expand-settings');
        if (!(k in ADB_KEYS)) throw new Error('不支持的按键：' + k + '，可用：' + Object.keys(ADB_KEYS).join(',') + ',notifications,quick_settings');
        shell(`input keyevent ${ADB_KEYS[k]}`);
    },

    async launch(pkg) {
        let comp = '';
        try { comp = shell(`cmd package resolve-activity --brief ${pkg} | tail -1`).trim(); } catch (e) { /* 退回 monkey */ }
        if (comp.includes('/')) shell(`am start -n ${comp}`);
        else shell(`monkey -p ${pkg} -c android.intent.category.LAUNCHER 1`);
    },

    canScreenshot() { return true; },

    async screenshot(maxSide) {
        if (SENSITIVE_APPS.has(foregroundApp())) throw new Error(blockedMessage(foregroundApp()));
        return captureScreen(maxSide);
    },
};

// ---------- adb 输入法 ----------
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

function imeInput(text, clear, submit) {
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
        const extra = `${clear ? ' --ez clear true' : ''}${submit ? ' --ez submit true' : ''}`;
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
        if (submit) shell(`input keyevent ${ADB_KEYS.enter}`);
    }
}

// ---------- adb 截图（缩小后编码为 PNG，零依赖） ----------

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

// ======================= 后端选择 =======================

let backend = null;

/** 优先无障碍；只有用户开启了高级模式（无线调试）才会用 adb。 */
async function pickBackend() {
    if (await a11y.available()) return (backend = a11y);
    if (await adbBackend.available()) return (backend = adbBackend);
    throw new Error(NO_BACKEND);
}

// ---------- 读屏 ----------

async function buildScreen() {
    const dump = await backend.dump();
    if (dump.blocked) {
        lastItems = new Map();
        return blockedMessage(dump.blocked);
    }
    const nodes = dump.nodes;
    const area = n => Math.max(0, n.b[2] - n.b[0]) * Math.max(0, n.b[3] - n.b[1]);
    const screenArea = screenW * screenH;
    const visible = n => area(n) > 0 && n.b[2] > 0 && n.b[3] > 0 && n.b[0] < screenW && n.b[1] < screenH;
    const isEdit = n => n.edit || /EditText|AutoCompleteTextView|SearchView/.test(n.cls);
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
            kind = '输入框'; label = own || n.hint || n.texts.join(' / ') || n.rid || '(空)';
        } else if (isTarget(n)) {
            kind = n.checkable ? (n.checked ? '开关:开' : '开关:关') : '按钮';
            label = [own, ...n.texts].filter(Boolean).join(' / ') || (n.rid ? 'id:' + n.rid : '无文字图标');
        } else if (n.scrollable && area(n) > screenArea * 0.1) {
            kind = '滚动区'; label = n.rid || n.cls;
        } else if (own) {
            kind = '文本'; label = own;
        } else continue;
        const fullLabel = label;
        label = clip(label);
        const cx = (n.b[0] + n.b[2]) >> 1, cy = (n.b[1] + n.b[3]) >> 1;
        const key = kind + label + (cx >> 4) + ',' + (cy >> 4);
        if (seen.has(key)) continue;
        seen.add(key);
        let flags = '';
        if (n.selected) flags += ' *选中';
        if (n.focused && isEdit(n)) flags += ' *焦点';
        if (!n.enabled) flags += ' (不可用)';
        items.push({ kind, label, fullLabel, cx, cy, b: n.b, flags, ref: n.ref });
        if (items.length >= MAX_ITEMS) break;
    }

    lastItems = new Map();
    const lines = [`App: ${dump.pkg || '?'}  屏幕 ${screenW}x${screenH}`];
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

function item(id) {
    const it = lastItems.get(Number(id));
    if (!it) throw new Error(`编号 ${id} 不存在，请先调用 phone_screen 获取最新编号`);
    return { ...it, label: it.fullLabel };
}

async function tap(args) {
    if (args.id != null) {
        const it = item(args.id);
        return backend.tap(it, it.cx, it.cy, args.long, args.reason);
    }
    if (args.x != null && args.y != null) return backend.tap(null, Math.round(args.x), Math.round(args.y), args.long, args.reason);
    throw new Error('需要 id，或者 x 和 y');
}

async function swipe(args) {
    const d = Math.min(Math.max(Number(args.distance) || 0.5, 0.1), 0.9);
    const box = args.id != null ? item(args.id).b : [0, 0, screenW, screenH];
    const cx = (box[0] + box[2]) >> 1, cy = (box[1] + box[3]) >> 1;
    const w = box[2] - box[0], h = box[3] - box[1];
    let x1, y1, x2, y2;
    switch (args.direction) {
        case 'up': x1 = x2 = cx; y1 = cy + h * d / 2; y2 = cy - h * d / 2; break;
        case 'down': x1 = x2 = cx; y1 = cy - h * d / 2; y2 = cy + h * d / 2; break;
        case 'left': y1 = y2 = cy; x1 = cx + w * d / 2; x2 = cx - w * d / 2; break;
        case 'right': y1 = y2 = cy; x1 = cx - w * d / 2; x2 = cx + w * d / 2; break;
        default: throw new Error('direction 只能是 up/down/left/right');
    }
    return backend.swipe(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
}

async function inputText(args) {
    const it = args.id != null ? item(args.id) : null;
    return backend.input(it, String(args.text ?? ''), args.clear, args.submit);
}

function key(args) {
    return backend.key(String(args.key || '').toLowerCase());
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

async function launch(args) {
    const pkg = resolvePackage(args.app);
    if (SENSITIVE_APPS.has(pkg)) throw new Error(blockedMessage(pkg));
    await backend.launch(pkg);
    return pkg;
}

// ---------- 截图 ----------

async function screenshot(args) {
    let src = backend;
    if (!src.canScreenshot()) {
        // 无障碍截图需要 Android 11+；高级模式开启时退回 adb
        if (src !== adbBackend && await adbBackend.available()) src = adbBackend;
        else throw new Error('本机系统低于 Android 11，无障碍服务无法截图。可只用 phone_screen 读控件。');
    }
    const shot = await src.screenshot(1280);
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
const reasonProp = { reason: { type: 'string', description: '点击原因；遇到付款/转账/删除等敏感按钮时会显示在用户的确认框里' } };

const TOOLS = [
    {
        name: 'phone_screen',
        description: '读取当前屏幕，返回压缩后的控件列表：[编号] 类型「文字」。操作控件时用编号。',
        inputSchema: { type: 'object', properties: {} },
        run: () => buildScreen(),
    },
    {
        name: 'phone_tap',
        description: '点击控件（按编号，或 x/y 坐标）。long=true 为长按。付款、转账、删除等敏感按钮会弹框请用户确认，用户拒绝则返回错误。',
        inputSchema: { type: 'object', properties: { ...target, long: { type: 'boolean' }, ...reasonProp, ...screenProp } },
        run: async a => { await tap(a); return null; },
    },
    {
        name: 'phone_input',
        description: '在输入框输入文字（支持中文，不会切换用户的键盘）。传 id 指定输入框，否则输入到当前焦点；clear 先清空；submit 输入后回车/搜索/发送。',
        inputSchema: {
            type: 'object', required: ['text'],
            properties: { text: { type: 'string' }, id: target.id, clear: { type: 'boolean' }, submit: { type: 'boolean' }, ...screenProp },
        },
        run: async a => { await inputText(a); return null; },
    },
    {
        name: 'phone_swipe',
        description: '滑动。direction=up 表示手指上滑（查看下方内容）。可传滚动区编号 id；distance 为 0.1~0.9 的比例。',
        inputSchema: {
            type: 'object', required: ['direction'],
            properties: { direction: { type: 'string', enum: ['up', 'down', 'left', 'right'] }, id: { type: 'integer' }, distance: { type: 'number' }, ...screenProp },
        },
        run: async a => { await swipe(a); return null; },
    },
    {
        name: 'phone_key',
        description: ADB_ENABLED
            ? '按键：back home recent enter search notifications quick_settings power lock（高级模式另有 delete volume_up volume_down 等）'
            : '按键：back home recent enter search notifications quick_settings power lock',
        inputSchema: { type: 'object', required: ['key'], properties: { key: { type: 'string' }, ...screenProp } },
        run: async a => { await key(a); return null; },
    },
    {
        name: 'phone_launch',
        description: '打开应用，app 填中文名（如 微信）或包名。',
        inputSchema: { type: 'object', required: ['app'], properties: { app: { type: 'string' }, ...screenProp } },
        run: async a => `已启动 ${await launch(a)}`,
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
        run: async a => {
            const log = [];
            for (const [i, act] of (a.actions || []).entries()) {
                if (act.tap != null) await tap({ id: act.tap, long: act.long, reason: act.reason });
                else if (act.x != null) await tap(act);
                else if (act.input != null) await inputText({ text: act.input, id: act.id, clear: act.clear, submit: act.submit });
                else if (act.key) await key(act);
                else if (act.swipe) await swipe({ direction: act.swipe, id: act.id, distance: act.distance });
                else if (act.launch) { await launch({ app: act.launch }); await wait(1500); }
                else if (act.wait) { await wait(Math.min(Number(act.wait), 10) * 1000); continue; }
                else throw new Error(`第 ${i + 1} 个动作无法识别：${JSON.stringify(act)}`);
                log.push(Object.keys(act)[0]);
                await wait(act.wait_after ? act.wait_after * 1000 : 600);
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
        noBackend: true,
    },
    {
        name: 'phone_screenshot',
        description: '截图并把图片交给你查看（已缩小，坐标需按返回的倍数换算）。仅在 phone_screen 拿不到控件（游戏/图片/视频）或需要看画面内容时使用。',
        inputSchema: { type: 'object', properties: { question: { type: 'string' } } },
        run: a => screenshot(a),
        noScreen: true,
    },
];

// adb shell 的危险命令：可能让手机无法正常使用（改分辨率、密度、锁屏、恢复出厂等），一律拒绝
const SHELL_FORBIDDEN = [
    // 查询（wm size）和恢复默认（wm size reset）放行
    [/\bwm\s+(size|density|overscan|scaling)\s+(?!reset\b)\S/, '修改分辨率 / DPI / 屏幕缩放'],
    [/\bwm\s+(set-user-rotation|fixed-to-user-rotation)\b/, '锁定屏幕方向'],
    [/\breboot\b.*\b(recovery|bootloader|fastboot|edl|sideload)\b/, '重启进入 recovery / bootloader'],
    [/\blocksettings\b/, '修改锁屏密码'],
    [/\b(wipe|factory[_-]?reset|recovery\s+--wipe|MASTER_CLEAR)\b/i, '恢复出厂 / 清除数据'],
    [/\b(dd|mkfs\w*|fastboot)\b/, '写入分区'],
    [/\brm\s+(-\w+\s+)*\/(system|vendor|data|sdcard|storage)?\s*($|[;&|])/, '删除系统目录'],
    [/\bsettings\s+put\s+\w+\s+(display_density_forced|display_size_forced|user_rotation|adb_enabled|development_settings_enabled|lock_screen\w*|screen_off_timeout)\b/, '修改显示 / 安全相关系统设置'],
    [/\bcmd\s+(overlay|display)\b/, '修改显示配置'],
];
// 可能造成数据丢失或系统行为改变的命令：执行前由无障碍服务弹框请用户确认
const SHELL_CONFIRM = /\b(pm\s+(uninstall|clear|disable|disable-user|hide|suspend|revoke|reset-permissions)|cmd\s+package\s+(uninstall|clear|disable|suspend)|settings\s+(put|delete)|svc\b|setprop\b|reboot\b|am\s+(force-stop|kill-all)|appops\s+set|cmd\s+(wifi|bluetooth_manager|connectivity)|rm\s|mv\s|chmod\s|input\s+keyevent\s+(26|223|276))/;

async function guardShell(cmd) {
    for (const [re, what] of SHELL_FORBIDDEN) {
        if (re.test(cmd)) {
            throw new Error(`已拒绝：该命令会${what}，可能让手机无法正常使用，CCMobile 不允许 AI 执行。如确有需要，请让用户自己在终端里执行。`);
        }
    }
    if (!SHELL_CONFIRM.test(cmd)) return;
    let r;
    try {
        r = await a11yCall({ cmd: 'confirm', label: 'adb shell ' + cmd.slice(0, 200), reason: '危险命令' }, 75000);
    } catch (e) {
        throw new Error('该命令可能删除数据或改变系统设置，需要用户确认，但无障碍服务未开启无法弹出确认框。请让用户自己执行。');
    }
    if (!r.allowed) throw new Error('用户拒绝执行该命令。不要重试，请询问用户。');
}

// adb shell 权限很大，只在用户开启高级模式（无线调试）时提供
if (ADB_ENABLED) {
    TOOLS.push({
        name: 'phone_shell',
        description: '执行 adb shell 查询类命令（高级模式，如 dumpsys / pm list / getprop / am start）。'
            + '修改分辨率、DPI、锁屏、恢复出厂等命令会被拒绝；卸载、清数据、settings put 等需用户在屏幕上确认。',
        inputSchema: { type: 'object', required: ['cmd'], properties: { cmd: { type: 'string' } } },
        run: async a => {
            if (!(await adbBackend.available())) throw new Error('无线调试未连接，phone_shell 不可用。请让用户在 CCMobile「手机控制」→ 高级里重新连接。');
            const cmd = String(a.cmd);
            await guardShell(cmd);
            return shell(cmd, { timeout: 60000 }).slice(0, 8000) || '(无输出)';
        },
        noScreen: true,
        noBackend: true,
    });
}

async function callTool(name, args) {
    const tool = TOOLS.find(t => t.name === name);
    if (!tool) throw new Error('未知工具 ' + name);
    if (!tool.noBackend) await pickBackend();
    const result = await tool.run(args || {});
    if (tool.name === 'phone_screen' || tool.noScreen) return result;
    if (args?.screen === false) return result || '完成';
    // 无障碍读屏会在服务端等界面安静，这里只需短暂等待
    await wait(tool.wait || (backend === a11y ? 400 : 900));
    return (result ? result + '\n\n' : '') + await buildScreen();
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
                serverInfo: { name: 'ccmobile-phone', version: '0.2.0' },
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
process.stdin.on('end', () => queue.then(() => process.exit(0)));
