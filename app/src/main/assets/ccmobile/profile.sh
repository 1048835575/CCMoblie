# CCMobile：由 App 写入 $PREFIX/etc/profile.d/ccmobile.sh，每次打开终端时加载。
CCM_DIR="$HOME/.ccmobile"
[ -f "$CCM_DIR/config.env" ] && . "$CCM_DIR/config.env"
case ":$PATH:" in *":$CCM_DIR/bin:"*) ;; *) export PATH="$CCM_DIR/bin:$PATH" ;; esac

if [ -t 0 ] && [ -z "$CCM_INSIDE" ]; then
    if [ ! -f "$CCM_DIR/.installed" ]; then
        echo "CCMobile 开发环境未安装：回到 App 首页点「安装开发环境」，或在此执行 ccm-setup"
    elif [ "$CCM_AUTOSTART" = "1" ] && [ -n "$ANTHROPIC_AUTH_TOKEN" ]; then
        ccm
    fi
fi
