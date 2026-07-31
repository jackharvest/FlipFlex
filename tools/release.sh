#!/bin/sh
# Cut a FlipFlex release: build, sign, verify, tag, publish.
#
#   tools/release.sh            # build and verify only, change nothing
#   tools/release.sh publish    # …and tag it and push a GitHub release
#
# The version is read from app/build.gradle.kts rather than passed in, so the
# APK, the tag and the string on Settings -> Help can never disagree. Bump it
# there -- BOTH versionCode and versionName -- and commit before running this.
set -e

cd "$(dirname "$0")/.."

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export JAVA_HOME
[ -x "$JAVA_HOME/bin/java" ] || {
	echo "No JDK at $JAVA_HOME -- brew install openjdk@17" >&2
	exit 1
}

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
[ -n "$VERSION" ] && [ -n "$CODE" ] || {
	echo "could not read versionName/versionCode out of app/build.gradle.kts" >&2
	exit 1
}
TAG="v$VERSION"
OUT="build/flipflex-$VERSION.apk"

# Signing is not optional for a published build, and the check is here rather
# than at the end because an unsigned APK is a twelve-megabyte way to find out.
# See app/build.gradle.kts: an APK signed with a different key cannot be
# installed over one already on a phone, so every release must carry the same
# signature as the one before it.
[ -f "$HOME/.flipflex/keystore.properties" ] || {
	echo "no ~/.flipflex/keystore.properties -- this build would be unsigned" >&2
	exit 1
}

./gradlew :app:assembleRelease
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$OUT"

# Prove it is signed, and print the fingerprint. If this digest ever differs
# from the previous release's, the key has changed and nobody can update in
# place -- which is worth noticing here rather than in an issue report.
SIGNER=$(find "$(sed -n 's/^sdk.dir=//p' local.properties)/build-tools" -name apksigner 2>/dev/null | sort | tail -1)
if [ -n "$SIGNER" ]; then
	"$SIGNER" verify --print-certs "$OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: /sha256 /p'
else
	echo "apksigner not found; skipping signature check" >&2
fi

echo "built $OUT  ($TAG, versionCode $CODE)"

[ "$1" = "publish" ] || {
	echo "not publishing. re-run with: tools/release.sh publish"
	exit 0
}

# A dirty tree here means the tag would point at something that is not what was
# built, which is the one thing a release tag exists to promise.
[ -z "$(git status --porcelain)" ] || {
	echo "working tree is dirty -- commit before publishing" >&2
	exit 1
}

git tag -a "$TAG" -m "FlipFlex $VERSION"
git push origin "$TAG"
gh release create "$TAG" "$OUT" \
	--title "FlipFlex $VERSION" \
	--generate-notes
echo "published $TAG"
