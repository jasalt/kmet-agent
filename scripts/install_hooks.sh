#!/bin/sh
# Install the repository's git hooks (scripts/git-hooks/*) into the git
# directory's hooks/, replacing same-named files and marking them executable.
# Copies, so a clone that never runs this is unaffected; re-run after a hook
# changes.
set -eu

hooks_src=$(dirname "$0")/git-hooks

if ! git_dir=$(git rev-parse --absolute-git-dir 2>/dev/null); then
  echo "install_hooks: not a git repository" >&2
  exit 1
fi

mkdir -p "$git_dir/hooks"
installed=0
for src in "$hooks_src"/*; do
  [ -f "$src" ] || continue
  dest="$git_dir/hooks/$(basename "$src")"
  cp "$src" "$dest"
  chmod +x "$dest"
  echo "installed $dest"
  installed=1
done

if [ "$installed" -eq 0 ]; then
  echo "install_hooks: no hooks in $hooks_src" >&2
  exit 1
fi
