#pragma once

#include <QObject>
#include <QDBusAbstractAdaptor>
#include <QDBusConnection>
#include <QDBusMessage>
#include "battery.hpp"
#include "deviceinfo.hpp"

class BatteryDBusAdaptor : public QDBusAbstractAdaptor
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "me.kavishdevar.librepods.Battery")

    // Battery levels (0-100)
    Q_PROPERTY(int LeftLevel READ leftLevel NOTIFY BatteryChanged)
    Q_PROPERTY(int RightLevel READ rightLevel NOTIFY BatteryChanged)
    Q_PROPERTY(int CaseLevel READ caseLevel NOTIFY BatteryChanged)
    Q_PROPERTY(int HeadsetLevel READ headsetLevel NOTIFY BatteryChanged)

    // Charging status
    Q_PROPERTY(bool LeftCharging READ leftCharging NOTIFY BatteryChanged)
    Q_PROPERTY(bool RightCharging READ rightCharging NOTIFY BatteryChanged)
    Q_PROPERTY(bool CaseCharging READ caseCharging NOTIFY BatteryChanged)
    Q_PROPERTY(bool HeadsetCharging READ headsetCharging NOTIFY BatteryChanged)

    // Availability (connected/detected)
    Q_PROPERTY(bool LeftAvailable READ leftAvailable NOTIFY BatteryChanged)
    Q_PROPERTY(bool RightAvailable READ rightAvailable NOTIFY BatteryChanged)
    Q_PROPERTY(bool CaseAvailable READ caseAvailable NOTIFY BatteryChanged)
    Q_PROPERTY(bool HeadsetAvailable READ headsetAvailable NOTIFY BatteryChanged)

    // Device info
    Q_PROPERTY(QString DeviceName READ deviceName NOTIFY DeviceChanged)
    Q_PROPERTY(bool Connected READ connected NOTIFY DeviceChanged)

public:
    BatteryDBusAdaptor(Battery *battery, DeviceInfo *deviceInfo, QObject *parent)
        : QDBusAbstractAdaptor(parent), m_battery(battery), m_deviceInfo(deviceInfo)
    {
        setAutoRelaySignals(true);

        // Connect battery signals to our relay
        connect(m_battery, &Battery::batteryStatusChanged, this, [this]() {
            emit BatteryChanged();
        });

        connect(m_deviceInfo, &DeviceInfo::batteryStatusChanged, this, [this]() {
            emit BatteryChanged();
        });

        connect(m_deviceInfo, &DeviceInfo::deviceNameChanged, this, [this]() {
            emit DeviceChanged();
        });
    }

    // Battery levels
    int leftLevel() const { return m_battery->getLeftPodLevel(); }
    int rightLevel() const { return m_battery->getRightPodLevel(); }
    int caseLevel() const { return m_battery->getCaseLevel(); }
    int headsetLevel() const { return m_battery->getHeadsetLevel(); }

    // Charging status
    bool leftCharging() const { return m_battery->isLeftPodCharging(); }
    bool rightCharging() const { return m_battery->isRightPodCharging(); }
    bool caseCharging() const { return m_battery->isCaseCharging(); }
    bool headsetCharging() const { return m_battery->isHeadsetCharging(); }

    // Availability
    bool leftAvailable() const { return m_battery->isLeftPodAvailable(); }
    bool rightAvailable() const { return m_battery->isRightPodAvailable(); }
    bool caseAvailable() const { return m_battery->isCaseAvailable(); }
    bool headsetAvailable() const { return m_battery->isHeadsetAvailable(); }

    // Device info - connected if device name is set and any battery is available
    QString deviceName() const { return m_deviceInfo->deviceName(); }
    bool connected() const {
        return !m_deviceInfo->deviceName().isEmpty() &&
               (leftAvailable() || rightAvailable() || headsetAvailable());
    }

public slots:
    // Method to get all battery info at once (useful for waybar)
    QVariantMap GetBatteryInfo()
    {
        QVariantMap info;
        info["left_level"] = leftLevel();
        info["left_charging"] = leftCharging();
        info["left_available"] = leftAvailable();
        info["right_level"] = rightLevel();
        info["right_charging"] = rightCharging();
        info["right_available"] = rightAvailable();
        info["case_level"] = caseLevel();
        info["case_charging"] = caseCharging();
        info["case_available"] = caseAvailable();
        info["headset_level"] = headsetLevel();
        info["headset_charging"] = headsetCharging();
        info["headset_available"] = headsetAvailable();
        info["device_name"] = deviceName();
        info["connected"] = connected();
        return info;
    }

signals:
    void BatteryChanged();
    void DeviceChanged();

private:
    Battery *m_battery;
    DeviceInfo *m_deviceInfo;
};
