#!/bin/sh
# attribution check: refuse AI-assistant attribution in a commit message.
#
# Standalone by design — it reads a message, not a repository:
#
#   scripts/attribution_check.sh <commit-msg-file>    the commit-msg hook's call
#   git log --format=%B <range> | scripts/attribution_check.sh
#   printf '%s\n' "$BODY" | scripts/attribution_check.sh
#
# Refused: a co-author trailer (AGENTS.md bans them outright), a Claude-Session
# trailer or claude.ai session link, Anthropic's machine address, and the
# "Generated with Claude Code" footer. Exit 1 names the offending lines, 0 is
# clean. Install the hook with scripts/install_hooks.sh.
set -eu

pattern='co-authored-by:|Claude-Session:|claude\.ai/code/session_|noreply@anthropic\.com|Generated with \[?Claude Code'

usage() {
  cat <<'EOF'
usage: attribution_check.sh [MESSAGE-FILE]

Refuse AI-assistant attribution in a commit message. The message comes from
MESSAGE-FILE, or from stdin when no file is given.

Exits 1 naming the offending lines; 0 when clean.
EOF
}

if [ $# -gt 1 ]; then
  usage >&2
  exit 2
fi

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
esac

if [ $# -eq 1 ]; then
  if [ ! -f "$1" ]; then
    echo "attribution-check: cannot read $1" >&2
    exit 2
  fi
  hits=$(grep -inE "$pattern" "$1") || hits=''
else
  if [ -t 0 ]; then
    usage >&2
    exit 2
  fi
  hits=$(grep -inE "$pattern") || hits=''
fi

if [ -n "$hits" ]; then
  echo "attribution-check: the message carries AI attribution:" >&2
  printf '%s\n' "$hits" | sed 's/^/  /' >&2
  exit 1
fi
