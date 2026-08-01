# Working on FlipFlex

A text-only Plex client for a **TCL 4058G flip phone** (AOSP 11, 240×320).
Sibling to `../OnionOS-PocketFlex`, which does the same job on a Miyoo Mini Plus
and is where all the Plex protocol knowledge came from.

Read `docs/phase2-playback.md` first — it is the live runbook and says exactly
where we are. `docs/launcher-menu.md` is how the app gets into the phone's Menu,
which took a resource overlay and a package rename. This file is the things that
cost time to discover. `docs/keymap.md` has the keypad as measured, what FlipFlex
binds each key to, and the first-run tour that teaches it.

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

**Everything about unlocking the phone now lives in
`../tcl-flip-macos-unlock`**, published at
**https://github.com/jackharvest/tcl-flip-macos-unlock** — every script that
writes to a partition, the `flash.bin` and per-partition dumps under its
`backups/`, the Magisk kit and the mtkclient clone under its `vendor/`, a
`docs/traps.md` of the silent failures, and `notes/` for the narrative of the
run this was all learned on. None of it is duplicated here any more: this repo
is the app, that repo is the handset. **Never push that repo's `backups/`
anywhere** — `proinfo` carries the IMEI and `nvram`/`nvdata`/`persist` carry
per-unit RF calibration.

What remains below about the unlock is only the part an app change can still
trip over: `endurance` is why installs work at all, and Magisk root is what
`tools/install-menu-overlay.sh` needs.

## The device, as measured (not as advertised)

Everything here came off the real unit via the unlock repo's `tools/recon.sh`,
not a spec sheet.

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

**`./gradlew` alone fails on a fresh shell** — see the App-layer traps section.
The mtkclient and Magisk toolchain notes moved to the unlock repo's
`docs/macos-setup.md`, which is the only place they are needed now.

## Root, and driving the on-screen UI

`tools/install-menu-overlay.sh` needs root, so shell `su` has to work. If it
does not, the cause is almost always this: **Magisk Superuser tab → `[Share
dUID] com.android.shell` → toggle ON.** `Settings → Superuser access` already
reads `Apps and ADB` and is not the problem. The first `su` request pops a
dialog with a **10 second** timeout; if nobody answers it, Magisk stores a
*deny* policy for that uid and every later request fails instantly with no
prompt at all. That looks like a broken setting and is not.

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
tools/build.sh [install|run]    # the APK; sets JAVA_HOME so gradlew works
tools/release.sh [publish]      # signed release APK, tag, GitHub release
tools/usb-plex.sh <ip> [stop]   # reach the LAN Plex server over USB, not Wi-Fi
tools/install-menu-overlay.sh   # the Menu entry, as a Magisk module. Reboots
tools/install-menu-overlay.sh --verify   # after the reboot: is the overlay on?
tools/install-menu-overlay.sh --remove   # put the Menu back as it was
```

Anything that reads or writes a partition — `recon.sh`, `backup.sh`, `mtk`,
`patch-boot.sh`, `flash-boot-from-recovery.sh` — is in `../tcl-flip-macos-unlock`
and only there. Run it from that directory, where its `vendor/` and `backups/`
also live.

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

### Driving the UI for a recording, which is not the same as driving it for a test

A long screen capture has to look like the app responding to a person, and
`adb shell input keyevent` cannot do that: **one injected key takes about 1.3
seconds on this handset**, because each call starts an `app_process` JVM. Six
presses of a D-pad then read as a hung app.

`sendevent` on `/dev/input/event2` (the matrix keypad — see `keymap.md` for the
scancodes) costs single-digit milliseconds and arrives with a real scancode, so
the timing in the finished video is the app's own. It needs **root**, and the
reason is not the group: `adb shell` is already in `input`, and the open still
fails, because SELinux does not let the `shell` domain touch an input device.

The trap is that `su` is what ruins the recording. Magisk toasts *Shell was
granted Superuser rights* over whatever is on screen, and it does it again on
later grants, not just the first — so a per-chapter `su -c` puts a grey box in
the middle of some arbitrary chapter. **Hold one long-lived root shell for the
whole session** and feed it scripts; the toast then happens once, before any
recording starts. (`su_notification` in Magisk's sqlite would silence it too,
but one shell is less to put back afterwards.)

Two more things worth not rediscovering. Key repeats faster than about **0.3 s
apart get dropped**, and a dropped press desynchronises every index that
follows it, so a chapter ends up somewhere it was never meant to go — verify
the starting state with a screenshot before each take rather than assuming it.
And recording the player at the native 240x320 does letterbox the landscape
window as the note above says, but the **picture-area difference survives it**:
landscape lands about 240x180 against portrait's 240x135, which is exactly the
comparison a rotation demo is trying to show. Record at 240x320 when the
finished video is portrait; use `--size 320x240` only when the player is the
whole subject.

## The unlock, in the two paragraphs an app change can trip over

The whole of it — bootloader, recovery, dumping boot, patching it with Magisk,
flashing it back, and every silent failure on the way — is
`../tcl-flip-macos-unlock`. Do not re-derive any of it here. What follows is
only the part that can bite you while working on the *app*.

**`ro.vendor.tct.endurance` is why installs work at all.** The 4058 vendor
framework refuses every APK install while it is false, and the refusal lies
about why:

```
adb: Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed rename]
```

with 11 GB free. Storage has nothing to do with it; the real event is
`PackageManager: App forbidden installation <pkg>` in logcat. If `tools/build.sh
install` ever starts failing like that, the boot image has been replaced or the
property did not get set this boot — reboot once, and if it persists the answer
is in the unlock repo, not in this one. flip2's wiki records the property as
racy even on a known-good recipe, so one bad boot is not evidence of anything.

**Never validate it with `getprop`.** It resolves to the `vendor_default_prop`
SELinux context, which `adb shell` cannot open, and *both* lookup paths then
fail silently in the same direction — the property reads empty whether or not
it is set. Read it as root or not at all:

```
getprop ro.vendor.tct.endurance                    ->  []       (as shell)
su -c 'getprop ro.vendor.tct.endurance'            ->  [true]   (as root)
```

This cost about eight flash-and-reboot cycles to learn. The only honest test is
to install an APK — and wait for `sys.boot_completed=1` first, or you get
`cmd: Can't find service: package`, which is system_server not being up yet and
nothing to do with the block.

**ADB itself was enabled by dialling `*#*#33284#*#*`, and an OTA can take that
away** — flip2 issue #42 reports newer TCL builds disabling the code.
`com.tcl.fota.system` is therefore `disabled-user`; re-enable with
`adb shell pm enable com.tcl.fota.system` if you ever want updates back. Losing
ADB on this phone means losing the ability to install a build at all.

## Conventions

- Comments explain *why*, naming the real case that forced the code. Same house
  style as PocketFlex.
- Commit after every working change; never leave the tree dirty. Rollback speed
  matters more here than tidy history, because the device work is destructive.
- Anything learned about the hardware goes in this file or the runbook at the
  moment it is learned, not later.
