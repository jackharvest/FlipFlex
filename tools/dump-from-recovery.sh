#!/bin/sh
# dump-from-recovery.sh -- pull every partition worth having, from inside
# neutronscott's recovery2.img, which ships su and busybox.
#
# This is the read path mtkclient could not give us. The 4058G preloader does
# not implement MediaTek's standard handshake -- only TCL's own 8-byte command
# protocol (see tools/bootseq.py) -- so mtkclient cannot attach, and BROM never
# appears on the bus at all. Recovery with su is therefore the only way to read
# flash on this device, and it is why the unlock has to come before the backup
# rather than after.
#
# Technique is from the flip2 wiki. The `busybox stty raw` matters: without it
# adb's line-discipline mangles binary on the way out and every image is
# silently corrupt.
#
#   tools/dump-from-recovery.sh            # the bootable core, a few minutes
#   tools/dump-from-recovery.sh all        # adds super (1 GB+) -- slow

set -e
here=$(cd "$(dirname "$0")" && pwd)
OUT="$here/../backups/$(date +%Y%m%d-%H%M)-UPCI-recovery"
mkdir -p "$OUT"

# Everything needed to put the phone back exactly as it was, plus the identity
# partitions that are irreplaceable if lost (nvram/nvdata/proinfo carry IMEI
# and RF calibration -- no download anywhere can regenerate these for YOUR unit).
# Every name here was confirmed present in /dev/block/by-name on the live 4058G
# before the unlock, so a "failed" line below means a read problem, not a typo.
# frp/oembin/swversion/md_udc were added after that listing -- small, and there
# is exactly one chance to capture them.
CORE="boot vendor_boot dtbo vbmeta vbmeta_system vbmeta_vendor lk lk2 \
recovery logo para boot_para seccfg md1img md1dsp md_udc nvram nvdata nvcfg \
proinfo protect1 protect2 persist oempersist efuse expdb flashinfo frp \
loader_ext1 loader_ext2 mcupmfw spmfw gz1 gz2 tee1 tee2 sec1 otp oembin \
swversion"

[ "${1:-}" = "all" ] && CORE="$CORE super"

if ! adb devices | sed 1d | grep -qw recovery && ! adb devices | sed 1d | grep -qw device; then
	echo "No adb device. Boot recovery2.img first:" >&2
	echo "  tools/bootseq.py FASTBOOT   (phone off, battery IN, plug in)" >&2
	echo "  fastboot boot backups/recovery2.img" >&2
	exit 1
fi

echo "==> confirming root is available"
adb shell "su -c id" 2>&1 | head -1

for p in $CORE; do
	printf '%-16s ' "$p"
	if adb exec-out "su -c 'busybox stty raw; busybox dd if=/dev/block/by-name/$p 2>/dev/null'" > "$OUT/$p.img" 2>/dev/null; then
		sz=$(stat -f%z "$OUT/$p.img" 2>/dev/null || echo 0)
		if [ "$sz" -gt 0 ]; then
			printf '%s\n' "$(du -h "$OUT/$p.img" | cut -f1)"
		else
			echo "EMPTY - removing"
			rm -f "$OUT/$p.img"
		fi
	else
		echo "failed"
		rm -f "$OUT/$p.img"
	fi
done

echo
echo "==> $OUT"
ls -lh "$OUT"
echo
find "$OUT" -name '*.img' -exec shasum -a 256 {} \; | tee "$OUT/SHA256SUMS"
echo
echo "boot.img is the one to patch. Verify it first:"
echo "  head -c 8 $OUT/boot.img   # must read ANDROID!"
