# Working on FlipFlex

A text-only Plex client for a **TCL 4058G flip phone** (AOSP 11, 240×320).
Sibling to `../OnionOS-PocketFlex`, which does the same job on a Miyoo Mini Plus
and is where all the Plex protocol knowledge came from.

Read `docs/phase0-unlock.md` first — it is the live runbook and says exactly
where we are. This file is the things that cost time to discover.
`docs/keymap.md` has the keypad, read off the stock `.kl` files.

The unlock work is also published, scrubbed of anything unit-specific, at
**https://github.com/jackharvest/tcl-flip-macos-unlock** — tools plus a
`docs/traps.md` of the silent failures. If you fix a bug in a `tools/` script
that exists in both, fix it there too. **Never push `backups/` anywhere**:
`proinfo` carries the IMEI and `nvram`/`nvdata`/`persist` carry per-unit RF
calibration.

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

## Getting root, and driving the on-screen UI

Root needs the Magisk app, which needs installs to work, which needs
`endurance` — so it comes *last*, not first. After the app is installed and has
done its own setup reboot:

1. Magisk **Settings → Superuser access** is already `Apps and ADB`. Not the problem.
2. **Superuser tab → `[Share dUID] com.android.shell` → toggle ON.** This is the
   one that matters. The first `su` request pops a dialog with a **10 second**
   timeout; if nobody answers it, Magisk stores a *deny* policy for that uid and
   every later request fails instantly with no prompt at all. That looks like a
   broken setting and is not.

**The Magisk UI cannot be navigated on this handset** — 240×320 with a D-pad
cannot reach the settings gear. Drive it over adb instead, which is how the
above was actually done:

```sh
adb exec-out screencap -p > /tmp/s.png     # then just look at it
adb shell uiautomator dump /sdcard/ui.xml  # exact tap coordinates, needs screen awake
adb shell input tap <x> <y>
adb shell input swipe 120 270 120 70 300   # scroll down
```

`uiautomator dump` returns `null root node` if the screen is asleep —
`input keyevent KEYCODE_WAKEUP` first. The dump is far more reliable than
reading coordinates off a screenshot, and the screenshot is more reliable than
guessing which screen you are on.

This is also the clearest possible statement of why FlipFlex exists: a stock
Android app's UI is simply not operable on this device.

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

**What the refusal actually looks like**, because the error message is a lie:

```
adb: Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed rename]
```

with 11 GB free. Storage has nothing to do with it. The real event is in logcat:

```
PackageManager: mIsAllowInstall= false,APK_INSTALL_FINISH=true
PackageManager: App forbidden installation <pkg>
  PackageManagerService$PrepareFailure: App forbidden installation
    at PackageManagerService.installPackagesLI(PackageManagerService.java:17165)
```

So TCL patched `PackageManagerService` itself. `strings` on
`/system/framework/services.jar` shows `mIsEndurance`, `mIsAllowInstall`,
`APK_INSTALL_FINISH` and `ro.vendor.tct.endurance` together, and
**`ro.vendor.tct.endurance` is the only `tct` property in the entire jar** — so
that really is the single gate, and there is no second flag to discover later.

It also confirms the fix has to happen at boot: `setprop` on a `ro.` property is
refused at runtime (`Failed to set property`), which is why this needs
`resetprop` from Magisk rather than anything simpler. Unlocking does not relax
the check.

### `getprop` CANNOT see this property. Never validate with it.

**This is the single most expensive thing in the project to have learned.** It
cost roughly eight flash-and-reboot cycles chasing a bug that did not exist.

`ro.vendor.tct.endurance` resolves to the `vendor_default_prop` SELinux context,
and `adb shell` cannot open that context's property area. Both lookup paths then
lie to you, in the same direction:

- `getprop | grep endurance` — `__system_property_foreach` **silently skips any
  context the caller cannot open**, so the property is simply absent from the list.
- `getprop ro.vendor.tct.endurance` — `__system_property_find` resolves the name
  to that same unopenable area and returns nothing.

So the property reads empty from adb **whether or not it is set**. It was in fact
being set correctly for many attempts before that was understood.

Proven side by side on one boot, once root was available:

```
getprop ro.vendor.tct.endurance                    ->  []       (as shell)
su -c 'getprop ro.vendor.tct.endurance'            ->  [true]   (as root)
su -c 'getprop | grep endurance'  ->  [ro.vendor.tct.endurance]: [true]
```

**Read it as root, or not at all.**

Do not be reassured by other `ro.vendor.*` properties being readable — 52 of them
are, but they live in `vendor_mtk_default_prop` and `exported_default_prop`,
which are different contexts. Check with `getprop -Z <name>` before drawing any
conclusion from a readable neighbour.

**The only honest test is to install an APK.** And wait for
`sys.boot_completed=1` first, or you get `cmd: Can't find service: package`,
which is system_server not being up yet and nothing to do with the block.

### The recipe that works

`tools/inject-endurance.sh` injects this into the ramdisk's `overlay.d`. It is
neutronscott/flip2's `create-boot` recipe verbatim, which is why it is trusted:

```
on post-fs-data
    exec u:r:magisk:s0 root root -- ${MAGISKTMP}/magisk resetprop -n ro.vendor.tct.endurance true
```

`${MAGISKTMP}` is substituted by magiskinit before init parses the file, so the
`$` never reaches init's expander. flip2's wiki records this property as racy
even on this recipe — "sometimes you cannot install APKs later, just reboot" —
so one bad boot is not evidence the approach is wrong.

### init .rc rules on this device, all learned the hard way

Every one of these fails **silently**, which is what made them expensive:

| Rule | What happens if you break it |
|---|---|
| **No `$` anywhere** | init drops the whole command at parse time. `echo rc=$?` made every command containing it vanish, looking exactly like it ran and failed |
| **Always give `<seclabel> <user> <group>`** | bare `exec -- cmd` is dropped outright — no marker file, no property, nothing |
| **`setprop` from a child process is denied** | markers set by `exec ... sh -c "... setprop ..."` never appear. `resetprop -n` works fine from a child; use it to report |
| **No nested quotes** | `sh -c "... 'inner' ..."` never runs. Plain `sh -c "..."` with a space *is* fine |

And Magisk's own failures are silent **by construction**: its vendored bionic
`#define`s `async_safe_format_log` to a no-op, `SysProp::add()` discards the
return value of `__system_property_add2`, and resetprop's set mode always exits
0. There is no error to find, so do not go looking for one — instrument instead.

The community answer is to flash `neutron.img` from `neutronscott/flip2`.
**We are not doing that**, on evidence from their own tracker:

- their image is built from a **KEEZ** boot (issue #28)
- a user on **KEKA** flashed it and got a bootloop (issue #24)
- our build is **UPCI** on a 4058G, a combination that appears in *no* issue —
  their community is 4058W / T408DL on KEEZ/KEFS/KEKA/KEE7, and 4058E on QK6J

Instead: dump **our own** stock boot, patch that, flash it back. Same
destination, no build mismatch to gamble on. Getting at it turned out to be the
hard part — see the mtkclient verdict below; the answer is `recovery2.img`.

Then, rather than baking the property into the image the way `neutron.img`
does, get plain Magisk root first and set the property separately —
`resetprop -n ro.vendor.tct.endurance true`, persisted via
`/data/adb/post-fs-data.d/`. Two independently debuggable steps instead of one
compound failure.

**`magiskboot` has no macOS build** — it ships only as Android ELF, so the patch
has to run on an ARM Android system somewhere. **Run it on the phone, in
`recovery2.img` — not in an emulator.** `tools/setup-magisk.sh` extracts the kit,
`tools/patch-boot.sh` drives it.

The emulator route was the original plan and it would have bootlooped the phone.
`boot_patch.sh` does `magiskboot cpio ramdisk.cpio "add 0750 init magiskinit"` —
it injects whichever `magiskinit` sits **in its own directory** and makes it
`/init`. In the Magisk app's flow that directory is the app's native library
dir, so the injected binary is the ABI of *whatever machine did the patching*.
This phone is `ro.product.cpu.abi=armeabi-v7a` with no `abilist64`, so patching
on an arm64 emulator writes an **arm64 `/init` for a CPU that cannot execute
64-bit code** — an unrunnable ELF as PID 1, and a bootloop whose cause is
invisible in the image. Patching inside recovery on the phone makes the ABI
correct by construction. (Apple Silicon also cannot execute AArch32 at all, so
an `armeabi-v7a` emulator would be full software emulation.)

`patch-boot.sh` re-extracts the injected `/init` afterwards and asserts it is
32-bit ARM. Do not remove that check — it is the difference between finding this
class of mistake in a second and finding it in a bootloop.

**The Magisk flags are measured, not guessed.** `boot_patch.sh` honours
pre-set env vars, and `patch-boot.sh` passes all five explicitly because
`get_flags()` derives them from mounts that look different inside recovery. The
values came off the live stock device before the unlock:

| Flag | Value | Because |
|---|---|---|
| `KEEPVERITY` | `true` | `/` is `/dev/block/dm-3`, not `rootfs` → system-as-root |
| `KEEPFORCEENCRYPT` | `true` | `/data` on `dm-6`, `ro.crypto.state=encrypted` |
| `PATCHVBMETAFLAG` | `false` | a real `vbmeta` exists (`mmcblk0p36`) |
| `LEGACYSAR` | `false` | no `skip_initramfs` in `/proc/cmdline` |
| `PREINITDEVICE` | `persist` | exists (`mmcblk0p4`), survives factory reset |

**mtkclient cannot attach to this phone, and that is settled.** BROM
(`0e8d:0003`) never appears on the bus no matter which keys are held; only the
preloader (`0e8d:2000`) does, for two to three seconds per power-up. mtkclient
sees it and fails the handshake identically over libusb *and* `--serialport`,
which points at TCL's preloader simply not implementing MediaTek's standard
handshake — it speaks only its own 8-byte command protocol. That is why flip2
built `autobooter` rather than using mtkclient, and it is why the read path has
to be recovery-with-`su` instead, which in turn is why the unlock must come
*before* the backup rather than after.

**The read path is `recovery2.img`** — neutronscott's recovery with `su` and
`busybox` baked in, from `http://scottn.us/downloads/recovery2.img` (**plain
HTTP only; the host serves nothing over TLS**, so verify what you get:
sha256 `394ad6bb38321565b121dec8c5dff098cd238919b766f9cbc0c994f4f0a376ac`,
25,165,824 bytes, which is exactly `partition-size:boot`). `tools/dump-from-recovery.sh`
drives it.

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
