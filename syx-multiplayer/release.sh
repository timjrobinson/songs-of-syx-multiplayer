#!/usr/bin/env bash
# Cut a release from main, locally (CI cannot build: the game jar is
# proprietary and lives only on developer machines).
#
#   ./release.sh            next patch version (latest vX.Y.Z tag + 1)
#   ./release.sh --minor    next minor (X.Y+1.0)
#   ./release.sh --major    next major (X+1.0.0)
#   ./release.sh 0.4.0      exactly this version
#   ./release.sh --dry-run  show what would happen, build, but do not tag/publish
#
# Steps: check we are on an up-to-date, clean main; compute the version; run
# the headless test suite and build with that version; package the zip; tag;
# push the tag; create the GitHub release with the zip and generated notes.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(dirname "$ROOT")"
FIRST_VERSION=0.3.3      # used when no vX.Y.Z tag exists yet
cd "$PROJ"

bump=patch; dry=0; explicit=""
for a in "$@"; do
    case "$a" in
        --minor) bump=minor ;;
        --major) bump=major ;;
        --dry-run) dry=1 ;;
        [0-9]*.[0-9]*.[0-9]*) explicit="$a" ;;
        *) echo "usage: release.sh [--minor|--major|X.Y.Z] [--dry-run]" >&2; exit 2 ;;
    esac
done

command -v gh >/dev/null || { echo "error: gh (GitHub CLI) is required and must be logged in" >&2; exit 1; }
branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch" == main ]] || { echo "error: release from main (currently on $branch)" >&2; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "error: working tree not clean" >&2; exit 1; }
git fetch -q origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "error: local main differs from origin/main (pull or push first)" >&2; exit 1; }

last="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -1)"
if [[ -n "$explicit" ]]; then
    version="$explicit"
elif [[ -z "$last" ]]; then
    version="$FIRST_VERSION"
else
    IFS=. read -r major minor patch <<< "${last#v}"
    case "$bump" in
        patch) version="$major.$minor.$((patch + 1))" ;;
        minor) version="$major.$((minor + 1)).0" ;;
        major) version="$((major + 1)).0.0" ;;
    esac
fi
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "error: tag v$version already exists" >&2; exit 1; }
echo "releasing $version (previous: ${last:-none}) from $(git rev-parse --short HEAD)"

export SYXMP_VERSION="$version"
"$ROOT/test.sh"
"$ROOT/package.sh" >/dev/null
zip="$ROOT/dist/SyxMultiplayer-$version.zip"
[[ -f "$zip" ]] || { echo "error: $zip missing" >&2; exit 1; }
echo "built $zip"

if [[ $dry == 1 ]]; then
    echo "dry run: would tag v$version, push it and create the GitHub release"
    exit 0
fi

git tag -a "v$version" -m "Syx Multiplayer $version"
git push -q origin "v$version"
gh release create "v$version" "$zip" \
    --title "Syx Multiplayer $version" \
    --generate-notes \
    --notes "Install: unzip into your Songs of Syx mods folder so you get \`SyxMultiplayer/_Info.txt\` and \`SyxMultiplayer/V71/script/SyxMultiplayer.jar\`; see the README for the full setup. Every player must run this exact version."
echo "released v$version"
