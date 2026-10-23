#!/system/bin/sh
# Personal Agent Substrate Android Shell Watchdog
# Monitors agentosd on localhost port 19898 and revives it automatically upon failure.

BIN_PATH="/data/local/tmp/agentosd"
LOG_PATH="/data/local/tmp/agentosd.log"
LOG_MAX_BYTES=5242880 # 5 MB
PORT=19898
SOCKET_PATH="/data/local/tmp/agentosd.sock"
CHECK_INTERVAL_SECS=3

rotate_logs_if_needed() {
    if [ -f "$LOG_PATH" ]; then
        FILE_SIZE=$(wc -c < "$LOG_PATH" 2>/dev/null || echo 0)
        if [ "$FILE_SIZE" -gt "$LOG_MAX_BYTES" ]; then
            mv "$LOG_PATH" "${LOG_PATH}.old"
            echo "[watchdog] Rotated log file at $(date)" > "$LOG_PATH"
        fi
    fi
}

start_daemon() {
    chmod 755 "$BIN_PATH"
    echo "[watchdog] Starting agentosd daemon at $(date)" >> "$LOG_PATH"
    AGENTOSD_PORT="$PORT" AGENTOSD_SOCKET="$SOCKET_PATH" nohup "$BIN_PATH" </dev/null >> "$LOG_PATH" 2>&1 &
    sleep 1
}

cleanup() {
    echo "[watchdog] Received termination signal, shutting down watchdog and daemon" >> "$LOG_PATH"
    pkill -f "$BIN_PATH"
    exit 0
}

trap cleanup TERM INT HUP

echo "[watchdog] Personal Agent Substrate Watchdog initialized for $BIN_PATH" >> "$LOG_PATH"

# Initial start if not running
if ! pgrep -f "$BIN_PATH" >/dev/null 2>&1; then
    start_daemon
fi

# Main watchdog loop
while true; do
    rotate_logs_if_needed

    # Check if process exists
    if ! pgrep -f "$BIN_PATH" >/dev/null 2>&1; then
        echo "[watchdog] ALERT: agentosd process not found. Restarting..." >> "$LOG_PATH"
        start_daemon
    fi

    sleep "$CHECK_INTERVAL_SECS"
done
