APP_NAME := "librepods"
DESKTOP_FILE := "assets/me.kavishdevar.librepods.desktop"
ICON_FILE := "assets/icon.png"

default: build-appimage

build:
    cargo build --release

prepare:
    #!/usr/bin/env bash
    set -euo pipefail

    tmpdir="$(mktemp -d)"
    echo "Building AppDir in: $tmpdir"

    mkdir -p "$tmpdir/usr/bin"
    mkdir -p "$tmpdir/usr/share/applications"
    mkdir -p "$tmpdir/usr/share/icons/hicolor/256x256/apps"

    cp target/release/{{APP_NAME}} "$tmpdir/usr/bin/"
    cp assets/icon.png "$tmpdir/usr/share/icons/hicolor/256x256/apps/me.kavishdevar.librepods.png"
    cp {{DESKTOP_FILE}} "$tmpdir/{{APP_NAME}}.desktop"

    printf '%s\n' \
        '#!/bin/bash' \
        'HERE="$(dirname "$(readlink -f "$0")")"' \
        'exec "$HERE/usr/bin/librepods" "$@"' \
        > "$tmpdir/AppRun"

    chmod +x "$tmpdir/AppRun"
    echo "$tmpdir" > .appdir_path

bundle:
    #!/usr/bin/env bash
    set -euo pipefail
    tmpdir="$(cat .appdir_path)"

    linuxdeploy \
        --appdir "$tmpdir" \
        --executable "$tmpdir/usr/bin/{{APP_NAME}}" \
        --desktop-file "$tmpdir/{{APP_NAME}}.desktop" \
        --icon-file "$tmpdir/usr/share/icons/hicolor/256x256/apps/me.kavishdevar.librepods.png"

build-appimage: build prepare bundle
    #!/usr/bin/env bash
    set -euo pipefail
    tmpdir="$(cat .appdir_path)"
    mkdir -p dist
    appimagetool "$tmpdir" "dist/LibrePods-x86_64.AppImage"
    rm -rf "$tmpdir" .appdir_path
    echo "Done!"


tarball version:
    #!/usr/bin/env bash
    set -euo pipefail
    cargo vendor vendor
    mkdir -p dist .cargo
    cat > .cargo/config.toml <<'EOF'
    [source.crates-io]
    replace-with = "vendored-sources"
    [source.vendored-sources]
    directory = "vendor"
    EOF
    TAR="librepods-v{{version}}-source.tar.gz"
    tar -czf "dist/${TAR}" \
      --transform "s,^,librepods-v{{version}}/," \
      Cargo.toml Cargo.lock src vendor .cargo assets flatpak
    echo "Created: dist/${TAR}"
