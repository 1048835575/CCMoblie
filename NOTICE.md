# NOTICE

CCMobile is a modified version of [Termux](https://github.com/termux/termux-app),
licensed under the GNU General Public License v3.0 only (see [LICENSE.md](LICENSE.md)).
The full upstream history is preserved in this repository.

## Modifications (2026-10)

Compared with upstream Termux, CCMobile:

- Adds the `com.termux.ccm` package (`app/src/main/java/com/termux/ccm/`): home/chat UI, setup wizard,
  settings, accessibility service for phone control, wireless-debugging pairing (advanced, off by default),
  input method for automated typing, floating progress bubble,
  Claude Code control-protocol handling, projects/sessions, i18n.
- Adds assets under `app/src/main/assets/ccmobile/` (setup and launcher scripts, phone-control MCP server,
  musl loaders, translations) and resources prefixed `ccm_`.
- Changes the launcher activity to `CcmHomeActivity`, the app name to "CCMobile" and the launcher icon.
- `TermuxActivity` / `activity_termux.xml`: settings entry in the drawer, page-scroll toggle buttons.
- `TerminalView`: public `scrollPage()` / `scrollToBottom()`.
- `TermuxInstaller`: made public so the setup wizard can install the bootstrap.
- `TermuxApplication`: initializes the UI language.
- Build: optional NDK path via `ccm.ndk.dir`, release signing via `keystore.properties`, version 0.9.3.

The package name stays `com.termux` because Termux bootstrap binaries hard-code `/data/data/com.termux`.

## Third-party components

| Component | License | Notes |
|---|---|---|
| Termux App | GPLv3 | Base of this project |
| terminal-emulator, terminal-view | Apache-2.0 | See module directories |
| termux-shared | MIT (some files GPLv3) | See `termux-shared/LICENSE.md` |
| Termux bootstrap packages (bash, apt, coreutils, …) | Their respective licenses | Built by [termux-packages](https://github.com/termux/termux-packages), downloaded at build time |
| musl libc dynamic loader (Alpine `musl` 1.2.5) | MIT | `app/src/main/assets/ccmobile/native/`, see `MUSL-COPYRIGHT.txt`; patched at install time (DNS paths) |
| Markwon, AndroidX, Material Components | Apache-2.0 | Gradle dependencies |

## Not included

**Claude Code** is proprietary software by Anthropic PBC and is **not** part of this repository or the APK.
It is downloaded from the npm registry by the user during setup, and its use is governed by
[Anthropic's terms](https://www.anthropic.com/legal). CCMobile is not affiliated with, endorsed or sponsored by Anthropic.
"Claude" and "Claude Code" are trademarks of Anthropic.
