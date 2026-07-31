#!/bin/sh
# Archival package for the WhatsLiberation experimental proof of concept.
set -eu

repo_name=WhatsLiberation
classification=prototype
repo_root=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
archive_dir=

die() {
    printf '%s\n' "future gate: $*" >&2
    exit 1
}

usage() {
    printf '%s\n' "usage: ./RESOURCE_CONSTRAINED_FUTURE_GATE.sh --archive-dir /absolute/existing/destination"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --archive-dir)
            shift
            [ "$#" -gt 0 ] || die "--archive-dir needs an absolute destination"
            archive_dir=$1
            ;;
        --help) usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
    shift
done

[ "$(basename "$repo_root")" = "$repo_name" ] || die "expected repository basename $repo_name"
git_root=$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null) || die "not a Git worktree"
[ "$git_root" = "$repo_root" ] || die "script must remain at the Git worktree root"
[ -n "$archive_dir" ] || die "archive output is opt-in; supply --archive-dir"
case "$archive_dir" in
    /*) ;;
    *) die "archive destination must be absolute" ;;
esac
[ -d "$archive_dir" ] || die "archive destination must already exist"
archive_dir=$(CDPATH= cd "$archive_dir" && pwd -P)
case "$archive_dir" in
    /|/tmp) die "refusing a broad archive destination" ;;
esac
case "$repo_root/" in
    "$archive_dir/"*) die "archive destination must not contain the target repository" ;;
esac
case "$archive_dir/" in
    "$repo_root/"*) die "archive destination must not be inside the target repository" ;;
esac

head=$(git -C "$repo_root" rev-parse HEAD)
branch=$(git -C "$repo_root" symbolic-ref -q --short HEAD 2>/dev/null || printf '%s' detached)
package_dir=$archive_dir/$repo_name-$head.archive
[ ! -e "$package_dir" ] || die "archive package already exists: $package_dir"
umask 077
mkdir "$package_dir"

{
    printf 'classification=%s\n' "$classification"
    printf 'repository=%s\n' "$repo_root"
    printf 'head=%s\n' "$head"
    printf 'branch=%s\n' "$branch"
    printf '%s\n' 'scope=Git metadata and HEAD bundle only; no Android device, WhatsApp content, export directory, ignored, or untracked data is read.'
    printf '%s\n' '[git-status-porcelain-v1-branch]'
    git -C "$repo_root" status --porcelain=v1 --branch
    printf '%s\n' '[cached-upstream]'
    git -C "$repo_root" rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || printf '%s\n' '(none)'
    printf '%s\n' '[cached-origin-head]'
    git -C "$repo_root" symbolic-ref -q --short refs/remotes/origin/HEAD 2>/dev/null || printf '%s\n' '(none)'
} > "$package_dir/git-metadata.txt"

git -C "$repo_root" bundle create "$package_dir/$repo_name-$head.bundle" HEAD
printf '%s\n' "Created metadata and a HEAD-only Git bundle. Chat exports and device data are intentionally excluded."
