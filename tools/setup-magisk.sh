#!/bin/sh
# setup-magisk.sh -- fetch Magisk and extract the 32-bit ARM patching kit.
#
# Same pattern as setup-mtkclient.sh: the download is gitignored (*.apk), the
# checksum is pinned here, and the tree is reproducible from scratch.
#
# Why we extract armeabi-v7a specifically
# ---------------------------------------
# boot_patch.sh does "add 0750 init magiskinit", i.e. it injects whichever
# magiskinit sits in its own directory and makes it /init. In the Magisk app's
# normal flow that directory is the app's native library dir, so the injected
# binary is the ABI of *the machine doing the patching*.
#
# The 4058G reports ro.product.cpu.abi=armeabi-v7a and has no abilist64 at all.
# Patching on an arm64 Android emulator would therefore inject an arm64 /init
# into a boot image for a CPU that cannot execute 64-bit code -- an unrunnable
# ELF as PID 1, which is a hard bootloop. So we pull armeabi-v7a out of the APK
# by hand and run the patch on the phone itself, where the ABI is correct by
# construction. See tools/patch-boot.sh.

set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

VERSION="v30.7"
APK="$root/vendor/Magisk-$VERSION.apk"
URL="https://github.com/topjohnwu/Magisk/releases/download/$VERSION/Magisk-$VERSION.apk"
SHA256="e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5"
SIZE=11613864

KIT="$root/vendor/magisk-armeabi-v7a"

mkdir -p "$root/vendor"

if [ -f "$APK" ] && [ "$(shasum -a 256 "$APK" | cut -d' ' -f1)" = "$SHA256" ]; then
	echo "==> $APK already present and verified"
else
	echo "==> downloading Magisk $VERSION"
	curl -fL --progress-bar -o "$APK" "$URL"
	got=$(shasum -a 256 "$APK" | cut -d' ' -f1)
	if [ "$got" != "$SHA256" ]; then
		echo "CHECKSUM MISMATCH" >&2
		echo "  expected $SHA256" >&2
		echo "  got      $got" >&2
		echo "Refusing to use this file. Delete it and retry." >&2
		exit 1
	fi
	echo "==> sha256 verified"
fi

sz=$(stat -f%z "$APK")
[ "$sz" = "$SIZE" ] || { echo "size $sz != expected $SIZE" >&2; exit 1; }

echo "==> extracting the armeabi-v7a kit"
rm -rf "$KIT"
mkdir -p "$KIT"
tmp="$KIT/.unzip"
# -o because the APK genuinely contains duplicate entries (res/2f.xml twice),
# and without it unzip stops to ask, then treats EOF as "overwrite nothing".
unzip -qo "$APK" -d "$tmp"

# boot_patch.sh looks for these exact names in its own directory. The APK ships
# them as lib*.so because that is the only way to get Android's installer to
# extract native binaries with the exec bit set.
cp "$tmp/lib/armeabi-v7a/libmagiskboot.so"   "$KIT/magiskboot"
cp "$tmp/lib/armeabi-v7a/libmagiskinit.so"   "$KIT/magiskinit"
cp "$tmp/lib/armeabi-v7a/libmagisk.so"       "$KIT/magisk"
cp "$tmp/lib/armeabi-v7a/libmagiskpolicy.so" "$KIT/magiskpolicy"
cp "$tmp/lib/armeabi-v7a/libinit-ld.so"      "$KIT/init-ld"
cp "$tmp/lib/armeabi-v7a/libbusybox.so"      "$KIT/busybox"
cp "$tmp/assets/boot_patch.sh"               "$KIT/"
cp "$tmp/assets/util_functions.sh"           "$KIT/"
cp "$tmp/assets/stub.apk"                    "$KIT/"

rm -rf "$tmp"
chmod 755 "$KIT"/*

# Guard against a future Magisk dropping 32-bit ARM, which would otherwise show
# up as a bootloop rather than as an error here.
echo "==> verifying every binary is 32-bit ARM"
fail=0
for b in magiskboot magiskinit magisk magiskpolicy init-ld busybox; do
	desc=$(file -b "$KIT/$b")
	case "$desc" in
		*"ELF 32-bit"*ARM*) printf '  %-14s OK   %s\n' "$b" "$desc" ;;
		*) printf '  %-14s WRONG ARCH: %s\n' "$b" "$desc"; fail=1 ;;
	esac
done
[ "$fail" = 0 ] || { echo "Refusing to ship a kit that is not 32-bit ARM." >&2; exit 1; }

echo
echo "==> $KIT"
ls -1 "$KIT"
echo
echo "Next: tools/patch-boot.sh <stock-boot.img>  (phone in recovery2.img)"
