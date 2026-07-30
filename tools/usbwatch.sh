#!/bin/bash
# usbwatch.sh -- log every USB device appearing or disappearing, 5x a second.
#
# Exists because BROM enumeration can be a ~1 second window, and "mtkclient
# didn't connect" has two completely different causes that need separating:
# the Mac never saw the device (cable, port, hub, phone not entering BROM), or
# the Mac saw it and mtkclient didn't (permissions, VID/PID, timing). Only one
# of those is worth debugging in Python.
#
# MediaTek is VID 0x0e8d = 3725 decimal, which is how ioreg prints it:
#   0e8d:0003  bootrom (BROM)
#   0e8d:2000  preloader VCOM
#   0e8d:2001  download agent
# Any of the three is a win -- mtkclient accepts all of them.

# Emit one line per device as vid|pid|name. The name is optional and MUST be:
# a MediaTek bootrom typically enumerates with no iProduct string at all, and
# an earlier version of this that only printed named devices reported "nothing
# appeared" while saying nothing about whether anything had.
snap() {
	ioreg -p IOUSB -w0 -l 2>/dev/null | awk '
		# ioreg draws the tree with pipes ("  | | +-o Foo"), so this cannot be
		# anchored to the start of the line.
		/\+-o /               { if (v != "") printf "%s|%s|%s\n", v, p, n; v=""; p=""; n="?" }
		/"idVendor" =/        { t=$0; sub(/.*= /,"",t); v=t }
		/"idProduct" =/       { t=$0; sub(/.*= /,"",t); p=t }
		/"USB Product Name"/  { t=$0; sub(/.*= "/,"",t); sub(/"$/,"",t); n=t }
		END                   { if (v != "") printf "%s|%s|%s\n", v, p, n }
	' | sort
}

prev=$(snap)
echo "watching USB. plug the phone in now.  ctrl-C to stop."
echo "baseline: $(printf '%s\n' "$prev" | grep -c .) devices"
echo

while :; do
	cur=$(snap)
	if [ "$cur" != "$prev" ]; then
		while read -r line; do
			[ -z "$line" ] && continue
			sign=${line:0:1}
			rest=${line:2}
			[ -z "$rest" ] && continue
			case "$sign" in
			'>') tag="+ APPEARED" ;;
			'<') tag="- gone    " ;;
			*)   continue ;;
			esac
			# 3725 == 0x0e8d. Shout about it -- this is the whole point.
			case "${rest%%|*}" in
			3725) tag="$tag  *** MEDIATEK -- THIS IS IT ***" ;;
			esac
			printf '%s  %s  %s\n' "$(date +%H:%M:%S)" "$tag" "$rest"
		done < <(diff <(printf '%s\n' "$prev") <(printf '%s\n' "$cur") | grep '^[<>]')
		prev=$cur
	fi
	sleep 0.2
done
