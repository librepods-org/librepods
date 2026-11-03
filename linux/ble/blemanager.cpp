#include "blemanager.h"
#include "enums.h"
#include <QDebug>
#include "logger.h"
#include <QMap>
#include <QDBusArgument>
#include <QDBusMetaType>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusServiceWatcher>
#include <QDBusVariant>
#include <QDBusVirtualObject>
#include "BluetoothMonitor.h"

AirpodsTrayApp::Enums::AirPodsModel getModelName(quint16 modelId)
{
    using namespace AirpodsTrayApp::Enums;
    static const QMap<quint16, AirPodsModel> modelMap = {
        {0x0220, AirPodsModel::AirPods1},
        {0x0F20, AirPodsModel::AirPods2},
        {0x1320, AirPodsModel::AirPods3},
        {0x1920, AirPodsModel::AirPods4},
        {0x1B20, AirPodsModel::AirPods4ANC},
        {0x0A20, AirPodsModel::AirPodsMaxLightning},
        {0x1F20, AirPodsModel::AirPodsMaxUSBC},
        {0x0E20, AirPodsModel::AirPodsPro},
        {0x1420, AirPodsModel::AirPodsPro2Lightning},
        {0x2420, AirPodsModel::AirPodsPro2USBC}
    };

    return modelMap.value(modelId, AirPodsModel::Unknown);
}

QString getColorName(quint8 colorId)
{
    switch (colorId)
    {
    case 0x00:
        return "White";
    case 0x01:
        return "Black";
    case 0x02:
        return "Red";
    case 0x03:
        return "Blue";
    case 0x04:
        return "Pink";
    case 0x05:
        return "Gray";
    case 0x06:
        return "Silver";
    case 0x07:
        return "Gold";
    case 0x08:
        return "Rose Gold";
    case 0x09:
        return "Space Gray";
    case 0x0A:
        return "Dark Blue";
    case 0x0B:
        return "Light Blue";
    case 0x0C:
        return "Yellow";
    default:
        return "Unknown";
    }
}

QString getConnectionStateName(BleInfo::ConnectionState state)
{
    using ConnectionState = BleInfo::ConnectionState;
    switch (state)
    {
    case ConnectionState::DISCONNECTED:
        return QString("Disconnected");
    case ConnectionState::IDLE:
        return QString("Idle");
    case ConnectionState::MUSIC:
        return QString("Playing Music");
    case ConnectionState::CALL:
        return QString("On Call");
    case ConnectionState::RINGING:
        return QString("Ringing");
    case ConnectionState::HANGING_UP:
        return QString("Hanging Up");
    case ConnectionState::UNKNOWN:
    default:
        return QString("Unknown");
    }
}

namespace
{
const QString bluezService = QStringLiteral("org.bluez");
const QString monitorManagerInterface = QStringLiteral("org.bluez.AdvertisementMonitorManager1");
const QString monitorInterface = QStringLiteral("org.bluez.AdvertisementMonitor1");
const QString deviceInterface = QStringLiteral("org.bluez.Device1");
const QString propertiesInterface = QStringLiteral("org.freedesktop.DBus.Properties");
const QString objectManagerInterface = QStringLiteral("org.freedesktop.DBus.ObjectManager");
const QString appPath = QStringLiteral("/me/kavishdevar/librepods/advmonitor");
const QString monitorPath = appPath + QStringLiteral("/proximity");

struct AdvPattern
{
    quint8 start = 0;
    quint8 adType = 0;
    QByteArray content;
};
}

Q_DECLARE_METATYPE(AdvPattern)

namespace
{
QDBusArgument &operator<<(QDBusArgument &arg, const AdvPattern &pattern)
{
    arg.beginStructure();
    arg << pattern.start << pattern.adType << pattern.content;
    arg.endStructure();
    return arg;
}

const QDBusArgument &operator>>(const QDBusArgument &arg, AdvPattern &pattern)
{
    arg.beginStructure();
    arg >> pattern.start >> pattern.adType >> pattern.content;
    arg.endStructure();
    return arg;
}

// The ObjectManager tree bluetoothd reads the monitor from, plus the monitor's own methods.
class MonitorApp : public QDBusVirtualObject
{
public:
    explicit MonitorApp(BleManager *manager) : manager(manager) {}

    QString introspect(const QString &) const override { return {}; }

    bool handleMessage(const QDBusMessage &message, const QDBusConnection &connection) override
    {
        if (message.path() == appPath && message.interface() == objectManagerInterface
            && message.member() == QLatin1String("GetManagedObjects"))
        {
            // Manufacturer specific data (0xFF) starting with Apple's company id 0x004C,
            // little endian, then the Proximity Pairing message type 0x07.
            const QList<AdvPattern> patterns{{0, 0xFF, QByteArray::fromHex("4c0007")}};
            const QVariantMap properties{
                {QStringLiteral("Type"), QStringLiteral("or_patterns")},
                {QStringLiteral("Patterns"), QVariant::fromValue(patterns)},
            };
            ManagedObjectList objects;
            objects[QDBusObjectPath(monitorPath)][monitorInterface] = properties;
            connection.send(message.createReply(QVariant::fromValue(objects)));
            return true;
        }

        if (message.path() != monitorPath || message.interface() != monitorInterface)
            return false;

        const QString member = message.member();
        const QList<QVariant> args = message.arguments();
        if (member == QLatin1String("DeviceFound") && !args.isEmpty())
            manager->onMonitorDeviceFound(args.first().value<QDBusObjectPath>().path());
        else if (member == QLatin1String("DeviceLost") && !args.isEmpty())
            manager->onMonitorDeviceLost(args.first().value<QDBusObjectPath>().path());
        else if (member == QLatin1String("Release"))
            manager->onMonitorReleased();
        else if (member != QLatin1String("Activate"))
            return false;

        if (!message.isReplyRequired())
            return true;
        connection.send(message.createReply());
        return true;
    }

private:
    BleManager *manager;
};

QString addressFromPath(const QString &devicePath)
{
    return devicePath.section(QLatin1Char('/'), -1).mid(4).replace(QLatin1Char('_'), QLatin1Char(':'));
}

QByteArray appleData(const QVariant &manufacturerData)
{
    QMap<quint16, QDBusVariant> entries;
    manufacturerData.value<QDBusArgument>() >> entries;
    return entries.value(0x004C).variant().toByteArray();
}
}

BleManager::BleManager(QObject *parent) : QObject(parent), bus(QDBusConnection::systemBus())
{
    qDBusRegisterMetaType<AdvPattern>();
    qDBusRegisterMetaType<QList<AdvPattern>>();
    qDBusRegisterMetaType<ManagedObjectList>();

    if (!bus.registerVirtualObject(appPath, new MonitorApp(this), QDBusConnection::SubPath))
        LOG_ERROR("Could not export the advertisement monitor at" << appPath);

    auto *watcher = new QDBusServiceWatcher(bluezService, bus, QDBusServiceWatcher::WatchForOwnerChange, this);
    connect(watcher, &QDBusServiceWatcher::serviceOwnerChanged, this, &BleManager::onBluezOwnerChanged);
    bus.connect(bluezService, QStringLiteral("/"), objectManagerInterface, QStringLiteral("InterfacesAdded"),
                this, SLOT(onInterfacesAdded(QDBusMessage)));
    bus.connect(bluezService, QStringLiteral("/"), objectManagerInterface, QStringLiteral("InterfacesRemoved"),
                this, SLOT(onInterfacesRemoved(QDBusMessage)));
}

BleManager::~BleManager()
{
    unregisterMonitor();
    bus.unregisterObject(appPath, QDBusConnection::UnregisterTree);
}

void BleManager::startScan()
{
    LOG_DEBUG("Starting BLE advertisement monitor...");
    wanted = true;
    registerMonitor();
}

void BleManager::stopScan()
{
    LOG_DEBUG("Stopping BLE advertisement monitor...");
    wanted = false;
    unregisterMonitor();
}

bool BleManager::isScanning() const
{
    return wanted;
}

void BleManager::registerMonitor()
{
    if (!wanted || registering || !adapterPath.isEmpty())
        return;
    registering = true;

    auto lookup = QDBusMessage::createMethodCall(bluezService, QStringLiteral("/"), objectManagerInterface,
                                                 QStringLiteral("GetManagedObjects"));
    auto *lookupWatcher = new QDBusPendingCallWatcher(bus.asyncCall(lookup), this);
    connect(lookupWatcher, &QDBusPendingCallWatcher::finished, this, [this](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        QDBusPendingReply<ManagedObjectList> reply = *call;
        QString adapter;
        if (reply.isValid())
        {
            const ManagedObjectList objects = reply.value();
            for (auto it = objects.constBegin(); it != objects.constEnd() && adapter.isEmpty(); ++it)
                if (it.value().contains(monitorManagerInterface))
                    adapter = it.key().path();
        }
        if (!wanted || adapter.isEmpty())
        {
            if (wanted)
                LOG_WARN("No Bluetooth adapter offers advertisement monitors yet");
            registering = false;
            return;
        }

        auto registration = QDBusMessage::createMethodCall(bluezService, adapter, monitorManagerInterface,
                                                           QStringLiteral("RegisterMonitor"));
        registration << QVariant::fromValue(QDBusObjectPath(appPath));
        auto *registrationWatcher = new QDBusPendingCallWatcher(bus.asyncCall(registration), this);
        connect(registrationWatcher, &QDBusPendingCallWatcher::finished, this, [this, adapter](QDBusPendingCallWatcher *call) {
            call->deleteLater();
            registering = false;
            QDBusPendingReply<> reply = *call;
            if (reply.isError())
            {
                LOG_ERROR("RegisterMonitor on" << adapter << "failed:" << reply.error().message());
                return;
            }
            LOG_INFO("Watching AirPods adverts through the advertisement monitor on" << adapter);
            adapterPath = adapter;
            if (!wanted)
                unregisterMonitor();
        });
    });
}

void BleManager::unregisterMonitor()
{
    forgetDevices();
    if (adapterPath.isEmpty())
        return;
    auto call = QDBusMessage::createMethodCall(bluezService, adapterPath, monitorManagerInterface,
                                               QStringLiteral("UnregisterMonitor"));
    call << QVariant::fromValue(QDBusObjectPath(appPath));
    bus.asyncCall(call);
    adapterPath.clear();
}

void BleManager::forgetDevices()
{
    for (auto it = names.constBegin(); it != names.constEnd(); ++it)
        bus.disconnect(bluezService, it.key(), propertiesInterface, QStringLiteral("PropertiesChanged"),
                       this, SLOT(onDevicePropertiesChanged(QDBusMessage)));
    names.clear();
}

void BleManager::onMonitorDeviceFound(const QString &devicePath)
{
    if (names.contains(devicePath))
        return;
    names.insert(devicePath, QString());
    bus.connect(bluezService, devicePath, propertiesInterface, QStringLiteral("PropertiesChanged"),
                this, SLOT(onDevicePropertiesChanged(QDBusMessage)));

    auto call = QDBusMessage::createMethodCall(bluezService, devicePath, propertiesInterface, QStringLiteral("GetAll"));
    call << deviceInterface;
    auto *watcher = new QDBusPendingCallWatcher(bus.asyncCall(call), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, devicePath](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        QDBusPendingReply<QVariantMap> reply = *call;
        if (reply.isValid() && names.contains(devicePath))
            handleDeviceProperties(devicePath, reply.value());
    });
}

void BleManager::onMonitorDeviceLost(const QString &devicePath)
{
    if (!names.remove(devicePath))
        return;
    bus.disconnect(bluezService, devicePath, propertiesInterface, QStringLiteral("PropertiesChanged"),
                   this, SLOT(onDevicePropertiesChanged(QDBusMessage)));
}

void BleManager::onMonitorReleased()
{
    LOG_WARN("bluetoothd released the advertisement monitor");
    forgetDevices();
    adapterPath.clear();
}

void BleManager::onDevicePropertiesChanged(const QDBusMessage &message)
{
    const QList<QVariant> args = message.arguments();
    if (args.size() < 2 || args.first().toString() != deviceInterface || !names.contains(message.path()))
        return;
    handleDeviceProperties(message.path(), qdbus_cast<QVariantMap>(args.at(1)));
}

void BleManager::onInterfacesAdded(const QDBusMessage &message)
{
    const QList<QVariant> args = message.arguments();
    if (args.size() < 2 || !wanted || !adapterPath.isEmpty())
        return;
    const auto interfaces = qdbus_cast<QMap<QString, QVariantMap>>(args.at(1));
    if (interfaces.contains(monitorManagerInterface))
        registerMonitor();
}

void BleManager::onInterfacesRemoved(const QDBusMessage &message)
{
    const QList<QVariant> args = message.arguments();
    if (args.isEmpty())
        return;
    const QString path = args.first().value<QDBusObjectPath>().path();
    if (path == adapterPath)
    {
        forgetDevices();
        adapterPath.clear();
    }
    else
    {
        onMonitorDeviceLost(path);
    }
}

void BleManager::onBluezOwnerChanged(const QString &, const QString &, const QString &newOwner)
{
    forgetDevices();
    adapterPath.clear();
    if (!newOwner.isEmpty())
        registerMonitor();
}

void BleManager::handleDeviceProperties(const QString &devicePath, const QVariantMap &properties)
{
    if (properties.contains(QStringLiteral("Alias")))
        names[devicePath] = properties.value(QStringLiteral("Alias")).toString();
    if (!properties.contains(QStringLiteral("ManufacturerData")))
        return;
    const QByteArray data = appleData(properties.value(QStringLiteral("ManufacturerData")));
    if (!data.isEmpty())
        parseAdvertisement(addressFromPath(devicePath), names.value(devicePath), data);
}

void BleManager::parseAdvertisement(const QString &address, const QString &name, const QByteArray &data)
{
    {
        // Ensure data is long enough and starts with prefix 0x07 (indicates Proximity Pairing Message)
        if (data.size() >= 10 && data[0] == 0x07)
        {
            BleInfo deviceInfo;
            deviceInfo.name = name.isEmpty() ? "AirPods" : name;
            deviceInfo.address = address;
            deviceInfo.rawData = data.left(data.size() - 16);
            deviceInfo.encryptedPayload = data.mid(data.size() - 16);

            // data[1] is the length of the data, so we can skip it

            // Check if pairing mode is paired (0x01) or pairing (0x00)
            if (data[2] == 0x00)
            {
                return; // Skip pairing mode devices (the values are differently structured)
            }

            
            // Parse device model (big-endian: high byte at data[3], low byte at data[4])
            deviceInfo.modelName = getModelName(static_cast<quint16>(data[4]) | (static_cast<quint8>(data[3]) << 8));

            // Status byte for primary pod and other flags
            quint8 status = static_cast<quint8>(data[5]);
            deviceInfo.status = status;

            // Pods battery byte (upper nibble: one pod, lower nibble: other pod)
            quint8 podsBatteryByte = static_cast<quint8>(data[6]);

            // Flags and case battery byte (upper nibble: case battery, lower nibble: flags)
            quint8 flagsAndCaseBattery = static_cast<quint8>(data[7]);

            // Lid open counter and device color
            quint8 lidIndicator = static_cast<quint8>(data[8]);
            deviceInfo.color = getColorName((quint8)(data[9]));

            deviceInfo.connectionState = static_cast<BleInfo::ConnectionState>(data[10]);

            // Next: Encrypted Payload: 16 bytes

            // Determine primary pod (bit 5 of status) and value flipping
            bool primaryLeft = (status & 0x20) != 0; // Bit 5: 1 = left primary, 0 = right primary
            bool areValuesFlipped = !primaryLeft;    // Flipped when right pod is primary

            deviceInfo.primaryLeft = primaryLeft; // Store primary pod information

            // Parse battery levels
            int leftNibble = areValuesFlipped ? (podsBatteryByte >> 4) & 0x0F : podsBatteryByte & 0x0F;
            int rightNibble = areValuesFlipped ? podsBatteryByte & 0x0F : (podsBatteryByte >> 4) & 0x0F;
            deviceInfo.leftPodBattery = (leftNibble == 15) ? -1 : leftNibble * 10;
            deviceInfo.rightPodBattery = (rightNibble == 15) ? -1 : rightNibble * 10;
            int caseNibble = flagsAndCaseBattery & 0x0F; // Extracts lower nibble
            deviceInfo.caseBattery = (caseNibble == 15) ? -1 : caseNibble * 10;

            // Parse charging statuses from flags (uper 4 bits of data[7])
            quint8 flags = (flagsAndCaseBattery >> 4) & 0x0F;                                        // Extracts lower nibble
            deviceInfo.rightCharging = areValuesFlipped ? (flags & 0x01) != 0 : (flags & 0x02) != 0; // Depending on primary, bit 0 or 1
            deviceInfo.leftCharging = areValuesFlipped ? (flags & 0x02) != 0 : (flags & 0x01) != 0;  // Depending on primary, bit 1 or 0
            deviceInfo.caseCharging = (flags & 0x04) != 0;                                           // bit 2

            // Additional status flags from status byte (data[5])
            deviceInfo.isThisPodInTheCase = (status & 0x40) != 0; // Bit 6
            deviceInfo.isOnePodInCase = (status & 0x10) != 0;     // Bit 4
            deviceInfo.areBothPodsInCase = (status & 0x04) != 0;  // Bit 2

            // In-ear detection with XOR logic
            bool xorFactor = areValuesFlipped ^ deviceInfo.isThisPodInTheCase;
            deviceInfo.isLeftPodInEar = xorFactor ? (status & 0x08) != 0 : (status & 0x02) != 0;  // Bit 3 or 1
            deviceInfo.isRightPodInEar = xorFactor ? (status & 0x02) != 0 : (status & 0x08) != 0; // Bit 1 or 3

            // Determine primary and secondary in-ear status
            deviceInfo.isPrimaryInEar = primaryLeft ? deviceInfo.isLeftPodInEar : deviceInfo.isRightPodInEar;
            deviceInfo.isSecondaryInEar = primaryLeft ? deviceInfo.isRightPodInEar : deviceInfo.isLeftPodInEar;

            // Microphone status
            deviceInfo.isLeftPodMicrophone = primaryLeft ^ deviceInfo.isThisPodInTheCase;
            deviceInfo.isRightPodMicrophone = !primaryLeft ^ deviceInfo.isThisPodInTheCase;

            deviceInfo.lidOpenCounter = lidIndicator & 0x07; // Extract bits 0-2 (count)
            quint8 lidState = static_cast<quint8>((lidIndicator >> 3) & 0x01); // Extract bit 3 (lid state)
            if (deviceInfo.isThisPodInTheCase) {
                deviceInfo.lidState = static_cast<BleInfo::LidState>(lidState);
            }

            // Update timestamp
            deviceInfo.lastSeen = QDateTime::currentDateTime();

            emit deviceFound(deviceInfo); // Emit signal for device found
        }
    }
}
