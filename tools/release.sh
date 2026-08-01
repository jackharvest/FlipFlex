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
NOTES="docs/release-notes/$VERSION.md"

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
	# "signing key" not "sha256": this is the certificate's digest, not the
	# APK's, and it is SUPPOSED to be identical every release. Printed bare it
	# reads like an artifact hash, and an unchanged one then looks like a build
	# that did not rebuild -- which cost a few minutes proving otherwise.
	"$SIGNER" verify --print-certs "$OUT" |
		sed -n 's/^Signer #1 certificate SHA-256 digest: /signing key /p'
	shasum -a 256 "$OUT" | sed 's/^\([0-9a-f]*\) .*/apk sha256 \1/'
else
	echo "apksigner not found; skipping signature check" >&2
fi

echo "built $OUT  ($TAG, versionCode $CODE)"

# Said here as well as enforced below, so a missing set of notes turns up while
# there is still time to write them rather than at the moment of publishing.
[ -f "$NOTES" ] || echo "note: no $NOTES yet -- publishing will refuse without it" >&2

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

# The notes are not decoration and not a changelog. Since 1.0.2 the handset
# reads them off the release page and shows them in the "FlipFlex X.Y.Z is
# available" panel, so they are what somebody reads while deciding whether to
# press Download and install -- on a 240x320 screen. A few bullets naming what a
# user would actually notice, with the rest swept into one "and other bug fixes"
# line. `--generate-notes` produced a list of commit subjects, which is the
# wrong thing on the release page and worse on the phone.
[ -f "$NOTES" ] || {
	echo "no $NOTES -- write the release notes before publishing." >&2
	echo "a few bullets a user would care about; see 'Shipping it' in CLAUDE.md" >&2
	exit 1
}

git tag -a "$TAG" -m "FlipFlex $VERSION"
git push origin "$TAG"
gh release create "$TAG" "$OUT" \
	--title "FlipFlex $VERSION" \
	--notes-file "$NOTES"
echo "published $TAG"
