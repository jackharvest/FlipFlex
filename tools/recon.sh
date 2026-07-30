#!/bin/sh
# recon.sh -- everything worth knowing about the phone BEFORE the bootloader
# unlock wipes it. Read-only; touches nothing on the device.
#
# Two answers here are load-bearing:
#   ro.build.fingerprint  decides whether the community neutron.img fits, or
#                         whether we have to rebuild one from our own OTA.
#   wm density            decides the whole row layout. mdpi (160) gives a
#                         240x320 dp canvas; 240dpi would give 160x213 and
#                         halve every measurement in the plan.

set -u

say() { printf '\n=== %s ===\n' "$1"; }
get() { printf '%-36s %s\n' "$1" "$(adb shell getprop "$1" 2>/dev/null | tr -d '\r')"; }

if [ -z "$(adb devices | sed 1d | grep -w device)" ]; then
	echo "No device in 'device' state. On the phone:"
	echo "  1. dial *#*#33284#*#*  to enable ADB"
	echo "  2. replug, and accept the RSA fingerprint prompt"
	echo
	adb devices -l
	exit 1
fi

say "identity"
get ro.product.model
get ro.product.device
get ro.product.name
get ro.build.fingerprint
get ro.build.version.release
get ro.build.version.sdk
get ro.build.version.incremental
get ro.build.date

say "the blocker"
# Empty or false is expected on a stock unit -- this is what neutron.img flips.
get ro.vendor.tct.endurance
get ro.boot.verifiedbootstate
get ro.boot.flash.locked
get ro.secure
get ro.debuggable

say "screen -- decides the row layout"
adb shell wm size 2>/dev/null | tr -d '\r'
adb shell wm density 2>/dev/null | tr -d '\r'
get ro.sf.lcd_density

say "soc / memory"
get ro.board.platform
get ro.product.cpu.abilist
get dalvik.vm.heapgrowthlimit
get dalvik.vm.heapsize
adb shell 'grep MemTotal /proc/meminfo' 2>/dev/null | tr -d '\r'

say "launcher -- can it show a sideloaded app?"
adb shell pm list packages 2>/dev/null | grep -iE 'launcher|home' | tr -d '\r'
printf '%-36s ' "current HOME resolver"
adb shell 'cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN' 2>/dev/null \
	| grep -i 'name=' | head -2 | tr -d '\r' | tr '\n' ' '
echo

say "storage"
adb shell df -h /data /storage/emulated 2>/dev/null | tr -d '\r'

say "install path sanity (expect this to FAIL pre-root)"
echo "not attempted -- needs a throwaway APK; that is gate step 0c"
