# Working on FlipFlex

A text-only Plex client for a **TCL 4058G flip phone** (AOSP 11, 240×320).
Sibling to `../OnionOS-PocketFlex`, which does the same job on a Miyoo Mini Plus
and is where all the Plex protocol knowledge came from.

Read `docs/phase2-playback.md` first — it is the live runbook and says exactly
where we are. `docs/phase0-unlock.md` is the closed unlock phase, kept because
its recovery table still matters. `docs/launcher-menu.md` is how the app gets
into the phone's Menu, which took a resource overlay and a package rename. This
file is the things that cost time to discover. `docs/keymap.md` has the keypad
as measured, what FlipFlex binds each key to, and the first-run tour that
teaches it.

**Phase 2 is proven: sign in → find server → browse → transcode → play →
report position → tear down, on the real handset against a real server.**
**Phase 3 adds the details page, subtitles, quality, search and downloads —
including offline playback with both radios switched off.**

**Shipped as v1.0.0 on 2026-07-31**, public at
**https://github.com/jackharvest/FlipFlex**. `README.md` is the landing page and
is written for someone who has never seen the phone; this file is written for
whoever has to change the code. Read *Shipping it* below before cutting another
release — the signing key is the one thing here that cannot be replaced.

Navigation has two rules that are not obvious from the code and are load-bearing
for everything else: **left is a second Back** on every screen but the player,
and **up walks list → tab strip → the `‹ Title` header**, where OK also goes up
a level. Both exist because the back arrow is one small mechanical key and
neither soft key is Back. Digits pick a tab by number. See `docs/keymap.md`.

**The package is `com.github.jackharvest.flipflex`.** It was
`io.github.jackharvest.flipflex` until the launcher work, and the rename is not
cosmetic: TCL's Launcher3 only treats an entry as a package name if it starts
with `com` or `org`, so the `io.` name could never appear in the Menu. See
`docs/launcher-menu.md`.

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
- ~~**The launcher is stock Launcher3**, not a TCL shell, so a sideloaded app
  will appear in the drawer.~~ **Wrong, and it was expensive.** `HOME` does
  resolve to `com.android.launcher3`, but the build is a TCL fork that never
  enumerates installed apps: it walks a hardcoded `allapp_list` array of package
  names and drops everything not in it. A sideloaded app appears nowhere but
  Recent Apps, however correct its manifest — Magisk is invisible for the same
  reason. The fix is a runtime resource overlay shipped as a Magisk module,
  plus a package name starting with `com`. See `docs/launcher-menu.md`, and do
  not reason about this launcher from AOSP's.
- **32-bit only.** Nothing we ship needs native code (Media3's core is pure Java
  over MediaCodec), but it means an arm64 emulator is *not* ABI-faithful, and
  any future native dependency must ship `armeabi-v7a`.

## App-layer traps, all of which failed silently or misleadingly

**`optString` cannot be used on Plex JSON.** `JSONObject.optString(name,
fallback)` returns the fallback only when the key is *absent*. For a key that is
present and explicitly `null` it returns the **four-character string `"null"`**,
because `JSONObject.NULL` is a sentinel object that `String.valueOf()` renders.
Plex uses explicit nulls everywhere — `authToken` before you link, `accessToken`
on a server you own, `grandparentRatingKey` on a movie. This stored `"null"` as
the account token and walked past the sign-in screen entirely. `plex/Json.kt`
exists for this; use `str()`/`strOrNull()` and grep for `optString` before
merging.

**Plex HTML-escapes its JSON, so every string needs unescaping.**
`plex.tv/api/v2/user` reports an account called *Alice & Bob* as
`"title": "Alice &amp; Bob"` — the server escapes user-visible strings as if
they were going into an HTML page, and does it in the JSON as well as the XML.
JSON needs none of that, so a client that takes the value at face value paints
the entity. It showed up on the splash and would equally have hit every film
with an ampersand or an apostrophe in its name. `Json.unescape` handles it, at
the same `str()`/`strOrNull()` choke point as the `optString` problem. **It is
one left-to-right pass on purpose** — a chain of replacements expands `&amp;lt;`
to `&lt;` and then to `<`, so a title that really did contain `&lt;` comes out
as a tag.

**`X-Plex-Platform: Android` cannot ask for a file.** The universal transcode
endpoints are served per client profile, and the built-in Android profile has no
single-file form: `start.mkv` with `protocol=http` answers a bare 400 with 89
bytes of HTML. `Chrome` serves the same request 49 MB of Matroska. HLS works
under both. So streaming keeps `Android` (honest, and what makes the server's
session list say `FlipFlex`) and **downloads claim `Chrome`** —
`PlexClient.PLATFORM_FILE`. Header and query string must agree.

Measuring this needs care: run the four combinations back to back and the
results contradict each other, because Plex refuses a new transcode for an item
it thinks still has a live session and that refusal is item-scoped. Bracket
every probe with a `state=stopped` timeline or the second reading of any pair is
about the session, not the platform.

**Subtitles are burned in, not soft.** An earlier note said ExoPlayer could
render a soft track so no burn-in was needed. It can — for text formats. It
cannot draw PGS or VOBSUB at all, which is what a Blu-ray rip carries, so a soft
path would work on part of a library and silently do nothing on the rest.
Everything is already transcoded, so `subtitles=burn` costs nothing extra and
gives one behaviour for every file. `subtitleSize` is a setting because Plex's
100 is sized for a television.

**A downloaded file has no seek index, and Plex will not give you one.**
`start.mkv` is muxed live into the socket, so it has an unknown-size Segment
and **no Cues element** — and `MatroskaExtractor` builds its seek map from Cues
and nothing else. With none, every `seekTo` collapses to zero: measured as six
presses of forward leaving the clock at 0:01 and restarting the picture. There
is no way round it on the server side. Plex **ignores the extension** —
`start.mp4` and `start.ts` both return Matroska, byte-identical in size — and
every profile that serves a single file gives **MP3** audio, which `MediaMuxer`
cannot write into MP4, so remuxing is out too. `dl/MatroskaIndex.kt` writes the
index instead: one linear walk, a cue point every five seconds, spliced in
*ahead of the first cluster* because the extractor parses forwards and never
follows the SeekHead. Fixed-width cue fields are load-bearing — see the class
comment.

**A paused transcode gets reaped, and the failure arrives minutes later.**
Plex keeps a transcode alive on segment requests. Closing the lid stops those,
Plex reaps the session but **leaves the segments it already produced** — so
playback resumes perfectly, runs on for several minutes, and only then dies on
the first segment that was never written. Measured: `/status/sessions` showed
FlipFlex playing with no transcode session behind it at all. The moment of the
error tells you nothing about the cause. `PlexPlayback.ping` every ten seconds
while not playing prevents most of it; `PlayerActivity.rebuildStream` catches
the rest by asking for a **new session id** at the current position.

**The handset drops its own Wi-Fi.** `CTRL-EVENT-DISCONNECTED reason=3
locally_generated=1` about eighteen seconds after a successful four-way
handshake, at −38 dBm, with no IPv4 ever assigned — DHCP never completes and
Android tears down the un-provisioned link. The AP is in WPA2/WPA3 transition
mode (`[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP]`) and the phone has saved the network
as SAE. This is not an app bug but it kills streams, and it is why
`tools/usb-plex.sh` exists: it puts the phone on the LAN server over the USB
cable so testing does not depend on the radio.

**Surround is already downmixed; do not add a parameter for it.** An 8-channel
E-AC-3 source comes back **2-channel** on both paths — AAC stereo over HLS
under `Android`, MP3 stereo over `start.mkv` under `Chrome`. `maxAudioChannels`,
`audioChannelCount` and a `X-Plex-Client-Profile-Extra` channel limitation all
made no difference because there was nothing to change.

**A minimal scroll can leave the cursor off screen, and a caption is what
triggers it.** `RowList.scrollToCursor` used to pick one anchor row, and for the
first row of a group it picked the *caption above it* so the caption would stay
visible. `scrollToPosition` does not move a list whose target is already
visible, so a caption on the last visible line meant no scroll at all and the
selected row sat just below the viewport — the amber bar simply gone, most
visibly in Settings, which has a caption every four rows. The rule is now that
the selected row is always on screen and the caption is a preference that only
applies going *up*. Do not reintroduce an anchor that is not the cursor.

**A full-screen transient message is a screen, and Back must only dismiss it.**
`showTransientMessage` covers the content frame and the next key takes it down
while still doing its own job — right for the arrow keys, wrong for Back, which
dismissed the message *and* left the activity. Reported as: Subtitles on a file
with no subtitle tracks, then Back, landing two levels up in the library. Back
is consumed when it clears a transient message.

**A failed network call is not a rejected login.** `PlexAuth.validate` used to
return `String?`, where null meant both "plex.tv refused this token" and "plex.tv
could not be reached" — and `SplashActivity` signed out on null. So **opening
the app with no network wiped the token**, on a device whose whole offline story
is a folder of downloads for a train. It returns a three-way `Validation` now,
and only `Rejected` discards anything. Recovering from the old behaviour needs a
second device with a browser, so it is not a small bug.

**Kotlin block comments nest.** A literal slash-star inside a KDoc — as in a
path like `transcode/universal/<star>` — opens a nested comment that never
closes. The error is `Missing '}'` on an unrelated line plus `Unclosed comment`
at EOF, neither of which points at the path.

**XML comments cannot contain `--`.** The prose dashes in every layout are em
dashes because of this; it is a fatal `SAXParseException` at resource merge, not
a warning. Kotlin comments are fine with `--` and still use it.

**`./gradlew` alone fails on a fresh shell** — `Unable to locate a Java
Runtime`. Homebrew's `openjdk@17` is keg-only, so `/usr/libexec/java_home`
cannot see it. Use `tools/build.sh`, which sets `JAVA_HOME`.

## What the SoC can decode

Off `/vendor/etc/media_codecs_mediatek_video.xml`, not a spec sheet. Hardware
decoders for **H.264, HEVC, VP9, MPEG2, MPEG4, VC1 and XVID, all capped at
1600×960** — far more headroom than a 240-wide viewport needs. Encoders are
AVC-only (MPEG4/H263 are locked to 176×144), which nothing here uses.

So the SoC is not the playback constraint; the server's transcoder and the radio
are. FlipFlex currently asks Plex for 320×240 at 800 kbps and forces a transcode
so that one code path covers every file in the library. Direct play is the
obvious next optimisation, not a necessity.

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
tools/build.sh [install|run]    # the APK; sets JAVA_HOME so gradlew works
tools/release.sh [publish]      # signed release APK, tag, GitHub release
tools/usb-plex.sh <ip> [stop]   # reach the LAN Plex server over USB, not Wi-Fi
tools/install-menu-overlay.sh   # the Menu entry, as a Magisk module. Reboots
tools/install-menu-overlay.sh --verify   # after the reboot: is the overlay on?
tools/install-menu-overlay.sh --remove   # put the Menu back as it was
```

ADB was enabled by dialling `*#*#33284#*#*`. **This is a resource that an OTA
can take away** — flip2 issue #42 reports newer TCL builds disabling that code.
`com.tcl.fota.system` is therefore `disabled-user`; re-enable with
`adb shell pm enable com.tcl.fota.system` if you ever want updates back.

## Shipping it — the release key is the irreplaceable part

The repo is public at **https://github.com/jackharvest/FlipFlex** and the APK is
a GitHub release asset. `tools/release.sh` reads the version out of
`app/build.gradle.kts` so the tag, the file name and the string on Settings →
Help cannot disagree; run it with no argument to build and verify, `publish` to
tag and push.

**The keystore lives in `~/.flipflex/`, not in the repo — not even gitignored
inside it.** A file that is not in the tree cannot be `git add -f`ed by mistake,
and this is the one file here whose loss is permanent: Android identifies an app
by its signature, so an APK signed with a different key **cannot** be installed
over one already on a phone. The only way past that is an uninstall, which takes
the Plex token and every downloaded episode with it. Back `~/.flipflex/` up
somewhere that is not this laptop.

That migration has already been paid once: the handset ran a debug-signed build
until 2026-07-31, so going to the release key needed an uninstall. The data was
carried across by hand and it is worth writing down, because the ownership step
is the one that is easy to miss —

```sh
adb shell 'su -c "cp -a /data/data/<pkg> /data/local/tmp/ff-data"'
adb uninstall <pkg> && adb install app-release.apk
adb shell 'su -c "stat -c %u /data/data/<pkg>"'     # a NEW uid, e.g. 10108 -> 10109
adb shell 'su -c "rm -rf /data/data/<pkg>/* &&
                  cp -a /data/local/tmp/ff-data/. /data/data/<pkg>/ &&
                  chown -R <newuid>:<newuid> /data/data/<pkg> &&
                  restorecon -R /data/data/<pkg>"'
```

A reinstall gets a new uid, so a straight copy-back leaves every file owned by
an app that no longer exists and the app sees an empty library. `restorecon`
puts the SELinux labels back; without it the files are there and unreadable.

**Never do this again if it can be avoided.** `adb install -r` with the same key
keeps the data by itself.

Screenshots and GIFs for the README are in `docs/media/`, captured with
`adb exec-out screencap` and `adb shell screenrecord` and converted with
`ffmpeg`. Two notes if they are ever regenerated: `screenrecord --size 320x240`
is needed for the player, or the landscape content arrives letterboxed inside a
240x320 frame; and the **server name and the Plex profile name must not appear**
— the ones in `docs/media/` were taken with `server_name` and `profile_name`
temporarily replaced in `shared_prefs/flipflex.xml`, because the splash and every
home-screen header display them.

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
