#!/system/bin/sh
# Magisk post-fs-data script for agentosd directory and security context preparation

MODDIR=${0%/*}

# Ensure runtime directories exist
mkdir -p /data/local/tmp
chmod 777 /data/local/tmp

# Ensure executable permissions on binaries
if [ -f "$MODDIR/system/bin/agentosd" ]; then
    chmod 755 "$MODDIR/system/bin/agentosd"
    chown root:shell "$MODDIR/system/bin/agentosd"
fi
