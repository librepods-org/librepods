#include <QSettings>
#include <QLocalServer>
#include <QLocalSocket>
#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QBluetoothLocalDevice>
#include <QBluetoothSocket>
#include <QQuickWindow>
#include <QLoggingCategory>
#include <QThread>
#include <QTimer>
#include <QProcess>
#include <QRegularExpression>
#include <QTranslator>
#include <QLibraryInfo>
#include <QDir>
#include <QFileInfo>
#include <QStandardPaths>

#include "airpods_packets.h"
#include "logger.h"
#include "media/mediacontroller.h"
#include "trayiconmanager.h"
#include "enums.h"
#include "battery.hpp"
#include "BluetoothMonitor.h"
#include "autostartmanager.hpp"
#include "deviceinfo.hpp"
#include "ble/blemanager.h"
#include "ble/bleutils.h"
#include "QRCodeImageProvider.hpp"
#include "systemsleepmonitor.hpp"

#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <bluetooth/bluetooth.h>
#include <bluetooth/l2cap.h>

using namespace AirpodsTrayApp::Enums;

Q_LOGGING_CATEGORY(librepods, "librepods")

// Raw L2CAP socket wrapper that connects directly by PSM, bypassing BlueZ DBus SDP lookup
class L2CAPSocket : public QObject {
    Q_OBJECT
public:
    explicit L2CAPSocket(QObject *parent = nullptr) : QObject(parent) {}
    ~L2CAPSocket() { doClose(); }

    bool isOpen() const { return m_fd >= 0; }
    QBluetoothAddress peerAddress() const { return m_peerAddress; }
    QString errorString() const { return m_errorString; }

    void connectToService(const QBluetoothAddress &address, quint16 psm) {
        doClose();
        m_peerAddress = address;

        m_fd = ::socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
        if (m_fd < 0) {
            m_errorString = QString::fromUtf8(strerror(errno));
            QTimer::singleShot(0, this, [this]() { emit errorOccurred(); });
            return;
        }

        int flags = fcntl(m_fd, F_GETFL, 0);
        fcntl(m_fd, F_SETFL, flags | O_NONBLOCK);

        struct sockaddr_l2 addr = {};
        addr.l2_family = AF_BLUETOOTH;
        addr.l2_psm = htobs(psm);

        const QString macStr = address.toString();
        const QStringList parts = macStr.split(QLatin1Char(':'));
        if (parts.size() == 6) {
            for (int i = 0; i < 6; i++)
                addr.l2_bdaddr.b[i] = static_cast<uint8_t>(parts[5 - i].toUInt(nullptr, 16));
        }

        const int ret = ::connect(m_fd, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr));
        if (ret == 0) {
            setupReadNotifier();
            emit connected();
        } else if (errno == EINPROGRESS) {
            m_writeNotifier = new QSocketNotifier(m_fd, QSocketNotifier::Write, this);
            connect(m_writeNotifier, &QSocketNotifier::activated, this, &L2CAPSocket::onWriteReady);
        } else {
            m_errorString = QString::fromUtf8(strerror(errno));
            doClose();
            QTimer::singleShot(0, this, [this]() { emit errorOccurred(); });
        }
    }

    qint64 write(const QByteArray &data) {
        if (m_fd < 0) return -1;
        return static_cast<qint64>(::write(m_fd, data.constData(), static_cast<size_t>(data.size())));
    }

    QByteArray readAll() {
        if (m_fd < 0) return {};
        QByteArray result;
        char buf[4096];
        ssize_t n;
        while ((n = ::recv(m_fd, buf, sizeof(buf), MSG_DONTWAIT)) > 0)
            result.append(buf, static_cast<int>(n));
        return result;
    }

    void close() { doClose(); }

signals:
    void connected();
    void disconnected();
    void readyRead();
    void errorOccurred();

private slots:
    void onWriteReady() {
        delete m_writeNotifier;
        m_writeNotifier = nullptr;

        int err = 0;
        socklen_t errLen = sizeof(err);
        getsockopt(m_fd, SOL_SOCKET, SO_ERROR, &err, &errLen);

        if (err != 0) {
            m_errorString = QString::fromUtf8(strerror(err));
            doClose();
            emit errorOccurred();
            return;
        }

        setupReadNotifier();
        emit connected();
    }

    void onReadReady() {
        char buf[1];
        const ssize_t n = ::recv(m_fd, buf, 1, MSG_PEEK | MSG_DONTWAIT);
        if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
            doClose();
            emit disconnected();
            return;
        }
        emit readyRead();
    }

private:
    void setupReadNotifier() {
        m_readNotifier = new QSocketNotifier(m_fd, QSocketNotifier::Read, this);
        connect(m_readNotifier, &QSocketNotifier::activated, this, &L2CAPSocket::onReadReady);
    }

    void doClose() {
        delete m_readNotifier;
        m_readNotifier = nullptr;
        delete m_writeNotifier;
        m_writeNotifier = nullptr;
        if (m_fd >= 0) { ::close(m_fd); m_fd = -1; }
    }

    int m_fd = -1;
    QSocketNotifier *m_readNotifier = nullptr;
    QSocketNotifier *m_writeNotifier = nullptr;
    QBluetoothAddress m_peerAddress;
    QString m_errorString;
};

class AirPodsTrayApp : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool airpodsConnected READ isAirpodsAvailable NOTIFY airPodsStatusChanged)
    Q_PROPERTY(bool airpodsCommandReady READ airpodsCommandReady NOTIFY airPodsCommandReadyChanged)
    Q_PROPERTY(int earDetectionBehavior READ earDetectionBehavior WRITE setEarDetectionBehavior NOTIFY earDetectionBehaviorChanged)
    Q_PROPERTY(bool crossDeviceEnabled READ crossDeviceEnabled WRITE setCrossDeviceEnabled NOTIFY crossDeviceEnabledChanged)
    Q_PROPERTY(AutoStartManager *autoStartManager READ autoStartManager CONSTANT)
    Q_PROPERTY(bool notificationsEnabled READ notificationsEnabled WRITE setNotificationsEnabled NOTIFY notificationsEnabledChanged)
    Q_PROPERTY(int retryAttempts READ retryAttempts WRITE setRetryAttempts NOTIFY retryAttemptsChanged)
    Q_PROPERTY(bool hideOnStart READ hideOnStart CONSTANT)
    Q_PROPERTY(DeviceInfo *deviceInfo READ deviceInfo CONSTANT)
    Q_PROPERTY(QString phoneMacStatus READ phoneMacStatus NOTIFY phoneMacStatusChanged)
    Q_PROPERTY(bool phoneConnected READ isPhoneConnected NOTIFY phoneConnectionChanged)
    Q_PROPERTY(bool hearingAidEnabled READ hearingAidEnabled WRITE setHearingAidEnabled NOTIFY hearingAidEnabledChanged)
    Q_PROPERTY(QString hearingAidSetupStatus READ hearingAidSetupStatus NOTIFY hearingAidSetupStatusChanged)
    Q_PROPERTY(QString headTrackingStatus READ headTrackingStatus NOTIFY headTrackingStatusChanged)

public:
    AirPodsTrayApp(bool debugMode, bool hideOnStart, QQmlApplicationEngine *parent = nullptr)
        : QObject(parent), debugMode(debugMode), m_settings(new QSettings("AirPodsTrayApp", "AirPodsTrayApp"))
        , m_autoStartManager(new AutoStartManager(this)), m_hideOnStart(hideOnStart), parent(parent)
        , m_deviceInfo(new DeviceInfo(this)), m_bleManager(new BleManager(this))
        , m_systemSleepMonitor(new SystemSleepMonitor(this))
    {
        QLoggingCategory::setFilterRules(QString("librepods.debug=%1").arg(debugMode ? "true" : "false"));
        LOG_INFO("Initializing LibrePods");

        // Initialize tray icon and connect signals
        trayManager = new TrayIconManager(this);
        trayManager->setNotificationsEnabled(loadNotificationsEnabled());
        connect(trayManager, &TrayIconManager::trayClicked, this, &AirPodsTrayApp::onTrayIconActivated);
        connect(trayManager, &TrayIconManager::openApp, this, &AirPodsTrayApp::onOpenApp);
        connect(trayManager, &TrayIconManager::openSettings, this, &AirPodsTrayApp::onOpenSettings);
        connect(trayManager, &TrayIconManager::noiseControlChanged, this, &AirPodsTrayApp::setNoiseControlMode);
        connect(trayManager, &TrayIconManager::conversationalAwarenessToggled, this, &AirPodsTrayApp::setConversationalAwareness);
        connect(m_deviceInfo, &DeviceInfo::batteryStatusChanged, trayManager, &TrayIconManager::updateBatteryStatus);
        connect(m_deviceInfo, &DeviceInfo::bluetoothAddressChanged, this, [this]() { updateHearingAidSetupStatus(); });
        connect(m_deviceInfo, &DeviceInfo::bluetoothAddressChanged, this, [this]() { updateHeadTrackingStatus(); });
        connect(m_deviceInfo, &DeviceInfo::noiseControlModeChanged, trayManager, &TrayIconManager::updateNoiseControlState);
        connect(m_deviceInfo, &DeviceInfo::conversationalAwarenessChanged, trayManager, &TrayIconManager::updateConversationalAwareness);
        connect(this, &AirPodsTrayApp::airPodsCommandReadyChanged, this, [this]() {
            trayManager->setAirPodsControlsEnabled(airpodsCommandReady());
        });
        connect(trayManager, &TrayIconManager::notificationsEnabledChanged, this, &AirPodsTrayApp::saveNotificationsEnabled);
        connect(trayManager, &TrayIconManager::notificationsEnabledChanged, this, &AirPodsTrayApp::notificationsEnabledChanged);
        connect(this, &AirPodsTrayApp::airPodsStatusChanged, this, [this]() { updateHearingAidSetupStatus(); });
        connect(this, &AirPodsTrayApp::airPodsStatusChanged, this, [this]() { updateHeadTrackingStatus(); });

        // Initialize MediaController and connect signals
        mediaController = new MediaController(this);
        connect(mediaController, &MediaController::mediaStateChanged, this, &AirPodsTrayApp::handleMediaStateChange);
        mediaController->followMediaChanges();

        monitor = new BluetoothMonitor(this);
        connect(monitor, &BluetoothMonitor::deviceConnected, this, &AirPodsTrayApp::bluezDeviceConnected);
        connect(monitor, &BluetoothMonitor::deviceDisconnected, this, &AirPodsTrayApp::bluezDeviceDisconnected);

        connect(m_bleManager, &BleManager::deviceFound, this, &AirPodsTrayApp::bleDeviceFound);
        connect(m_deviceInfo->getBattery(), &Battery::primaryChanged, this, &AirPodsTrayApp::primaryChanged);
        connect(m_systemSleepMonitor, &SystemSleepMonitor::systemGoingToSleep, this, &AirPodsTrayApp::onSystemGoingToSleep);
        connect(m_systemSleepMonitor, &SystemSleepMonitor::systemWakingUp, this, &AirPodsTrayApp::onSystemWakingUp);

        // Load settings
        CrossDevice.isEnabled = loadCrossDeviceEnabled();
        setEarDetectionBehavior(loadEarDetectionSettings());
        setRetryAttempts(loadRetryAttempts());

        monitor->checkAlreadyConnectedDevices();
        LOG_INFO("AirPodsTrayApp initialized");

        QBluetoothLocalDevice localDevice;

        const QList<QBluetoothAddress> connectedDevices = localDevice.connectedDevices();
        for (const QBluetoothAddress &address : connectedDevices) {
            QBluetoothDeviceInfo device(address, "", 0);
            if (isAirPodsDevice(device)) {
                connectToDevice(device);

                // On startup after reboot, activate A2DP profile for already connected AirPods
                QTimer::singleShot(2000, this, [this, address]()
                {
                    QString formattedAddress = address.toString().replace(":", "_");
                    mediaController->setConnectedDeviceMacAddress(formattedAddress);
                    mediaController->activateA2dpProfile();
                    LOG_INFO("A2DP profile activation attempted for AirPods found on startup");
                    emit airPodsStatusChanged();
                });
                return;
            }
        }

        initializeDBus();
        initializeBluetooth();
        updateHearingAidSetupStatus();
        updateHeadTrackingStatus();
        trayManager->setAirPodsControlsEnabled(airpodsCommandReady());
    }

    ~AirPodsTrayApp() {
        saveCrossDeviceEnabled();
        saveEarDetectionSettings();

        delete socket;
        delete phoneSocket;
    }

    bool areAirpodsConnected() const { return socket && socket->isOpen(); }
    bool isAirpodsAudioConnected() const
    {
        return mediaController &&
               !m_deviceInfo->bluetoothAddress().isEmpty() &&
               mediaController->isActiveOutputDeviceAirPods();
    }
    bool isAirpodsAvailable() const { return areAirpodsConnected() || isAirpodsAudioConnected(); }
    bool airpodsCommandReady() const { return areAirpodsConnected() && m_airPodsCommandReady; }
    int earDetectionBehavior() const { return mediaController->getEarDetectionBehavior(); }
    bool crossDeviceEnabled() const { return CrossDevice.isEnabled; }
    AutoStartManager *autoStartManager() const { return m_autoStartManager; }
    bool notificationsEnabled() const { return trayManager->notificationsEnabled(); }
    void setNotificationsEnabled(bool enabled) { trayManager->setNotificationsEnabled(enabled); }
    int retryAttempts() const { return m_retryAttempts; }
    bool hideOnStart() const { return m_hideOnStart; }
    DeviceInfo *deviceInfo() const { return m_deviceInfo; }
    QString phoneMacStatus() const { return m_phoneMacStatus; }
    bool isPhoneConnected() const { return phoneSocket && phoneSocket->isOpen(); }
    bool hearingAidEnabled() const { return m_deviceInfo->hearingAidEnabled(); }
    QString hearingAidSetupStatus() const { return m_hearingAidSetupStatus; }
    QString headTrackingStatus() const { return m_headTrackingStatus; }

private:
    struct TerminalLauncher
    {
        QString executable;
        QStringList argumentsPrefix;
    };

    QString findHeadTrackingScript() const
    {
        const QString appDir = QCoreApplication::applicationDirPath();
        const QStringList candidates = {
            QDir(appDir).filePath(QStringLiteral("head-tracking/gestures.py")),
            QDir(appDir).filePath(QStringLiteral("../head-tracking/gestures.py")),
            QDir(appDir).filePath(QStringLiteral("../share/librepods/head-tracking/gestures.py")),
            QStringLiteral("/usr/share/librepods/head-tracking/gestures.py"),
            QStringLiteral("/usr/local/share/librepods/head-tracking/gestures.py")
        };

        for (const QString &candidate : candidates)
        {
            const QString normalizedPath = QDir::cleanPath(candidate);
            if (QFileInfo::exists(normalizedPath))
            {
                return normalizedPath;
            }
        }

        return QString();
    }

    QString findHearingAidAdjustmentsScript() const
    {
        const QString appDir = QCoreApplication::applicationDirPath();
        const QStringList candidates = {
            QDir(appDir).filePath(QStringLiteral("hearing-aid-adjustments.py")),
            QDir(appDir).filePath(QStringLiteral("../hearing-aid-adjustments.py")),
            QDir(appDir).filePath(QStringLiteral("../share/librepods/hearing-aid-adjustments.py")),
            QStringLiteral("/usr/share/librepods/hearing-aid-adjustments.py"),
            QStringLiteral("/usr/local/share/librepods/hearing-aid-adjustments.py")
        };

        for (const QString &candidate : candidates)
        {
            const QString normalizedPath = QDir::cleanPath(candidate);
            if (QFileInfo::exists(normalizedPath))
            {
                return normalizedPath;
            }
        }

        return QString();
    }

    QString normalizedPhoneMac() const
    {
        const QString rawMac = QString::fromUtf8(qgetenv("PHONE_MAC_ADDRESS")).trimmed();
        if (rawMac.isEmpty())
        {
            return QString();
        }

        QString cleanedMac = rawMac;
        cleanedMac.remove(QRegularExpression(QStringLiteral("[^0-9A-Fa-f]")));
        if (cleanedMac.size() != 12)
        {
            return QString();
        }

        QStringList octets;
        octets.reserve(6);
        for (int index = 0; index < cleanedMac.size(); index += 2)
        {
            octets << cleanedMac.mid(index, 2).toUpper();
        }

        const QString normalizedMac = octets.join(QLatin1Char(':'));
        const QBluetoothAddress address(normalizedMac);
        if (address.isNull() || normalizedMac == QStringLiteral("00:00:00:00:00:00"))
        {
            return QString();
        }

        return normalizedMac;
    }

    void setAirPodsCommandReady(bool ready)
    {
        if (m_airPodsCommandReady == ready)
        {
            return;
        }

        m_airPodsCommandReady = ready;
        emit airPodsCommandReadyChanged();
    }

    QString findPythonInterpreter() const
    {
        const QStringList interpreters = {QStringLiteral("python3"), QStringLiteral("python")};
        for (const QString &interpreter : interpreters)
        {
            const QString executable = QStandardPaths::findExecutable(interpreter);
            if (!executable.isEmpty())
            {
                return executable;
            }
        }

        return QString();
    }

    QList<TerminalLauncher> availableTerminalLaunchers() const
    {
        return {
            {QStringLiteral("x-terminal-emulator"), {QStringLiteral("-e")}},
            {QStringLiteral("kgx"), {QStringLiteral("--")}},
            {QStringLiteral("gnome-terminal"), {QStringLiteral("--")}},
            {QStringLiteral("konsole"), {QStringLiteral("-e")}},
            {QStringLiteral("xfce4-terminal"), {QStringLiteral("-e")}},
            {QStringLiteral("mate-terminal"), {QStringLiteral("-e")}},
            {QStringLiteral("kitty"), {QStringLiteral("-e")}},
            {QStringLiteral("alacritty"), {QStringLiteral("-e")}},
            {QStringLiteral("wezterm"), {QStringLiteral("start"), QStringLiteral("--always-new-process"), QStringLiteral("--")}},
            {QStringLiteral("xterm"), {QStringLiteral("-e")}}
        };
    }

    void setHeadTrackingStatus(const QString &status)
    {
        if (m_headTrackingStatus != status)
        {
            m_headTrackingStatus = status;
            emit headTrackingStatusChanged();
        }
    }

    void setHearingAidSetupStatus(const QString &status)
    {
        if (m_hearingAidSetupStatus != status)
        {
            m_hearingAidSetupStatus = status;
            emit hearingAidSetupStatusChanged();
        }
    }

    void updateHearingAidSetupStatus()
    {
        if (findHearingAidAdjustmentsScript().isEmpty())
        {
            setHearingAidSetupStatus(QStringLiteral("Advanced adjustments script not found"));
            return;
        }

        if (!areAirpodsConnected() || m_deviceInfo->bluetoothAddress().isEmpty())
        {
            setHearingAidSetupStatus(QStringLiteral("Connect your AirPods to open advanced adjustments"));
            return;
        }

        setHearingAidSetupStatus(QStringLiteral("Ready to adjust hearing aid/transparency for %1").arg(m_deviceInfo->bluetoothAddress()));
    }

    void updateHeadTrackingStatus()
    {
        if (findHeadTrackingScript().isEmpty())
        {
            setHeadTrackingStatus(QStringLiteral("Head tracking scripts not found"));
            return;
        }

        if (!areAirpodsConnected() || m_deviceInfo->bluetoothAddress().isEmpty())
        {
            setHeadTrackingStatus(QStringLiteral("Connect your AirPods to test head gestures"));
            return;
        }

        setHeadTrackingStatus(QStringLiteral("Ready to open head gesture detector for %1").arg(m_deviceInfo->bluetoothAddress()));
    }

    void updatePhoneMacStatusFromConfiguration()
    {
        const QString configuredMac = QString::fromUtf8(qgetenv("PHONE_MAC_ADDRESS")).trimmed();
        const QString validMac = normalizedPhoneMac();

        if (!CrossDevice.isEnabled)
        {
            if (validMac.isEmpty())
            {
                updatePhoneMacStatus(configuredMac.isEmpty()
                                         ? QStringLiteral("Cross-device disabled. Set a phone MAC to enable it.")
                                         : QStringLiteral("Cross-device disabled. Fix the phone MAC before enabling it."));
                return;
            }

            updatePhoneMacStatus(QStringLiteral("Cross-device disabled. Ready to connect to %1 when enabled.").arg(validMac));
            return;
        }

        if (validMac.isEmpty())
        {
            updatePhoneMacStatus(configuredMac.isEmpty()
                                     ? QStringLiteral("Cross-device needs a valid phone MAC before it can connect.")
                                     : QStringLiteral("Cross-device skipped: invalid phone MAC `%1`.").arg(configuredMac));
            return;
        }

        updatePhoneMacStatus(QStringLiteral("Cross-device configured for %1").arg(validMac));
    }

    bool startInTerminal(const QString &program, const QStringList &arguments, const QString &workingDirectory) const
    {
        for (const TerminalLauncher &launcher : availableTerminalLaunchers())
        {
            const QString terminalPath = QStandardPaths::findExecutable(launcher.executable);
            if (terminalPath.isEmpty())
            {
                continue;
            }

            QStringList terminalArguments = launcher.argumentsPrefix;
            terminalArguments << program;
            terminalArguments << arguments;
            if (QProcess::startDetached(terminalPath, terminalArguments, workingDirectory))
            {
                return true;
            }
        }

        return false;
    }

    bool debugMode;
    bool isConnectedLocally = false;

    QQmlApplicationEngine *parent = nullptr;

    struct {
        bool isAvailable = true;
        bool isEnabled = true; // Ability to disable the feature
    } CrossDevice;

    void initializeDBus() { }

    bool isAirPodsDevice(const QBluetoothDeviceInfo &device)
    {
        return device.serviceUuids().contains(QBluetoothUuid("74ec2172-0bad-4d01-8f77-997b2be0722a"));
    }

    void notifyAndroidDevice()
    {
        if (!CrossDevice.isEnabled) {
            return;
        }

        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Phone::NOTIFICATION);
            LOG_DEBUG("Sent notification packet to Android: " << AirPodsPackets::Phone::NOTIFICATION.toHex());
        }
        else
        {
            LOG_WARN("Phone socket is not open, cannot send notification packet");
        }
    }

    void disconnectDevice(const QString &devicePath) {
        LOG_INFO("Disconnecting device at " << devicePath);
    }

public slots:
    void connectToDevice(const QString &address) {
        LOG_INFO("Connecting to device with address: " << address);
        QBluetoothAddress btAddress(address);
        QBluetoothDeviceInfo device(btAddress, "", 0);
        connectToDevice(device);
    }

    void setNoiseControlMode(NoiseControlMode mode)
    {
        if (m_deviceInfo->noiseControlMode() == mode)
        {
            LOG_INFO("Noise control mode is already set to: " << static_cast<int>(mode));
            return;
        }
        LOG_INFO("Setting noise control mode to: " << mode);
        QByteArray packet = AirPodsPackets::NoiseControl::getPacketForMode(mode);
        writePacketToSocket(packet, "Noise control mode packet written: ");
    }
    void setNoiseControlModeInt(int mode)
    {
        if (mode < 0 || mode > static_cast<int>(NoiseControlMode::Adaptive))
        {
            LOG_ERROR("Invalid noise control mode: " << mode);
            return;
        }
        setNoiseControlMode(static_cast<NoiseControlMode>(mode));
    }

    void setConversationalAwareness(bool enabled)
    {
        if (m_deviceInfo->conversationalAwareness() == enabled)
        {
            LOG_INFO("Conversational awareness is already " << (enabled ? "enabled" : "disabled"));
            return;
        }

        LOG_INFO("Setting conversational awareness to: " << (enabled ? "enabled" : "disabled"));
        QByteArray packet = enabled ? AirPodsPackets::ConversationalAwareness::ENABLED
                                    : AirPodsPackets::ConversationalAwareness::DISABLED;

        if (writePacketToSocket(packet, "Conversational awareness packet written: "))
        {
            m_deviceInfo->setConversationalAwareness(enabled);
        }
    }

    void setOneBudANCMode(bool enabled)
    {
        if (m_deviceInfo->oneBudANCMode() == enabled)
        {
            LOG_INFO("One Bud ANC mode is already " << (enabled ? "enabled" : "disabled"));
            return;
        }

        LOG_INFO("Setting One Bud ANC mode to: " << (enabled ? "enabled" : "disabled"));
        QByteArray packet = enabled ? AirPodsPackets::OneBudANCMode::ENABLED
                                    : AirPodsPackets::OneBudANCMode::DISABLED;

        if (writePacketToSocket(packet, "One Bud ANC mode packet written: "))
        {
            m_deviceInfo->setOneBudANCMode(enabled);
        }
        else
        {
            LOG_ERROR("Failed to send One Bud ANC mode command: socket not open");
        }
    }

    void setAllowOffOption(bool enabled)
    {
        QByteArray packet = enabled ? AirPodsPackets::AllowOffOption::ENABLED
                                    : AirPodsPackets::AllowOffOption::DISABLED;
        if (writePacketToSocket(packet, "Allow Off Option packet written: "))
            m_deviceInfo->setAllowOffOption(enabled);
    }

    void setVolumeSwipeEnabled(bool enabled)
    {
        QByteArray packet = enabled ? AirPodsPackets::VolumeSwipe::ENABLED
                                    : AirPodsPackets::VolumeSwipe::DISABLED;
        if (writePacketToSocket(packet, "Volume Swipe packet written: "))
            m_deviceInfo->setVolumeSwipeEnabled(enabled);
    }

    void setVolumeSwipeInterval(int interval)
    {
        interval = qBound(0, interval, 100);
        QByteArray packet = AirPodsPackets::VolumeSwipe::getIntervalPacket(static_cast<quint8>(interval));
        if (writePacketToSocket(packet, "Volume Swipe interval packet written: "))
            m_deviceInfo->setVolumeSwipeInterval(interval);
    }

    void setAdaptiveVolumeEnabled(bool enabled)
    {
        QByteArray packet = enabled ? AirPodsPackets::AdaptiveVolume::ENABLED
                                    : AirPodsPackets::AdaptiveVolume::DISABLED;
        if (writePacketToSocket(packet, "Adaptive Volume packet written: "))
            m_deviceInfo->setAdaptiveVolumeEnabled(enabled);
    }

    void setCaseChargingSoundsEnabled(bool enabled)
    {
        QByteArray packet = AirPodsPackets::CaseChargingSounds::getPacket(enabled);
        if (writePacketToSocket(packet, "Case Charging Sounds packet written: "))
            m_deviceInfo->setCaseChargingSoundsEnabled(enabled);
    }

    void setStemLongPressModes(int modes)
    {
        // Clamp to valid range; require at least 2 bits set
        quint8 m = static_cast<quint8>(modes & 0x0F);
        if (__builtin_popcount(m) < 2) return;
        QByteArray packet = AirPodsPackets::StemLongPress::getPacket(m);
        if (writePacketToSocket(packet, "Stem Long Press packet written: ")) {
            m_deviceInfo->setStemLongPressModes(m);
            m_settings->setValue("stemLongPressModes", m);
        }
    }

    void setCustomizeTransparencyEnabled(bool enabled)
    {
        m_deviceInfo->setCustomizeTransparencyEnabled(enabled);
        sendCustomizeTransparency();
    }

    // Called from QML with per-bud float arrays
    Q_INVOKABLE void applyCustomizeTransparency(
        bool enabled,
        QList<qreal> leftEq, qreal leftAmp, qreal leftTone, bool leftConv, qreal leftAnr,
        QList<qreal> rightEq, qreal rightAmp, qreal rightTone, bool rightConv, qreal rightAnr)
    {
        using namespace AirPodsPackets::CustomizeTransparency;
        BudSettings left, right;
        for (int i = 0; i < 8 && i < leftEq.size(); i++)  left.eq[i]  = static_cast<float>(leftEq[i]);
        for (int i = 0; i < 8 && i < rightEq.size(); i++) right.eq[i] = static_cast<float>(rightEq[i]);
        left.amplification     = static_cast<float>(leftAmp);
        left.tone              = static_cast<float>(leftTone);
        left.conversationBoost = leftConv ? 1.0f : 0.0f;
        left.ambientNoise      = static_cast<float>(leftAnr);
        right.amplification     = static_cast<float>(rightAmp);
        right.tone              = static_cast<float>(rightTone);
        right.conversationBoost = rightConv ? 1.0f : 0.0f;
        right.ambientNoise      = static_cast<float>(rightAnr);

        m_transpLeft  = left;
        m_transpRight = right;
        m_deviceInfo->setCustomizeTransparencyEnabled(enabled);
        sendCustomizeTransparency();
    }

    Q_INVOKABLE void applyHeadphoneAccommodation(bool phoneEnabled, bool mediaEnabled, QList<int> eq8)
    {
        m_deviceInfo->setHeadphoneAccomPhoneEnabled(phoneEnabled);
        m_deviceInfo->setHeadphoneAccomMediaEnabled(mediaEnabled);
        m_headphoneEq = eq8;
        QByteArray packet = AirPodsPackets::HeadphoneAccommodation::getPacket(phoneEnabled, mediaEnabled, eq8);
        writePacketToSocket(packet, "Headphone Accommodation packet written: ");
    }

    void setRetryAttempts(int attempts)
    {
        if (m_retryAttempts != attempts)
        {
            LOG_DEBUG("Setting retry attempts to: " << attempts);
            m_retryAttempts = attempts;
            emit retryAttemptsChanged(attempts);
            saveRetryAttempts(attempts);
        }
    }

    void initiateMagicPairing()
    {
        if (!socket || !socket->isOpen())
        {
            LOG_ERROR("Socket nicht offen, Magic Pairing kann nicht gestartet werden");
            return;
        }

        writePacketToSocket(AirPodsPackets::MagicPairing::REQUEST_MAGIC_CLOUD_KEYS, "Magic Pairing packet written: ");
    }

    void setAdaptiveNoiseLevel(int level)
    {
        level = qBound(0, level, 100);
        if (m_deviceInfo->adaptiveNoiseLevel() != level && m_deviceInfo->adaptiveModeActive())
        {
            QByteArray packet = AirPodsPackets::AdaptiveNoise::getPacket(level);
            writePacketToSocket(packet, "Adaptive noise level packet written: ");
            m_deviceInfo->setAdaptiveNoiseLevel(level);
        }
    }

    void renameAirPods(const QString &newName)
    {
        if (newName.isEmpty())
        {
            LOG_WARN("Cannot set empty name");
            return;
        }
        if (newName.size() > 32)
        {
            LOG_WARN("Name is too long, must be 32 characters or less");
            return;
        }
        if (newName == m_deviceInfo->deviceName())
        {
            LOG_INFO("Name is already set to: " << newName);
            return;
        }

        QByteArray packet = AirPodsPackets::Rename::getPacket(newName);
        if (writePacketToSocket(packet, "Rename packet written: "))
        {
            LOG_INFO("Sent rename command for new name: " << newName);
            m_deviceInfo->setDeviceName(newName);
        }
        else
        {
            LOG_ERROR("Failed to send rename command: socket not open");
        }
    }

    void setEarDetectionBehavior(int behavior)
    {
        if (behavior == earDetectionBehavior())
        {
            LOG_INFO("Ear detection behavior is already set to: " << behavior);
            return;
        }

        mediaController->setEarDetectionBehavior(static_cast<MediaController::EarDetectionBehavior>(behavior));
        saveEarDetectionSettings();
        emit earDetectionBehaviorChanged(behavior);
    }

    void setCrossDeviceEnabled(bool enabled)
    {
        if (CrossDevice.isEnabled == enabled)
        {
            LOG_INFO("Cross-device feature is already " << (enabled ? "enabled" : "disabled"));
            return;
        }

        if (enabled && normalizedPhoneMac().isEmpty())
        {
            LOG_WARN("Cross-device not enabled because no valid phone MAC is configured");
            updatePhoneMacStatusFromConfiguration();
            return;
        }

        CrossDevice.isEnabled = enabled;
        saveCrossDeviceEnabled();
        updatePhoneMacStatusFromConfiguration();

        if (!enabled && phoneSocket)
        {
            phoneSocket->close();
            phoneSocket->deleteLater();
            phoneSocket = nullptr;
            updatePhoneConnectionState();
        }

        connectToPhone();
        emit crossDeviceEnabledChanged(enabled);
    }

    void setPhoneMac(const QString &mac)
    {
        const QString trimmedMac = mac.trimmed();
        if (trimmedMac.isEmpty()) {
            LOG_WARN("Empty MAC provided, ignoring");
            qputenv("PHONE_MAC_ADDRESS", QByteArray());
            if (parent) {
                parent->rootContext()->setContextProperty("PHONE_MAC_ADDRESS", QString());
            }

            if (phoneSocket) {
                phoneSocket->close();
                phoneSocket->deleteLater();
                phoneSocket = nullptr;
                updatePhoneConnectionState();
            }

            if (CrossDevice.isEnabled)
            {
                CrossDevice.isEnabled = false;
                saveCrossDeviceEnabled();
                emit crossDeviceEnabledChanged(false);
            }

            updatePhoneMacStatusFromConfiguration();
            return;
        }

        QString cleanedMac = trimmedMac;
        cleanedMac.remove(QRegularExpression(QStringLiteral("[^0-9A-Fa-f]")));
        if (cleanedMac.size() != 12) {
            LOG_ERROR("Invalid MAC address format: " << trimmedMac);
            updatePhoneMacStatus(QStringLiteral("Invalid MAC: %1").arg(trimmedMac));
            return;
        }

        QStringList octets;
        octets.reserve(6);
        for (int index = 0; index < cleanedMac.size(); index += 2)
        {
            octets << cleanedMac.mid(index, 2).toUpper();
        }
        const QString normalizedMac = octets.join(QLatin1Char(':'));
        if (QBluetoothAddress(normalizedMac).isNull() || normalizedMac == QStringLiteral("00:00:00:00:00:00"))
        {
            LOG_ERROR("Invalid MAC address value: " << trimmedMac);
            updatePhoneMacStatus(QStringLiteral("Invalid MAC: %1").arg(trimmedMac));
            return;
        }

        qputenv("PHONE_MAC_ADDRESS", normalizedMac.toUtf8());
        LOG_INFO("PHONE_MAC_ADDRESS environment variable set to: " << normalizedMac);

        // Update QML context property so UI placeholders reflect the new value
        if (parent) {
            parent->rootContext()->setContextProperty("PHONE_MAC_ADDRESS", normalizedMac);
        }

        updatePhoneMacStatusFromConfiguration();

        // If a phone socket exists, restart connection using the new MAC
        if (phoneSocket) {
            phoneSocket->close();
            phoneSocket->deleteLater();
            phoneSocket = nullptr;
            updatePhoneConnectionState();
        }

        if (CrossDevice.isEnabled)
        {
            connectToPhone();
        }
    }

    void updatePhoneMacStatus(const QString &status)
    {
        m_phoneMacStatus = status;
        emit phoneMacStatusChanged();
    }

    void updatePhoneConnectionState()
    {
        emit phoneConnectionChanged();
    }

    void openHearingAidAdjustments()
    {
        if (!areAirpodsConnected() || m_deviceInfo->bluetoothAddress().isEmpty())
        {
            setHearingAidSetupStatus(QStringLiteral("Connect your AirPods to open advanced adjustments"));
            return;
        }

        const QString scriptPath = findHearingAidAdjustmentsScript();
        if (scriptPath.isEmpty())
        {
            setHearingAidSetupStatus(QStringLiteral("Advanced adjustments script not found"));
            return;
        }

        const QFileInfo scriptInfo(scriptPath);
        const QStringList interpreters = {QStringLiteral("python3"), QStringLiteral("python")};
        for (const QString &interpreter : interpreters)
        {
            if (QProcess::startDetached(interpreter, QStringList() << scriptInfo.filePath() << m_deviceInfo->bluetoothAddress(), scriptInfo.absolutePath()))
            {
                setHearingAidSetupStatus(QStringLiteral("Opened advanced adjustments for %1").arg(m_deviceInfo->bluetoothAddress()));
                return;
            }
        }

        setHearingAidSetupStatus(QStringLiteral("Failed to launch advanced adjustments. Check Python and PyQt5."));
    }

    void openHeadTrackingGestures()
    {
        if (!areAirpodsConnected() || m_deviceInfo->bluetoothAddress().isEmpty())
        {
            setHeadTrackingStatus(QStringLiteral("Connect your AirPods to test head gestures"));
            return;
        }

        const QString scriptPath = findHeadTrackingScript();
        if (scriptPath.isEmpty())
        {
            setHeadTrackingStatus(QStringLiteral("Head tracking scripts not found"));
            return;
        }

        const QString interpreter = findPythonInterpreter();
        if (interpreter.isEmpty())
        {
            setHeadTrackingStatus(QStringLiteral("Python not found. Install python3 to run head gesture detection."));
            return;
        }

        const QFileInfo scriptInfo(scriptPath);
        const QStringList arguments = {scriptInfo.filePath(), m_deviceInfo->bluetoothAddress()};
        if (startInTerminal(interpreter, arguments, scriptInfo.absolutePath()))
        {
            setHeadTrackingStatus(QStringLiteral("Opened head gesture detector for %1").arg(m_deviceInfo->bluetoothAddress()));
            return;
        }

        setHeadTrackingStatus(QStringLiteral("No supported terminal emulator found. Run `%1 %2 %3` manually.")
                                  .arg(QFileInfo(interpreter).fileName(), scriptInfo.filePath(), m_deviceInfo->bluetoothAddress()));
    }

    void reconnectPhoneRelay()
    {
        if (!CrossDevice.isEnabled)
        {
            updatePhoneMacStatusFromConfiguration();
            return;
        }

        const QString validPhoneMac = normalizedPhoneMac();
        if (validPhoneMac.isEmpty())
        {
            updatePhoneMacStatusFromConfiguration();
            return;
        }

        if (phoneSocket)
        {
            phoneSocket->close();
            phoneSocket->deleteLater();
            phoneSocket = nullptr;
            updatePhoneConnectionState();
        }

        updatePhoneMacStatus(QStringLiteral("Retrying cross-device relay to %1...").arg(validPhoneMac));
        connectToPhone();
    }

    void setHearingAidEnabled(bool enabled)
    {
        if (m_deviceInfo->hearingAidEnabled() == enabled)
        {
            LOG_INFO("Hearing aid is already " << (enabled ? "enabled" : "disabled"));
            return;
        }

        LOG_INFO("Setting hearing aid to: " << (enabled ? "enabled" : "disabled"));
        QByteArray packet = enabled ? AirPodsPackets::HearingAid::ENABLED
                                    : AirPodsPackets::HearingAid::DISABLED;

        if (writePacketToSocket(packet, "Hearing aid packet written: "))
        {
            m_deviceInfo->setHearingAidEnabled(enabled);
        }
    }

    bool writePacketToSocket(const QByteArray &packet, const QString &logMessage, bool requireReady = true)
    {
        if (socket && socket->isOpen())
        {
            if (requireReady && !m_airPodsCommandReady)
            {
                LOG_WARN("AirPods socket connected but commands are not ready yet");
                return false;
            }

            const qint64 bytesWritten = socket->write(packet);
            if (bytesWritten != packet.size())
            {
                LOG_ERROR("Failed to queue full packet to socket. Expected " << packet.size() << " bytes, wrote " << bytesWritten);
                return false;
            }

            LOG_DEBUG(logMessage << packet.toHex());
            return true;
        }
        else
        {
            LOG_ERROR("Socket is not open, cannot write packet");
            return false;
        }
    }

    bool loadCrossDeviceEnabled() { return m_settings->value("crossdevice/enabled", false).toBool(); }
    void saveCrossDeviceEnabled() { m_settings->setValue("crossdevice/enabled", CrossDevice.isEnabled); }

    int loadEarDetectionSettings() { return m_settings->value("earDetection/setting", MediaController::EarDetectionBehavior::PauseWhenOneRemoved).toInt(); }
    void saveEarDetectionSettings() { m_settings->setValue("earDetection/setting", mediaController->getEarDetectionBehavior()); }

    bool loadNotificationsEnabled() const { return m_settings->value("notifications/enabled", true).toBool(); }
    void saveNotificationsEnabled(bool enabled) { m_settings->setValue("notifications/enabled", enabled); }

    int loadRetryAttempts() const { return m_settings->value("bluetooth/retryAttempts", 3).toInt(); }
    void saveRetryAttempts(int attempts) { m_settings->setValue("bluetooth/retryAttempts", attempts); }

    void onSystemGoingToSleep()
    {
        if (m_bleManager->isScanning())
        {
            LOG_INFO("Stopping BLE scan before going to sleep");
            m_bleManager->stopScan();
        }
    }
    void onSystemWakingUp()
    {
        LOG_INFO("System is waking up, starting ble scan");
        m_bleManager->startScan();

        // Check if AirPods are already connected and activate A2DP profile
        if (areAirpodsConnected() && m_deviceInfo && !m_deviceInfo->bluetoothAddress().isEmpty())
        {
            LOG_INFO("AirPods already connected after wake-up, re-activating A2DP profile");
            mediaController->setConnectedDeviceMacAddress(m_deviceInfo->bluetoothAddress().replace(":", "_"));

            // Always activate A2DP profile after system wake since the profile might have been lost
            QTimer::singleShot(1000, this, [this]()
            {
                mediaController->activateA2dpProfile();
                LOG_INFO("A2DP profile activation attempted after system wake-up");
            });
        }

        // Also check for already connected devices via BlueZ
        monitor->checkAlreadyConnectedDevices();
    }

private slots:
    void onTrayIconActivated()
    {
        QQuickWindow *window = qobject_cast<QQuickWindow *>(
            QGuiApplication::topLevelWindows().constFirst());
        if (window)
        {
            window->show();
            window->raise();
            window->requestActivate();
        }
    }

    void onOpenApp()
    {
        QObject *rootObject = parent->rootObjects().first();
        if (rootObject) {
            QMetaObject::invokeMethod(rootObject, "reopen", Q_ARG(QVariant, "app"));
        }
        else
        {
            loadMainModule();
        }
    }

    void onOpenSettings()
    {
        QObject *rootObject = parent->rootObjects().first();
        if (rootObject) {
            QMetaObject::invokeMethod(rootObject, "reopen", Q_ARG(QVariant, "settings"));
        }
        else
        {
            loadMainModule();
        }
    }

    void sendHandshake() {
        LOG_INFO("Connected to device, sending initial packets");
        writePacketToSocket(AirPodsPackets::Connection::HANDSHAKE, "Handshake packet written: ", false);
    }

    void bluezDeviceConnected(const QString &address, const QString &name)
    {
        QBluetoothDeviceInfo device(QBluetoothAddress(address), name, 0);
        connectToDevice(device);

        // After system reboot, AirPods might be connected but A2DP profile not active
        // Attempt to activate A2DP profile after a delay to ensure connection is established
        QTimer::singleShot(2000, this, [this, address]()
        {
            if (!address.isEmpty())
            {
                QString formattedAddress = address;
                formattedAddress = formattedAddress.replace(":", "_");
                mediaController->setConnectedDeviceMacAddress(formattedAddress);
                mediaController->activateA2dpProfile();
                LOG_INFO("A2DP profile activation attempted for newly connected device");
                emit airPodsStatusChanged();
            }
        });
    }

    void onDeviceDisconnected(const QBluetoothAddress &address)
    {
        LOG_INFO("Device disconnected: " << address.toString());
        setAirPodsCommandReady(false);
        if (socket)
        {
            LOG_WARN("Socket is still open, closing it");
            socket->close();
            socket = nullptr;
        }
        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Connection::AIRPODS_DISCONNECTED);
            LOG_DEBUG("AIRPODS_DISCONNECTED packet written: " << AirPodsPackets::Connection::AIRPODS_DISCONNECTED.toHex());
        }

        // Clear the device name and model
        m_deviceInfo->reset();
        m_bleManager->startScan();
        emit airPodsStatusChanged();

        // Show system notification
        trayManager->showNotification(
            tr("AirPods Disconnected"),
            tr("Your AirPods have been disconnected"));
        trayManager->resetTrayIcon();
    }

    void bluezDeviceDisconnected(const QString &address, const QString &name)
    {
        Q_UNUSED(name);
        if (address == m_deviceInfo->bluetoothAddress())
        {
            if (isAirpodsAudioConnected())
            {
                if (areAirpodsConnected())
                {
                    LOG_WARN("Ignoring BlueZ disconnect because AirPods are still the active audio output and the command channel is still connected: " << address);
                }
                else
                {
                    LOG_WARN("AirPods are still the active audio output, but the command channel is no longer connected. Keeping UI connected and disabling command controls: " << address);
                    setAirPodsCommandReady(false);
                }
                emit airPodsStatusChanged();
                return;
            }

            onDeviceDisconnected(QBluetoothAddress(address));
        } else {
            LOG_WARN("Disconnected device does not match connected device: " << address << " != " << m_deviceInfo->bluetoothAddress());
        }
    }

    void parseMetadata(const QByteArray &data)
    {
        // Verify the data starts with the METADATA header
        if (!data.startsWith(AirPodsPackets::Parse::METADATA))
        {
            LOG_ERROR("Invalid metadata packet: Incorrect header");
            return;
        }

        int pos = AirPodsPackets::Parse::METADATA.size(); // Start after the header

        // Check if there is enough data to skip the initial bytes (based on example structure)
        if (data.size() < pos + 6)
        {
            LOG_ERROR("Metadata packet too short to parse initial bytes");
            return;
        }
        pos += 6; // Skip 6 bytes after the header as per example structure

        auto extractString = [&data, &pos]() -> QString
        {
            if (pos >= data.size())
            {
                return QString();
            }
            int start = pos;
            while (pos < data.size() && data.at(pos) != '\0')
            {
                ++pos;
            }
            QString str = QString::fromUtf8(data.mid(start, pos - start));
            if (pos < data.size())
            {
                ++pos; // Move past the null terminator
            }
            return str;
        };

        m_deviceInfo->setDeviceName(extractString());
        m_deviceInfo->setModelNumber(extractString());
        m_deviceInfo->setManufacturer(extractString());

        m_deviceInfo->setModel(parseModelNumber(m_deviceInfo->modelNumber()));
        emit modelChanged();

        // Log extracted metadata
        LOG_INFO("Parsed AirPods metadata:");
        LOG_INFO("Device Name: " << m_deviceInfo->deviceName());
        LOG_INFO("Model Number: " << m_deviceInfo->modelNumber());
        LOG_INFO("Manufacturer: " << m_deviceInfo->manufacturer());
    }

    QString getEarStatus(char value)
    {
        return (value == 0x00) ? "In Ear" : (value == 0x01) ? "Out of Ear"
                                                            : "In case";
    }

    void connectToDevice(const QBluetoothDeviceInfo &device)
    {
        if (socket && socket->isOpen() && socket->peerAddress() == device.address())
        {
            LOG_INFO("Already connected to the device: " << device.name());
            return;
        }

        LOG_INFO("Connecting to device: " << device.name());
        setAirPodsCommandReady(false);

        if (socket)
        {
            socket->close();
            socket->deleteLater();
            socket = nullptr;
        }

        L2CAPSocket *localSocket = new L2CAPSocket(this);
        socket = localSocket;

        connect(localSocket, &L2CAPSocket::connected, this, [this, localSocket]()
        {
            connect(localSocket, &L2CAPSocket::readyRead, this, [this, localSocket]()
            {
                QByteArray data = localSocket->readAll();
                QMetaObject::invokeMethod(this, "parseData", Qt::QueuedConnection, Q_ARG(QByteArray, data));
                QMetaObject::invokeMethod(this, "relayPacketToPhone", Qt::QueuedConnection, Q_ARG(QByteArray, data));
            });
            emit airPodsStatusChanged();
            sendHandshake();
        });

        connect(localSocket, &L2CAPSocket::disconnected, this, [this]()
        {
            setAirPodsCommandReady(false);
            emit airPodsStatusChanged();
        });

        connect(localSocket, &L2CAPSocket::errorOccurred, this, [this, device, localSocket]()
        {
            setAirPodsCommandReady(false);
            LOG_ERROR("Socket error: " << localSocket->errorString());

            static int retryCount = 0;
            if (retryCount < m_retryAttempts)
            {
                retryCount++;
                LOG_INFO("Retrying connection (attempt " << retryCount << ")");
                QTimer::singleShot(1500, this, [this, device]()
                    { connectToDevice(device); });
            }
            else
            {
                LOG_ERROR("Failed to connect after " << m_retryAttempts << " attempts");
                retryCount = 0;
            }
        });

        m_deviceInfo->setBluetoothAddress(device.address().toString());
        localSocket->connectToService(device.address(), quint16(0x1001));
        notifyAndroidDevice();
    }

    void parseData(const QByteArray &data)
    {
        LOG_DEBUG("Received: " << data.toHex());

        if (!data.isEmpty())
        {
            setAirPodsCommandReady(true);
        }

        if (data.startsWith(AirPodsPackets::Parse::HANDSHAKE_ACK))
        {
            writePacketToSocket(AirPodsPackets::Connection::SET_SPECIFIC_FEATURES, "Set specific features packet written: ", false);
        }
        else if (data.startsWith(AirPodsPackets::Parse::FEATURES_ACK))
        {
            writePacketToSocket(AirPodsPackets::Connection::REQUEST_NOTIFICATIONS, "Request notifications packet written: ", false);

            QTimer::singleShot(2000, this, [this]() {
                if (m_deviceInfo->batteryStatus().isEmpty()) {
                    writePacketToSocket(AirPodsPackets::Connection::REQUEST_NOTIFICATIONS, "Request notifications packet written: ", false);
                }
                // Restore stem long press config on every connection (AirPods forget it)
                sendStemLongPressConfig();
            });
        }
        // Magic Cloud Keys Response
        else if (data.startsWith(AirPodsPackets::MagicPairing::MAGIC_CLOUD_KEYS_HEADER))
        {
            auto keys = AirPodsPackets::MagicPairing::parseMagicCloudKeysPacket(data);
            LOG_INFO("Received Magic Cloud Keys:");
            LOG_INFO("MagicAccIRK: " << keys.magicAccIRK.toHex());
            LOG_INFO("MagicAccEncKey: " << keys.magicAccEncKey.toHex());

            // Store the keys
            m_deviceInfo->setMagicAccIRK(keys.magicAccIRK);
            m_deviceInfo->setMagicAccEncKey(keys.magicAccEncKey);
            m_deviceInfo->saveToSettings(*m_settings);
        }
        // Get CA state
        else if (data.startsWith(AirPodsPackets::ConversationalAwareness::HEADER)) {
            if (auto result = AirPodsPackets::ConversationalAwareness::parseState(data))
            {
                m_deviceInfo->setConversationalAwareness(result.value());
                LOG_INFO("Conversational awareness state received: " << m_deviceInfo->conversationalAwareness());
            }
        }
        // Hearing Aid state
        else if (data.startsWith(AirPodsPackets::HearingAid::HEADER)) {
            if (auto result = AirPodsPackets::HearingAid::parseState(data))
            {
                m_deviceInfo->setHearingAidEnabled(result.value());
                LOG_INFO("Hearing aid state received: " << m_deviceInfo->hearingAidEnabled());
            }
        }
        // Noise Control Mode
        else if (data.size() == 11 && data.startsWith(AirPodsPackets::NoiseControl::HEADER))
        {
            if (auto value = AirPodsPackets::NoiseControl::parseMode(data))
            {
                m_deviceInfo->setNoiseControlMode(value.value());
                LOG_INFO("Noise control mode received: " << m_deviceInfo->noiseControlMode());
            }
        }
        // Ear Detection
        else if (data.size() == 8 && data.startsWith(AirPodsPackets::Parse::EAR_DETECTION))
        {
            m_deviceInfo->getEarDetection()->parseData(data);
            mediaController->handleEarDetection(m_deviceInfo->getEarDetection());
        }
        // Battery Status
        else if ((data.size() == 22 || data.size() == 12) && data.startsWith(AirPodsPackets::Parse::BATTERY_STATUS))
        {
            m_deviceInfo->getBattery()->parsePacket(data);
            m_deviceInfo->updateBatteryStatus();
            LOG_INFO("Battery status: " << m_deviceInfo->batteryStatus());
        }
        // Conversational Awareness Data
        else if (data.size() == 10 && data.startsWith(AirPodsPackets::ConversationalAwareness::DATA_HEADER))
        {
            LOG_INFO("Received conversational awareness data");
            mediaController->handleConversationalAwareness(data);
        }
        else if (data.startsWith(AirPodsPackets::Parse::METADATA))
        {
            parseMetadata(data);
            initiateMagicPairing();
            mediaController->setConnectedDeviceMacAddress(m_deviceInfo->bluetoothAddress().replace(":", "_"));
            if (m_deviceInfo->getEarDetection()->oneOrMorePodsInEar()) // AirPods get added as output device only after this
            {
                mediaController->activateA2dpProfile();
            }
            m_bleManager->stopScan();
            emit airPodsStatusChanged();
        }
        else if (data.startsWith(AirPodsPackets::OneBudANCMode::HEADER)) {
            if (auto value = AirPodsPackets::OneBudANCMode::parseState(data))
            {
                m_deviceInfo->setOneBudANCMode(value.value());
                LOG_INFO("One Bud ANC mode received: " << m_deviceInfo->oneBudANCMode());
            }
        }
        else if (data.startsWith(AirPodsPackets::AllowOffOption::HEADER)) {
            if (auto value = AirPodsPackets::AllowOffOption::parseState(data))
            {
                m_deviceInfo->setAllowOffOption(value.value());
                LOG_INFO("Allow Off Option received: " << value.value());
            }
        }
        else if (data.startsWith(AirPodsPackets::VolumeSwipe::HEADER)) {
            if (auto value = AirPodsPackets::VolumeSwipe::parseState(data))
            {
                m_deviceInfo->setVolumeSwipeEnabled(value.value());
                LOG_INFO("Volume Swipe received: " << value.value());
            }
        }
        else if (data.startsWith(AirPodsPackets::AdaptiveVolume::HEADER)) {
            if (auto value = AirPodsPackets::AdaptiveVolume::parseState(data))
            {
                m_deviceInfo->setAdaptiveVolumeEnabled(value.value());
                LOG_INFO("Adaptive Volume received: " << value.value());
            }
        }
        else if (data.startsWith(AirPodsPackets::StemLongPress::HEADER)) {
            if (auto modes = AirPodsPackets::StemLongPress::parseModes(data))
            {
                m_deviceInfo->setStemLongPressModes(modes.value());
                LOG_INFO("Stem Long Press modes received: " << modes.value());
            }
        }
        else
        {
            LOG_DEBUG("Unrecognized packet format: " << data.toHex());
        }
    }

    void connectToPhone() {
        if (!CrossDevice.isEnabled) {
            updatePhoneMacStatusFromConfiguration();
            return;
        }

        if (phoneSocket && phoneSocket->isOpen()) {
            LOG_INFO("Already connected to the phone");
            return;
        }

        const QString validPhoneMac = normalizedPhoneMac();
        if (validPhoneMac.isEmpty())
        {
            LOG_WARN("Skipping cross-device connection because no valid phone MAC is configured");
            updatePhoneMacStatusFromConfiguration();
            return;
        }

        if (phoneSocket)
        {
            phoneSocket->close();
            phoneSocket->deleteLater();
            phoneSocket = nullptr;
        }

        QBluetoothAddress phoneAddress(validPhoneMac);
        phoneSocket = new QBluetoothSocket(QBluetoothServiceInfo::L2capProtocol);
        connect(phoneSocket, &QBluetoothSocket::connected, this, [this]() {
            LOG_INFO("Connected to phone");
            updatePhoneMacStatus(QStringLiteral("Connected to phone for cross-device relay"));
            updatePhoneConnectionState();
            if (!lastBatteryStatus.isEmpty()) {
                phoneSocket->write(lastBatteryStatus);
                LOG_DEBUG("Sent last battery status to phone: " << lastBatteryStatus.toHex());
            }
            if (!lastEarDetectionStatus.isEmpty()) {
                phoneSocket->write(lastEarDetectionStatus);
                LOG_DEBUG("Sent last ear detection status to phone: " << lastEarDetectionStatus.toHex());
            }
        });

        connect(phoneSocket, QOverload<QBluetoothSocket::SocketError>::of(&QBluetoothSocket::errorOccurred), this, [this](QBluetoothSocket::SocketError error) {
            LOG_ERROR("Phone socket error: " << error << ", " << phoneSocket->errorString());
            updatePhoneMacStatus(QStringLiteral("Cross-device connection failed: %1").arg(phoneSocket->errorString()));
            updatePhoneConnectionState();
        });

        connect(phoneSocket, &QBluetoothSocket::disconnected, this, [this]() {
            updatePhoneMacStatusFromConfiguration();
            updatePhoneConnectionState();
        });

        phoneSocket->connectToService(phoneAddress, QBluetoothUuid("1abbb9a4-10e4-4000-a75c-8953c5471342"));
        updatePhoneMacStatus(QStringLiteral("Connecting to phone %1 for cross-device relay...").arg(validPhoneMac));
    }

    void relayPacketToPhone(const QByteArray &packet)
    {
        if (!CrossDevice.isEnabled) {
            return;
        }
        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Phone::NOTIFICATION + packet);
        }
        else
        {
            connectToPhone();
            LOG_WARN("Phone socket is not open, cannot relay packet");
        }
    }

    void handlePhonePacket(const QByteArray &packet) {
        if (packet.startsWith(AirPodsPackets::Phone::NOTIFICATION))
        {
            QByteArray airpodsPacket = packet.mid(4);
            if (writePacketToSocket(airpodsPacket, "Relayed packet to AirPods: ")) {
                LOG_DEBUG("Relayed packet to AirPods: " << airpodsPacket.toHex());
            } else {
                LOG_ERROR("Socket is not ready, cannot relay packet to AirPods");
            }
        }
        else if (packet.startsWith(AirPodsPackets::Phone::CONNECTED))
        {
            LOG_INFO("AirPods connected");
            isConnectedLocally = true;
            CrossDevice.isAvailable = false;
        }
        else if (packet.startsWith(AirPodsPackets::Phone::DISCONNECTED))
        {
            LOG_INFO("AirPods disconnected");
            isConnectedLocally = false;
            CrossDevice.isAvailable = true;
        }
        else if (packet.startsWith(AirPodsPackets::Phone::STATUS_REQUEST))
        {
            LOG_INFO("Connection status request received");
            QByteArray response = (socket && socket->isOpen()) ? AirPodsPackets::Phone::CONNECTED
                                                               : AirPodsPackets::Phone::DISCONNECTED;
            phoneSocket->write(response);
            LOG_DEBUG("Sent connection status response: " << response.toHex());
        }
        else if (packet.startsWith(AirPodsPackets::Phone::DISCONNECT_REQUEST))
        {
            LOG_INFO("Disconnect request received");
            if (socket && socket->isOpen()) {
                socket->close();
                LOG_INFO("Disconnected from AirPods");
                QProcess process;
                process.start("bluetoothctl", QStringList() << "disconnect" << m_deviceInfo->bluetoothAddress());
                process.waitForFinished();
                QString output = process.readAllStandardOutput().trimmed();
                LOG_INFO("Bluetoothctl output: " << output);
                isConnectedLocally = false;
                CrossDevice.isAvailable = true;
            }
        }
        else
        {
            if (writePacketToSocket(packet, "Relayed packet to AirPods: ")) {
                LOG_DEBUG("Relayed packet to AirPods: " << packet.toHex());
            } else {
                LOG_ERROR("Socket is not ready, cannot relay packet to AirPods");
            }
        }
    }

    void onPhoneDataReceived() {
        QByteArray data = phoneSocket->readAll();
        LOG_DEBUG("Data received from phone: " << data.toHex());
        QMetaObject::invokeMethod(this, "handlePhonePacket", Qt::QueuedConnection, Q_ARG(QByteArray, data));
    }

    void bleDeviceFound(const BleInfo &device)
    {
        if (BLEUtils::isValidIrkRpa(m_deviceInfo->magicAccIRK(), device.address)) {
            m_deviceInfo->setModel(device.modelName);
            auto decryptet = BLEUtils::decryptLastBytes(device.encryptedPayload, m_deviceInfo->magicAccEncKey());
            m_deviceInfo->getBattery()->parseEncryptedPacket(decryptet, device.primaryLeft, device.isThisPodInTheCase, isModelHeadset(m_deviceInfo->model()));
            m_deviceInfo->getEarDetection()->overrideEarDetectionStatus(device.isPrimaryInEar, device.isSecondaryInEar);
        }
    }

public:
    void handleMediaStateChange(MediaController::MediaState state) {
        if (state == MediaController::MediaState::Playing) {
            LOG_INFO("Media started playing, sending disconnect request to Android and taking over audio");
            sendDisconnectRequestToAndroid();
            connectToAirPods(true);
        }
    }

    void sendDisconnectRequestToAndroid()
    {
        if (!CrossDevice.isEnabled) return;

        if (phoneSocket && phoneSocket->isOpen())
        {
            phoneSocket->write(AirPodsPackets::Phone::DISCONNECT_REQUEST);
            LOG_DEBUG("Sent disconnect request to Android: " << AirPodsPackets::Phone::DISCONNECT_REQUEST.toHex());
        }
        else
        {
            LOG_WARN("Phone socket is not open, cannot send disconnect request");
        }
    }

    bool isPhoneConnected() {
        return phoneSocket && phoneSocket->isOpen();
    }

    void connectToAirPods(bool force) {
        if (socket && socket->isOpen()) {
            LOG_INFO("Already connected to AirPods");
            return;
        }

        if (force) {
            LOG_INFO("Forcing connection to AirPods");
            QProcess process;
            process.start("bluetoothctl", QStringList() << "connect" << m_deviceInfo->bluetoothAddress());
            process.waitForFinished();
            QString output = process.readAllStandardOutput().trimmed();
            LOG_INFO("Bluetoothctl output: " << output);
        }
        QBluetoothLocalDevice localDevice;
        const QList<QBluetoothAddress> connectedDevices = localDevice.connectedDevices();
        for (const QBluetoothAddress &address : connectedDevices) {
            QBluetoothDeviceInfo device(address, "", 0);
            LOG_DEBUG("Connected device: " << device.name() << " (" << device.address().toString() << ")");
            if (isAirPodsDevice(device)) {
                connectToDevice(device);
                return;
            }
        }
        LOG_WARN("AirPods not found among connected devices");
    }

    void initializeBluetooth() {
        connectToPhone();

        m_deviceInfo->loadFromSettings(*m_settings);
        if (!areAirpodsConnected()) {
            m_bleManager->startScan();
        }
    }

    void loadMainModule() {
        if (!parent)
        {
            return;
        }

        const auto showExistingWindow = [this]() -> bool
        {
            if (parent->rootObjects().isEmpty())
            {
                return false;
            }

            if (auto *window = qobject_cast<QQuickWindow *>(parent->rootObjects().first()))
            {
                window->setVisible(true);
                window->show();
                window->raise();
                window->requestActivate();
                return true;
            }

            QObject *rootObject = parent->rootObjects().first();
            if (rootObject)
            {
                rootObject->setProperty("visible", true);
                QMetaObject::invokeMethod(rootObject, "raise");
                QMetaObject::invokeMethod(rootObject, "requestActivate");
                return true;
            }

            return false;
        };

        if (showExistingWindow())
        {
            return;
        }

        parent->load(QUrl(QStringLiteral("qrc:/linux/Main.qml")));
        showExistingWindow();
    }

signals:
    void noiseControlModeChanged(NoiseControlMode mode);
    void earDetectionStatusChanged(const QString &status);
    void batteryStatusChanged(const QString &status);
    void conversationalAwarenessChanged(bool enabled);
    void adaptiveNoiseLevelChanged(int level);
    void deviceNameChanged(const QString &name);
    void modelChanged();
    void primaryChanged();
    void airPodsStatusChanged();
    void airPodsCommandReadyChanged();
    void earDetectionBehaviorChanged(int behavior);
    void crossDeviceEnabledChanged(bool enabled);
    void notificationsEnabledChanged(bool enabled);
    void retryAttemptsChanged(int attempts);
    void oneBudANCModeChanged(bool enabled);
    void phoneMacStatusChanged();
    void phoneConnectionChanged();
    void hearingAidEnabledChanged(bool enabled);
    void hearingAidSetupStatusChanged();
    void headTrackingStatusChanged();

private:
    void sendCustomizeTransparency()
    {
        using namespace AirPodsPackets::CustomizeTransparency;
        QByteArray packet = getPacket(m_deviceInfo->customizeTransparencyEnabled(), m_transpLeft, m_transpRight);
        writePacketToSocket(packet, "Customize Transparency packet written: ");
    }

    void sendStemLongPressConfig()
    {
        int modes = m_settings->value("stemLongPressModes", 0x06).toInt();
        m_deviceInfo->setStemLongPressModes(modes);
        if (__builtin_popcount(static_cast<quint8>(modes)) >= 2) {
            QByteArray packet = AirPodsPackets::StemLongPress::getPacket(static_cast<quint8>(modes));
            writePacketToSocket(packet, "Stem Long Press config restored: ", false);
        }
    }

    // Data members
    L2CAPSocket *socket = nullptr;
    QBluetoothSocket *phoneSocket = nullptr;
    QByteArray lastBatteryStatus;
    QByteArray lastEarDetectionStatus;
    MediaController* mediaController;
    TrayIconManager *trayManager;
    BluetoothMonitor *monitor;
    QSettings *m_settings;
    AutoStartManager *m_autoStartManager;
    int m_retryAttempts = 3;
    bool m_hideOnStart = false;
    DeviceInfo *m_deviceInfo;
    BleManager *m_bleManager;
    SystemSleepMonitor *m_systemSleepMonitor = nullptr;
    bool m_airPodsCommandReady = false;
    QString m_phoneMacStatus;
    QString m_hearingAidSetupStatus;
    QString m_headTrackingStatus;
    AirPodsPackets::CustomizeTransparency::BudSettings m_transpLeft;
    AirPodsPackets::CustomizeTransparency::BudSettings m_transpRight;
    QList<int> m_headphoneEq = QList<int>(8, 0);
};

int main(int argc, char *argv[]) {
    QApplication app(argc, argv);

    // Load translations
    QTranslator *translator = new QTranslator(&app);
    QString locale = QLocale::system().name();

    // Try to load translation from various locations
    QStringList translationPaths = {
        QCoreApplication::applicationDirPath(),
        QCoreApplication::applicationDirPath() + "/translations",
        QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation) + "/librepods/translations",
        "/usr/share/librepods/translations",
        "/usr/local/share/librepods/translations"
    };

    // Try full locale (e.g. es_AR), then language-only fallback (e.g. es)
    const QString langCode = locale.left(2);
    for (const QString &path : translationPaths) {
        if (translator->load("librepods_" + locale, path) ||
            (langCode != locale && translator->load("librepods_" + langCode, path))) {
            app.installTranslator(translator);
            break;
        }
    }

    QLocalServer::removeServer("app_server");

    QFile stale("/tmp/app_server");
    if (stale.exists())
        stale.remove();

    QLocalSocket socket_check;
    socket_check.connectToServer("app_server");

    if (socket_check.waitForConnected(300)) {
        LOG_INFO("Another instance already running! Reopening window...");

        socket_check.write("reopen");
        socket_check.flush();
        socket_check.waitForBytesWritten(200);
        socket_check.disconnectFromServer();

        return 0;
    }
    app.setDesktopFileName("me.kavishdevar.librepods");
    app.setQuitOnLastWindowClosed(false);

    bool debugMode = false;
    bool hideOnStart = false;
    for (int i = 1; i < argc; ++i) {
        if (QString(argv[i]) == "--debug")
            debugMode = true;

        if (QString(argv[i]) == "--hide")
            hideOnStart = true;
    }

    QQmlApplicationEngine engine;
    qmlRegisterType<Battery>("me.kavishdevar.Battery", 1, 0, "Battery");
    qmlRegisterType<DeviceInfo>("me.kavishdevar.DeviceInfo", 1, 0, "DeviceInfo");
    AirPodsTrayApp *trayApp = new AirPodsTrayApp(debugMode, hideOnStart, &engine);
    engine.rootContext()->setContextProperty("airPodsTrayApp", trayApp);

    // Expose PHONE_MAC_ADDRESS environment variable to QML for placeholder in settings
    {
        QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
        QString phoneMacEnv = env.value("PHONE_MAC_ADDRESS", "");
        engine.rootContext()->setContextProperty("PHONE_MAC_ADDRESS", phoneMacEnv);
    }

    engine.addImageProvider("qrcode", new QRCodeImageProvider());
    trayApp->loadMainModule();

    QLocalServer server;
    QLocalServer::removeServer("app_server");

    if (!server.listen("app_server"))
    {
        LOG_ERROR("Unable to start the listening server");
        LOG_DEBUG("Server error: " << server.errorString());
    }
    else
    {
        LOG_DEBUG("Server started, waiting for connections...");
    }
    QObject::connect(&server, &QLocalServer::newConnection, [&]() {
        QLocalSocket* socket = server.nextPendingConnection();
        // Handles Proper Connection
        QObject::connect(socket, &QLocalSocket::readyRead, [socket, &engine, &trayApp]() {
            QString msg = socket->readAll();
            // Check if the message is "reopen", if so, trigger onOpenApp function
            if (msg == "reopen") {
                LOG_INFO("Reopening app window");
                QObject *rootObject = engine.rootObjects().first();
                if (rootObject) {
                    QMetaObject::invokeMethod(rootObject, "reopen", Q_ARG(QVariant, "app"));
                }
                else
                {
                    trayApp->loadMainModule();
                }
            }
            else if (msg == "noise:off") {
                trayApp->setNoiseControlModeInt(0);
            }
            else if (msg == "noise:anc") {
                trayApp->setNoiseControlModeInt(1);
            }
            else if (msg == "noise:transparency") {
                trayApp->setNoiseControlModeInt(2);
            }
            else if (msg == "noise:adaptive") {
                trayApp->setNoiseControlModeInt(3);
            }
            else
            {
                LOG_ERROR("Unknown message received: " << msg);
            }
            socket->disconnectFromServer();
        });
        // Handles connection errors
        QObject::connect(socket, &QLocalSocket::errorOccurred, [socket]() {
            LOG_ERROR("Failed to connect to the duplicate app instance");
            LOG_DEBUG("Connection error: " << socket->errorString());
        });

        // Handle server-level errors
        QObject::connect(&server, &QLocalServer::serverError, [&]() {
            LOG_ERROR("Server failed to accept a new connection");
            LOG_DEBUG("Server error: " << server.errorString());
        });
    });

    QObject::connect(&app, &QCoreApplication::aboutToQuit, [&]() {
        LOG_DEBUG("Application quitting. Cleaning up local server...");

        if (server.isListening()) {
            server.close();
        }

        QLocalServer::removeServer("app_server");
        QFile stale("/tmp/app_server");
        if (stale.exists())
            stale.remove();
    });
    return app.exec();
}

#include "main.moc"
