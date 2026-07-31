#!/bin/sh
# Put FlipFlex in the phone's Menu, by way of a Magisk module.
#
# WHY THIS EXISTS. TCL's Launcher3 does not enumerate installed apps. It renders
# a hardcoded array of package names -- allapp_list, read out of
# /system/priv-app/Launcher3/Launcher3.apk -- and silently drops the ones that
# are not installed. Nothing in FlipFlex's own manifest can change that. It
# declares MAIN/LAUNCHER, `cmd package resolve-activity` resolves it, and the
# Menu still does not show it. Neither does it show Magisk.
#
# So the fix is a runtime resource overlay that replaces that one array. See
# overlay/src/main/res/values/arrays.xml.
#
# WHY A MAGISK MODULE RATHER THAN `pm install`. A static RRO is only honoured
# when it is preinstalled in a system partition; an overlay APK installed into
# /data is refused outright on API 30. Magisk's magic mount is how a file gets
# into /product/overlay on a device whose partitions are read-only and
# verity-protected, and removing the module puts the phone back exactly as it
# was -- which is the whole reason to do it this way rather than repacking
# Launcher3.apk (which cannot be resigned without the platform key anyway).
#
#   tools/install-menu-overlay.sh            # build, install, reboot
#   tools/install-menu-overlay.sh --no-reboot
#   tools/install-menu-overlay.sh --remove
set -e

cd "$(dirname "$0")/.."

MODULE=flipflex-menu
MODDIR=/data/adb/modules/$MODULE
APK=overlay/build/outputs/apk/release/overlay-release.apk

# Every one of these has to run as root, and `adb shell su -c '...'` is the only
# way to get there on this handset -- adb itself is not rooted.
sh_root() { adb shell "su -c '$1'"; }

if [ "$1" = "--remove" ]; then
	sh_root "rm -rf $MODDIR"
	echo "module removed; reboot to take effect"
	exit 0
fi

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export JAVA_HOME
[ -x "$JAVA_HOME/bin/java" ] || {
	echo "No JDK at $JAVA_HOME -- brew install openjdk@17" >&2
	exit 1
}

./gradlew :overlay:assembleRelease
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

# /system/product is a symlink to /product on this build, so the module mirrors
# the real path. Mounting the symlinked one would have Magisk shadow a symlink
# with a directory, which does not do what it looks like it does.
sh_root "mkdir -p $MODDIR/system/product/overlay"

# Straight through `su` rather than `adb push` + `mv`: /data/adb is 0700 root,
# so a push cannot land anywhere underneath it, and pushing to /data/local/tmp
# first leaves a copy of the APK behind on the phone.
adb shell "su -c 'cat > $MODDIR/system/product/overlay/FlipFlexMenu.apk'" < "$APK"

# 0644 root:root, matching every other file in /product/overlay. A mode the
# framework does not expect on a system APK is a scan failure with no log line
# that says so.
sh_root "chmod 0644 $MODDIR/system/product/overlay/FlipFlexMenu.apk"
sh_root "chown 0:0 $MODDIR/system/product/overlay/FlipFlexMenu.apk"

sh_root "cat > $MODDIR/module.prop" <<'EOF'
id=flipflex-menu
name=FlipFlex Menu Entry
version=v1
versionCode=1
author=FlipFlex
description=Adds FlipFlex to the TCL launcher Menu by overlaying Launcher3's allapp_list, which is a hardcoded whitelist that ignores installed apps.
EOF

# Magisk skips a module directory carrying `disable` or `remove`. Neither is
# created here, but an earlier run that was removed with the Magisk app leaves
# one behind, and the module then silently does nothing.
sh_root "rm -f $MODDIR/disable $MODDIR/remove"

echo "module staged at $MODDIR"

if [ "$1" = "--no-reboot" ]; then
	echo "reboot the phone, then: tools/install-menu-overlay.sh --verify"
	exit 0
fi

if [ "$1" = "--verify" ]; then
	echo "--- overlay scanned and enabled?"
	adb shell cmd overlay list 2>&1 | grep -A4 'com.android.launcher3' || true
	exit 0
fi

adb reboot
echo "rebooting; give it about a minute, then --verify"
