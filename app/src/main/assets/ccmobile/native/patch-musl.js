// 生成适配 Android 的 musl 动态加载器副本（Android 没有 /etc/resolv.conf）：
//   1. DNS 配置路径 /etc/resolv.conf → /proc/self/fd/9（启动脚本把 App 写的 resolv.conf 打开为 fd 9）
//   2. 读不到配置时的默认 DNS 127.0.0.1 → 指定的公共 DNS（字节数不能超过原串）
// 用法：node patch-musl.js <原始加载器> <输出路径> <DNS，如 223.5.5.5>
'use strict';
const fs = require('fs');
const [src, dest, dns = '223.5.5.5'] = process.argv.slice(2);
if (!src || !dest) { console.error('用法：node patch-musl.js <src> <dest> [dns]'); process.exit(2); }
if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(dns) || dns.length > 9) {
    console.error(`DNS 需为不超过 9 个字符的 IPv4 地址（如 223.5.5.5、8.8.8.8、1.1.1.1），收到：${dns}`);
    process.exit(2);
}
const b = fs.readFileSync(src);

function replaceOnce(from, to) {
    const f = Buffer.from(from + '\0');
    const t = Buffer.alloc(f.length);
    Buffer.from(to + '\0').copy(t);
    const i = b.indexOf(f);
    if (i < 0 || b.indexOf(f, i + 1) >= 0) throw new Error(`在加载器中定位「${from}」失败（应恰好出现一次）`);
    t.copy(b, i);
}

replaceOnce('/etc/resolv.conf', '/proc/self/fd/9');
replaceOnce('127.0.0.1', dns);
const tmp = dest + '.tmp';
fs.writeFileSync(tmp, b, { mode: 0o700 });
fs.renameSync(tmp, dest);
console.log(`已生成 ${dest}（默认 DNS ${dns}）`);
