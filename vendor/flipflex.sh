#!/system/bin/sh
# Runs as root from init, via overlay.d. Argument names the call site.
TAG="$1"
/system/bin/toybox touch "/dev/ffs-ran-$TAG"
/debug_ramdisk/magisk resetprop -n ro.vendor.tct.endurance true \
    && /system/bin/toybox touch "/dev/ffs-set-$TAG"
/system/bin/toybox touch "/dev/ffs-end-$TAG"
