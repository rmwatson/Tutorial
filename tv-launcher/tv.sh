#!/usr/bin/env bash
# My Home TV launcher helper for Mac and Linux.
#
#   ./tv.sh backup     Save a snapshot of the TV's apps and settings (changes nothing)
#   ./tv.sh install    Back up, then install My Home and switch on its Home-button service
#   ./tv.sh revert     Put the TV back how it was: switch the service off and uninstall My Home
#   ./tv.sh status     Show what's installed and switched on right now
#   ./tv.sh audit      List every app installed and running on the TV (changes nothing)
#   ./tv.sh remove <app> [app…]
#                      Back up, then switch off apps for your profile (restorable)
#   ./tv.sh restore-apps
#                      Bring back every app removed with 'remove'
#   ./tv.sh ha-setup <address> <token>
#                      Connect My Home to Home Assistant (e.g. 192.168.1.5:8123 and a long-lived token)
#
# Settings (optional):  TV=192.168.1.42  PORT=5555  APK=/path/to/MyHome.apk
set -euo pipefail

TV="${TV:-192.168.1.42}"
PORT="${PORT:-5555}"
PKG="com.myhome.tvlauncher"
SERVICE="$PKG/$PKG.HomeButtonService"
APK_URL="https://github.com/rmwatson/Tutorial/releases/download/my-home-latest/MyHome.apk"
DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP="$DIR/tv-backup"
ADB=""
SERIAL="$TV:$PORT"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

find_adb() {
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
  if [ -x "$DIR/platform-tools/adb" ]; then ADB="$DIR/platform-tools/adb"; return; fi
  say "Downloading Google's Android platform tools (adb)…"
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    *) os=linux ;;
  esac
  curl -fL -o "$DIR/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
  unzip -q -o "$DIR/platform-tools.zip" -d "$DIR"
  rm -f "$DIR/platform-tools.zip"
  ADB="$DIR/platform-tools/adb"
}

tvsh() { "$ADB" -s "$SERIAL" shell "$@" | tr -d '\r'; }

connect() {
  find_adb
  say "Connecting to the TV at $SERIAL…"
  "$ADB" connect "$SERIAL" >/dev/null || true
  for _ in $(seq 1 60); do
    state="$("$ADB" -s "$SERIAL" get-state 2>&1 || true)"
    case "$state" in
      *unauthorized*) echo "Look at the TV: choose 'Always allow from this computer' and press OK. Waiting…" ;;
      device) echo "Connected."; return ;;
      *) "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true ;;
    esac
    sleep 2
  done
  echo "Couldn't connect to $SERIAL."
  echo "Check: same Wi-Fi, TV switched on, Settings > System > Developer options > USB debugging is ON."
  echo "If the TV shows 'Wireless debugging' with its own port, run e.g.:  PORT=37123 ./tv.sh $1"
  exit 1
}

take_snapshot() {
  local out="$1"
  mkdir -p "$out"
  tvsh getprop ro.product.model > "$out/model.txt"
  tvsh getprop ro.build.fingerprint >> "$out/model.txt"
  tvsh pm list packages -f > "$out/packages.txt"
  tvsh pm list packages -d > "$out/packages-disabled.txt"
  tvsh settings list system > "$out/settings-system.txt"
  tvsh settings list secure > "$out/settings-secure.txt"
  tvsh settings list global > "$out/settings-global.txt"
  tvsh settings get secure enabled_accessibility_services > "$out/enabled_accessibility_services.txt"
  tvsh settings get secure accessibility_enabled > "$out/accessibility_enabled.txt"
  tvsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    > "$out/home-app.txt" 2>&1 || true
}

backup() {
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  say "Backing up the TV to tv-backup/$stamp …"
  take_snapshot "$BACKUP/$stamp"
  # The very first snapshot is kept forever as "original": revert always goes back to it.
  if [ ! -d "$BACKUP/original" ]; then
    cp -R "$BACKUP/$stamp" "$BACKUP/original"
    echo "Saved as the original state too (tv-backup/original)."
  fi
  echo "Model: $(head -1 "$BACKUP/$stamp/model.txt")"
  echo "Apps installed: $(wc -l < "$BACKUP/$stamp/packages.txt" | tr -d ' ')"
}

install() {
  backup
  local apk="${APK:-$DIR/MyHome.apk}"
  if [ -z "${APK:-}" ]; then
    say "Downloading the newest My Home build…"
    curl -fL -o "$apk" "$APK_URL"
  fi
  say "Installing My Home…"
  "$ADB" -s "$SERIAL" install -r "$apk"

  say "Switching on the Home-button service…"
  local cur; cur="$(tvsh settings get secure enabled_accessibility_services)"
  if [ -z "$cur" ] || [ "$cur" = "null" ]; then
    tvsh settings put secure enabled_accessibility_services "$SERVICE"
  elif [[ ":$cur:" != *":$SERVICE:"* ]]; then
    tvsh settings put secure enabled_accessibility_services "$cur:$SERVICE"
  fi
  tvsh settings put secure accessibility_enabled 1

  tvsh am start -n "$PKG/.MainActivity" >/dev/null
  say "Done! My Home is open on the TV. Press Home on the remote to try it."
  echo "To undo everything:  ./tv.sh revert"
}

revert() {
  say "Putting the TV back how it was…"
  restore_apps
  local cur new
  cur="$(tvsh settings get secure enabled_accessibility_services)"
  # Remove only our service, keeping anything else that's switched on.
  new="$(printf '%s' "$cur" | tr ':' '\n' | grep -v -x -F "$SERVICE" | grep -v -x 'null' | paste -sd: - || true)"
  if [ -z "$new" ]; then
    tvsh settings delete secure enabled_accessibility_services >/dev/null || true
  else
    tvsh settings put secure enabled_accessibility_services "$new"
  fi
  if [ -f "$BACKUP/original/accessibility_enabled.txt" ]; then
    local orig; orig="$(cat "$BACKUP/original/accessibility_enabled.txt")"
    if [ "$orig" = "null" ] || [ -z "$orig" ]; then
      tvsh settings delete secure accessibility_enabled >/dev/null || true
    else
      tvsh settings put secure accessibility_enabled "$orig"
    fi
  fi
  if tvsh pm list packages | grep -q -x "package:$PKG"; then
    "$ADB" -s "$SERIAL" uninstall "$PKG"
  fi
  tvsh input keyevent KEYCODE_HOME

  if [ -d "$BACKUP/original" ]; then
    local now="$BACKUP/after-revert-$(date +%Y%m%d-%H%M%S)"
    take_snapshot "$now"
    say "Compared with the original backup:"
    if diff <(sort "$BACKUP/original/packages.txt") <(sort "$now/packages.txt") >/dev/null \
      && diff "$BACKUP/original/enabled_accessibility_services.txt" "$now/enabled_accessibility_services.txt" >/dev/null; then
      echo "Apps and accessibility settings match the original. The TV is back to how it was."
    else
      echo "Differences (lines with < were there originally, > are there now):"
      diff <(sort "$BACKUP/original/packages.txt") <(sort "$now/packages.txt") || true
      diff "$BACKUP/original/enabled_accessibility_services.txt" "$now/enabled_accessibility_services.txt" || true
    fi
  fi
}

audit() {
  local out="$BACKUP/audit-$(date +%Y%m%d-%H%M%S).txt"
  mkdir -p "$BACKUP"
  {
    echo "== TV"; tvsh getprop ro.product.model; tvsh getprop ro.build.version.release
    echo; echo "== Apps you installed (package, installer)"; tvsh pm list packages -3 -i | sort
    echo; echo "== Built-in apps"; tvsh pm list packages -s | sort
    echo; echo "== Already switched off"; tvsh pm list packages -d | sort
    echo; echo "== Removed for this profile earlier"; [ -f "$BACKUP/removed-apps.txt" ] && cat "$BACKUP/removed-apps.txt" || echo "(none)"
    echo; echo "== Running right now"; tvsh ps -A -o USER,NAME | awk '$1 ~ /^u0_a/ {print $2}' | sort -u
    echo; echo "== Accessibility services"; tvsh settings get secure enabled_accessibility_services
    echo; echo "== Notification listeners"; tvsh settings get secure enabled_notification_listeners
    echo; echo "== Keyboard"; tvsh settings get secure default_input_method
    echo; echo "== Device admins"; tvsh dumpsys device_policy | grep -E '^ *Admin|ComponentInfo' | sed 's/^ *//' | sort -u || true
    echo; echo "== Allowed to draw over other apps"; tvsh cmd appops query-op SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
    echo; echo "== Allowed to install other apps"; tvsh cmd appops query-op REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
  } | tee "$out"
  say "Saved to ${out#$DIR/}"
}

remove_apps() {
  if [ $# -eq 0 ]; then echo "Usage: ./tv.sh remove <package> [package…]"; exit 1; fi
  backup
  say "Removing for your profile (restore any time with: ./tv.sh restore-apps)…"
  local pkg
  for pkg in "$@"; do
    if [ "$pkg" = "$PKG" ] || [ "$pkg" = "com.google.android.apps.tv.launcherx" ] || [ "$pkg" = "com.google.android.tvlauncher" ]; then
      echo "  skipped $pkg (needed by My Home or the TV)"; continue
    fi
    if ! tvsh pm list packages | grep -q -x "package:$pkg"; then
      echo "  $pkg is not installed – skipped"; continue
    fi
    if tvsh pm uninstall -k --user 0 "$pkg" | grep -q Success; then
      echo "$pkg" >> "$BACKUP/removed-apps.txt"
      echo "  removed $pkg"
    else
      echo "  couldn't remove $pkg – left as it was"
    fi
  done
}

restore_apps() {
  if [ ! -s "$BACKUP/removed-apps.txt" ]; then echo "No removed apps to restore."; return; fi
  say "Bringing back removed apps…"
  local pkg left=""
  while read -r pkg; do
    [ -z "$pkg" ] && continue
    if tvsh cmd package install-existing "$pkg" | grep -q -i "installed\|success"; then
      echo "  restored $pkg"
    else
      echo "  couldn't restore $pkg"; left="$left$pkg"$'\n'
    fi
  done < <(sort -u "$BACKUP/removed-apps.txt")
  printf '%s' "$left" > "$BACKUP/removed-apps.txt"
}

ha_setup() {
  local url="${1:-}" token="${2:-}"
  if [ -z "$url" ] || [ -z "$token" ]; then
    echo "Usage: ./tv.sh ha-setup <address> <token>"
    echo "  address: how you open Home Assistant, e.g. 192.168.1.5:8123"
    echo "  token:   Home Assistant > your profile > Security > Long-lived access tokens > Create token"
    exit 1
  fi
  say "Sending the Home Assistant address and token to My Home…"
  tvsh am start -n "$PKG/.MainActivity" --es ha_url "$url" --es ha_token "$token" >/dev/null
  echo "Done. The TV should say 'Home Assistant connected', and a Home button appears at the top."
}

status() {
  say "TV: $(tvsh getprop ro.product.model)"
  if tvsh pm list packages | grep -q -x "package:$PKG"; then
    echo "My Home: installed ($(tvsh dumpsys package "$PKG" | grep -m1 versionName | tr -d ' '))"
  else
    echo "My Home: not installed"
  fi
  echo "Accessibility services on: $(tvsh settings get secure enabled_accessibility_services)"
}

cmd="${1:-}"
case "$cmd" in
  backup|install|revert|status) connect "$cmd"; "$cmd" ;;
  ha-setup) connect "$cmd"; ha_setup "${2:-}" "${3:-}" ;;
  audit) connect "$cmd"; audit ;;
  remove) connect "$cmd"; shift; remove_apps "$@" ;;
  restore-apps) connect "$cmd"; restore_apps ;;
  *) sed -n '2,17p' "$0"; exit 1 ;;
esac
