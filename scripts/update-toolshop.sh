#!/bin/bash
# Updates the ToolShop plugin on the Minecraft server to the latest GitHub build.
#
#   sudo update-toolshop             check for an update and install it (asks before restarting)
#   sudo update-toolshop -y          same, without asking
#   sudo update-toolshop --rollback  put back the version that was there before the last update
#
# The server is restarted, so everyone online gets disconnected for about 20 seconds.

set -euo pipefail

URL="https://github.com/cowmoo03/toolshop/releases/download/latest/ToolShop.jar"
SERVER_DIR="/opt/minecraft/server"
SERVICE="minecraft"
RUN_AS="minecraft"
PLUGIN="$SERVER_DIR/plugins/ToolShop.jar"
SIDE_DIR="$SERVER_DIR/plugins-disabled"
STAGED="$SIDE_DIR/ToolShop-download.jar"
BACKUP="$SIDE_DIR/ToolShop-previous.jar"
STARTED_AT="$(date +%s)"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32mOK\033[0m  %s\n' "$*"; }
fail() { printf '\033[1;31mERROR\033[0m %s\n' "$*" >&2; exit 1; }

# Reads the version out of a plugin jar, e.g. "1.2.2".
jar_version() {
    unzip -p "$1" paper-plugin.yml 2>/dev/null | sed -n "s/^version: *['\"]\{0,1\}\([^'\"]*\)['\"]\{0,1\} *$/\1/p" | head -n1
}

wait_for_enable() {
    local want="$1"
    say "Waiting for ToolShop v$want to start (up to 90 seconds)..."
    for _ in $(seq 1 45); do
        sleep 2
        # Only look at server output since this restart, so an older run can't fool the check.
        if journalctl -u "$SERVICE" --since "@$STARTED_AT" --no-pager 2>/dev/null \
                | grep -q "Enabling ToolShop v$want"; then
            ok "ToolShop v$want is running."
            return 0
        fi
    done
    echo
    echo "ToolShop v$want didn't report starting. Last ToolShop/error lines since the restart:"
    journalctl -u "$SERVICE" --since "@$STARTED_AT" --no-pager 2>/dev/null \
        | grep -iE "toolshop|exception|error" | tail -n 15 || true
    echo
    echo "To go back to the previous version:  sudo update-toolshop --rollback"
    exit 1
}

[ "$(id -u)" -eq 0 ] || fail "Run it with sudo:  sudo update-toolshop"
command -v unzip >/dev/null || fail "unzip is missing. Install it with:  sudo apt install -y unzip"
[ -d "$SERVER_DIR/plugins" ] || fail "Can't find $SERVER_DIR/plugins"
sudo -u "$RUN_AS" mkdir -p "$SIDE_DIR"

ASSUME_YES=0
MODE="update"
for arg in "$@"; do
    case "$arg" in
        -y|--yes) ASSUME_YES=1 ;;
        --rollback) MODE="rollback" ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) fail "Unknown option: $arg (try --help)" ;;
    esac
done

confirm() {
    [ "$ASSUME_YES" -eq 1 ] && return 0
    read -r -p "$1 [y/N] " answer
    [[ "$answer" =~ ^[Yy]$ ]] || { echo "Cancelled. Nothing was changed."; exit 0; }
}

swap_in() {
    # $1 = jar to install
    say "Stopping the server (the world is saved first)..."
    systemctl stop "$SERVICE"
    STARTED_AT="$(date +%s)"
    install -o "$RUN_AS" -g "$RUN_AS" -m 644 "$1" "$PLUGIN"
    systemctl start "$SERVICE"
}

if [ "$MODE" = "rollback" ]; then
    [ -f "$BACKUP" ] || fail "There is no previous version saved at $BACKUP"
    old="$(jar_version "$BACKUP")"
    cur="$(jar_version "$PLUGIN" || true)"
    say "Installed: v${cur:-unknown}   Rolling back to: v$old"
    confirm "This restarts the server and disconnects everyone. Continue?"
    swap_in "$BACKUP"
    wait_for_enable "$old"
    exit 0
fi

say "Downloading the latest ToolShop build..."
rm -f "$STAGED"
sudo -u "$RUN_AS" curl -fsSL -o "$STAGED" "$URL" || fail "Download failed. Nothing was changed."

new="$(jar_version "$STAGED")"
[ -n "$new" ] || { rm -f "$STAGED"; fail "The download isn't a valid ToolShop jar. Nothing was changed."; }
cur=""
[ -f "$PLUGIN" ] && cur="$(jar_version "$PLUGIN")"
say "Installed: v${cur:-none}   Latest: v$new   ($(stat -c %s "$STAGED") bytes)"

if [ "$cur" = "$new" ]; then
    rm -f "$STAGED"
    ok "Already up to date. Nothing to do."
    exit 0
fi

confirm "This restarts the server and disconnects everyone for about 20 seconds. Continue?"

if [ -f "$PLUGIN" ]; then
    install -o "$RUN_AS" -g "$RUN_AS" -m 644 "$PLUGIN" "$BACKUP"
    say "Saved the current version (v${cur:-unknown}) for rollback."
fi
swap_in "$STAGED"
rm -f "$STAGED"
wait_for_enable "$new"
