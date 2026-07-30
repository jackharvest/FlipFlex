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
is observable, which is worth knowing for pause-on-close later. It is a switch,
so expect it via `InputDevice`/`KeyEvent` state rather than as a tidy key press.

**Three dedicated keys are potentially ours** — `MESSENGER`,
`FAVORITE_CONTACTS`, `SPEAKER`, plus `QUICK_DIAL` on the other device. A
text-only Plex client with four spare hardware buttons is a real luxury.

### Still unverified

This is the mapping the framework *will apply*. It is not proof our app
**receives** each event, which is a different question and needs a real APK:

- `POWER` never reaches apps.
- `BACK` is consumed by the framework unless intercepted.
- `SOFT_LEFT`/`SOFT_RIGHT` are frequently eaten by the system on OEM builds.
- `MESSENGER`/`FAVORITE_CONTACTS`/`SPEAKER`/`QUICK_DIAL` may be bound to stock
  apps and never delivered.

So gate item 4 is now *predicted with high confidence* rather than closed. The
remaining test is cheap once an APK installs: log `onKeyDown`/`onKeyUp` and press
everything. Do not write `KeyMap.kt` against this table without that pass.
