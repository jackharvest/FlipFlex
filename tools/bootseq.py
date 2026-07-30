#!/usr/bin/env python3
"""Send a TCL preloader boot sequence over VCOM. macOS port of flip2's autobooter.

    tools/bootseq.py            # FASTBOOT (the default, and what we want)
    tools/bootseq.py METAMETA

Why this exists
---------------
The 4058G's preloader publishes a USB CDC-ACM interface (0e8d:2000, "MT65xx
Preloader") for roughly 2-3 seconds on every power-up, then continues booting.
TCL's preloader accepts a magic 8-byte string on that port which tells it to
stop and enter another mode instead. Sending "FASTBOOT" gets fastboot; the
preloader answers "READY" plus the last three characters of the command,
reversed -- so "FASTBOOT" is confirmed by "READYTOO".

flip2's autobooter.py does this on Windows by reading COM ports out of the
registry. plugnburn's original took a fixed device path. Neither works here:
the port on macOS is /dev/cu.usbmodem<something> and it only exists inside that
2-3 second window, so the name cannot be known in advance and must be polled
for.

Run this BEFORE connecting. It waits.
"""
import glob
import sys
import time

try:
    from serial import Serial
except ImportError:
    sys.exit("pyserial missing. Use vendor/mtkclient/.venv/bin/python, or pip install pyserial")

BOOTSEQ = bytes(sys.argv[1] if len(sys.argv) > 1 else "FASTBOOT", "ascii")
# The preloader echoes the last three bytes back reversed: FASTBOOT -> READYTOO.
CONFIRM = b"READY" + BOOTSEQ[:-4:-1]

# cu.* rather than tty.*: the tty device blocks on open waiting for carrier
# detect, which a preloader never asserts. cu.* is the callout node and opens
# immediately. Getting this wrong looks like a hang, not an error.
PATTERN = "/dev/cu.usbmodem*"


def find_port():
    for path in glob.glob(PATTERN):
        if "debug" not in path.lower():
            return path
    return None


print(f"sending {BOOTSEQ.decode()}, expecting {CONFIRM.decode()}")
print(f"watching {PATTERN} -- connect the phone now (battery out is most reliable)")

deadline = time.time() + 300
spun = 0
while time.time() < deadline:
    port = find_port()
    if not port:
        spun += 1
        if spun % 20 == 0:
            print(".", end="", flush=True)
        time.sleep(0.05)
        continue

    print(f"\nport appeared: {port}")
    try:
        s = Serial(port, 115200, timeout=0.5)
    except OSError as e:
        # Lost the race with enumeration or the window closed. Not fatal --
        # the phone will offer another window on the next power-up.
        print(f"  could not open ({e}); waiting for the next window")
        time.sleep(0.2)
        continue

    # Spam it. We may have caught the port mid-window and the preloader is not
    # always listening on the first byte.
    with s:
        for attempt in range(40):
            try:
                s.write(BOOTSEQ)
                resp = s.read(8)
            except OSError:
                break
            if not resp:
                continue
            print(f"  attempt {attempt}: {resp!r}")
            if resp == CONFIRM:
                print(f"\nACCEPTED -- phone should now be in {BOOTSEQ.decode()} mode")
                sys.exit(0)
    print("  window closed without confirmation; waiting for another")

sys.exit("timed out after 5 minutes")
