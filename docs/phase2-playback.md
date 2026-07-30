# Phase 2 — connect to Plex and play something

**Live runbook. Update the status line after every step.**

> **STATUS: THE FULL PATH IS PROVEN.** Sign in → find server → browse →
> transcode → decode → report position → tear down. Measured on the real 4058G
> against a real server on 2026-07-30, not in an emulator.
>
> Everything below the "What is proven" table is either a trap that cost time or
> a decision with a reason. Read those before changing the corresponding code.

## What is proven, and how it was checked

Each of these was verified against the server, not just by the app looking happy.

| Step | Evidence |
|---|---|
| Sign-in | `plex.tv/link` four-character code, token stored |
| Server discovery | Picked `192-168-1-50.<hash>.plex.direct:32400` — same `/24` as the phone, so the rank-0 path, no fallback needed |
| Browse | Continue Watching, six libraries, seasons, episodes, all with real resume state |
| Transcode + play | HLS, hardware AVC video + `c2.android.aac.decoder` audio, 1:25:30 title |
| Identity | Server session list shows `product: FlipFlex` |
| Progress reporting | `viewOffset 50360` while playing, server-side |
| Pause on background | `state: paused`, `viewOffset 82713` held |
| Resume point persists | `/library/onDeck` → `viewOffset 82713 of 5129594` |
| Teardown | After leaving the player: **0 open transcodes, 0 sessions** |

The resume point then came back round: reopening Continue Watching showed
*Queen of Tears · 1h 24m left* with a progress bar, read from the server.

## The traps, in the order they cost time

### `optString` cannot be used on Plex JSON. This is the big one.

`JSONObject.optString(name, fallback)` returns the fallback **only when the key
is absent**. When the key is present and explicitly `null`, it returns the
four-character string `"null"` — `JSONObject.NULL` is a sentinel object and
`String.valueOf()` renders it.

Plex uses explicit nulls constantly: `authToken` before you link, `accessToken`
on a server you own, `grandparentRatingKey` on anything that is not an episode.

The failure was not subtle and it was not obvious. `/pins/<id>` answers
`{"authToken": null}` until the user types the code, so:

```
optString("authToken", "").ifEmpty { null }   ->  "null"   (non-empty!)
```

The app stored `"null"` as the account token, walked straight past the sign-in
screen, and every later call would have 401'd in a way that looks exactly like
an expired login. On screen it looked like the PIN appearing for five seconds
and then vanishing.

**`plex/Json.kt` exists solely for this.** Use `str()` / `strOrNull()`. There
should be no `optString` call anywhere in `plex/`; grep for it before merging.

### Kotlin block comments nest

`/** ... /video/:/transcode/universal/* ... */` in a KDoc opens a **nested**
comment at the `/*` inside the path, which never closes. The whole file then
fails to compile and the reported error is `Missing '}'` on an unrelated line
plus `Unclosed comment` at EOF — neither of which points at the path.

Unlike Java and C, Kotlin nests block comments. Do not write a literal
slash-star inside one, even in a code span.

### XML comments cannot contain `--`

Same house style, different language. `<!-- ... A -- B ... -->` is a fatal
`SAXParseException` at resource-merge time. The prose dashes in every layout are
em dashes for this reason. Kotlin comments are unaffected and still use `--`.

### `./gradlew` fails on a fresh shell

Homebrew's `openjdk@17` is keg-only, so `/usr/libexec/java_home` cannot see it
and Gradle reports `Unable to locate a Java Runtime`. Nothing in the repo
recorded this — the build worked only in whichever shell happened to have
`JAVA_HOME` exported. **Use `tools/build.sh`**, which sets it.

## Decisions, with reasons

### Transcode always, for now

`directPlay=0&directStream=0`. Not because the SoC needs it — the decoder table
in `/vendor/etc/media_codecs_mediatek_video.xml` is generous:

| Codec | Hardware decoder | Max |
|---|---|---|
| H.264 | `OMX.MTK.VIDEO.DECODER.AVC` | 1600×960 |
| HEVC / VP9 / MPEG2 / MPEG4 / VC1 / XVID | all present | 1600×960 |

(Encoders are AVC-only; MPEG4/H263 are locked to 176×144. Irrelevant to us.)

The reason is that one code path behaves the same for every file in the library,
so a failure is a failure of *our* plumbing rather than of whatever container
that one file happens to use. **Direct play is the obvious next optimisation**
now that this path is proven — it would remove the server's transcoder from the
critical path entirely for files the MT6739 can already decode.

### 320×240 at 800 kbps

The panel is 240×320 at density 160, so a 16:9 video occupies 240×135 of it.
320×240 still oversamples that. The constraint being respected is the server's
transcoder and the radio, not the SoC.

### HLS, not a progressive stream

A stalled segment is recoverable; a broken pipe on a continuous stream just ends
playback. Same reasoning PocketFlex used.

### `X-Plex-Platform: Android`

PocketFlex had to lie and claim `Chrome`, because Plex only serves the
`transcode/universal` endpoints for a platform it has a client profile for, and
`Linux` produced a bare 400 with no body. `Android` is both honest and certainly
profiled, and it works — the server lists us as `product: FlipFlex`.

If a transcode ever 400s with a good token and a good ratingKey, this constant
is the first thing to suspect.

### `runBlocking` in `PlayerActivity.onDestroy`

Normally the wrong tool. It is correct here because `lifecycleScope` is
cancelled the moment `onDestroy` returns, so a `launch {}` would be killed
before the request left the device — and the two calls it makes are exactly
what makes resume work and what stops an orphaned `ffmpeg` on the server.

### Cleartext is permitted in the manifest

Plex issues certificates for hostnames like `192-168-1-50.<hash>.plex.direct`.
A phone whose DNS will not resolve those gets NXDOMAIN, and the only way to
reach an otherwise perfectly good LAN server is the literal IP over plain http.
`PlexServers.pick` tries https first and falls back per candidate.

## Input model — this supersedes the proof-of-concept art

The art draws the softkey bar as `Back | Select`. That was drawn before Phase 1
established that the dedicated back arrow reaches an app as an ordinary
`KEYCODE_BACK` — and it does.

| Input | Action |
|---|---|
| **Left softkey** | **Home** — unwind to the start screen from any depth |
| **Right softkey** | **Options** — context menu for the focused row |
| Back arrow | Up one level |
| D-pad centre | Select |
| D-pad ←/→ in a list | Page by ~7 rows |
| D-pad ←/→ in the player | Seek ∓15 s |
| Volume rocker | Not ours. Passed to the system |

With only two softkeys, spending one on a function that already has a physical
button would waste half the input budget — and it would leave nothing for the
per-item actions Plex puts behind its three-dot menu. Options currently offers
Resume / Play from start / Play next up / Shuffle all / Go to season / Go to
show / Mark watched / Refresh, depending on what is focused.

## Pause on close, and deliberately no resume on open

`CLAMSHELL` never reaches an app — the framework keeps it for screen on/off, as
measured in `keymap.md`. That does not matter, because the *consequence* is
guaranteed: closing the lid backgrounds the app, so `onPause`/`onStop` fire.
Playback pauses there and the position is posted to Plex. No root, no
`/dev/input` reading. **This retires the "read the lid as root" enhancement** —
it is not needed for pause-on-close.

Resume-on-open is **not implemented, on purpose.** A media app that starts
making noise the moment the phone is opened is a liability in a meeting or a
quiet room. What matters is that Plex knows the position, so resume is correctly
staged wherever you next pick it up. Opening the lid returns you to a paused
player showing exactly where you were.

## Audio routing

`setHandleAudioBecomingNoisy(true)` and `handleAudioFocus = true` are both set.
The first stops a yanked headphone jack or a dropped Bluetooth headset from
switching mid-episode to the loudspeaker; the second lets a call or an alarm
duck or stop us instead of two things playing at once. Both matter on this
handset because the jack and Bluetooth are the two good ways to listen to it.

`ACCDET` (`event0`) exposes a real `SW_HEADPHONE_INSERT` if jack state is ever
needed beyond the standard broadcast.

## Still open

- [ ] Direct play for files the MT6739 can decode, skipping the transcoder
- [ ] Subtitles — ExoPlayer can render a soft track, so no burn-in needed
- [ ] Audio track selection (dub vs original)
- [ ] Search, and A–Z jump on long libraries
- [ ] Paging past the first 60 items of a library
- [ ] Music, and the Now Playing screen from the art
- [ ] Autoplay next episode
- [ ] A real Settings screen — sign-out is currently the only entry
