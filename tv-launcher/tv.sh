#!/usr/bin/env bash
# My Home TV launcher helper for Mac and Linux.
#
#   ./tv.sh backup     Save a snapshot of the TV's apps and settings (changes nothing)
#   ./tv.sh install    Back up, then install My Home and switch on its Home-button service
#   ./tv.sh revert     Put the TV back how it was: switch the service off and uninstall My Home
#   ./tv.sh status     Show what's installed and switched on right now
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
  for _ in $(seq 1 30); do
    state="$("$ADB" -s "$SERIAL" get-state 2>&1 || true)"
    case "$state" in
      *device*) echo "Connected."; return ;;
      *unauthorized*) echo "Look at the TV: choose 'Always allow from this computer' and press OK." ;;
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
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
