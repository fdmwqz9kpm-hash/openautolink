#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 or 21 installation}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.gradle/android-user}"
mkdir -p "$ANDROID_USER_HOME"

cd "$ROOT"
./gradlew :app:bundleRelease --no-daemon --dependency-verification=strict -PoalBuilderUnsigned=true "$@"
BUILT="$ROOT/app/build/outputs/bundle/release/app-release.aab"

if [ -z "${OAL_SIGNING_DIR:-}" ]; then
    printf 'Unsigned bundle: %s\n' "$BUILT"
    exit 0
fi

: "${BUNDLETOOL_JAR:?Set BUNDLETOOL_JAR to the verified bundletool jar}"
SIGNING_DIR="$(cd "$OAL_SIGNING_DIR" && pwd)"
case "$SIGNING_DIR/" in "$ROOT/"*) echo 'Signing inputs must be outside the repository' >&2; exit 1 ;; esac
for name in upload-key.jks store-password key-password alias; do
    test -s "$SIGNING_DIR/$name" || { echo "Missing signing input: $name" >&2; exit 1; }
done
CODE="$("$JAVA_HOME/bin/java" -jar "$BUNDLETOOL_JAR" dump manifest --bundle="$BUILT" --xpath='/manifest/@android:versionCode')"
OUTPUT_DIR="${OAL_OUTPUT_DIR:-$ROOT/app/build/outputs/local-signed}"
mkdir -p "$OUTPUT_DIR"
SIGNED="$OUTPUT_DIR/openautolink-car-v${CODE}-signed.aab"
cp "$BUILT" "$SIGNED"
"$JAVA_HOME/bin/jarsigner" -keystore "$SIGNING_DIR/upload-key.jks" \
    -storepass:file "$SIGNING_DIR/store-password" -keypass:file "$SIGNING_DIR/key-password" \
    "$SIGNED" "$(cat "$SIGNING_DIR/alias")"
"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL_JAR" validate --bundle="$SIGNED"
VERIFY_OUTPUT="$("$JAVA_HOME/bin/jarsigner" -verify "$SIGNED" 2>&1)"
case "$VERIFY_OUTPUT" in *'jar verified.'*) ;; *) echo "$VERIFY_OUTPUT" >&2; exit 1 ;; esac
case "$VERIFY_OUTPUT" in *unsigned*) echo 'Bundle contains unsigned entries' >&2; exit 1 ;; esac
(cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$SIGNED")" > "$(basename "$SIGNED").sha256")
printf 'Verified signed bundle: %s\n' "$SIGNED"
