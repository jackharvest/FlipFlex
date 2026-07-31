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

Round three, measured the same way on 2026-07-30. This is the details page,
subtitles, quality, search and downloads:

| Step | Evidence |
|---|---|
| In the Menu | FlipFlex is item 10 of the phone's Menu, launches from there. See `launcher-menu.md` |
| Details page | *Ship of Ghouls* — show, S1·E6, 1985, 22m left, TV-PG; `720p · H264 · 1.7 Mbps → 320x240 800 kbps`; summary; Play/Resume; subtitle, audio, quality and download rows |
| Search | Green call key from any screen; "scooby" returned a grouped `SHOWS` hub; "awkward" returned `SHOWS` + `EPISODES` |
| Search → details | Result opens the details page, which opens the player |
| Subtitles | Burned in and legible at 125% on the real panel, mid-episode |
| Download | 54 MB Matroska for a 23-minute episode at 240x180/320 kbps, `.part` renamed on success |
| Downloads library | Home row `Downloads · 54 MB · 1` → `SHOWS` → *My Awkward Senpai · 1 episode* |
| **Offline playback** | Wi-Fi **and** mobile data off: details page says "Saved on this phone", Play decodes on `OMX.MTK.VIDEO.DECODER.AVC` from local storage |
| Offline home | Cold start with both radios off gives Downloads + Settings, and **the token survives** |

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

Round four of feedback, measured the same way on 2026-07-30. Reorder, seekable
downloads, badges, and the two playback failures:

| Step | Evidence |
|---|---|
| Reorder | Home row → the library list rocks; OK lifts *Movies*, ↓↓ carries it below *TV Shows*, OK drops it, Done. The new order survives a force-stop |
| Download seeking, before | Six presses of forward on a downloaded episode left the clock at **0:01** and restarted the picture each time |
| Cues index | 250 cue points, 6.7 kB, spliced ahead of the first cluster; `ffmpeg -ss 900` on the result decodes immediately |
| Downloaded item page | Badges `480p` `MSMPEG4V3` `2.1 Mbps` `53 MB`; no quality or subtitle picker; "ON THIS PHONE" and Delete |
| Download quality | Picker on the details page, opens on High with a tick; default moved to 480x360 |
| Accented rows | Subtitles rose, Streaming quality green, Download quality violet, all with a leading stripe |
| Session reaped | Five minutes paused → `/status/sessions` shows FlipFlex playing with **no transcode session**; playback then ran on for minutes and died with `ERROR_CODE_IO_BAD_HTTP_STATUS` |
| Surround | An 8-channel E-AC-3 source comes back **2-channel** on both paths already — no parameter needed |

Round five, on the handset on 2026-07-31. The first-run controls tour:

| Step | Evidence |
|---|---|
| First launch | With `tour_seen` absent, the splash hands off to the tour rather than to Home; OK on the last step lands on `HomeActivity` |
| All twelve steps | Each lights its own control: ring arcs, OK, back arrow, either soft key with its own on-screen label, the green key, star and hash, the nine digits, favourites and mail, the hinge |
| Two D-pad steps | Up/down lights the top and bottom arcs, left/right the sides — the two are not the same picture |
| Key jumps | The green call key jumps to the search step and `0` to the PIN step, instead of opening search or doing nothing |
| Reachable again | Settings → HELP → Controls, with the left soft key reading "Done" and Back returning to the settings list |

Round six, on the handset on 2026-07-31. Navigation, continuity and the two
network policies:

| Step | Evidence |
|---|---|
| Settings is tabbed | `Playback · Downloads · Account · Help`, all four inside 240dp; `2` and `4` jump straight to a tab |
| The lost highlight | Crossing `SETTINGS FOR THIS` on a details page now leaves the amber bar on the first row *under* the caption, on screen. It used to land off the bottom |
| Settings colours | Quality green, Subtitles and size rose, Wi-Fi only green, direct play blue, every Downloads row violet — the same four meanings as the details page |
| Direct play | Turning it **on** opens a panel naming the codecs and the 1600x960 ceiling, with Cancel under the cursor. Turning it off asks nothing |
| Streaming on LTE | Wi-Fi off, server reached over `usb-plex.sh`: Play opened *Stream over mobile data?*, Cancel default, "Play anyway" decoded on the first attempt |
| Download on LTE | With Wi-Fi only `On`, Download queued the row and said it would fetch itself on the next Wi-Fi |
| Before and after | *The Secret World of Arrietty* — `1080p H264 9.9 Mbps DCA 5.1` → `320x240 800 kbps AAC Stereo`, two columns with an arrow between them |
| No subtitle tracks | *3 Ninjas* → Subtitles → "This file has no subtitle tracks" → **Back returns to 3 Ninjas**, not to Movies |
| Left is Back | ← left Settings for Home, and a details page for the library it was opened from |
| Up past the top | List → tab strip → `‹ Settings` lit amber; OK there went up a level |
| Off the top of the A-Z | Rail at `#`, one more ↑, and the `Library` tab took the cursor |
| Search tip | The `EN KT9` line sits under the results while the field has the cursor, and gives its two rows back when the cursor moves into them |
| The tour | Twelve steps; the new ones light the back arrow with the pad's left/right, and the digits |
| Home | No `Reorder libraries` row. It is in Options, where it always also was |

## The traps, in the order they cost time

### A downloaded file cannot be seeked, and the reason is a missing index

`start.mkv` is muxed **live**, straight into the socket. A live Matroska muxer
cannot go back and fill anything in, so what lands has a Segment of unknown
size (`01 FF FF FF FF FF FF FF`) and **no Cues element**. Cues is the cluster
index; a muxer can only write it once it knows where every cluster ended up.

ExoPlayer's `MatroskaExtractor` builds its seek map from Cues and from nothing
else. With none present it publishes `SeekMap.Unseekable`, and every `seekTo`
then collapses to the only seek point there is: **zero**. On the handset that
looks like six presses of the forward key leaving the clock at 0:01 and the
picture restarting — a seek that goes backwards, not one that does nothing.

Three ways out were measured before the fourth was written:

| Idea | Why not |
|---|---|
| Ask Plex for a seekable container | It ignores the extension. `start.mp4` and `start.ts` both return **Matroska**, byte-identical in size to `start.mkv` |
| Remux to MP4 with `MediaMuxer` | The audio comes back **MP3** from every profile that serves a single file — Chrome, Safari, Roku, Chromecast all the same, and `X-Plex-Client-Profile-Extra` does not move it. `MediaMuxer` cannot write MP3 into MP4 |
| Download HLS and keep the segments | Would work — the child playlist is full VOD, all 174 segments listed up front — but it rewrites the whole download path |

So FlipFlex writes the index itself. `dl/MatroskaIndex.kt` walks the top level
once, collects a cue point every five seconds, and rewrites the file with Cues
spliced in **ahead of the first cluster**. Ahead, because the extractor parses
forwards and never follows the SeekHead, so an index at the end of the file is
one it reads after it no longer needs it.

Two things make this cheap and safe. Every cluster in what the transcoder
produces has a **known size**, so the walk is arithmetic rather than a scan for
the next cluster id — verified on a real download, 1733 of them, all sized.
And the clusters are copied byte for byte, so nothing is re-encoded and a
failure leaves the original in place, playable and merely unseekable.

The one arithmetic trap is that `CueClusterPosition` is an offset from the
start of the Segment's data, and inserting the index *moves every cluster*. The
positions therefore have to be written with the length of the index already
added — and the length depends on the positions. `CueTime` and
`CueClusterPosition` are written as **fixed eight-byte integers** to break that
circle: the encoded size then depends only on the number of cue points, so the
index can be built once to measure and again with the shift applied. There is
an assertion that the two agree; do not remove it.

### A minimal scroll can hide the cursor, and a caption is what makes it happen

The report was "the highlighted row is sometimes completely absent from the
screen, usually when entering a new subheading area", reproduced most easily in
Settings. It was not a paint bug and not a focus bug.

`RowList.scrollToCursor` picked **one** anchor row and scrolled to it, and one
of its three cases picked a row that was not the cursor: moving onto the first
row of a group, it anchored on the *caption above* so the caption would stay
visible. `LinearLayoutManager.scrollToPosition` is a minimal scroll — a target
that is already fully on screen moves the list not at all — so with the caption
occupying the last visible line, nothing scrolled and the selected row sat just
below the viewport. The amber bar was then off screen with no indication of
where, and pressing down again simply moved it further away.

The rule now is that **the selected row is on screen, always**, and the caption
is a preference that only applies when the cursor is travelling upwards:

- everything above the cursor unselectable → pin to 0, so a details page shows
  its summary and a grouped list its first caption;
- cursor above the viewport → bring it to the top, taking its caption with it;
- cursor below the viewport → bring it to the bottom, caption or no caption;
- otherwise hold still, which is also what stops the list twitching under a
  held key.

`findFirstCompletelyVisibleItemPosition` returning `NO_POSITION` means nothing
has been laid out yet — the first submit — and is handled separately rather than
being treated as "above the viewport".

### A transcode session is reaped while the lid is shut, and the failure is delayed

This is the "it plays a bit and then says 400" report, and looking at the
moment of the error tells you nothing about it.

A transcode session is kept alive by segment requests. Pause — which on this
handset means the lid is shut, the single most common thing that happens to it
— stops those, and Plex eventually reaps the session. But it leaves the
segments it had already produced. So playback **resumes perfectly**, runs on
for however many minutes the transcoder had got ahead, and only then reaches
the first segment that was never written.

Measured: after a five-minute pause, `/status/sessions` showed
`The Adventures of Tintin | product=FlipFlex | state=playing` with
`tsKey=-` — playing, with no transcode session behind it at all. Four minutes
later the screen said `ERROR_CODE_IO_BAD_HTTP_STATUS`.

Two fixes, and both are wanted:

- **`PlexPlayback.ping`**, every ten seconds whenever the player is alive and
  not playing. This is what the endpoint is for and what every Plex client
  does. It stops most of these happening at all.
- **`PlayerActivity.rebuildStream`**, on any playback error while streaming. A
  new session id, a new URL at the current position, up to three times, with
  the budget returned after a minute of clean playback. This catches the rest —
  including every cause nobody has thought of, which on this handset turned out
  to matter (see the Wi-Fi note below).

Note that a rebuild **must** use a new session identifier. The old one is
exactly what the server has stopped believing in; reusing it asks Plex to
resume something it has already thrown away.

### The handset drops its own Wi-Fi, and that kills streams

Worth writing down because it looks like an app fault and is not. Observed
twice in ten minutes during this round, on a link at **−38 dBm** with the
access point in the same room:

```
wlan0: CTRL-EVENT-CONNECTED  ... [PTK=CCMP GTK=CCMP]
   (18 seconds later, no IPv4 address ever assigned)
wlan0: CTRL-EVENT-DISCONNECTED reason=3 locally_generated=1
```

`locally_generated=1` means the **phone** deauthenticated, not the AP. The
four-way handshake completes every time; what never completes is DHCP, and
Android tears an un-provisioned connection down after about eighteen seconds
and falls back to LTE — at which point a LAN Plex server is simply gone.

Two details for whoever chases this: the AP advertises
`[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP]`, which is WPA2/WPA3 transition mode, and
the phone has saved the network with `configKey="<our-ssid>"SAE` — it is
associating as WPA3. Transition mode is a known source of exactly this on older
MediaTek Wi-Fi stacks. The BSSID `9a:2a:…` is locally administered, so it is a
mesh node rather than the router itself.

This is not FlipFlex's bug, but it is very probably the "streaming dies after
about thirty seconds" report, and it is why `rebuildStream` exists as well as
`ping`: a stream that survives a reassociation is worth more here than one that
explains why it stopped.

**Testing around it**: `tools/usb-plex.sh` puts the app on the LAN over the USB
cable — a TCP relay on the Mac plus `adb reverse tcp:32400 tcp:32400`, with the
stored server URI pointed at `http://127.0.0.1:32400`. Costs no cellular data
and does not care what the radio is doing.

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

### `X-Plex-Platform: Android` cannot ask for a file

Downloads fetch `start.mkv` with `protocol=http`, which is one continuous
Matroska stream rather than a playlist. Under the platform we claim for
streaming, that is a bare 400 with 89 bytes of HTML and no reason.

Measured against 1.43.2, every probe bracketed by a `state=stopped` timeline:

| Platform | Endpoint | Result |
|---|---|---|
| `Android` | `start.mkv` + `protocol=http` | **400** |
| `Android` | `start.m3u8` + `protocol=hls` | 200 |
| `Chrome` | `start.mkv` + `protocol=http` | **200, 49 MB of Matroska** |
| `Chrome` | `start.m3u8` + `protocol=hls` | 200 |

The universal transcode endpoints are served per client profile, and the
built-in Android profile has no single-file form — Plex's own Android app
streams HLS and downloads through a different mechanism entirely. So streaming
keeps `Android` and **downloads claim `Chrome`** (`PlexClient.PLATFORM_FILE`).
The header and the query string must agree; the download service passes the
platform to `PlexClient.headers` for exactly that reason.

**The bracketing is not optional, and getting it wrong is how this was nearly
mis-diagnosed.** Run the four combinations back to back and they contradict each
other, because of the item-scoped stale session below: the second probe of any
pair fails whatever platform it claims. The first matrix run here reported
`Chrome`+`mkv` as 200 and then as 400 within a minute, which reads like a flaky
server and is not.

### A failed request is not a rejected token

`PlexAuth.validate` returned `String?`. Null meant *both* "plex.tv refused this
token" and "plex.tv could not be reached", and `SplashActivity` responded to null
by calling `signOut()`.

So **opening the app with no network wiped the stored token and demanded a
re-link at plex.tv/link** — on a handset whose entire offline feature is a folder
of downloads to watch on a train. Reproduced exactly that way: Wi-Fi off, mobile
data off, cold start, sign-in screen, token gone from prefs. Recovering needs a
second device with a browser, so from where the user is standing it is not
recoverable at all.

`PlexClient.reply` now keeps the distinction (`code == NO_REPLY` means nothing
answered) and `validate` returns `Ok` / `Rejected` / `Unreachable`. **Only
`Rejected` discards anything**, and only 401 and 403 produce it — a 5xx is
plex.tv's problem, not the token's. `Unreachable` goes straight to Home, which
draws the Downloads library.

The general rule this is an instance of: *a token must only ever be thrown away
because a server said to.*

### A message that covers the screen is a screen, and Back has to treat it as one

`showTransientMessage` fills the content frame and is dismissed by the next key
press, with that key **also doing its normal job**. That is right for the arrow
keys — the remark is about an action, not a place — and it was wrong for Back in
a way that read as a navigation bug.

Choosing Subtitles on a file with no subtitle tracks says so, full screen. It
looks like a page, so the way off it is Back, and Back both took the message
down *and* left the details page: the user asked about *Ocean's Thirteen* and
ended up in Movies, two levels from where they had been, with nothing on screen
explaining the jump.

Back is now consumed when it dismisses a transient message. Taking the remark
down is a whole job for one key press, and every other key keeps the old
behaviour.

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
| D-pad ↑ at the top row | Step into the tab strip, then into the `‹ Title` header |
| **D-pad ← in a list** | **Up one level** — a second Back, for a phone whose back key may not outlive it |
| D-pad → in a list | Page by ~7 rows, or step into the A-Z rail |
| Digits in a list | The tab of that number, where the screen has tabs |
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

**And none of it is discoverable, so the app teaches it once.** The first launch
opens the controls tour ahead of whatever it was going to show — including ahead
of plex.tv/link, which already has to be navigated with keys nobody has been
told about. Twelve steps on a drawing of the handset, one control lit at a time
with a leader line to its name. See `keymap.md` for why the drawing is a canvas
and not the reference PNG, and why `Store.tourSeen` is set on the way in rather
than on the way out.

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

## Settings is four tabs, and the colours are the continuity

The same `TabStrip` the library views use, with `Playback · Downloads · Account
· Help`. It replaced fifteen rows and four captions, and the captions were the
problem twice over: as rows they were what the cursor had to skip, which is what
put the highlight off the screen (see the scroll trap above), and as navigation
they meant "Sign out" was two pages of scrolling past settings nobody was
looking for. Every tab is now shorter than the screen, and a digit reaches one
directly.

`TabStrip` scrolls rather than dividing the width. Three tabs fitted in thirds;
four do not, and dividing by however many there are ends in three ellipsised
words that all read "Recomm…". The tabs keep their natural width, the current
one is centred, and the rest run off both edges.

**The accent colours are the same four, meaning the same four things**: blue is
what the file already is, green is what reaches the screen, violet is storage on
the phone, rose is subtitles. So Subtitles in Settings is rose because the
subtitle picker on the details page is rose, and someone who has met one
recognises the other as the same setting. Direct play is blue for a real reason
rather than a spare one — it is the file arriving as it already is.

## Mobile data is a three-way policy, not a switch

`NetPolicy` is `WIFI_ONLY | ASK | ANY`, held separately for streaming and for
downloads, because the right default is not the same for both:

- **downloads default to `WIFI_ONLY`.** A download is a decision to spend a few
  hundred megabytes at once, and the queue waiting for Wi-Fi *is* the feature;
- **streaming defaults to `ASK`.** A phone that silently refuses to play
  anything away from the house looks broken, and pressing Play is something
  people do in the ten minutes before a train.

Two things about where the question is asked. It is asked **before** the
transcode is requested, or the server has already begun spending the data the
panel is about — which is why `FlipActivity.startPlayback` wraps the intent
rather than the player checking on the way up. And under `WIFI_ONLY` a download
is still **queued**, not refused: `DownloadService` reaches the same check,
logs it and stops, so the row starts by itself on the next Wi-Fi rather than
having to be pressed again.

A local copy is never guarded. The player prefers the file on disk, so the radio
is not involved and a warning about mobile data would simply be untrue.

`Net.Link` has three values for the same reason the policy does. `NONE` is not
`METERED`: a queue with no network should wait exactly as it waits for Wi-Fi,
but telling someone they are "on mobile data" when the phone has no connection
at all is a dialog that is false, in front of a playback that was going to fail
with a network error anyway.

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

## The details page, and what it changed

Choosing a playable item now opens `DetailActivity` rather than starting a
transcode. It carries the description, `1080p H.264 · 3.3 Mbps → 320x240 800
kbps`, the subtitle and audio tracks, the quality preset and the download
control.

**It is one list, not a poster with controls under it.** A summary is four to
six wrapped lines at 10sp, which is most of a 270dp content area, so the prose
and the actions cannot both be permanently visible — and a scrolling text view
above a list means two things competing for the down key with nothing on screen
saying which has it. `RowList.Row.isBlurb` is prose rendered as an unselectable
row, so there is one cursor and one meaning for every key.

That produced a bug worth keeping the fix for. The cursor lands on the first
*selectable* row, which here is three paragraphs down, and `submit` used to
scroll to the selection — so the one screen whose job is to show a description
opened with the description off the top of the screen. The grouped Settings list
lost its first caption the same way. `RowList.submit` now scrolls to 0 whenever
everything above the cursor is unselectable, which is a property of the rows
rather than of `keepSelection`, and fixes both.

**Subtitles are per item and server-side.** Choosing a track sends
`subtitleStreamID` to `/library/parts/<id>?allParts=1`, which is how Plex itself
stores it and why the choice follows you to a TV. That means an episode can have
a track selected from another client while this phone's global switch is off — so
`PlayerActivity.intent` takes a nullable `burnSubtitles` and the details page
passes a real value. Without it the page said "Subtitles: English" and then
played without any.

## Downloads

`protocol=http` against `start.mkv` gives one continuous Matroska file, exactly
as PocketFlex's `dlworker.sh` does. A foreground service fetches one at a time —
not for lack of threads, but because each one is a live transcode on the server,
and three concurrent transcodes on a home NAS is how the bare 400 above happens.

**Nothing is resumable.** The endpoint serves one continuous stream and honours
no byte ranges, so an interrupted transfer can only be discarded. Everything is
arranged around that: the file is written to `.part`, the `.part` is deleted on
any path that is not a clean finish, and `Downloads.recover` puts a row left
saying `downloading` back to `queued` at startup.

**Size is the only honest success test.** Plex closes the stream cleanly when
its transcoder falls over, so the connection reports success and what lands is a
valid, tiny, unplayable file. Anything under 256 kB is a failure.

**The index carries the hierarchy rather than pointing at it.** Show, season and
`S01E04` are copied onto every row when it is queued. That duplication is the
whole feature: the Downloads library has to be browsable with the radio off, and
an index that needed `/library/metadata` to work out which season an episode
belongs to would only work when you did not need it. Episodes sort by that code,
never by title — "Episode 10" sorts before "Episode 2".

**Home degrades rather than failing.** With no server and at least one download,
the home screen is Downloads plus Settings instead of "Cannot reach Plex".

## Search is on the green call key

`CALL` is the one genuinely spare hardware button, and it has to be consumed in
`dispatchKeyEvent` or the dialer takes it. It opens search from every screen
except the player, where leaving would tear down ExoPlayer and the transcode
mid-episode.

PocketFlex deliberately has no search, and was right: on a Miyoo Mini, typing a
title off an on-screen grid is slower than scrolling to it. This handset ships a
real system IME — `com.iqqijni.dvt912key`, T9 with prediction, the only enabled
input method on the device — so the field is a plain `EditText` and nothing more.

**`clearFocus()` does not move focus, and that cost an evening.** The field is
the only focusable view on the screen, so Android has nowhere else to put focus
and hands it straight back: the caret stayed, the IME stayed attached, and every
OK press was swallowed before it reached `onKeyDown`. The cursor moved through
the results and choosing one did nothing. The fix is `list.requestFocus()` —
focus has to be *given* somewhere, not taken away. The rows themselves stay
unfocusable, so the D-pad still reaches `onKeyDown` the way it does everywhere
else.

`/hubs/search` rather than `/search`, because it comes back already grouped and
those groups map straight onto the captioned rows the list already draws.

## Still open

- [ ] Direct play for files the MT6739 can decode, skipping the transcoder.
      Worth less than it sounds on a 240-wide panel: it would pull a 1080p file
      across the radio to draw it into 320x180, costing more bandwidth and more
      battery for the same picture. What it saves is the server's transcoder.
      **Settings → Try direct play** now exists as an experiment rather than a
      feature, so the transcoder can be taken out of the path and the failure
      rate compared. Expect it to work on part of the library and fail on the
      rest: the MT6739 decodes HEVC only to 1600x960, so a 1080p HEVC source is
      above what the chip will accept. **Turning it on now asks first**, in a
      panel naming the codecs, the ceiling and the reason everything is
      converted — because an experiment that is expected to fail on part of any
      library gets reported as a broken app if nobody says so beforehand. The
      details page also stops predicting a quality preset that is no longer in
      the path, and shows the file's own numbers on both sides of the arrow
- [ ] Music, and the Now Playing screen from the art
- [ ] **Autoplay next episode — and with it, shuffle as a *queue*.** Shuffle
      currently plays *one* random thing and stops at the end of it, because
      there is no queue and `STATE_ENDED` finishes the activity. That is the
      right feature for "put something on", and the wrong one for anyone who
      expects a shuffled run. Both need the same thing: a list of ratingKeys in
      the player and an advance on end, which has to close out the finished
      item's session before preflighting the next — see the item-scoped stale
      session trap above, which is exactly what an advance would trip over.
      `Downloads` already carries `code`, so the offline version of this can
      find the next episode without a server
- [ ] A landing-page redesign — the user has an idea for it and it is next
- [ ] Downloads have no storage ceiling. PocketFlex keeps a 300 MB floor free
      because a full SD card is unrecoverable from the device; /data here has
      11 GB and no such guard yet. Note that indexing now needs the file's size
      again in free space for a few seconds at the end of every download
- [x] ~~Seeking in downloads~~ — the file arrives with no index; we write one
- [x] ~~Playback dying after the lid has been shut~~ — ping, and rebuild on error
- [x] ~~Downloads and offline playback~~
- [x] ~~Subtitles~~ — burned in, not soft. See the note below
- [x] ~~Audio track selection (dub vs original)~~
- [x] ~~Search~~
- [x] ~~Quality/bitrate settings, and Wi-Fi-only guards~~

### Subtitles: burned in, and why the earlier note was wrong

The line that used to sit in this list said ExoPlayer can render a soft track so
no burn-in is needed. It can — for text formats. It cannot draw PGS or VOBSUB at
all, which is what a Blu-ray rip carries, so a soft path would work on part of a
library and silently do nothing on the rest. Everything here is already being
transcoded, so `subtitles=burn` costs nothing extra and gives one behaviour for
every file. `subtitleSize` is a setting because Plex's 100 is sized for a
television; 125 is the default and is legible on the real panel.
