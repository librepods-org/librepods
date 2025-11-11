#!/bin/bash
set -e

# Detect script location and navigate to repo root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check for updates
echo "Checking for updates..."
git fetch
if [ $(git rev-parse HEAD) = $(git rev-parse @{u}) ]; then
    echo "Already up to date."
    exit 0
fi

# Pull latest changes
echo "Updating repository..."
git pull

# Build
echo "Building librepods..."
cd linux
mkdir -p build
cd build
cmake .. && make -j $(nproc)

# Install
echo "Installing librepods..."
killall librepods 2>/dev/null || true
sleep 1
sudo cp librepods /usr/local/bin/

# Restart if it was running
echo "Starting librepods..."
nohup librepods >/dev/null 2>&1 &

echo "Update complete!"