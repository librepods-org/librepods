#include "cli.h"

#include <QLocalSocket>
#include <QCommandLineParser>
#include <QCommandLineOption>
#include <QTextStream>

#ifndef LIBREPODS_VERSION
#define LIBREPODS_VERSION "0.1"
#endif

namespace CLI {

QString noiseControlModeName(NoiseControlMode mode) {
    switch (mode) {
        case NoiseControlMode::Off: return "off";
        case NoiseControlMode::NoiseCancellation: return "noise-cancellation";
        case NoiseControlMode::Transparency: return "transparency";
        case NoiseControlMode::Adaptive: return "adaptive";
        default: return "unknown";
    }
}

std::optional<NoiseControlMode> parseNoiseControlMode(const QString &name) {
    QString lower = name.toLower();
    if (lower == "off" || lower == "0") return NoiseControlMode::Off;
    if (lower == "noise-cancellation" || lower == "nc" || lower == "anc" || lower == "1") return NoiseControlMode::NoiseCancellation;
    if (lower == "transparency" || lower == "tr" || lower == "2") return NoiseControlMode::Transparency;
    if (lower == "adaptive" || lower == "3") return NoiseControlMode::Adaptive;
    return std::nullopt;
}

bool isInstanceRunning() {
    QLocalSocket socket;
    socket.connectToServer("app_server");
    bool running = socket.waitForConnected(300);
    socket.disconnectFromServer();
    return running;
}

QString sendIpcCommand(const QString &command, int timeout) {
    QLocalSocket socket;
    socket.connectToServer("app_server");

    if (!socket.waitForConnected(500)) {
        return QString();
    }

    socket.write(command.toUtf8());
    socket.flush();
    socket.waitForBytesWritten(500);

    if (socket.waitForReadyRead(timeout)) {
        QString response = QString::fromUtf8(socket.readAll());
        socket.disconnectFromServer();
        return response;
    }

    socket.disconnectFromServer();
    return QString();
}

int handleCLICommands(QApplication &app) {
    app.setApplicationName("LibrePods");
    app.setApplicationVersion(LIBREPODS_VERSION);

    QCommandLineParser parser;
    parser.setApplicationDescription("LibrePods - Control your AirPods on Linux");

    // Standard options
    parser.addHelpOption();
    parser.addVersionOption();

    // Application options
    QCommandLineOption debugOption(QStringList() << "debug",
        "Enable debug logging output");
    parser.addOption(debugOption);

    QCommandLineOption hideOption(QStringList() << "hide",
        "Start with window hidden (tray only)");
    parser.addOption(hideOption);

    // CLI query options
    QCommandLineOption statusOption(QStringList() << "s" << "status",
        "Show AirPods connection status and battery levels");
    parser.addOption(statusOption);

    QCommandLineOption jsonOption(QStringList() << "j" << "json",
        "Output in JSON format (use with --status)");
    parser.addOption(jsonOption);

    QCommandLineOption waybarOption(QStringList() << "w" << "waybar",
        "Output in Waybar custom module format");
    parser.addOption(waybarOption);

    // CLI control options
    QCommandLineOption setNoiseModeOption(QStringList() << "set-noise-mode",
        "Set noise control mode (off, transparency, noise-cancellation/nc/anc, adaptive)",
        "mode");
    parser.addOption(setNoiseModeOption);

    QCommandLineOption setCAOption(QStringList() << "set-conversational-awareness",
        "Set conversational awareness (on/off, true/false, 1/0)",
        "state");
    parser.addOption(setCAOption);

    QCommandLineOption setAdaptiveLevelOption(QStringList() << "set-adaptive-level",
        "Set adaptive noise level (0-100)",
        "level");
    parser.addOption(setAdaptiveLevelOption);

    parser.process(app);

    bool wantsStatus = parser.isSet(statusOption);
    bool wantsJson = parser.isSet(jsonOption);
    bool wantsWaybar = parser.isSet(waybarOption);
    QString noiseMode = parser.value(setNoiseModeOption);
    QString caState = parser.value(setCAOption);
    QString adaptiveLevel = parser.value(setAdaptiveLevelOption);

    // Check if this is a CLI command
    bool hasStatusQuery = wantsStatus || wantsWaybar;
    bool hasControlCommand = !noiseMode.isEmpty() || !caState.isEmpty() || !adaptiveLevel.isEmpty();
    bool isCLICommand = hasStatusQuery || hasControlCommand;

    if (!isCLICommand) {
        // Not a CLI command, return -1 to indicate GUI should start
        return -1;
    }

    // Handle CLI commands
    QTextStream out(stdout);
    QTextStream err(stderr);

    if (!isInstanceRunning()) {
        err << "Error: LibrePods is not running. Start the application first.\n";
        return 1;
    }

    // Handle waybar output
    if (wantsWaybar) {
        QString response = sendIpcCommand("cli:status:waybar");

        if (response.isEmpty()) {
            // Output disconnected state for waybar
            out << R"({"text": "󰥰 --", "tooltip": "LibrePods not running", "class": "disconnected"})" << "\n";
            return 0;
        }

        out << response;
        if (!response.endsWith('\n')) out << "\n";
        return 0;
    }

    // Handle status query
    if (wantsStatus) {
        QString cmd = wantsJson ? "cli:status:json" : "cli:status:text";
        QString response = sendIpcCommand(cmd);

        if (response.isEmpty()) {
            err << "Error: No response from LibrePods\n";
            return 1;
        }

        out << response;
        if (!response.endsWith('\n')) out << "\n";
        return 0;
    }

    // Handle set noise mode
    if (!noiseMode.isEmpty()) {
        auto mode = parseNoiseControlMode(noiseMode);
        if (!mode.has_value()) {
            err << "Error: Invalid noise mode '" << noiseMode << "'\n";
            err << "Valid modes: off, transparency, noise-cancellation (nc/anc), adaptive\n";
            return 1;
        }

        QString response = sendIpcCommand("cli:set-noise-mode:" + QString::number(static_cast<int>(mode.value())));
        if (response.startsWith("OK")) {
            out << "Noise control mode set to: " << noiseControlModeName(mode.value()) << "\n";
            return 0;
        } else {
            err << "Error: " << (response.isEmpty() ? "No response from LibrePods" : response) << "\n";
            return 1;
        }
    }

    // Handle set conversational awareness
    if (!caState.isEmpty()) {
        QString lower = caState.toLower();
        bool enabled;
        if (lower == "on" || lower == "true" || lower == "1" || lower == "yes") {
            enabled = true;
        } else if (lower == "off" || lower == "false" || lower == "0" || lower == "no") {
            enabled = false;
        } else {
            err << "Error: Invalid state '" << caState << "'\n";
            err << "Valid values: on/off, true/false, 1/0, yes/no\n";
            return 1;
        }

        QString response = sendIpcCommand("cli:set-ca:" + QString(enabled ? "1" : "0"));
        if (response.startsWith("OK")) {
            out << "Conversational awareness set to: " << (enabled ? "on" : "off") << "\n";
            return 0;
        } else {
            err << "Error: " << (response.isEmpty() ? "No response from LibrePods" : response) << "\n";
            return 1;
        }
    }

    // Handle set adaptive level
    if (!adaptiveLevel.isEmpty()) {
        bool ok;
        int level = adaptiveLevel.toInt(&ok);
        if (!ok || level < 0 || level > 100) {
            err << "Error: Invalid adaptive level '" << adaptiveLevel << "'\n";
            err << "Valid range: 0-100\n";
            return 1;
        }

        QString response = sendIpcCommand("cli:set-adaptive-level:" + QString::number(level));
        if (response.startsWith("OK")) {
            out << "Adaptive noise level set to: " << level << "\n";
            return 0;
        } else {
            err << "Error: " << (response.isEmpty() ? "No response from LibrePods" : response) << "\n";
            return 1;
        }
    }

    return 0;
}

} // namespace CLI
