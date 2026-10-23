#!/system/bin/sh
# Magisk late_start service script for agentosd

MODDIR=${0%/*}
DAEMON_BIN="/system/bin/agentosd"
if [ ! -f "$DAEMON_BIN" ]; then
    DAEMON_BIN="$MODDIR/system/bin/agentosd"
fi

LOG_FILE="/data/local/tmp/agentosd.log"
PORT=19898
SOCKET="/data/local/tmp/agentosd.sock"

# Wait for boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Launch daemon loop with self-healing watchdog
while true; do
    if ! pgrep -f "$DAEMON_BIN" >/dev/null 2>&1; then
        echo "[agentosd-service] Starting Substrate daemon at $(date)" >> "$LOG_FILE"
        chmod 755 "$DAEMON_BIN"
        AGENTOSD_PORT="$PORT" AGENTOSD_SOCKET="$SOCKET" nohup "$DAEMON_BIN" </dev/null >> "$LOG_FILE" 2>&1 &
    fi
    sleep 10
done
