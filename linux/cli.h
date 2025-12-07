#pragma once

#include <QString>
#include <QApplication>
#include <optional>
#include "enums.h"

using namespace AirpodsTrayApp::Enums;

namespace CLI {

// Noise control mode helpers
QString noiseControlModeName(NoiseControlMode mode);
std::optional<NoiseControlMode> parseNoiseControlMode(const QString &name);

// Check if another instance is running
bool isInstanceRunning();

// Send IPC command to running instance and get response
QString sendIpcCommand(const QString &command, int timeout = 2000);

// Parse CLI arguments and handle CLI commands
// Returns: -1 if should continue to GUI, otherwise the exit code
int handleCLICommands(QApplication &app);

} // namespace CLI
