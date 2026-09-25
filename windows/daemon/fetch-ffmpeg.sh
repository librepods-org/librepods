#!/usr/bin/env bash
# Fetch the minimal FFmpeg 7.1 shared libraries the daemon links against for
# AAC-ELD decode (avcodec / avutil / swresample) into vendor/ffmpeg — so the
# ~28k lines of FFmpeg headers are NOT vendored into git. Runs both on the
# Windows CI runner (Git Bash) and locally (WSL / Linux cross-build).
#
# We copy BOTH import-lib formats: .lib (MSVC — what CI's default target uses)
# and .dll.a (MinGW — the x86_64-pc-windows-gnu cross-build), so build.rs links
# under either toolchain. Pinned to a dated BtbN autobuild tag + SHA256 (NOT the
# rolling "latest" tag, which gets rebuilt and breaks the checksum).
#
# IMPORTANT — pin only to the LAST autobuild OF A MONTH (…-MM-{28..31}-…). BtbN
# keeps the last ~14 daily builds plus one build per month (~2 years back) and
# deletes the rest, so a mid-month tag 404s a fortnight later — which is exactly
# how this broke before. To move versions: pick a month-end tag, keep the
# av*-NN.dll SONAMEs in sync with what the app loads, update URL + URL_SHA256.
set -euo pipefail

URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-07-31-14-10/ffmpeg-n7.1.5-12-g1fdbca85aa-win64-lgpl-shared-7.1.zip"
URL_SHA256="0f376f96fb38554ccefb1b2ae9c7c6a7b351f0e60a372b38262c320e8392c5d0"

here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/vendor/ffmpeg"

# Already fetched (or vendored) — nothing to do.
if [ -f "$dest/lib/libavcodec.dll.a" ] || [ -f "$dest/lib/avcodec.lib" ]; then
  echo "ffmpeg already present in $dest"
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "fetching FFmpeg 7.1 shared libs…"
if ! curl -fsSL -o "$tmp/ff.zip" "$URL"; then
  echo "error: could not download $URL" >&2
  echo "If this is a 404, the pinned BtbN autobuild was pruned. Pick the LAST" >&2
  echo "autobuild of a month from https://github.com/BtbN/FFmpeg-Builds/releases" >&2
  echo "and update URL + URL_SHA256 above (those monthly tags are kept)." >&2
  exit 1
fi
echo "${URL_SHA256}  $tmp/ff.zip" | sha256sum -c -
unzip -q "$tmp/ff.zip" -d "$tmp/x"
root="$(find "$tmp/x" -maxdepth 1 -type d -name 'ffmpeg*' | head -1)"

mkdir -p "$dest/include" "$dest/lib" "$dest/bin"
cp -r "$root"/include/libavcodec "$root"/include/libavutil "$root"/include/libswresample "$dest/include/"
# import libs — both MSVC (.lib) and MinGW (.dll.a)
cp "$root"/lib/avcodec.lib "$root"/lib/avutil.lib "$root"/lib/swresample.lib "$dest/lib/"
cp "$root"/lib/libavcodec.dll.a "$root"/lib/libavutil.dll.a "$root"/lib/libswresample.dll.a "$dest/lib/"
# runtime DLLs
cp "$root"/bin/avcodec-61.dll "$root"/bin/avutil-59.dll "$root"/bin/swresample-5.dll "$dest/bin/"

echo "ffmpeg fetched into $dest"
