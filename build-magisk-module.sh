#!/bin/sh

set -eux

cd root-module
rm -f ../btl2capfix.zip

# COPYFILE_DISABLE env is a macOS fix to avoid parasitic files in ZIPs: https://superuser.com/a/260264
export COPYFILE_DISABLE=1
curl -L -o ./radare2-5.9.9-android-aarch64.tar.gz "https://github.com/devnoname120/radare2/releases/download/5.9.8-android-aln/radare2-5.9.9-android-aarch64-aln.tar.gz"
zip -r ../btl2capfix.zip . -x \*.DS_Store \*__MACOSX \*DEBIAN ._\* .gitignore
