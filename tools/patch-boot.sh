#!/bin/sh
# patch-boot.sh -- patch our own stock boot.img with Magisk, on the phone.
#
#   tools/patch-boot.sh backups/<date>-UPCI-recovery/boot.img
#
# Runs Magisk's own boot_patch.sh inside recovery2.img rather than in the
# Magisk app on an emulator. Two reasons, and the first one is a bootloop:
#
# 1. ABI. boot_patch.sh injects whichever magiskinit is in its directory as
#    /init. In the app it is the app's own ABI, so patching on an arm64
#    emulator writes an arm64 /init -- and this phone reports
#    ro.product.cpu.abi=armeabi-v7a with no abilist64, so PID 1 would be an
#    unrunnable ELF. In recovery on the phone, the ABI is right by construction.
#
# 2. Apple Silicon cannot execute AArch32 at all, so an armeabi-v7a emulator
#    would be full software emulation -- slow, and one more thing to go wrong.
#
# The flags are passed explicitly rather than left to get_flags(). They were
# measured on the live stock device before the unlock (see docs/phase0-unlock.md):
# recovery mounts things differently and would not necessarily re-derive them.

set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
KIT="$root/vendor/magisk-armeabi-v7a"

BOOT="$1"
[ -n "$BOOT" ] || { echo "usage: $0 <stock-boot.img>" >&2; exit 1; }
[ -f "$BOOT" ] || { echo "no such file: $BOOT" >&2; exit 1; }
[ -d "$KIT" ] || { echo "kit missing -- run tools/setup-magisk.sh first" >&2; exit 1; }

# An Android boot image starts with this magic. A truncated or empty dump is the
# most likely failure of dump-from-recovery.sh, and it would otherwise only
# surface as a bootloop after flashing.
magic=$(dd if="$BOOT" bs=8 count=1 2>/dev/null)
[ "$magic" = "ANDROID!" ] || {
	echo "$BOOT does not start with ANDROID! (got '$magic')" >&2
	echo "That dump is not a boot image. Re-run tools/dump-from-recovery.sh." >&2
	exit 1
}
echo "==> $BOOT looks like a boot image ($(du -h "$BOOT" | cut -f1))"

# Measured on the stock 4058G before the unlock:
#   / is /dev/block/dm-3, not rootfs        -> SYSTEM_AS_ROOT, so KEEPVERITY
#   /data is on dm-6, ro.crypto.state=encrypted -> KEEPFORCEENCRYPT
#   /dev/block/by-name/vbmeta exists        -> no need to patch vbmeta in boot
#   no skip_initramfs in /proc/cmdline      -> modern SAR, not LEGACYSAR
FLAGS="KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false RECOVERYMODE=false LEGACYSAR=false"

echo "==> flags: $FLAGS"

if ! adb devices | sed 1d | grep -qE 'recovery|device'; then
	echo "No adb device. Boot recovery2.img first:" >&2
	echo "  tools/bootseq.py FASTBOOT   (phone off, battery IN, plug in)" >&2
	echo "  fastboot boot backups/recovery2.img" >&2
	exit 1
fi

echo "==> confirming root"
adb shell "su -c id" 2>&1 | head -1

# Recovery has no /data worth using and / is read-only, but /dev is tmpfs and
# always writable. Probe rather than assume -- recovery2.img is not TWRP and its
# layout is not documented anywhere.
# In recovery /data is not mounted, so /data/local/tmp does not exist, and adbd
# runs as uid 2000 (shell) rather than root -- so the work directory has to be
# both root-created and world-writable for adb push to reach it. /dev is tmpfs
# and always present; the rest are probed because recovery2.img is not TWRP and
# its layout is documented nowhere.
WORK=""
for cand in /dev/magisk-work /tmp/magisk-work /cache/magisk-work; do
	if adb shell "su -c 'mkdir -p $cand && chmod 777 $cand && touch $cand/.w && echo ok'" 2>/dev/null | tr -d '\r' | grep -q ok; then
		WORK="$cand"
		break
	fi
done
[ -n "$WORK" ] || { echo "found nowhere writable on the device" >&2; exit 1; }
echo "==> working in $WORK"

echo "==> pushing the patching kit"
for f in magiskboot magiskinit magisk magiskpolicy init-ld busybox boot_patch.sh util_functions.sh stub.apk; do
	adb push "$KIT/$f" "$WORK/" >/dev/null
	printf '  %s\n' "$f"
done
adb push "$BOOT" "$WORK/stock-boot.img" >/dev/null
echo "  stock-boot.img"

adb shell "su -c 'chmod 755 $WORK/*'" >/dev/null

echo
echo "==> running Magisk boot_patch.sh on the device"
# BOOTMODE=true only to make ui_print echo to stdout; util_functions would
# otherwise write to /proc/self/fd/$OUTFD, and OUTFD is unset outside a
# recovery installer. It does mean boot_patch.sh would try
# `magisk --preinit-device`, so PREINITDEVICE is pre-set to skip that -- magisk
# is dynamically linked and there is no linker in recovery.
#
# persist is the preinit choice: it exists on this device (mmcblk0p4), it is not
# touched by a factory reset, and it is not otapkg/cache which can be.
LOG=$(dirname "$BOOT")/magisk-patch.log
adb shell "su -c 'cd $WORK && BOOTMODE=true PREINITDEVICE=persist $FLAGS sh ./boot_patch.sh stock-boot.img'" 2>&1 | tee "$LOG" | sed 's/^/   /'

adb shell "su -c 'ls -l $WORK/new-boot.img'" 2>&1 | tr -d '\r' | grep -q new-boot.img || {
	echo "no new-boot.img was produced -- see the log above" >&2
	exit 1
}

OUT=$(dirname "$BOOT")/magisk-patched-boot.img
echo
echo "==> pulling the result"
adb shell "su -c 'cat $WORK/new-boot.img'" > "$OUT" 2>/dev/null || {
	# exec-out is the reliable binary path; plain shell mangles it.
	adb exec-out "su -c 'busybox stty raw; cat $WORK/new-boot.img'" > "$OUT"
}

# The check that actually matters. Extract the /init we just injected and prove
# it is 32-bit ARM. If this says aarch64, flashing it is a guaranteed bootloop,
# and this is the last moment it is cheap to find out.
echo "==> verifying the injected /init is 32-bit ARM"
adb shell "su -c 'cd $WORK && rm -rf v && mkdir v && cd v && ../magiskboot unpack ../new-boot.img && ../magiskboot cpio ramdisk.cpio \"extract init init\"'" >/dev/null 2>&1
INIT=$(dirname "$BOOT")/patched-init.bin
adb exec-out "su -c 'busybox stty raw; cat $WORK/v/init'" > "$INIT" 2>/dev/null || true

if [ -s "$INIT" ]; then
	desc=$(file -b "$INIT")
	echo "  injected /init: $desc"
	case "$desc" in
		*"ELF 32-bit"*ARM*) echo "  OK -- correct ABI for MT6739" ;;
		*) echo "  WRONG ARCH. Do not flash this image." >&2; exit 1 ;;
	esac
else
	echo "  could not extract /init to verify -- check manually before flashing" >&2
fi

echo
ls -lh "$OUT"
shasum -a 256 "$OUT"
echo
# fastboot boot is NOT an option on this LK -- it uploads, fails the handoff with
# usb_read e00002ed, and boots Android instead. So this image cannot be tried
# without being written, and the stock image below is the entire safety net.
echo "Keep the stock image -- it is the only way back."
echo "  fastboot flash boot $OUT"
echo
echo "If it bootloops: tools/bootseq.py FASTBOOT, then"
echo "  fastboot flash boot $(dirname "$BOOT")/boot.img"
