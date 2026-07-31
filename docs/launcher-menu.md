# Getting into the phone's Menu

**Closed.** FlipFlex appears in the Menu as item 10, verified on the handset on
2026-07-30. This file records why that took a resource overlay and a package
rename rather than a line in `AndroidManifest.xml`.

## The symptom

FlipFlex was installed, enabled, and reachable only from Recent Apps. It was in
no menu, no folder and no drawer. So was Magisk. Both declared the obvious
thing, and the framework agreed they had:

```
$ adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \
      com.github.jackharvest.flipflex
com.github.jackharvest.flipflex/.ui.SplashActivity
```

A correctly declared launcher activity that no launcher shows.

## What TCL's Launcher3 actually does

`CLAUDE.md` used to say the launcher was stock AOSP Launcher3, so a sideloaded
app would appear in the drawer. **That was wrong**, and it is the assumption
that made this take as long as it did. `HOME` really does resolve to
`com.android.launcher3`, but the build is a TCL fork with a very different
all-apps implementation.

It does not enumerate installed apps. It walks a hardcoded array of package
names and matches each one against the installed set. Read out of
`/system/priv-app/Launcher3/Launcher3.apk` by parsing `resources.arsc`:

```
array/allapp_list = com.android.dialer, com.android.mms, com.android.contacts,
                    com.android.gallery3d, com.android.music, com.tcl.camera,
                    org.chromium.chrome, @string/tools_key, com.android.settings,
                    com.att.deviceunlock, com.cricketwireless.deviceunlock

array/tools_list  = com.android.soundrecorder, com.android.calendar,
                    com.android.deskclock, com.android.note,
                    com.android.calculator2, com.android.email,
                    com.jrdcom.filemanager, com.android.stk,
                    com.tcl.tct.weather, com.android.dialer, com.tcl.logger
```

Nine of those eleven are installed on this unit. That is exactly the nine rows
the stock Menu shows, in exactly that order. There is no "everything else"
bucket.

### Three rules in the loop that reads it

From `Launcher.bindAllApplications`, decompiled with jadx rather than guessed at:

```java
String[] stringArray = getResources().getStringArray(R.array.allapp_list);
stringArray[4] = getResources().getString(R.string.def_appmenu_position_five);
for (String str : stringArray) {
    if (!str.startsWith("com") && !str.startsWith("org")) {
        ... folder / media centre / carrier special ...
    } else {
        for (AppInfo a : installedApps)
            if (str.equals(a.componentName.getPackageName())) appsshow.add(a);
    }
}
```

| Rule | Consequence |
|---|---|
| `stringArray[4]` is **overwritten** with `@string/def_appmenu_position_five` | Position five is not ours to set. It resolves to `com.android.music` on this build |
| An entry counts as a package name **only if it starts with `com` or `org`** | The single most important line in the file. See below |
| Anything else is a special: Tools folder, media centre, myATT, myLatam | `@string/tools_key` resolves to the literal `"Tools"` |

### `startsWith("com") || startsWith("org")` is the whole problem

The app was `io.github.jackharvest.flipflex`. That starts with `io`, so it never
reached the package branch at all — it fell into the *special* branch and was
rendered as a **second, iconless "Tools" folder** pointing at
`ToolsNewActivity`.

That is worth dwelling on, because it is the failure mode this project keeps
meeting: not an error, not a missing row, but a silently wrong answer that looks
like a rendering glitch. The first overlay build "worked" — the Menu changed,
Tools moved, a tenth row nearly appeared — and none of it meant what it looked
like.

**So the app is now `com.github.jackharvest.flipflex`.** Which is also the more
correct reverse-DNS for a repository on github.com; `io.github.<user>` is the
GitHub Pages domain. The prefs file was copied across with root so the Plex
token survived the rename:

```sh
adb shell "su -c 'cp /data/data/<old>/shared_prefs/flipflex.xml \
                     /data/data/<new>/shared_prefs/flipflex.xml'"
adb shell "su -c 'chown -R \$(stat -c %u:%g /data/data/<new>) .../shared_prefs \
                  && restorecon -R .../shared_prefs'"
```

Without the `chown` and `restorecon` the app cannot read its own file, and the
symptom is a sign-in screen rather than a permission error.

## Why an overlay, and not any of the alternatives

| Approach | Why not |
|---|---|
| Repack `Launcher3.apk` with a patched array | It is signed. Modifying any entry breaks the APK signature, PackageManager then refuses to scan it out of `/system`, and the phone boots with **no launcher at all**. Re-signing needs the platform key, which a `release-keys` build does not hand out |
| Install the overlay to `/data` with `pm install` | A static RRO is only honoured when preinstalled in a system partition. On API 30 an overlay from `/data` is refused outright |
| Squat an unused whitelisted package name | `com.att.deviceunlock` and `com.cricketwireless.deviceunlock` are both free on this Tellc unit, so a stub APK with one of those ids would have worked. Rejected once the `startsWith` rule was understood: renaming our own package is one line and leaves one APK instead of two, and the squatted id would differ per carrier |
| Register FlipFlex as a second `category.HOME` | Turns every press of the home key into a chooser. The insurance option in the original plan, and a bad one |

## The overlay

`overlay/` is a Gradle module with no code and one resource. Its manifest shape
was copied from TCL's own `/product/overlay/Launcher3-overlay.apk`, decompiled
to check rather than guessed at — which is also the proof the approach works,
because the framework is already running a static overlay against this exact
target package. Theirs replaces the four wallpapers, so there is nothing to
collide with.

```xml
<overlay android:targetPackage="com.android.launcher3"
         android:isStatic="true" android:priority="10" />
<application android:hasCode="false" />
```

No `android:targetName`. A *named* overlay may only touch resources the target
published in an `<overlayable>` block, and Launcher3 publishes none; an unnamed
overlay in a system partition may set any resource, which is what reaching a
private `string-array` requires.

`tools/install-menu-overlay.sh` builds it and stages it as a Magisk module at
`/data/adb/modules/flipflex-menu/system/product/overlay/FlipFlexMenu.apk`.
Magisk's magic mount is how a file gets into a read-only, verity-protected
partition, and removing the module puts the phone back exactly as it was.

Two things about that script that are not decoration:

- **It builds `release`, not `debug.`** AGP marks debug builds
  `android:testOnly`, and PackageManager refuses to scan a testOnly package out
  of `/system`. The signing key does not matter for a system overlay; the build
  type does.
- **The APK goes through `su` with `cat`, not `adb push`.** `/data/adb` is
  `0700 root`, so a push cannot land underneath it, and staging via
  `/data/local/tmp` leaves a copy of the APK on the phone.

Verify after the reboot:

```sh
$ adb shell cmd overlay list | grep -A3 launcher3
com.android.launcher3
[x] com.github.jackharvest.flipflex.menu
[x] com.android.launcher3.tct_overlay_product__
```

`[x]` is enabled. `---` would be scanned-but-disabled.

## The one wart, stated plainly

`allapp_list` item 8 is the literal string `Tools`, where the stock array has a
reference to Launcher3's private `@string/tools_key`. aapt2 cannot resolve a
private cross-package reference without the target APK on the build classpath,
and pulling a 5 MB APK into the build to recover one word is not worth it —
`getStringArray` flattens references, so both sides end up comparing the same
string, and the folder still opens.

**This pins the folder's label to English.** On a handset set to another
language the launcher would compare its own localised string against this one,
fail to match, and Tools would become a nameless duplicate of itself. That is
the line to fix if FlipFlex is ever used on a non-English device.

## The icon was a second, separate bug

With the overlay working, the row appeared with **no icon**. The launcher binds
icons with `imageView.setImageBitmap(appInfo.iconBitmap)`, and the placeholder
`ic_launcher` was a `<vector>`. A vector has no bitmap to hand over, and TCL's
icon path produces nothing rather than rasterising it.

Fixed by shipping real PNGs in `mipmap-mdpi` through `mipmap-xxxhdpi`, cropped
from `img/FlipFlexSplashLogo_240x320.png`. The device is mdpi, so the 48px one
is the one it actually loads.

## Tools used, for next time

`resources.arsc` was parsed with a throwaway Python script (chunk header →
string pools → `ResTable_type` → bag entries); jadx decompiled `classes.dex`.
Neither apktool nor aapt2 was available on this Mac, and neither turned out to
be necessary — the array is readable with a struct parser and the logic is
readable with jadx.

The useful habit: **read the launcher, do not experiment with it.** Every
experiment here costs a reboot, and two of the three hypotheses that a reboot
would have tested (an `isSystemApp` filter, a per-index icon map) were wrong.
