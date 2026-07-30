#!/bin/sh
# install-fota-guard.sh -- re-disable TCL's OTA updater on every boot.
#
#   tools/install-fota-guard.sh          # phone booted, root available
#
# Why
# ---
# `pm disable-user com.tcl.fota.system` persists in /data/system/packages.xml,
# which means it does NOT survive anything that resets app state -- and we
# already lost it once, to the factory reset that `fastboot flashing unlock`
# triggers. That mattered: flip2 issue #42 reports newer TCL builds removing the
# `*#*#33284#*#*` code that is the only way to enable ADB on this phone. An OTA
# landing while FOTA is briefly re-enabled costs us ADB permanently, with no
# route back.
#
# This is cheap insurance against ever being in that window again, and it is
# what makes putting the SIM back in a comfortable decision rather than a
# careful one.
#
# service.d, not post-fs-data.d: `pm` needs the framework, which does not exist
# at post-fs-data. Even service.d can start before PackageManager is ready, so
# the script waits for sys.boot_completed itself rather than assuming.
#
# To undo: adb shell su -c 'rm /data/adb/service.d/fota-guard.sh'

set -e
GUARD=/data/adb/service.d/fota-guard.sh

adb devices | sed 1d | grep -qw device || { echo "phone not booted/visible to adb" >&2; exit 1; }
adb shell "su -c id" 2>/dev/null | grep -q "uid=0" || {
	echo "no root. Magisk app -> Superuser tab -> enable com.android.shell" >&2
	exit 1
}

echo "==> writing $GUARD"
adb shell "su -c 'mkdir -p /data/adb/service.d'"
adb shell "su -c 'cat > $GUARD'" <<'EOF'
#!/system/bin/sh
# FlipFlex: keep TCL's OTA updater disabled. See tools/install-fota-guard.sh.
# An OTA can remove the *#*#33284#*#* ADB code (flip2 #42) and there is no way
# back from that, so this re-asserts the disable on every boot.

# service.d can run before PackageManager exists; pm would just fail silently.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
    sleep 2
    i=$((i + 1))
done
sleep 10

pm disable-user --user 0 com.tcl.fota.system >/dev/null 2>&1

# A breadcrumb, so "did the guard actually run" is answerable later without
# guessing. resetprop rather than setprop: setprop is refused from a child
# process on this device.
/debug_ramdisk/magisk resetprop -n flipflex.fotaguard.ran 1 2>/dev/null
EOF

adb shell "su -c 'chmod 755 $GUARD'"
echo "==> installed:"
adb shell "su -c 'ls -l $GUARD'" | tr -d '\r'
echo
echo "==> current state of the updater"
adb shell "su -c 'dumpsys package com.tcl.fota.system | grep -m1 enabled='" | tr -d '\r'
echo "    enabled=3 means disabled-user, which is what we want."
echo
echo "Takes effect from the next boot. Verify then with:"
echo "  adb shell getprop flipflex.fotaguard.ran      # 1 once it has run"
