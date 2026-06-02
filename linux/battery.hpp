#pragma once

#include <QByteArray>
#include <QMap>
#include <QString>
#include <QObject>
#include <climits>

#include "airpods_packets.h"
#include "logger.h"

class Battery : public QObject
{
    Q_OBJECT

    Q_PROPERTY(quint8 leftPodLevel READ getLeftPodLevel NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool leftPodCharging READ isLeftPodCharging NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool leftPodAvailable READ isLeftPodAvailable NOTIFY batteryStatusChanged)
    Q_PROPERTY(quint8 rightPodLevel READ getRightPodLevel NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool rightPodCharging READ isRightPodCharging NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool rightPodAvailable READ isRightPodAvailable NOTIFY batteryStatusChanged)
    Q_PROPERTY(quint8 headsetLevel READ getHeadsetLevel NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool headsetCharging READ isHeadsetCharging NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool headsetAvailable READ isHeadsetAvailable NOTIFY batteryStatusChanged)
    Q_PROPERTY(quint8 caseLevel READ getCaseLevel NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool caseCharging READ isCaseCharging NOTIFY batteryStatusChanged)
    Q_PROPERTY(bool caseAvailable READ isCaseAvailable NOTIFY batteryStatusChanged)

public:
    explicit Battery(QObject *parent = nullptr) : QObject(parent)
    {
        reset();
    }

    void reset()
    {
        // Initialize all components to unknown state
        states[Component::Headset] = {};
        states[Component::Left] = {};
        states[Component::Right] = {};
        states[Component::Case] = {};
        emit batteryStatusChanged();
    }

    // Enum for AirPods components
    enum class Component
    {
        Headset = 0x01, // AirPods Max
        Right = 0x02,
        Left = 0x04,
        Case = 0x08,
    };
    Q_ENUM(Component)

    enum class BatteryStatus
    {
        Charging = 0x01,
        Discharging = 0x02,
        Disconnected = 0x04,
    };
    Q_ENUM(BatteryStatus)

    // Struct to hold battery level and status
    struct BatteryState
    {
        quint8 level = 0; // Battery level (0-100), 0 if unknown
        BatteryStatus status = BatteryStatus::Disconnected;
    };

    // Parse the battery status packet and detect primary/secondary pods
    bool parsePacket(const QByteArray &packet)
    {
        if (!packet.startsWith(AirPodsPackets::Parse::BATTERY_STATUS))
        {
            return false;
        }

        // Get battery count (number of components)
        quint8 batteryCount = static_cast<quint8>(packet[6]);
        if (batteryCount > 3 || packet.size() != 7 + 5 * batteryCount)
        {
            return false; // Invalid count or size mismatch
        }

        QMap<Component, BatteryState> newStates = states;

        // Track pods to determine primary and secondary based on order
        QList<Component> podsInPacket;
        podsInPacket.reserve(2);

        for (quint8 i = 0; i < batteryCount; ++i)
        {
            int offset = 7 + (5 * i);
            quint8 type = static_cast<quint8>(packet[offset]);

            // Verify spacer and end bytes
            if (static_cast<quint8>(packet[offset + 1]) != 0x01 ||
                static_cast<quint8>(packet[offset + 4]) != 0x01)
            {
                return false;
            }

            Component comp = static_cast<Component>(type);
            auto level = static_cast<quint8>(packet[offset + 2]);
            auto status = static_cast<BatteryStatus>(packet[offset + 3]);

            if (status != BatteryStatus::Disconnected)
            {
                newStates[comp] = {level, status};
            }

            // If this is a pod (Left or Right), add it to the list
            if (comp == Component::Left || comp == Component::Right || comp == Component::Headset)
            {
                podsInPacket.append(comp);
            }
        }

        // Update states
        states = newStates;

        // Set primary and secondary pods based on order
        if (!podsInPacket.isEmpty())
        {
            if (podsInPacket.count() == 1 && podsInPacket[0] == Component::Headset) {
                // AirPods Max
                primaryPod = podsInPacket[0];
                emit primaryChanged();
            } else {
                Component newPrimaryPod = podsInPacket[0]; // First pod is primary
                if (newPrimaryPod != primaryPod)
                {
                    primaryPod = newPrimaryPod;
                    emit primaryChanged();
                }
            }
        }
        if (podsInPacket.size() >= 2)
        {
            secondaryPod = podsInPacket[1]; // Second pod is secondary
        }

        // Emit signal to notify about battery status change
        emit batteryStatusChanged();

        if (primaryPod == Component::Headset) {
            LOG_INFO("Primary Pod:" << primaryPod);
        } else {
            // Log which is left and right pod
            LOG_INFO("Primary Pod:" << primaryPod);
            LOG_INFO("Secondary Pod:" << secondaryPod);
        }

        return true;
    }

    bool parseEncryptedPacket(const QByteArray &packet, bool isLeftPodPrimary, bool podInCase, bool isHeadset)
    {
        // Validate packet size (expect 16 bytes based on provided payloads)
        if (packet.size() != 16)
        {
            return false;
        }

        // Determine byte indices based on isFlipped
        int leftByteIndex = isLeftPodPrimary ? 1 : 2;
        int rightByteIndex = isLeftPodPrimary ? 2 : 1;

        // Extract raw battery bytes
        unsigned char rawLeftBatteryByte = static_cast<unsigned char>(packet.at(leftByteIndex));
        unsigned char rawRightBatteryByte = static_cast<unsigned char>(packet.at(rightByteIndex));
        unsigned char rawCaseBatteryByte = static_cast<unsigned char>(packet.at(3));

        // Extract battery data (charging status and raw level 0-127)
        auto [isLeftCharging, rawLeftBattery] = formatBattery(rawLeftBatteryByte);
        auto [isRightCharging, rawRightBattery] = formatBattery(rawRightBatteryByte);
        auto [isCaseCharging, rawCaseBattery] = formatBattery(rawCaseBatteryByte);
        if (isHeadset) {
            int batteries[] = {rawLeftBattery, rawRightBattery, rawCaseBattery};
            bool statuses[] = {isLeftCharging, isRightCharging, isCaseCharging};
            // Find the first battery that isn't CHAR_MAX
            auto it = std::find_if(std::begin(batteries), std::end(batteries), [](int i) { return i != CHAR_MAX; });
            if (it != std::end(batteries)) {
                std::size_t idx = it - std::begin(batteries);
                int battery = *it;
                primaryPod = Component::Headset;
                states[Component::Headset] =  {static_cast<quint8>(battery), statuses[idx] ? BatteryStatus::Charging : BatteryStatus::Discharging};
            }
        } else {
            if (rawLeftBattery == CHAR_MAX) {
                rawLeftBattery = states.value(Component::Left).level; // Use last valid level
                isLeftCharging = states.value(Component::Left).status == BatteryStatus::Charging;
            }

            if (rawRightBattery == CHAR_MAX) {
                rawRightBattery = states.value(Component::Right).level; // Use last valid level
                isRightCharging = states.value(Component::Right).status == BatteryStatus::Charging;
            }

            if (rawCaseBattery == CHAR_MAX) {
                rawCaseBattery = states.value(Component::Case).level; // Use last valid level
                isCaseCharging = states.value(Component::Case).status == BatteryStatus::Charging;
            }

            // Update states
            states[Component::Left] = {static_cast<quint8>(rawLeftBattery), isLeftCharging ? BatteryStatus::Charging : BatteryStatus::Discharging};
            states[Component::Right] = {static_cast<quint8>(rawRightBattery), isRightCharging ? BatteryStatus::Charging : BatteryStatus::Discharging};
            if (podInCase) {
                states[Component::Case] = {static_cast<quint8>(rawCaseBattery), isCaseCharging ? BatteryStatus::Charging : BatteryStatus::Discharging};
            }
            primaryPod = isLeftPodPrimary ? Component::Left : Component::Right;
            secondaryPod = isLeftPodPrimary ? Component::Right : Component::Left;
        }
        emit batteryStatusChanged();
        emit primaryChanged();

        return true;
    }

    // Get the raw state for a component
    BatteryState getState(Component comp) const
    {
        return states.value(comp, {});
    }

    // Get a formatted status string including charging state
    QString getComponentStatus(Component comp) const
    {
        BatteryState state = getState(comp);
        if (state.level == 0)
        {
            return "Unknown";
        }

        QString statusStr;
        switch (state.status)
        {
        case BatteryStatus::Charging:
            statusStr = "Charging";
            break;
        case BatteryStatus::Discharging:
            statusStr = "Discharging";
            break;
        case BatteryStatus::Disconnected:
            statusStr = "Disconnected";
            break;
        default:
            statusStr = "Invalid";
            break;
        }
        return QString("%1% (%2)").arg(state.level).arg(statusStr);
    }

    Component getPrimaryPod() const { return primaryPod; }
    Component getSecondaryPod() const { return secondaryPod; }

    quint8 getLeftPodLevel() const { return states.value(Component::Left).level; }
    bool isLeftPodCharging() const { return isStatus(Component::Left, BatteryStatus::Charging); }
    bool isLeftPodAvailable() const { return !isStatus(Component::Left, BatteryStatus::Disconnected); }
    quint8 getRightPodLevel() const { return states.value(Component::Right).level; }
    bool isRightPodCharging() const { return isStatus(Component::Right, BatteryStatus::Charging); }
    bool isRightPodAvailable() const { return !isStatus(Component::Right, BatteryStatus::Disconnected); }
    quint8 getCaseLevel() const { return states.value(Component::Case).level; }
    bool isCaseCharging() const { return isStatus(Component::Case, BatteryStatus::Charging); }
    bool isCaseAvailable() const { return !isStatus(Component::Case, BatteryStatus::Disconnected); }
    quint8 getHeadsetLevel() const { return states.value(Component::Headset).level; }
    bool isHeadsetCharging() const { return isStatus(Component::Headset, BatteryStatus::Charging); }
    bool isHeadsetAvailable() const { return !isStatus(Component::Headset, BatteryStatus::Disconnected); }

signals:
    void batteryStatusChanged();
    void primaryChanged();

private:
    bool isStatus(Component component, BatteryStatus status) const
    {
        return states.value(component).status == status;
    }

    std::pair<bool, int> formatBattery(unsigned char byteVal)
    {
        bool charging = (byteVal & 0x80) != 0;
        int level = byteVal & 0x7F;
        return std::make_pair(charging, level);
    }

    QMap<Component, BatteryState> states;
    Component primaryPod;
    Component secondaryPod;
};
