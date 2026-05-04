#!/bin/bash
# Run this after logging back in to activate everything

# Restart pipewire/wireplumber for new Bluetooth config
systemctl --user restart wireplumber.service
systemctl --user restart pipewire.service

echo "Done! Connect your AirPods 4 via Bluetooth and run the librepods app."
echo "Binary location: ~/Desktop/librepods/librepods/linux/build/librepods"
