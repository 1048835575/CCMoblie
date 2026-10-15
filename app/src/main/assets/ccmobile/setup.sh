#!/data/data/com.termux/files/usr/bin/bash
# CCMobile 首次安装脚本：安装 Node.js / adb / ripgrep，再安装 Claude Code。
# 由 App 首页「安装开发环境」在后台调用，也可在终端执行 `ccm-setup` 重装。

CCM_DIR="$HOME/.ccmobile"
[ -f "$CCM_DIR/config.env" ] && . "$CCM_DIR/config.env"
export CCM_SETUP_RUNNING=1

# 双语提示：L "中文" "English"，按 CCM_LANG 选择
L() { if [ "$CCM_LANG" = "en" ]; then printf '%s' "$2"; else printf '%s' "$1"; fi; }
c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
# App 首页解析 "##STEP 序号/总数 描述" 行来显示进度
step()   { echo "##STEP $1/6 $2"; }
c_info() { printf '\033[36m==> %s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*"; }

fail() {
    c_err "$(L "安装失败：$*" "Install failed: $*")"
    c_err "$(L "检查网络后在 App 里点「重试」；也可在设置里切换镜像源。" "Check your network and tap 'Retry' in the app; you can also switch mirrors in Settings.")"
    exit 1
}

echo
c_ok "$(L "======== CCMobile 首次安装 ========" "======== CCMobile setup ========")"
echo "$(L "预计需要 3~10 分钟，请保持网络畅通。" "This takes about 3–10 minutes. Please stay online.")"
echo

# 1. 软件源镜像
step 1 "$(L "配置软件源" "Configure package mirror")"
mirror_url() {
    case "$1" in
        tuna)    echo "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main" ;;
        ustc)    echo "https://mirrors.ustc.edu.cn/termux/apt/termux-main" ;;
        aliyun)  echo "https://mirrors.aliyun.com/termux/termux-main" ;;
        *)       echo "https://packages-cf.termux.dev/apt/termux-main" ;;
    esac
}
# 用户选的镜像优先，失败（如镜像正在同步、索引大小不符）时依次换下一个
MIRRORS=()
for m in "${CCM_APT_MIRROR:-tuna}" tuna ustc aliyun official; do
    case " ${MIRRORS[*]} " in *" $m "*) ;; *) MIRRORS+=("$m") ;; esac
done
MI=0
use_mirror() {
    MIRROR="$(mirror_url "${MIRRORS[$MI]}")"
    c_info "$(L "使用软件源：$MIRROR" "Using mirror: $MIRROR")"
    echo "deb $MIRROR stable main" > "$PREFIX/etc/apt/sources.list"
    rm -rf "$PREFIX/var/lib/apt/lists/partial/"* 2>/dev/null
}
# 避免 termux-change-repo 的 mirrors 选择覆盖我们的配置
rm -f "$PREFIX/etc/termux/chosen_mirrors" 2>/dev/null
use_mirror

export DEBIAN_FRONTEND=noninteractive
APT_OPTS="-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
APT_LOG="$PREFIX/tmp/ccm-apt.log"
mkdir -p "$PREFIX/tmp"

# apt 有些失败（索引下载出错）退出码仍为 0，所以同时检查输出里的 E:/Err: 行
apt_ok() {
    "$@" 2>&1 | tee "$APT_LOG"
    [ "${PIPESTATUS[0]}" = 0 ] && ! grep -qE '^(E|Err):' "$APT_LOG"
}

# 在当前镜像执行，失败则换下一个镜像（重新 update）后再试
apt_retry() {
    local what="$1"; shift
    while :; do
        apt_ok "$@" && return 0
        MI=$((MI + 1))
        [ "$MI" -lt "${#MIRRORS[@]}" ] || fail "$what"
        c_err "$(L "当前镜像失败，换下一个镜像重试…" "This mirror failed, trying the next one…")"
        use_mirror
        [ "$1" = apt-get ] && [ "$2" = update ] && continue
        apt_ok apt-get update $APT_OPTS || continue
    done
}

step 2 "$(L "更新软件包索引" "Update package index")"
apt_retry "apt update" apt-get update $APT_OPTS
step 3 "$(L "升级基础包" "Upgrade base packages")"
apt_retry "apt upgrade" apt-get upgrade $APT_OPTS

step 4 "$(L "安装 Node.js / adb / ripgrep" "Install Node.js / adb / ripgrep")"
apt_retry "apt install" apt-get install $APT_OPTS \
    nodejs-lts android-tools ripgrep git curl termux-tools

command -v node >/dev/null || fail "$(L "node 未安装成功" "node was not installed")"
command -v adb  >/dev/null || fail "$(L "adb 未安装成功" "adb was not installed")"
c_ok "node $(node --version), $(adb version | head -1)"

# 2. npm 源 + Claude Code
case "${CCM_NPM_REGISTRY:-npmmirror}" in
    npmmirror) REG="https://registry.npmmirror.com" ;;
    *)         REG="https://registry.npmjs.org" ;;
esac
npm config set registry "$REG" >/dev/null

# 默认只装最新版（官方原生程序）；最新版装不上或本机不支持时，才装兼容版 2.1.112（Node 运行）。
# 最新版只能在 ARM64 上运行：x86_64 安卓会用 seccomp 拦下它用到的旧系统调用。
NATIVE_OK=0
if [ "$CCM_CC_CHANNEL" = "latest" ] && [ "$(uname -m)" = "aarch64" ]; then
    step 5 "$(L "安装最新版 Claude Code（约 110MB）" "Install latest Claude Code (~110MB)")"
    # 新版本刚发布时镜像可能还没同步完，失败后等一会儿再试一次
    if bash "$CCM_DIR/bin/ccm-native" install || { sleep 5; bash "$CCM_DIR/bin/ccm-native" install; }; then
        NATIVE_OK=1
    else
        c_err "$(L "最新版安装失败，改装兼容版 2.1.112（自动模式改为完全放行）；App 会在后台重试最新版" "Installing Latest failed; installing 2.1.112 instead (auto mode becomes bypass). The app will retry Latest in the background")"
    fi
elif [ "$CCM_CC_CHANNEL" = "latest" ]; then
    c_info "$(L "本机是 $(uname -m) 架构，最新版暂不支持，使用兼容版 2.1.112（自动模式改为完全放行）" "This device is $(uname -m); Latest isn't supported, using 2.1.112 (auto mode becomes bypass)")"
fi
if [ "$NATIVE_OK" = 0 ]; then
    step 5 "$(L "安装兼容版 Claude Code ${CCM_CC_VERSION:-2.1.112}" "Install compatible Claude Code ${CCM_CC_VERSION:-2.1.112}")"
    bash "$CCM_DIR/bin/ccm-compat" || fail "npm install claude-code"
fi

step 6 "$(L "写入配置" "Write configuration")"
# 3. 首次运行状态：跳过引导、信任工作目录
mkdir -p "$HOME/.claude" "$CCM_DIR/workspace/shots"
if [ ! -f "$HOME/.claude.json" ]; then
    cat > "$HOME/.claude.json" <<EOF
{
  "hasCompletedOnboarding": true,
  "theme": "dark",
  "projects": {
    "$CCM_DIR/workspace": { "hasTrustDialogAccepted": true, "allowedTools": [] }
  }
}
EOF
fi
if [ ! -f "$CCM_DIR/workspace/CLAUDE.md" ]; then
    if [ "$CCM_LANG" = "en" ]; then cp "$CCM_DIR/CLAUDE.en.md" "$CCM_DIR/workspace/CLAUDE.md"; else cp "$CCM_DIR/CLAUDE.md" "$CCM_DIR/workspace/CLAUDE.md"; fi
fi

touch "$CCM_DIR/.installed"
echo
c_ok "$(L "======== 安装完成 ========" "======== Setup complete ========")"
echo "##DONE"
