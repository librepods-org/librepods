#ifndef BLEMANAGER_H
#define BLEMANAGER_H

#include <QObject>
#include <QDBusConnection>
#include <QDBusMessage>
#include <QHash>
#include <QMap>
#include <QString>
#include <QDateTime>
#include "enums.h"

class BleInfo
{
public:
    QString name;
    QString address;
    int leftPodBattery = -1; // -1 indicates not available
    int rightPodBattery = -1;
    int caseBattery = -1;
    bool leftCharging = false;
    bool rightCharging = false;
    bool caseCharging = false;
    AirpodsTrayApp::Enums::AirPodsModel modelName = AirpodsTrayApp::Enums::AirPodsModel::Unknown;
    quint8 lidOpenCounter = 0;
    QString color = "Unknown"; // Default color
    quint8 status = 0;
    QByteArray rawData;
    QByteArray encryptedPayload; // 16 bytes of encrypted payload

    // Additional status flags from Kotlin version
    bool isLeftPodInEar = false;
    bool isRightPodInEar = false;
    bool isPrimaryInEar = false;
    bool isSecondaryInEar = false;
    bool isLeftPodMicrophone = false;
    bool isRightPodMicrophone = false;
    bool isThisPodInTheCase = false;
    bool isOnePodInCase = false;
    bool areBothPodsInCase = false;
    bool primaryLeft = true; // True if left pod is primary, false if right pod is primary

    // Lid state enumeration
    enum class LidState
    {
        OPEN = 0x0,
        CLOSED = 0x1,
        UNKNOWN,
    } lidState = LidState::UNKNOWN;

    // Connection state enumeration
    enum class ConnectionState : uint8_t
    {
        DISCONNECTED = 0x00,
        IDLE = 0x04,
        MUSIC = 0x05,
        CALL = 0x06,
        RINGING = 0x07,
        HANGING_UP = 0x09,
        UNKNOWN = 0xFF // Using 0xFF for representing null in the original
    } connectionState = ConnectionState::UNKNOWN;

    QDateTime lastSeen; // Timestamp of last detection
};

class BleManager : public QObject
{
    Q_OBJECT
public:
    explicit BleManager(QObject *parent = nullptr);
    ~BleManager();

    void startScan();
    void stopScan();
    bool isScanning() const;

    void onMonitorDeviceFound(const QString &devicePath);
    void onMonitorDeviceLost(const QString &devicePath);
    void onMonitorReleased();

signals:
    void deviceFound(const BleInfo &device);

private slots:
    void onDevicePropertiesChanged(const QDBusMessage &message);
    void onInterfacesAdded(const QDBusMessage &message);
    void onInterfacesRemoved(const QDBusMessage &message);
    void onBluezOwnerChanged(const QString &service, const QString &oldOwner, const QString &newOwner);

private:
    void registerMonitor();
    void unregisterMonitor();
    void forgetDevices();
    void handleDeviceProperties(const QString &devicePath, const QVariantMap &properties);
    void parseAdvertisement(const QString &address, const QString &name, const QByteArray &data);

    // Watches AirPods adverts through BlueZ's Advertisement Monitor rather than a discovery
    // session. Discovery sessions are shared: every client that starts or stops one makes
    // bluetoothd stop and restart the controller scan for all of them, and a session that is
    // always open turns each scan the desktop asks for into that stop and restart. bluetoothd
    // 5.87 can wedge on it waiting for a "discovery stopped" event that never comes, after
    // which no client can scan until the service restarts. A monitor runs on the passive
    // background scan (offloaded to the controller where it supports MSFT patterns) and only
    // reports devices whose adverts match.
    QDBusConnection bus;
    bool wanted = false;
    bool registering = false;
    QString adapterPath;
    QHash<QString, QString> names; // device path -> Alias
};

#endif // BLEMANAGER_H
