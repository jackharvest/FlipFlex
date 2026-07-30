#!/bin/sh
# inject-endurance.sh -- add an init .rc to a Magisk-patched boot image that
# sets ro.vendor.tct.endurance at boot, lifting TCL's APK install block.
#
#   tools/inject-endurance.sh <magisk-patched-boot.img>   # phone in recovery
#
# Why this is baked into the image
# --------------------------------
# The plan was Magisk root first, property second, via
# /data/adb/post-fs-data.d/ -- two independently debuggable steps. That is not
# possible on this device, and the reason is circular:
#
#   writing /data/adb          needs root
#   root from adb              needs MagiskSU to grant it
#   MagiskSU grants nothing    without the Magisk app to approve the request
#   installing the Magisk app  needs ro.vendor.tct.endurance
#
# MagiskSU is present and working -- /debug_ramdisk/su reports 30.7:MAGISKSU --
# and it answers `Permission denied` for exactly this reason. So the property has
# to be set from inside the boot image, with no /data and no app involved. That
# is what neutron.img does and what we rejected it for; the difference is that
# this is OUR boot image, from OUR UPCI dump, so there is no build mismatch.
#
# Two mechanisms, deliberately
# ----------------------------
# 1. init's own setprop. ro.vendor.tct.endurance is declared in no build.prop
#    anywhere on the device, so it is genuinely unset rather than set-to-empty,
#    and a ro. property can be set once. plat_property_contexts:193 has the
#    prefix rule `ro.vendor.  u:object_r:vendor_default_prop:s0`, so it has a
#    valid SELinux context and init is permitted to set it.
# 2. Magisk's resetprop, which writes the property area directly and does not
#    care whether the property is read-only or already set.
#
# Both are in the .rc. If ${MAGISKTMP} is not substituted the exec simply fails
# to find its binary, which init logs and ignores -- it cannot break the boot.
# Belt and braces here is worth it because each attempt costs a flash and a
# reboot, and PackageManagerService reads the property long after post-fs-data.

set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
KIT="$root/vendor/magisk-armeabi-v7a"

IMG="$1"
[ -n "$IMG" ] || { echo "usage: $0 <magisk-patched-boot.img>" >&2; exit 1; }
[ -f "$IMG" ] || { echo "no such file: $IMG" >&2; exit 1; }
[ -d "$KIT" ] || { echo "kit missing -- run tools/setup-magisk.sh" >&2; exit 1; }

magic=$(dd if="$IMG" bs=8 count=1 2>/dev/null)
[ "$magic" = "ANDROID!" ] || { echo "$IMG is not a boot image" >&2; exit 1; }

OUT="${IMG%.img}-endurance.img"
RC="$root/vendor/init.flipflex.rc"

cat > "$RC" <<'EOF'
# FlipFlex: lift TCL's APK install block.
#
# PackageManagerService on this build carries a TCL patch that refuses every
# install while mIsAllowInstall is false, which is derived from
# ro.vendor.tct.endurance. The refusal surfaces as
# INSTALL_FAILED_INSUFFICIENT_STORAGE with 11 GB free, so do not go looking at
# storage. See docs/phase0-unlock.md.
#
# Three triggers, because a ro. property can only be set once and we do not know
# which of them runs before system_server reads it. The later attempts fail
# harmlessly once one has succeeded.
#
# debug.flipflex.* are markers, and they are the whole diagnostic: without root
# there is no way to see init's early log, so the .rc has to report on itself.
#   both markers set, endurance empty -> the .rc ran, setprop was refused
#   no markers at all                 -> the .rc was never injected
# debug. rather than sys. because debug_prop is settable from init on every
# build; a marker that cannot be set tells you nothing.
# init's own setprop does NOT work here, and this was established by experiment,
# not guessed: with markers in all three blocks, every marker got set and
# ro.vendor.tct.endurance stayed empty. It is not "already set" (it appears
# nowhere in `getprop`'s list) and it is not SELinux (no avc for it anywhere in
# the log, while unrelated vendor_default_prop denials do show up). init simply
# will not mint a ro.vendor.* property from a platform .rc -- ours is injected
# into /system/etc/init/hw/init.rc, so it runs as u:r:init:s0 rather than the
# u:r:vendor_init:s0 that vendor properties expect.
#
# resetprop sidesteps all of it by writing the property area directly, which is
# what it exists for. /debug_ramdisk is hardcoded rather than ${MAGISKTMP}
# because we confirmed that path on the running device (`magisk --path`), and a
# substitution that silently does not happen would look exactly like this bug.
# NO '$' ANYWHERE IN THIS FILE. init's rc parser treats $ as the start of a
# property expansion, and when it cannot expand one it drops the entire command
# at parse time, silently. An earlier version used `echo rc=$?` and every
# command containing it vanished -- which looks identical to the command running
# and failing. That cost a flash-and-reboot cycle to discover.
#
# Reporting is via setprop rather than files: a file created from the magisk
# context lands with an SELinux label that adb shell cannot stat, so the
# evidence is unreadable exactly when you need it. Properties are readable by
# anyone.
#
# `exec u:r:magisk:s0 root root -- ...` is confirmed working on this device;
# bare `exec -- ...` was never demonstrated to, so do not switch to it.
# No `sh -c "..."` either. `exec u:r:magisk:s0 root root -- toybox touch FILE`
# demonstrably works, but every `sh -c` form tried has silently done nothing --
# whether init mangles the quoted argument or the magisk context cannot setprop
# was never worth pinning down, because shipping a real script file avoids both.
#
# Two paths because it is not certain which one Magisk populates: plain files in
# overlay.d are copied into the rootfs (-> /flipflex.sh), and overlay.d/sbin is
# copied into MAGISKTMP (-> /debug_ramdisk/flipflex.sh). Whichever exists runs;
# the other fails harmlessly, and the marker files say which.
# Only direct binary invocations with plain arguments -- the one form proven to
# work on this device. No sh, no quotes, no redirection, no '$'.
#
# The debug.flipflex.rp* lines are the key measurement. They ask resetprop to set
# a harmless debug. property right next to the real one. That separates two very
# different failures which until now looked identical:
#   rp marker set, endurance empty -> resetprop runs fine, this property is special
#   neither set                    -> resetprop is not running or not working
# The cp lines answer a third: whether /debug_ramdisk/magisk even exists yet at
# that trigger, since Magisk populates MAGISKTMP during its own post-fs-data.
# resetprop -n writes straight into /dev/__properties__/<context-file>. It
# succeeded for debug_prop and failed for vendor_default_prop, which points at
# SELinux write access to that particular file rather than at resetprop. Every
# exec so far carried an explicit u:r:magisk:s0 label; init's own context can
# write every property file, and a bare `exec --` inherits it. That is the one
# combination never tried -- earlier bare-exec attempts all also used `sh -c`
# and '$', so they proved nothing about the context.
#
# Both forms are here, writing distinct markers, so one boot settles which
# context can do it.
# Bare `exec --` is silently dropped by this init -- no marker file, no
# property, nothing. Only the full `exec <seclabel> <user> <group> --` form runs,
# so the context cannot be left to default and has to be named outright.
#
# u:r:magisk:s0 runs but cannot write the vendor_default_prop file.
# vendor_init is the context vendor properties are actually meant to be set
# from, and init is the owner of every property file, so try both. Each gets a
# debug. marker next to it: the marker says "this context could exec and write
# a property at all", which is what tells a context failure apart from a
# property-specific one.
# Narrowing to one question: can resetprop CREATE a new "ro." property at all?
# Everything else is now controlled for -- same exec form, same context, same
# trigger, adjacent lines, all four brand-new names.
#
#   gamma only            -> resetprop cannot create ro. properties, full stop
#   gamma + alpha         -> it can, but not in the ro.vendor namespace
#   gamma + alpha + beta  -> it can, and something is specific to tct.endurance
#
# u:r:init:s0 throughout: it is the only named context confirmed to both exec
# and write properties on this device (vendor_init cannot even be exec'd into,
# and bare `exec --` is dropped outright).
# resetprop CAN create new ro. properties -- ro.flipflex.alpha proved it. What it
# cannot do is create one in the vendor_default_prop context area, which is where
# every ro.vendor.* name resolves. Renaming is not an option: property lookup
# maps a name to its context area, so creating it under default_prop would leave
# PackageManagerService unable to find it.
#
# So: grant the access first with magiskpolicy --live, then set it. No avc is
# logged for the failure, but Android's sepolicy is full of dontaudit rules that
# suppress exactly this, so silence is not evidence of permission.
#
# debug.flipflex.quoted is a control: it is the first quoted argument containing
# a space we have ever asked this init to parse, and magiskpolicy needs one. If
# it comes back as "a b" the quoting works and a failure below is really about
# policy; if it is empty, the rule never reached magiskpolicy at all.
# Quoted arguments containing spaces DO parse (debug.flipflex.quoted came back
# as "a b"), so sh -c is usable after all -- the earlier sh -c failures were
# `setprop` being refused from a child process, not the quoting. resetprop -n
# works fine from a child, so it is what reports results here.
#
# That finally allows capturing stderr instead of inferring from silence. Still
# no '$' anywhere: init drops any command containing one it cannot expand, so
# no command substitution, and exit status is reported via && / || instead.
# This is neutronscott/flip2's create-boot recipe, verbatim, because it is
# confirmed working on 4058G-family hardware and ours is not. ${MAGISKTMP} is
# substituted by magiskinit before init ever parses this, so the '$' never
# reaches init's expander. u:r:magisk:s0 because Magisk's own policy patch makes
# that domain unconstrained AND permissive -- SELinux cannot be the obstacle
# there. -n is redundant (resetprop forces skip_svc for any ro. key) but
# harmless, and keeping it matches the known-good recipe exactly.
#
# Do not judge the outcome by `getprop`. __system_property_foreach silently
# skips any context the calling process cannot open, so a bare `getprop | grep`
# can show nothing for a property that was set perfectly well. The only honest
# test is whether an APK installs.
#
# flip2's wiki records this property as racy even on this recipe -- "sometimes
# you cannot install APKs later, just reboot" -- so a single failed boot is not
# evidence the approach is wrong.
on post-fs-data
    setprop debug.flipflex.pfd 1
    exec u:r:magisk:s0 root root -- ${MAGISKTMP}/magisk resetprop -n ro.vendor.tct.endurance true
EOF

# The payload. Marker files rather than properties: a file created from the
# magisk context gets a label adb shell cannot stat, but the NAME is still
# visible in a plain `ls /dev`, which is all we need to know it ran.
SH="$root/vendor/flipflex.sh"
cat > "$SH" <<'EOF'
#!/system/bin/sh
# Runs as root from init, via overlay.d. Argument names the call site.
TAG="$1"
/system/bin/toybox touch "/dev/ffs-ran-$TAG"
/debug_ramdisk/magisk resetprop -n ro.vendor.tct.endurance true \
    && /system/bin/toybox touch "/dev/ffs-set-$TAG"
/system/bin/toybox touch "/dev/ffs-end-$TAG"
EOF

echo "==> the .rc to inject"
sed 's/^/    /' "$RC"

adb devices | sed 1d | grep -qw recovery || {
	echo "Phone is not in recovery. From Android: adb reboot recovery" >&2
	exit 1
}
adb shell "su -c id" 2>&1 | grep -q "uid=0" || { echo "no root in recovery" >&2; exit 1; }

WORK=/dev/rcwork
adb shell "su -c 'rm -rf $WORK && mkdir -p $WORK && chmod 777 $WORK'" >/dev/null
echo "==> pushing"
adb push "$KIT/magiskboot" "$WORK/" >/dev/null
adb push "$IMG" "$WORK/boot.img" >/dev/null
adb push "$RC" "$WORK/init.flipflex.rc" >/dev/null
adb push "$SH" "$WORK/flipflex.sh" >/dev/null
adb shell "su -c 'chmod 755 $WORK/magiskboot'" >/dev/null

echo "==> unpack, inject, repack"
# overlay.d and overlay.d/sbin already exist in a Magisk-patched ramdisk, so the
# mkdirs would fail -- hence `|| true` on those alone, never on the adds.
adb shell "su -c 'cd $WORK && ./magiskboot unpack boot.img && (./magiskboot cpio ramdisk.cpio \"mkdir 0750 overlay.d\" || true) && (./magiskboot cpio ramdisk.cpio \"mkdir 0750 overlay.d/sbin\" || true) && ./magiskboot cpio ramdisk.cpio \"add 0644 overlay.d/init.flipflex.rc init.flipflex.rc\" \"add 0755 overlay.d/flipflex.sh flipflex.sh\" \"add 0755 overlay.d/sbin/flipflex.sh flipflex.sh\" && ./magiskboot repack boot.img'" 2>&1 | sed 's/^/    /'

echo "==> confirming the file is really in the new ramdisk"
adb shell "su -c 'cd $WORK && rm -rf chk && mkdir chk && cd chk && ../magiskboot unpack ../new-boot.img >/dev/null 2>&1 && ../magiskboot cpio ramdisk.cpio \"ls -r\" 2>/dev/null | grep overlay.d'" 2>&1 | tr -d '\r' | sed 's/^/    /'

echo "==> pulling"
adb exec-out "su -c 'busybox stty raw; cat $WORK/new-boot.img'" > "$OUT"

sz=$(stat -f%z "$OUT")
echo
printf '%-14s %s bytes\n' "$(basename "$OUT")" "$sz"
# Hard failure, not a note. This pull is a 24 MB stream over adb and it WILL be
# cut short if anything kills the command -- a timeout once left a 5.5 MB file
# here, which then got written to the boot partition twice. Delete the bad
# output so a later step cannot pick up a stale truncated image.
if [ "$sz" != 25165824 ]; then
	echo "TRUNCATED PULL: expected 25165824 bytes, got $sz. Removing $OUT." >&2
	rm -f "$OUT"
	exit 1
fi
shasum -a 256 "$OUT"
echo
echo "Next: tools/flash-boot-from-recovery.sh $OUT"
