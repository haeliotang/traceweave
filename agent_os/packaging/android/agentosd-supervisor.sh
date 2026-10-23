#!/system/bin/sh
# Personal Agent Substrate Android Shell Supervisor
# Monitors agentosd on localhost port 19898 and revives it automatically upon failure.

BIN_PATH="/data/local/tmp/agentosd"
LOG_PATH="/data/local/tmp/agentosd.log"
PID_FILE="/data/local/tmp/agentosd-supervisor.pid"
LOG_MAX_BYTES=5242880 # 5 MB
PORT=19898
SOCKET_PATH="/data/local/tmp/agentosd.sock"
CHECK_INTERVAL_SECS=3

# Write supervisor PID
echo "$$" > "$PID_FILE"

rotate_logs_if_needed() {
    if [ -f "$LOG_PATH" ]; then
        FILE_SIZE=$(wc -c < "$LOG_PATH" 2>/dev/null || echo 0)
        if [ "$FILE_SIZE" -gt "$LOG_MAX_BYTES" ]; then
            mv "$LOG_PATH" "${LOG_PATH}.old"
            echo "[supervisor] Rotated log file at $(date)" > "$LOG_PATH"
        fi
    fi
}

start_daemon() {
    chmod 755 "$BIN_PATH"
    echo "[supervisor] Starting agentosd daemon at $(date)" >> "$LOG_PATH"
    AGENTOSD_PORT="$PORT" AGENTOSD_SOCKET="$SOCKET_PATH" nohup "$BIN_PATH" </dev/null >> "$LOG_PATH" 2>&1 &
    sleep 1
}

cleanup() {
    echo "[supervisor] Received termination signal, shutting down supervisor and daemon" >> "$LOG_PATH"
    rm -f "$PID_FILE"
    for pid in $(pidof agentosd 2>/dev/null); do
        kill -9 "$pid" 2>/dev/null || true
    done
    exit 0
}

trap cleanup TERM INT HUP

echo "[supervisor] Personal Agent Substrate Supervisor initialized (PID $$)" >> "$LOG_PATH"

# Initial start if not running
if [ -z "$(pidof agentosd 2>/dev/null)" ]; then
    start_daemon
fi

# Main monitoring loop
while true; do
    rotate_logs_if_needed

    # Check if agentosd process exists
    if [ -z "$(pidof agentosd 2>/dev/null)" ]; then
        echo "[supervisor] ALERT: agentosd process not found. Reviving..." >> "$LOG_PATH"
        start_daemon
    fi

    sleep "$CHECK_INTERVAL_SECS"
done
