#!/bin/sh
# flash-boot-from-recovery.sh -- write a boot image from inside recovery2.img,
# then read it back and prove it landed byte-for-byte.
#
#   tools/flash-boot-from-recovery.sh <image>        # flash it
#
# Used for both directions: the Magisk-patched image going on, and the stock
# image going back if it bootloops. Same script either way, deliberately -- the
# restore path should not be one you are writing for the first time under
# pressure.
#
# Why dd here rather than fastboot: we already have root in recovery, and
# fastboot needs another battery-pull + tools/bootseq.py trip because
# `adb reboot bootloader` does not reach fastboot on this LK. Same bytes.
#
# The read-back is the point. fastboot at least tells you it wrote; dd to a
# block device will happily half-succeed on a short write and say nothing, and
# a truncated boot image is a bootloop.

set -e
IMG="$1"
[ -n "$IMG" ] || { echo "usage: $0 <boot-image>" >&2; exit 1; }
[ -f "$IMG" ] || { echo "no such file: $IMG" >&2; exit 1; }

PART=boot
# 0x1800000, from `fastboot getvar partition-size:boot`. Both boot and recovery
# are this size on the 4058G.
EXPECT=25165824

magic=$(dd if="$IMG" bs=8 count=1 2>/dev/null)
[ "$magic" = "ANDROID!" ] || { echo "$IMG is not a boot image (magic '$magic')" >&2; exit 1; }

sz=$(stat -f%z "$IMG")
if [ "$sz" -gt "$EXPECT" ]; then
	echo "$IMG is $sz bytes, larger than the $EXPECT-byte $PART partition" >&2
	exit 1
fi

want=$(shasum -a 256 "$IMG" | cut -d' ' -f1)
echo "==> $IMG"
echo "    $sz bytes, sha256 $want"

adb devices | sed 1d | grep -qw recovery || {
	echo "Phone is not in recovery. Get there with:" >&2
	echo "  tools/bootseq.py FASTBOOT   (phone off, battery IN, plug in)" >&2
	echo "  fastboot reboot recovery" >&2
	exit 1
}
adb shell "su -c id" 2>&1 | grep -q "uid=0" || { echo "no root in recovery" >&2; exit 1; }

WORK=/dev/flashwork
adb shell "su -c 'rm -rf $WORK && mkdir -p $WORK && chmod 777 $WORK'" >/dev/null
echo "==> pushing"
adb push "$IMG" "$WORK/img" >/dev/null

# Confirm the push itself was clean before writing it to flash. Pushing over
# adb to a tmpfs in a 916 MB device is where a silent truncation would happen.
pushed=$(adb shell "su -c 'busybox sha256sum $WORK/img'" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
echo "    on-device sha256 $pushed"
[ "$pushed" = "$want" ] || { echo "push corrupted the image -- refusing to flash" >&2; exit 1; }

echo "==> writing to /dev/block/by-name/$PART"
adb shell "su -c 'dd if=$WORK/img of=/dev/block/by-name/$PART bs=1048576 && sync'" 2>&1 | sed 's/^/    /'

echo "==> reading it back"
# The partition is exactly EXPECT bytes and the image is padded to match, so a
# hash of the whole partition must equal the hash of the file. If the image were
# ever smaller than the partition this would need a count= instead.
got=$(adb shell "su -c 'busybox dd if=/dev/block/by-name/$PART bs=1048576 2>/dev/null | busybox sha256sum'" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
echo "    partition sha256 $got"

if [ "$got" = "$want" ]; then
	echo
	echo "VERIFIED -- $PART now matches $IMG exactly."
else
	echo
	echo "MISMATCH. The partition does not match the image." >&2
	echo "  wanted $want" >&2
	echo "  got    $got" >&2
	echo "Do NOT reboot. Re-run, or write the stock image back." >&2
	exit 1
fi
