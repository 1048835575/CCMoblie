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
case "${CCM_APT_MIRROR:-tuna}" in
    tuna)    MIRROR="https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main" ;;
    ustc)    MIRROR="https://mirrors.ustc.edu.cn/termux/apt/termux-main" ;;
    aliyun)  MIRROR="https://mirrors.aliyun.com/termux/termux-main" ;;
    *)       MIRROR="https://packages-cf.termux.dev/apt/termux-main" ;;
esac
c_info "$(L "使用软件源：$MIRROR" "Using mirror: $MIRROR")"
echo "deb $MIRROR stable main" > "$PREFIX/etc/apt/sources.list"
# 避免 termux-change-repo 的 mirrors 选择覆盖我们的配置
rm -f "$PREFIX/etc/termux/chosen_mirrors" 2>/dev/null

APT_OPTS="-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"

step 2 "$(L "更新软件包索引" "Update package index")"
apt-get update $APT_OPTS || fail "apt update"
step 3 "$(L "升级基础包" "Upgrade base packages")"
DEBIAN_FRONTEND=noninteractive apt-get upgrade $APT_OPTS || fail "apt upgrade"

step 4 "$(L "安装 Node.js / adb / ripgrep" "Install Node.js / adb / ripgrep")"
DEBIAN_FRONTEND=noninteractive apt-get install $APT_OPTS \
    nodejs-lts android-tools ripgrep git curl termux-tools || fail "apt install"

command -v node >/dev/null || fail "$(L "node 未安装成功" "node was not installed")"
command -v adb  >/dev/null || fail "$(L "adb 未安装成功" "adb was not installed")"
c_ok "node $(node --version), $(adb version | head -1)"

# 2. npm 源 + Claude Code
case "${CCM_NPM_REGISTRY:-npmmirror}" in
    npmmirror) REG="https://registry.npmmirror.com" ;;
    *)         REG="https://registry.npmjs.org" ;;
esac
npm config set registry "$REG" >/dev/null
CC_VER="${CCM_CC_VERSION:-2.1.112}"
step 5 "$(L "安装 Claude Code $CC_VER" "Install Claude Code $CC_VER")"
c_info "Claude Code @$CC_VER (registry: $REG)"
# sharp 等可选原生依赖在 Android 上装不上，忽略即可
npm install -g --no-fund --no-audit --omit=optional "@anthropic-ai/claude-code@$CC_VER" || fail "npm install claude-code"

CLI_JS="$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js"
if [ ! -f "$CLI_JS" ]; then
    c_err "$(L "警告：@$CC_VER 不是纯 JS 版本（2.1.113 起改为原生二进制，无法直接用 Node 运行）。" "Warning: @$CC_VER is not a JS build (2.1.113+ ships native binaries that Node cannot run).")"
    fail "$(L "请在设置里把稳定版 Claude Code 版本号改回 2.1.112" "Set the stable Claude Code version back to 2.1.112 in Settings")"
fi

# 稳定版始终保留，作为最新版不可用时的回退
if [ "$CCM_CC_CHANNEL" = "latest" ]; then
    step 5 "$(L "安装最新版 Claude Code（实验性，约 110MB）" "Install latest Claude Code (experimental, ~110MB)")"
    if ! bash "$CCM_DIR/bin/ccm-native" install; then
        c_err "$(L "最新版安装失败，将使用稳定版 2.1.112；可稍后在设置中重试" "Installing Latest failed; using Stable 2.1.112. You can retry later in Settings")"
    fi
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
