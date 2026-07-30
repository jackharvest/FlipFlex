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
| Teardown | After leaving the player: **0 open transcodes, 0 FlipFlex sessions** |

The resume point then came back round: reopening Continue Watching showed
*Queen of Tears · 1h 24m left* with a progress bar, read from the server.

Round two of feedback, measured the same way on 2026-07-30:

| Step | Evidence |
|---|---|
| Controls fade | Bare video 6 s after playback starts; any key brings them back |
| Rotation | All three switch live — `ROTATION_270` → `90` → `0` with video still running |
| Rotation persists | Survives `am force-stop`, read back from prefs |
| Library tabs | Recommended / Library / Categories, reached with ↑, switched with ←/→ |
| Categories | 30 genres off `/genre`; Action browses **598 of 1746** titles, so the filter is real |
| Groups capped | Three rows plus `» more`; the button opens the group in full |
| Shuffle, films | Random title from a 1746-film library |
| Shuffle, television | Random *episode* from a 636-show library, via `type=4` |
| Shuffle, one show | Random episode of the focused show |
| Seasons | Header *2 Stupid Dogs*, row *Season 1 · 13 episodes* — no repetition |
| Sign out | Confirm panel, Cancel default; cancelling leaves the token in prefs |

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
| D-pad ↑ at the top row | Step into the library view tabs |
| D-pad ←/→ in a list | Page by ~7 rows, or step into the A-Z rail |
| D-pad ←/→ in the player | Seek ∓15 s |
| D-pad ↑/↓ in the player | Seek too, in whichever direction the rotation points them |
| Volume rocker | Not ours. Passed to the system |

With only two softkeys, spending one on a function that already has a physical
button would waste half the input budget — and it would leave nothing for the
per-item actions Plex puts behind its three-dot menu. Options currently offers
the three view switches, Shuffle *library*, Resume / Play from start / Play next
up / Shuffle *this show* / Go to season / Go to show / Mark watched / Refresh,
depending on what is focused.

**Two entries in one menu must never share a label.** The library shuffle and the
show shuffle were both called "Shuffle" and appeared together on the A-Z screen,
one meaning this show and one meaning all six hundred of them. They are named
after their scope now — "Shuffle TV Shows" against "Shuffle this show".

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

## The player is landscape and immersive

Rotated, the panel is 320×240, so a 16:9 video is **320×180 instead of 240×135 —
1.8× the picture area**. The rotation is honoured by WindowManager and needs no
sensor.

### There are two landscapes and one of them is wrong

The first build used `SCREEN_ORIENTATION_LANDSCAPE`, which is `ROTATION_90` on
this handset. That is the **worse** of the two, for a physical reason: it puts
the edge carrying the **power button and the headphone jack** along the bottom,
so a handset with anything plugged into it cannot be stood on a table — the
cable is underneath it. The opposite rotation puts the volume-rocker edge down,
which sits flat.

So the default is now `reverseLandscape` (`ROTATION_270`, verified with
`dumpsys window | grep mCurrentRotation`), and all three orientations are offered
in the player's Options menu and remembered in prefs. Nothing about this is
derivable from a spec sheet; it is which way up the sockets are.

**Switching does not restart playback.** `requestedOrientation` overrides the
manifest, and the manifest declares `orientation|screenSize` in `configChanges`,
so the views re-lay out in place. Measured: video kept running across all three
switches. Without that `configChanges` entry the activity would be recreated,
which would tear down ExoPlayer mid-episode and leave a session the server still
believes is live — see the stale-session trap below.

### The controls are transient, and what arms them

Nothing is permanently on screen. The status bar, shell header and shell softkey
bar together are **72 of 240 rows, thirty percent of the panel**, so all three
are hidden and the controls are drawn over the video.

The hide is armed by **`onIsPlayingChanged(true)`, not by the key press that
asked for playback**. That distinction is the whole feature: a key press happens
seconds before the first frame, while the server is still opening the transcode,
so arming there fades the controls out during the buffering they exist to
explain and leaves the user watching a black rectangle with nothing on it.

Anything that is *not* playing — paused, buffering, still waiting on the
transcode — pins them up instead. A frozen frame with no controls is
indistinguishable from a crash, and that is exactly what the user sees every time
they reopen the lid.

They come back on **any** key, via `FlipActivity.onKeyPressed`, which fires
before anything decides what the key means. That is what catches the two
softkeys, which are handled by the shell and never reach `onAction`.

### The D-pad turns with the handset, and so must its meaning

`LEFT` and `RIGHT` always seek back and forward — those keys keep their printed
meaning. `UP` and `DOWN` seek too, because in landscape they point along the
picture and have no other job, but **which way they seek follows the rotation**:
in reverse landscape the key printed "up" points at the right of the picture, so
binding it to "back" would have people pressing a key aimed at the end of the
film and travelling towards the start.

The same problem hits the **Options panel**, which is a vertical list drawn over
a rotated screen. `FlipActivity.screenDirection` is the answer: the default is
identity, the player overrides it from its orientation, and the panel accepts
both the printed key and the one that actually points down the screen. No key is
dead and none of them moves the wrong way. Measured at `ROTATION_270`: `RIGHT`
moves down the menu, `LEFT` moves up, and `UP`/`DOWN` still work.

## The library views: three tabs, and why the list is capped

A library has three views, named as Plex names them, drawn as a 13dp strip under
the header: **Recommended · Library · Categories**. The strip is not decoration.
A list of titles and a list of genres are the same shape on a 240dp panel, so
without a permanent marker there is nothing on screen that says which one you are
looking at — and the Options menu, which is where view switching used to live
exclusively, only tells you after you open it.

- **Reached with ↑ off the top row.** There is no spare key for it and it sits
  directly above the list, so that is the obvious gesture. ←/→ move between tabs,
  OK switches, ↓ or Back returns to the list. A second ↑ is swallowed rather than
  treated as "leave" — the key that got you in must not also be the key that
  drops you out.
- **Switching `finish()`es the old view.** The tabs are a view switcher, not
  navigation. Leaving the old one on the stack makes the back arrow walk
  sideways through views you have already seen instead of up a level.
- **The one deliberate exception**: pressing Categories while inside a single
  genre is *not* a no-op, because that is a different screen and it is the
  quickest way back to the genre list.

**Categories is genres and only genres.** Plex offers year, decade, rating,
director and more; on a screen that shows seven rows, a list of facets to choose
a facet from is a level of navigation that buys nothing. Use `fastKey` from
`/library/sections/<key>/genre`, not `key` — `fastKey` is a complete path with
the filter already on it, while `key` is a bare id on some server versions and a
path on others. `PlexLibrary.pathItems` pages it, and it checks whether the path
already carries a query before appending: a URL with two `?` gets answered with
the **unfiltered** library rather than an error, so the bug would be a genre
browse that silently shows everything.

**Recommended shows three rows per group, then a thin `» more`.** At six — what
it fetched before — four groups was a twenty-eight row list and reaching
"Recently Watched" meant paging past everything above it. Each group is still
*fetched* twelve deep, because the difference between fetched and shown is what
tells us whether the "more" button is worth drawing at all.

**Two amber bars is two cursors.** Once the tab strip could hold focus, the
screen had a lit tab and a lit list row at the same time and no way to tell which
one OK would act on. `RowList.parked` paints the selection in a muted colour
whenever another control owns the cursor; the A-Z rail uses it too, and is better
for it.

**A season list must not repeat the show name.** `PlexItem.toRow` puts the show
on the first line for a season *outside* its own show — Recently Added returns
seasons titled "Season 10", which names nothing on its own. Inside the show it is
the opposite: the header already says the show, so a row that repeats it gives
you a screen reading "Queen of Tears" six times with the one distinguishing
detail in small print underneath. There, the season is the title and the episode
count is the subtitle.

## Shuffle is two requests, and never `sort=random`

`PlexLibrary.randomInSection` asks for a single item purely to read the server's
`totalSize`, then fetches one item at a random offset inside it. Fetching the
library and picking from it would mean parsing several megabytes of JSON into a
128 MB heap to throw all but one row away.

`sort=random` exists on newer servers and is **not** used: it is a silent no-op
on older ones, which shuffles you to the first title alphabetically every time
and looks exactly like a broken feature.

`type=4` is what makes this work on television — it enumerates *episodes* across
the whole section, so shuffling a TV library gives a random episode of a random
show rather than a show you then have to pick an episode of. Verified on the
handset: a random Dora episode from a 636-show library.

**The failure path is the feature.** The show-level shuffle used to end in
`randomOrNull()?.let { play(it) }`, so a show whose episodes came back empty did
precisely nothing, with no message — which is indistinguishable from a broken
option and is exactly how it got reported. It now filters to playable leaves and
says so when there is nothing to play. `FlipActivity.showTransientMessage` exists
for remarks like that: the full-screen message view is right for "no server" and
wrong for "that found nothing", so this one clears itself on the next key press
instead of parking over a perfectly good list.

## A stale session blocks the *item*, not the client

Playback intermittently failed preflight with a bare `HTTP 400`. It was not
load, not `X-Plex-Platform`, and not the resolution. Measured against 1.43.2:

| Request | Result |
|---|---|
| Our client, item with a stale session | **400** |
| **A different client identifier**, same item | **400** |
| Our client, a different item | **200** |
| The same item after a `state=stopped` timeline | **200** |

So **Plex refuses a new transcode for any item it believes still has a live
session, and the refusal is item-scoped**. Stopping the transcode is not enough;
only the `stopped` timeline clears it.

`onDestroy` sends that on the clean path but does not run when the process is
killed — a crash, a force-stop, or `adb install -r` over a running build. So the
ratingKey, position and duration are mirrored into prefs on every timeline
report, and the next `startPlayback` closes out whatever was left open. **The
position is stored rather than zeroed**: `stopped` with `time=0` would wipe the
resume point and turn a crash into lost progress.

Also note the timeline call **requires `X-Plex-Client-Identifier`**. Without it
the server answers 400 and the session is not cleared. The app sends it as a
header; a hand-rolled `curl` reproduction will not unless you add it.

## Still open

- [ ] **Downloads and offline playback** — the big one, and its own phase. Needs
      a foreground service, storage management, an offline metadata cache and a
      local-file path through the player. PocketFlex's `dlworker.sh` is the
      model: `protocol=http` against `start.mkv` gives one continuous file.
- [ ] Direct play for files the MT6739 can decode, skipping the transcoder
- [ ] Subtitles — ExoPlayer can render a soft track, so no burn-in needed
- [ ] Audio track selection (dub vs original)
- [ ] Search
- [ ] Music, and the Now Playing screen from the art
- [ ] **Autoplay next episode — and with it, shuffle as a *queue*.** Shuffle
      currently plays *one* random thing and stops at the end of it, because
      there is no queue and `STATE_ENDED` finishes the activity. That is the
      right feature for "put something on", and the wrong one for anyone who
      expects a shuffled run. Both need the same thing: a list of ratingKeys in
      the player and an advance on end, which has to close out the finished
      item's session before preflighting the next — see the item-scoped stale
      session trap above, which is exactly what an advance would trip over
- [ ] Quality/bitrate settings, and Wi-Fi-only guards
