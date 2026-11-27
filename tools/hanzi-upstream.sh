#!/bin/bash
# Check the upstream branch for new commits and whether they merge cleanly into hanzi-lens.
#   tools/hanzi-upstream.sh          report only
#   tools/hanzi-upstream.sh --merge  when clean, also merge them and push hanzi-lens
# Exit status: 0 up to date or clean (merged with --merge), 3 conflicts (nothing changed), other: error.
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM=origin/dev   # NuvioMedia/NuvioTV
REMOTE=hanzi          # zollans/nuviotv-hanzi-lens
BRANCH=hanzi-lens

merge=false
[[ "${1:-}" == --merge ]] && merge=true

[[ "$(git branch --show-current)" == "$BRANCH" ]] || { echo "error: not on $BRANCH" >&2; exit 2; }
git diff --quiet && git diff --cached --quiet || { echo "error: uncommitted changes" >&2; exit 2; }

git fetch --quiet origin
git fetch --quiet "$REMOTE"
if ! git merge-base --is-ancestor "$REMOTE/$BRANCH" HEAD; then
  echo "error: $REMOTE/$BRANCH has commits this checkout lacks; pull them first" >&2
  exit 2
fi

new=$(git rev-list --count "HEAD..$UPSTREAM")
if [[ "$new" -eq 0 ]]; then
  echo "STATUS: up to date with $UPSTREAM ($(git rev-parse --short "$UPSTREAM"))"
  exit 0
fi
echo "$new new upstream commit(s) on $UPSTREAM:"
git log --oneline --no-decorate "HEAD..$UPSTREAM" | head -30

if ! out=$(git merge-tree --write-tree --name-only HEAD "$UPSTREAM"); then
  echo "STATUS: conflicts merging $UPSTREAM into $BRANCH, in:"
  # First line is the tree id; conflicted paths follow until a blank line.
  echo "$out" | sed -n '2,/^$/p' | sed '/^$/d; s/^/  /'
  exit 3
fi

if ! $merge; then
  echo "STATUS: merges cleanly (not merged; rerun with --merge)"
  exit 0
fi
git merge --quiet --no-edit -m "Merge upstream $UPSTREAM ($(git rev-parse --short "$UPSTREAM")) into $BRANCH" "$UPSTREAM"
git push --quiet "$REMOTE" "$BRANCH"
echo "STATUS: merged $UPSTREAM and pushed $BRANCH ($(git rev-parse --short HEAD))"
