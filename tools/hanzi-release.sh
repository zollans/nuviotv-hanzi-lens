#!/bin/bash
# Build the Hanzi Lens fork and publish it as a GitHub release on zollans/nuviotv-hanzi-lens,
# where the app's in-app updater picks it up.
#   tools/hanzi-release.sh            release, unless nothing changed since the last release
#   tools/hanzi-release.sh --force    release even if nothing changed
#   tools/hanzi-release.sh --dry-run  print the version it would release and stop
# Needs: the hanzi-lens branch checked out and clean, local.properties with the NUVIO_SUPABASE_*
# values (without them the build cannot sign in or sync), and gh signed in.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=zollans/nuviotv-hanzi-lens
REMOTE=hanzi
BRANCH=hanzi-lens
UPSTREAM=origin/dev
ABIS=(armeabi-v7a arm64-v8a)

force=false dry=false
for arg in "$@"; do
  case "$arg" in
    --force) force=true ;;
    --dry-run) dry=true ;;
    *) echo "usage: $0 [--force] [--dry-run]" >&2; exit 2 ;;
  esac
done

[[ "$(git branch --show-current)" == "$BRANCH" ]] || { echo "error: not on $BRANCH" >&2; exit 2; }
git diff --quiet && git diff --cached --quiet || { echo "error: uncommitted changes" >&2; exit 2; }
grep -q '^NUVIO_SUPABASE_ANON_KEY=..' local.properties 2>/dev/null \
  || { echo "error: local.properties has no NUVIO_SUPABASE_ANON_KEY; the build could not sign in" >&2; exit 2; }

git fetch --quiet --tags "$REMOTE"
last_tag=$(gh release list --repo "$REPO" --limit 1 --exclude-drafts --json tagName --jq '.[0].tagName // empty')
if [[ -n "$last_tag" ]] && ! $force \
  && git diff --quiet "$last_tag" HEAD -- . ':(exclude)hanzi-release.properties'; then
  echo "STATUS: nothing new since $last_tag"
  exit 0
fi

base=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts | head -1)
[[ -n "$base" ]] || { echo "error: upstream versionName not found in app/build.gradle.kts" >&2; exit 2; }
if [[ "$base" == *-* ]]; then sep=.; else sep=-; fi
n=1
while gh release view "v$base${sep}hanzi.$n" --repo "$REPO" >/dev/null 2>&1; do n=$((n + 1)); done
version="$base${sep}hanzi.$n"
echo "Releasing $version (last release: ${last_tag:-none})"
$dry && exit 0

current=$(sed -n 's/^HANZI_REVISION=//p' hanzi-release.properties)
if [[ "$current" != "$n" ]]; then
  sed -i '' "s/^HANZI_REVISION=.*/HANZI_REVISION=$n/" hanzi-release.properties
  git commit --quiet -m "Hanzi Lens release $version" hanzi-release.properties
fi

gradle_build() {
  ./gradlew :app:assembleFullDebug --console=plain \
    -Dorg.gradle.jvmargs="-Xmx8g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
}
# One retry: the audio-sync runtime download from GitHub sometimes times out.
gradle_build || gradle_build

sdk_dir=$(sed -n 's/^sdk.dir=//p' local.properties)
aapt2=$(ls -d "$sdk_dir"/build-tools/*/aapt2 | tail -1)
out=app/build/outputs/apk/full/debug
dist=build/hanzi-release
rm -rf "$dist" && mkdir -p "$dist"
for abi in "${ABIS[@]}"; do
  apk="$out/app-full-$abi-debug.apk"
  built=$("$aapt2" dump badging "$apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  [[ "$built" == "$version" ]] || { echo "error: $apk is version $built, expected $version" >&2; exit 1; }
  cp "$apk" "$dist/NuvioTV-Hanzi-Lens-$version-$abi.apk"
done

git push --quiet "$REMOTE" "$BRANCH"
upstream_base=$(git merge-base HEAD "$UPSTREAM")
notes="Nuvio TV with Hanzi Lens (pinyin, English line and word lookup on Chinese subtitles).

Based on NuvioMedia/NuvioTV \`${UPSTREAM#origin/}\` at $(git rev-parse --short "$upstream_base") (upstream version $base). Fork commit $(git rev-parse --short HEAD).

Installs as \`com.nuviodebug.com\`, next to the official app. Fire TV and other 32-bit devices use the armeabi-v7a APK."
prerelease=()
[[ "$base" == *-* ]] && prerelease=(--prerelease)
gh release create "v$version" "$dist"/*.apk --repo "$REPO" --target "$(git rev-parse HEAD)" \
  --title "Nuvio TV Hanzi Lens $version" --notes "$notes" ${prerelease[@]+"${prerelease[@]}"}
git fetch --quiet --tags "$REMOTE"
echo "STATUS: released v$version"
