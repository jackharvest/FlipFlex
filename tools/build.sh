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
	adb install -r "$APK"
	;;
esac

if [ "$1" = "run" ]; then
	adb shell am start -n "$PKG/.ui.SplashActivity"
fi
