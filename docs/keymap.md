# The 4058G keypad

Read off the stock device before the unlock, from
`/system/usr/keylayout/*.kl` — the files Android itself uses to turn scancodes
into `KeyEvent` keycodes. Not guessed, and not inferred from key presses.

Android picks a keylayout by input device name, and all three device names match
a `.kl` filename exactly, so these are the files actually in force:

```
event1  "mtk-kpd"         -> mtk-kpd.kl
event2  "matrix-keypad"   -> matrix-keypad.kl      <- the whole keypad
event3  "gpio_keys"       -> gpio_keys.kl
event0  "ACCDET"                                    (headset detect, not a key)
```

## matrix-keypad — everything you press

| Key | Scancode | Keycode | `KeyEvent` value |
|---|---|---|---|
| `1`–`9` | 2–10 | `1`–`9` | 8–16 |
| `0` | 11 | `0` | 7 |
| `*` | 522 | `STAR` | 17 |
| `#` | 523 | `POUND` | 18 |
| Left softkey | 139 | **`SOFT_LEFT`** | 1 |
| Right softkey | 48 | **`SOFT_RIGHT`** | 2 |
| D-pad up | 103 | `DPAD_UP` | 19 |
| D-pad down | 108 | `DPAD_DOWN` | 20 |
| D-pad left | 105 | `DPAD_LEFT` | 21 |
| D-pad right | 106 | `DPAD_RIGHT` | 22 |
| D-pad centre | 28 | `DPAD_CENTER` | 23 |
| Back | 158 | `BACK` | 4 |
| Call | 231 | `CALL` | 5 |
| Messenger | 30 | `MESSENGER` | 219 |
| Contacts | 138 | `FAVORITE_CONTACTS` | 220 |
| Speaker | 59 | `SPEAKER` | 227 |

## mtk-kpd and gpio_keys

| Key | Scancode | Keycode |
|---|---|---|
| Volume down / up | 114 / 115 | `VOLUME_DOWN` / `VOLUME_UP` |
| Power | 116 | `POWER` |
| Quick dial | 212 | `QUICK_DIAL` |
| **Lid** | 252 | **`CLAMSHELL`** |

## What this settles, and what it does not

**The softkeys are `SOFT_LEFT`/`SOFT_RIGHT`, not `MENU`/`BACK`.** That was the
open question in the Phase 0 gate and the thing `KeyMap.kt` was waiting on.
PocketFlex's two-button idiom maps onto them directly.

**`STAR` and `POUND` sit at 522/523**, not the usual 227/228. Nothing depends on
the scancode at the app layer, but it means these came from a vendor keypad
driver rather than a generic one — do not assume any other scancode here matches
a stock AOSP table.

**The lid is an input device.** `CLAMSHELL` on `gpio_keys` means closing the flip
is observable, which is worth knowing for pause-on-close later. ~~It is a switch,
so expect it via `InputDevice`/`KeyEvent` state rather than as a tidy key press.~~
Wrong on both counts: it is an `EV_KEY`, not a switch, and it never reaches an
app at all. See *The lid* below.

~~**Three dedicated keys are potentially ours** — `MESSENGER`,
`FAVORITE_CONTACTS`, `SPEAKER`, plus `QUICK_DIAL` on the other device. A
text-only Plex client with four spare hardware buttons is a real luxury.~~
**This was wrong**, and it is the most misleading line the `.kl` files produced.
Three of those four are bound above the app layer and the fourth is not a
physical button. See *NOT delivered* below.

---

# VERIFIED — what the app actually receives

Everything above is what the `.kl` files *promise*. This section is what a real
APK *measured*, on 2026-07-30, with `ProbeActivity` (`app/src/main/java/.../probe/`)
instrumenting `dispatchKeyEvent`, `onKeyDown` and `onKeyUp` separately. Every
event below arrived with `src=769` (`SOURCE_KEYBOARD|SOURCE_DPAD`) and a real
scancode, i.e. from the keypad driver — not injected.

**This closes Phase 0 gate item 4.** `KeyMap.kt` is written against *this* table,
not the one above.

> `adb shell input keyevent` cannot answer this question and must never be used
> to try. Injected events arrive with `scanCode=0` and `deviceId=-1`; they bypass
> the keypad driver and the framework's interception policy entirely. Only
> physical presses count.

## Delivered — 23 keys, safe to bind

| Key | Keycode | Scan | Saw it |
|---|---|---|---|
| `1`–`9` | `1`–`9` (8–16) | 2–10 | `D` `↓` `↑` |
| `0` | `0` (7) | 11 | `D` `↓` `↑` |
| `*` | `STAR` (17) | 522 | `D` `↓` `↑` |
| `#` | `POUND` (18) | 523 | `D` `↓` `↑` |
| **Left softkey** | **`SOFT_LEFT` (1)** | 139 | `D` `↓` `↑` |
| **Right softkey** | **`SOFT_RIGHT` (2)** | 48 | `D` `↓` `↑` |
| D-pad up/down/left/right | `DPAD_*` (19–22) | 103/108/105/106 | `D` `↓` `↑` |
| D-pad centre (OK) | `DPAD_CENTER` (23) | 28 | `D` `↓` `↑` |
| **Back arrow** | **`BACK` (4)** | 158 | `D` `↓` |
| Green call | `CALL` (5) | 231 | `D` `↓` `↑` |
| Volume up / down | `VOLUME_UP`/`DOWN` (24/25) | 115 / 114 | `D` `↓` `↑` |

**The softkeys arrive.** This was the one load-bearing unknown — OEM builds
frequently eat them — and it is now settled. The two-slot `Back | Select` bar
from the proof-of-concept art is viable exactly as drawn.

**`BACK` is interceptable.** Consuming it in `dispatchKeyEvent` works, and it
never reaches `onKeyUp` once consumed — which is why the table shows no `↑`. The
dedicated back arrow next to `*` is an ordinary `KEYCODE_BACK`, so it is the
natural in-app Back and needs no special handling.

**`CALL` is fully delivered** and can be consumed before the dialer sees it. It
is the one genuinely spare hardware key we have.

## NOT delivered — do not design around these

| Key | What actually happens |
|---|---|
| Messenger / email | Launches the stock mail app. Never reaches us. |
| Contacts / favourites | Launches the stock contacts app. Never reaches us. |
| Speaker (outside, by the camera) | Handled by the framework. Never reaches us. |
| `POWER` | Backgrounds the app, as expected. |
| `QUICK_DIAL` | **Not a physical button on this unit** despite being in `gpio_keys.kl`. |

So the "four spare hardware buttons" noted above were **wrong** — three of those
four are bound above the app layer and the fourth does not exist. The real spare
count is **one** (`CALL`). A `.kl` entry proves a mapping exists, not that a
button exists or that an app can have it.

## The lid: a key the framework keeps for itself

`CLAMSHELL` never arrives as a `KeyEvent`. Closing and reopening the flip
backgrounds the app to the launcher instead.

But it is not invisible — as root, `getevent -lt` shows it plainly:

```
/dev/input/event3: EV_KEY  00fc  DOWN      <- lid closed
/dev/input/event3: EV_KEY  00fc  UP        <- lid opened
```

`0x00fc` is 252, exactly as `gpio_keys.kl` says. It is a genuine `EV_KEY` on
`gpio_keys`, not an `EV_SW` switch — `getevent -pl /dev/input/event3` reports
only a `KEY` class, unlike `ACCDET` which does expose `SW_HEADPHONE_INSERT`. The
framework consumes it for screen on/off and forwards nothing.

**Pause-on-close does not need it.** A lid close backgrounds the app, so
`onPause()`/`onStop()` fire; that is where playback pauses and the position is
posted to Plex. Guaranteed lifecycle, no privileged access.

~~Reading `/dev/input/event3` as root would additionally let us tell a lid-close
apart from any other backgrounding, and drive resume-on-open. That is a real
option on this rooted handset, but it is an enhancement, not a dependency.~~
**Retired, and not for technical reasons.** Resume-on-open is deliberately not a
feature: a media app that starts making noise the moment the phone is opened is
a liability in a meeting or a quiet room. Since pause-on-close falls out of the
ordinary lifecycle, nothing needs `/dev/input` at all. Verified end to end in
Phase 2 — see `phase2-playback.md`.

---

# What each key does in FlipFlex

The measured table above says what the hardware delivers. This is what the app
binds it to. **This supersedes the proof-of-concept art**, which drew the
softkey bar as `Back | Select`.

| Input | Action |
|---|---|
| **Left softkey** | **Home** — unwind to the start screen from any depth |
| **Right softkey** | **Options** — context menu for the focused row |
| Back arrow | Up one level |
| D-pad centre | Select |
| D-pad ↑/↓ | Move selection |
| D-pad ←/→ in a list | Page by about 7 rows |
| D-pad ←/→ in the player | Seek ∓15 s |
| Volume rocker | Not consumed. The system's own handling is what users expect |
| `CALL` | The one genuinely spare key. Unbound, but reserved — it must be caught in `dispatchKeyEvent` or the dialer takes it |
| `*` `#`, digits | Arrive and are ours. Unbound so far |

**Neither softkey is Back**, and that is the whole point. The back arrow is a
real `KEYCODE_BACK` that reaches the app, so spending one of only two softkeys
on it would waste half the input budget — and leave nothing for the per-item
actions Plex puts behind its three-dot menu.

`KeyMap.kt` is the code that implements this; `FlipActivity` routes it.
