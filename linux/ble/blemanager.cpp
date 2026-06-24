#include "blemanager.h"
#include "enums.h"
#include <QDebug>
#include <QTimer>
#include "logger.h"
#include <QMap>

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
        {0x2420, AirPodsModel::AirPodsPro2USBC},
        {0x2720, AirPodsModel::AirPodsPro3},
        {0x3F20, AirPodsModel::AirPodsPro3}
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

BleManager::BleManager(QObject *parent) : QObject(parent)
{
    discoveryAgent = new QBluetoothDeviceDiscoveryAgent(this);
    discoveryAgent->setLowEnergyDiscoveryTimeout(0); // Continuous scanning

    connect(discoveryAgent, &QBluetoothDeviceDiscoveryAgent::deviceDiscovered,
            this, &BleManager::onDeviceDiscovered);
    connect(discoveryAgent, &QBluetoothDeviceDiscoveryAgent::finished,
            this, &BleManager::onScanFinished);
    connect(discoveryAgent, &QBluetoothDeviceDiscoveryAgent::errorOccurred,
            this, &BleManager::onErrorOccurred);
}

BleManager::~BleManager()
{
    delete discoveryAgent;
}

void BleManager::startScan()
{
    LOG_DEBUG("Starting BLE scan...");
    discoveryAgent->start(QBluetoothDeviceDiscoveryAgent::LowEnergyMethod);
}

void BleManager::stopScan()
{
    LOG_DEBUG("Stopping BLE scan...");
    discoveryAgent->stop();
}

bool BleManager::isScanning() const
{
    return discoveryAgent->isActive();
}

void BleManager::onDeviceDiscovered(const QBluetoothDeviceInfo &info)
{
    // Check for Apple's manufacturer ID (0x004C)
    if (info.manufacturerData().contains(0x004C))
    {
        QByteArray data = info.manufacturerData().value(0x004C);
        // Ensure data is long enough and starts with prefix 0x07 (indicates Proximity Pairing Message)
        if (data.size() >= 10 && data[0] == 0x07)
        {
            QString address = info.address().toString();
            BleInfo deviceInfo;
            deviceInfo.name = info.name().isEmpty() ? "AirPods" : info.name();
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

void BleManager::onScanFinished()
{
    if (discoveryAgent->isActive())
    {
        discoveryAgent->start(QBluetoothDeviceDiscoveryAgent::LowEnergyMethod);
    }
}

void BleManager::onErrorOccurred(QBluetoothDeviceDiscoveryAgent::Error error)
{
    LOG_ERROR("BLE scan error occurred:" << error);
    stopScan();
}
