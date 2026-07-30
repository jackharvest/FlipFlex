#!/bin/sh
# backup.sh -- take everything off the phone that we would need to un-brick it,
# BEFORE writing anything. Read-only; the device is in BROM mode and Android is
# not running, so nothing here depends on root, ADB, or an unlocked bootloader.
#
# Ordered deliberately: the fast critical set first, because that alone is
# enough to recover from a bad boot flash, and a full `rf` takes hours.
#
# Context for why this exists: neutronscott/flip2 issues #50 and #55 are both
# people whose phones still answered in BROM but who had no image to restore.
# The chip surviving is not the same as the phone surviving.
#
#   tools/backup.sh critical     small partitions + GPT      ~2 minutes
#   tools/backup.sh full         entire eMMC to flash.bin    ~1-2 hours
#   tools/backup.sh boot         just boot, to patch         seconds

set -e
here=$(cd "$(dirname "$0")" && pwd)
MTK="$here/mtk"
OUT="$here/../backups/$(date +%Y%m%d)-UPCI"
mkdir -p "$OUT"

# The bootable core. Everything here is small, and between them they are what
# a phone needs to reach a working system. `super` and `userdata` are excluded
# deliberately -- they are gigabytes, and neither is at risk from a boot flash.
CRITICAL="boot,vbmeta,vbmeta_system,vbmeta_vendor,lk,lk2,dtbo,recovery,vendor_boot,logo,para,boot_para,seccfg,md1img,md1dsp,nvram,nvdata,nvcfg,proinfo,protect1,protect2,persist,oempersist"

case "${1:-critical}" in
boot)
	echo "==> boot only -> $OUT/boot.img"
	"$MTK" r boot "$OUT/boot.img"
	;;
critical)
	echo "==> GPT -> $OUT/"
	"$MTK" gpt "$OUT/gpt" || echo "!! gpt dump failed, continuing"
	echo "==> preloader (lives in the boot1 hardware partition, not the GPT)"
	"$MTK" r preloader "$OUT/preloader.bin" --parttype boot1 || echo "!! preloader failed, continuing"
	echo "==> critical partitions"
	# One invocation: mtkclient takes a comma-separated pair of lists and does
	# them in a single DA session, which is far quicker than N separate runs.
	names="$CRITICAL"
	files=$(printf '%s' "$names" | tr ',' '\n' | sed "s|^|$OUT/|;s|\$|.img|" | paste -sd, -)
	"$MTK" r "$names" "$files"
	;;
full)
	echo "==> FULL eMMC -> $OUT/flash.bin  (this is the one worth sharing)"
	echo "    16 GB over USB 2.0. Expect 1-2 hours. Do not unplug."
	"$MTK" rf "$OUT/flash.bin"
	;;
*)
	echo "usage: backup.sh [critical|full|boot]" >&2
	exit 2
	;;
esac

echo
echo "==> done. contents of $OUT:"
ls -lh "$OUT"
echo
echo "Checksums (keep these with the files if you share them):"
find "$OUT" -type f -exec shasum -a 256 {} \; | tee "$OUT/SHA256SUMS"
