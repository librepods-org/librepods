# LibrePods Linux

A native Linux application to control your AirPods, with support for:

- Noise Control modes (Off, Transparency, Adaptive, Noise Cancellation)
- Conversational Awareness
- Battery monitoring
- Auto play/pause on ear detection
- Seamless handoff between phone and PC

## Prerequisites

1. Your phone's Bluetooth MAC address (can be found in Settings > About Device)
2. Qt6 packages

   ```bash
   # For Arch Linux / EndeavourOS
   sudo pacman -S qt6-base qt6-connectivity qt6-multimedia-ffmpeg qt6-multimedia

   # For Debian
   sudo apt-get install qt6-base-dev qt6-declarative-dev qt6-connectivity-dev qt6-multimedia-dev \
        qml6-module-qtquick-controls qml6-module-qtqml-workerscript qml6-module-qtquick-templates \
        qml6-module-qtquick-window qml6-module-qtquick-layouts

    # For Fedora
    sudo dnf install qt6-qtbase-devel qt6-qtconnectivity-devel \
        qt6-qtmultimedia-devel qt6-qtdeclarative-devel
   ```
3. OpenSSL development headers

    ```bash
    # On Arch Linux / EndevaourOS, these are included in the OpenSSL package, so you might already have them installed.
    sudo pacman -S openssl
    
    # For Debian / Ubuntu
    sudo apt-get install libssl-devel
    
    # For Fedora
    sudo dnf install openssl-devel
    ```
## Setup

1. Set the `PHONE_MAC_ADDRESS` environment variable to your phone's Bluetooth MAC address by running the following:

   ```bash
   export PHONE_MAC_ADDRESS="XX:XX:XX:XX:XX:XX"  # Replace with your phone's MAC
   ```

2. Build the application:

   ```bash
   mkdir build
   cd build
   cmake ..
   make -j $(nproc)
   ```

3. Run the application:

   ```bash
   ./librepods
   ```

## Usage

- Left-click the tray icon to view battery status
- Right-click to access the control menu:
  - Toggle Conversational Awareness
  - Switch between noise control modes
  - View battery levels
  - Control playback
