#!/bin/sh
# Build (and optionally install) the FlipFlex APK.
#
# This exists because `./gradlew` alone fails on a fresh shell with "Unable to
# locate a Java Runtime". Homebrew's openjdk@17 is keg-only -- it is deliberately
# not symlinked into /Library/Java/JavaVirtualMachines, so /usr/libexec/java_home
# cannot see it and Gradle has no JDK to run on. Nothing in the repo recorded
# that, so the toolchain worked only in whichever shell happened to have
# JAVA_HOME exported.
#
#   tools/build.sh              # assemble debug
#   tools/build.sh install      # assemble, then adb install -r
#   tools/build.sh run          # assemble, install, and launch
set -e

cd "$(dirname "$0")/.."

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export JAVA_HOME
[ -x "$JAVA_HOME/bin/java" ] || {
	echo "No JDK at $JAVA_HOME -- brew install openjdk@17" >&2
	exit 1
}

APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.github.jackharvest.flipflex

# "$@" is NOT passed through: the arguments to this script are install/run, and
# handing those to Gradle makes it fail with "task 'install' not found" while
# the real compile error scrolls past in the suppressed output.
./gradlew :app:assembleDebug || exit 1
echo "built $APK"

case "$1" in
install | run)
	# -r keeps app data across reinstalls, which matters here: the Plex token
	# lives in SharedPreferences and re-linking means typing a PIN into
	# plex.tv/link again on another machine every single build.
	#
	# A phone carrying a release build will refuse this one, because Android
	# identifies an app by its signature and the debug key is not the release
	# key. The message it gives says INSTALL_FAILED_UPDATE_INCOMPATIBLE and
	# nothing about which build is which, so say it here instead -- and do not
	# offer to uninstall, because that silently discards the login and every
	# downloaded episode.
	if ! adb install -r "$APK"; then
		echo >&2
		echo "If that said UPDATE_INCOMPATIBLE, the phone has the RELEASE build" >&2
		echo "on it and this is a debug one. They are signed with different keys." >&2
		echo "Either build a release (tools/release.sh) and 'adb install -r' that," >&2
		echo "or uninstall first -- which throws away the Plex token and the" >&2
		echo "downloads. See 'Shipping it' in CLAUDE.md before uninstalling." >&2
		exit 1
	fi
	# Restart the launcher, or the Menu entry looks like it has been lost.
	#
	# TCL's Launcher3 builds the Menu once, in bindAllApplications, by walking
	# allapp_list against the app list it was handed at bind time. A reinstall
	# does not make it rebind, so FlipFlex simply stops being drawn -- the row is
	# gone while the overlay is still installed and `cmd overlay list` still says
	# [x], which makes it look like the overlay broke rather than the launcher
	# going stale. Reported as "the Menu entry disappeared when we moved to 1.0";
	# a force-stop brought it straight back.
	#
	# Safe: Launcher3 is the HOME app, so the system restarts it immediately. It
	# costs about a second of blank wallpaper.
	adb shell am force-stop com.android.launcher3 || true
	;;
esac

if [ "$1" = "run" ]; then
	adb shell am start -n "$PKG/.ui.SplashActivity"
fi
