#!/bin/bash
# Universal Deployment and Management Tool for Personal Agent Substrate Daemon (agentosd)
# Supports Android (ADB), macOS (launchd), Linux (systemd), and Magisk packaging.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CORE_DIR="$ROOT_DIR/agent_os/core"
PACKAGING_DIR="$ROOT_DIR/agent_os/packaging"

ACTION="${1:-status}"
TARGET="${2:-android}"
DEVICE_ID="${ADB_DEVICE:-25161JEGR06836}"

usage() {
    echo "Usage: $0 [install|start|stop|restart|status|build-magisk] [android|macos|linux]"
    echo ""
    echo "Actions:"
    echo "  install       Build and deploy agentosd binary and service configurations"
    echo "  start         Start daemon or background supervisor"
    echo "  stop          Stop running daemon and supervisor"
    echo "  restart       Restart daemon"
    echo "  status        Check health and running status"
    echo "  build-magisk  Build flashable Magisk module ZIP package"
    exit 1
}

# ==============================================================================
# Magisk Packaging
# ==============================================================================
build_magisk() {
    echo "=== Building Flashable Magisk Module for agentosd ==="
    OUTPUT_ZIP="$ROOT_DIR/agent_os/build/agentosd-magisk-v1.0.0.zip"
    mkdir -p "$ROOT_DIR/agent_os/build"

    AARCH64_BIN="$CORE_DIR/target/aarch64-linux-android/release/agentosd"
    if [ ! -f "$AARCH64_BIN" ]; then
        echo "Error: Android aarch64 binary not found at $AARCH64_BIN. Please compile first."
        exit 1
    fi

    # Copy binary into magisk staging directory
    mkdir -p "$PACKAGING_DIR/magisk/system/bin"
    cp "$AARCH64_BIN" "$PACKAGING_DIR/magisk/system/bin/agentosd"
    chmod 755 "$PACKAGING_DIR/magisk/system/bin/agentosd"
    chmod 755 "$PACKAGING_DIR/magisk/service.sh"
    chmod 755 "$PACKAGING_DIR/magisk/post-fs-data.sh"

    # Create ZIP archive
    (
        cd "$PACKAGING_DIR/magisk"
        rm -f "$OUTPUT_ZIP"
        zip -r -q "$OUTPUT_ZIP" module.prop service.sh post-fs-data.sh system META-INF
    )

    # Clean up staging binary to avoid keeping large binaries in repo tree
    rm -f "$PACKAGING_DIR/magisk/system/bin/agentosd"
    rmdir "$PACKAGING_DIR/magisk/system/bin" 2>/dev/null || true

    echo "Magisk module package created successfully: $OUTPUT_ZIP"
    ls -lh "$OUTPUT_ZIP"
}

# ==============================================================================
# macOS Target (launchd)
# ==============================================================================
start_macos() {
    echo "=== Starting agentosd via launchd on macOS ==="
    PLIST_DEST="$HOME/Library/LaunchAgents/org.traceform.agentosd.plist"
    mkdir -p "$HOME/Library/LaunchAgents"
    cp "$PACKAGING_DIR/macos/org.traceform.agentosd.plist" "$PLIST_DEST"
    launchctl unload "$PLIST_DEST" 2>/dev/null || true
    launchctl load -w "$PLIST_DEST"
    echo "Loaded launchd service: $PLIST_DEST"
    sleep 1
    status_macos
}

stop_macos() {
    echo "=== Stopping agentosd on macOS ==="
    PLIST_DEST="$HOME/Library/LaunchAgents/org.traceform.agentosd.plist"
    if [ -f "$PLIST_DEST" ]; then
        launchctl unload "$PLIST_DEST" 2>/dev/null || true
    fi
    pkill -f "agentosd" 2>/dev/null || true
    echo "Stopped."
}

status_macos() {
    echo "=== Checking Substrate Status on macOS ==="
    python3 -c "
import socket
try:
    s = socket.create_connection(('127.0.0.1', 19898), timeout=2)
    s.sendall(b'{\"id\":\"stat-mac\",\"action\":\"ping\",\"params\":{}}\n')
    print('Health ping response (19898):', s.recv(1024).decode().strip())
    s.close()
except Exception as e:
    print('No daemon responding on 127.0.0.1:19898:', e)
"
}

# ==============================================================================
# Linux Target (systemd)
# ==============================================================================
start_linux() {
    echo "=== Starting agentosd via systemd on Linux ==="
    systemctl --user start agentosd.service 2>/dev/null || sudo systemctl start agentosd.service
    status_linux
}

stop_linux() {
    echo "=== Stopping agentosd on Linux ==="
    systemctl --user stop agentosd.service 2>/dev/null || sudo systemctl stop agentosd.service
}

status_linux() {
    echo "=== Checking Substrate Status on Linux ==="
    python3 -c "
import socket
try:
    s = socket.create_connection(('127.0.0.1', 19898), timeout=2)
    s.sendall(b'{\"id\":\"stat-linux\",\"action\":\"ping\",\"params\":{}}\n')
    print('Health ping response (19898):', s.recv(1024).decode().strip())
    s.close()
except Exception as e:
    print('No daemon responding on 127.0.0.1:19898:', e)
"
}

# ==============================================================================
# Android Target (ADB Shell + Supervisor)
# ==============================================================================
deploy_android() {
    echo "=== Deploying agentosd and Supervisor to Android Device ($DEVICE_ID) ==="
    AARCH64_BIN="$CORE_DIR/target/aarch64-linux-android/release/agentosd"
    if [ ! -f "$AARCH64_BIN" ]; then
        echo "Error: aarch64 binary not found at $AARCH64_BIN"
        exit 1
    fi

    adb -s "$DEVICE_ID" push "$AARCH64_BIN" /data/local/tmp/agentosd
    adb -s "$DEVICE_ID" push "$PACKAGING_DIR/android/agentosd-supervisor.sh" /data/local/tmp/agentosd-supervisor.sh
    adb -s "$DEVICE_ID" shell "chmod 755 /data/local/tmp/agentosd /data/local/tmp/agentosd-supervisor.sh"
    
    # Setup port forwarding
    adb -s "$DEVICE_ID" forward tcp:19898 tcp:19898
    echo "Pushed binary, supervisor, and configured port forwarding tcp:19898"
}

start_android() {
    echo "=== Starting agentosd Supervisor on Android Device ($DEVICE_ID) ==="
    deploy_android
    # Stop existing instances via PID file and pidof
    stop_android
    sleep 1
    # Launch supervisor in background
    adb -s "$DEVICE_ID" shell "nohup /data/local/tmp/agentosd-supervisor.sh </dev/null >/data/local/tmp/supervisor.log 2>&1 &"
    sleep 2
    status_android
}

stop_android() {
    echo "=== Stopping agentosd and Supervisor on Android Device ($DEVICE_ID) ==="
    adb -s "$DEVICE_ID" shell "
        if [ -f /data/local/tmp/agentosd-supervisor.pid ]; then
            kill -9 \$(cat /data/local/tmp/agentosd-supervisor.pid) 2>/dev/null || true
            rm -f /data/local/tmp/agentosd-supervisor.pid
        fi
        for p in \$(pidof agentosd 2>/dev/null); do
            kill -9 \$p 2>/dev/null || true
        done
    "
    echo "Stopped."
}

status_android() {
    echo "=== Checking Substrate Status on Android Device ($DEVICE_ID) ==="
    adb -s "$DEVICE_ID" forward tcp:19898 tcp:19898
    adb -s "$DEVICE_ID" shell "
        echo '--- Process State ---'
        if [ -f /data/local/tmp/agentosd-supervisor.pid ]; then
            echo 'Supervisor PID:' \$(cat /data/local/tmp/agentosd-supervisor.pid)
        else
            echo 'Supervisor not running'
        fi
        echo 'agentosd PIDs:' \$(pidof agentosd 2>/dev/null || echo 'None')
    "
    python3 -c "
import socket
try:
    s = socket.create_connection(('127.0.0.1', 19898), timeout=2)
    s.sendall(b'{\"id\":\"stat\",\"action\":\"ping\",\"payload\":{}}\n')
    print('Health ping response:', s.recv(1024).decode().strip())
    s.close()
except Exception as e:
    print('Connection error on 127.0.0.1:19898:', e)
"
}

# ==============================================================================
# Dispatch
# ==============================================================================
case "$ACTION" in
    build-magisk)
        build_magisk
        ;;
    install)
        if [ "$TARGET" = "android" ]; then
            deploy_android
        else
            echo "Install not implemented for target: $TARGET"
        fi
        ;;
    start)
        if [ "$TARGET" = "android" ]; then
            start_android
        elif [ "$TARGET" = "macos" ]; then
            start_macos
        elif [ "$TARGET" = "linux" ]; then
            start_linux
        fi
        ;;
    stop)
        if [ "$TARGET" = "android" ]; then
            stop_android
        elif [ "$TARGET" = "macos" ]; then
            stop_macos
        elif [ "$TARGET" = "linux" ]; then
            stop_linux
        fi
        ;;
    restart)
        if [ "$TARGET" = "android" ]; then
            stop_android
            sleep 1
            start_android
        elif [ "$TARGET" = "macos" ]; then
            stop_macos
            sleep 1
            start_macos
        elif [ "$TARGET" = "linux" ]; then
            stop_linux
            sleep 1
            start_linux
        fi
        ;;
    status)
        if [ "$TARGET" = "android" ]; then
            status_android
        elif [ "$TARGET" = "macos" ]; then
            status_macos
        elif [ "$TARGET" = "linux" ]; then
            status_linux
        fi
        ;;
    *)
        usage
        ;;
esac
