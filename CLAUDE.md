# Working on FlipFlex

A text-only Plex client for a **TCL 4058G flip phone** (AOSP 11, 240×320).
Sibling to `../OnionOS-PocketFlex`, which does the same job on a Miyoo Mini Plus
and is where all the Plex protocol knowledge came from.

Read `docs/phase0-unlock.md` first — it is the live runbook and says exactly
where we are. This file is the things that cost time to discover.

## The device, as measured (not as advertised)

Everything here came off the real unit via `tools/recon.sh`, not a spec sheet.

| | |
|---|---|
| Model / codename | `4058G` / `Gflip6_NA_OM` |
| Build | `TCL/4058G/Gflip6_NA_OM:11/RP1A.200720.011/UPCI:user/release-keys`, 20 Dec 2024 |
| SoC | MT6739, **32-bit only** — `zygote32`, `armeabi-v7a`, no `abilist64` |
| RAM / heap | 916 MB total, `dalvik.vm.heapgrowthlimit=128m` |
| Screen | 240×320, **density 160 (mdpi)** — a true 240×320 dp canvas |
| Launcher | stock AOSP `com.android.launcher3` |
| Storage | 11 GB free on `/data` |

Three of those killed risks that were in the original plan:

- **Density is 160.** The row layout gets the full 240×320 dp. Had TCL shipped
  240 dpi it would have been 160×213 and every measurement would have halved.
- **The launcher is stock Launcher3**, not a TCL shell, so a sideloaded app will
  appear in the drawer. The "register as `category.HOME`" fallback is insurance,
  not the plan.
- **32-bit only.** Nothing we ship needs native code (Media3's core is pure Java
  over MediaCodec), but it means an arm64 emulator is *not* ABI-faithful, and
  any future native dependency must ship `armeabi-v7a`.

## Environment gotchas on this Mac

**mtkclient needs Python 3.10+, not the 3.9 its README says.**
`Library/Exploit/heapbait.py` uses PEP 604 `HeapParams | None` annotations,
which are a syntax error before 3.10. We run it on `/opt/homebrew/bin/python3.14`.

**mtkclient dies at import without macFUSE, and the guard for that is wrong.**
Two places do `from mfusepy import ...` under `except ImportError`, but mfusepy
raises `OSError('Unable to find libfuse')`. So the CLI was unusable on any Mac
without a kernel extension installed, for a feature we never call. Both are
patched to `except (ImportError, OSError)` — the author's own no-op fallbacks
were already there and correctly guarded at the call site. Carried as
`vendor/mtkclient-macos-fuse.patch`; **do not install macFUSE to work around
this**, it wants a kext approval and a reboot for nothing.

Skip `pyside6`/`shiboken6` from their `requirements.txt` — GUI only, and the
most fragile part of the install.

## Driving the phone

```sh
tools/recon.sh                  # read-only device survey; run it after any flash
tools/mtk printgpt              # mtkclient, correct interpreter and cwd
tools/backup.sh critical        # ~2 min, enough to un-brick
tools/backup.sh full            # ~1-2 h, the shareable flash.bin
tools/setup-mtkclient.sh        # rebuild vendor/mtkclient from scratch
```

ADB was enabled by dialling `*#*#33284#*#*`. **This is a resource that an OTA
can take away** — flip2 issue #42 reports newer TCL builds disabling that code.
`com.tcl.fota.system` is therefore `disabled-user`; re-enable with
`adb shell pm enable com.tcl.fota.system` if you ever want updates back.

## The unlock, and why we are not following the wiki

The 4058 vendor framework refuses every APK install unless
`ro.vendor.tct.endurance` is true, and that property cannot be set without root.

The community answer is to flash `neutron.img` from `neutronscott/flip2`.
**We are not doing that**, on evidence from their own tracker:

- their image is built from a **KEEZ** boot (issue #28)
- a user on **KEKA** flashed it and got a bootloop (issue #24)
- our build is **UPCI** on a 4058G, a combination that appears in *no* issue —
  their community is 4058W / T408DL on KEEZ/KEFS/KEKA/KEE7, and 4058E on QK6J

Instead: dump **our own** stock boot with mtkclient in BROM mode (no root, no
unlock, no ADB needed), patch that, flash it back. Same destination, no build
mismatch to gamble on.

Then, rather than baking the property into the image the way `neutron.img`
does, get plain Magisk root first and set the property separately —
`resetprop -n ro.vendor.tct.endurance true`, persisted via
`/data/adb/post-fs-data.d/`. Two independently debuggable steps instead of one
compound failure.

**`magiskboot` has no macOS build** — it ships only as Android ELF. Patch the
dumped boot by loading it into an Android emulator and using the Magisk app's
"Select and Patch a File", which needs no root, then `adb pull` the result.

## Backups are not optional

flip2 issues #50 and #55 are both people whose phone still answered in BROM but
who had no image to restore. **The chip surviving is not the same as the phone
surviving.** `tools/backup.sh critical` before any write; `full` is worth doing
because a verified 4058G `flash.bin` does not exist publicly and #55 is someone
who needed exactly that.

`backups/` is gitignored — some of those partitions carry IMEI and calibration
data and must not leave the machine by accident.

## Conventions

- Comments explain *why*, naming the real case that forced the code. Same house
  style as PocketFlex.
- Commit after every working change; never leave the tree dirty. Rollback speed
  matters more here than tidy history, because the device work is destructive.
- Anything learned about the hardware goes in this file or the runbook at the
  moment it is learned, not later.
