#!/usr/bin/env bash
# Ephemeral JShell probe against already-compiled classes (AGENTS.md [VR1j], [VR1k]).
#
# Why this exists: a committed JUnit test costs a test compile plus a Spring context
# start to answer a question the agent discards moments later. This lane runs the JDK's
# own jshell against build/classes/java/main, invokes no build tool, and writes nothing.
#
# Deliberately invokes no Gradle. Gradle's stale-output cleanup removes build/classes
# when a run's task graph does not declare it, which would silently empty the very
# directory this probe reads.
set -euo pipefail

RED="${RED:-\033[0;31m}"
YELLOW="${YELLOW:-\033[0;33m}"
CYAN="${CYAN:-\033[0;36m}"
NC="${NC:-\033[0m}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "$script_dir/.." && pwd -P)"
classes_dir="$repo_root/build/classes/java/main"
resources_dir="$repo_root/build/resources/main"

if [ "$#" -ne 1 ]; then
    echo -e "${RED}Usage: probe_jshell.sh <path outside the repo>/check.jsh${NC}" >&2
    exit 2
fi

snippet="$1"
if [ ! -f "$snippet" ]; then
    echo -e "${RED}Probe snippet not found: $snippet${NC}" >&2
    exit 2
fi
snippet="$(cd "$(dirname "$snippet")" && pwd -P)/$(basename "$snippet")"

# Probes are ephemeral and must never become committable artifacts. Refuse any snippet
# living inside this checkout or any sibling worktree; the session scratchpad is the workpad.
for tree in "$repo_root" $(git -C "$repo_root" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2}'); do
    tree_real="$(cd "$tree" && pwd -P 2>/dev/null)" || continue
    case "$snippet" in
        "$tree_real"/*)
            echo -e "${RED}Probe snippets must live outside the repository (found under $tree_real).${NC}" >&2
            echo -e "${RED}Write the snippet to the session scratchpad instead.${NC}" >&2
            exit 2
            ;;
    esac
done

if [ ! -d "$classes_dir" ]; then
    echo -e "${RED}No compiled classes at $classes_dir. Run 'make build' first.${NC}" >&2
    exit 2
fi

# Probes see BUILT bytecode only. An edited .java file is invisible until a compile runs.
reference_class="$(find "$classes_dir" -name '*.class' -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -n 1 || true)"
if [ -n "$reference_class" ] && [ -n "$(find "$repo_root/src/main/java" -name '*.java' -newer "$reference_class" -print -quit 2>/dev/null)" ]; then
    echo -e "${YELLOW}STALE: src/main/java has edits newer than the compiled classes.${NC}" >&2
    echo -e "${YELLOW}This probe reads OLD bytecode. Run 'make build' before trusting the result.${NC}" >&2
fi

echo -e "${CYAN}Probing $snippet against $classes_dir${NC}" >&2

# jshell exits 0 even on compile errors and uncaught exceptions, and keeps executing
# later snippets. Scan its output for error markers so a failed probe fails the lane.
output="$(jshell --class-path "$classes_dir:$resources_dir" -q --execution local "$snippet" 2>&1)"
printf '%s\n' "$output"

if printf '%s\n' "$output" | grep -qE '^(Error:|Exception )'; then
    echo -e "${RED}Probe reported an error or uncaught exception.${NC}" >&2
    exit 1
fi
