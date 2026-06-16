#pragma once

#include <QObject>
#include <QByteArray>
#include <QSettings>
#include "battery.hpp"
#include "enums.h"
#include "eardetection.hpp"

using namespace AirpodsTrayApp::Enums;

class DeviceInfo : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString batteryStatus READ batteryStatus WRITE setBatteryStatus NOTIFY batteryStatusChanged)
    Q_PROPERTY(int noiseControlMode READ noiseControlModeInt WRITE setNoiseControlModeInt NOTIFY noiseControlModeChangedInt)
    Q_PROPERTY(bool conversationalAwareness READ conversationalAwareness WRITE setConversationalAwareness NOTIFY conversationalAwarenessChanged)
    Q_PROPERTY(bool hearingAidEnabled READ hearingAidEnabled WRITE setHearingAidEnabled NOTIFY hearingAidEnabledChanged)
    Q_PROPERTY(int adaptiveNoiseLevel READ adaptiveNoiseLevel WRITE setAdaptiveNoiseLevel NOTIFY adaptiveNoiseLevelChanged)
    Q_PROPERTY(QString deviceName READ deviceName WRITE setDeviceName NOTIFY deviceNameChanged)
    Q_PROPERTY(Battery *battery READ getBattery CONSTANT)
    Q_PROPERTY(bool oneBudANCMode READ oneBudANCMode WRITE setOneBudANCMode NOTIFY oneBudANCModeChanged)
    Q_PROPERTY(AirPodsModel model READ model WRITE setModel NOTIFY modelChanged)
    Q_PROPERTY(bool adaptiveModeActive READ adaptiveModeActive NOTIFY noiseControlModeChangedInt)
    Q_PROPERTY(QString podIcon READ podIcon NOTIFY modelChanged)
    Q_PROPERTY(QString caseIcon READ caseIcon NOTIFY modelChanged)
    Q_PROPERTY(bool leftPodInEar READ isLeftPodInEar NOTIFY primaryChanged)
    Q_PROPERTY(bool rightPodInEar READ isRightPodInEar NOTIFY primaryChanged)
    Q_PROPERTY(QString bluetoothAddress READ bluetoothAddress WRITE setBluetoothAddress NOTIFY bluetoothAddressChanged)
    Q_PROPERTY(QString magicAccIRK READ magicAccIRKHex CONSTANT)
    Q_PROPERTY(QString magicAccEncKey READ magicAccEncKeyHex CONSTANT)

public:
    explicit DeviceInfo(QObject *parent = nullptr) : QObject(parent), m_battery(new Battery(this)), m_earDetection(new EarDetection(this)) {
        connect(getEarDetection(), &EarDetection::statusChanged, this, &DeviceInfo::primaryChanged);
    }

    QString batteryStatus() const { return m_batteryStatus; }
    void setBatteryStatus(const QString &status)
    {
        if (m_batteryStatus != status)
        {
            m_batteryStatus = status;
            emit batteryStatusChanged(status);
        }
    }

    NoiseControlMode noiseControlMode() const { return m_noiseControlMode; }
    void setNoiseControlMode(NoiseControlMode mode)
    {
        if (m_noiseControlMode != mode)
        {
            m_noiseControlMode = mode;
            emit noiseControlModeChanged(mode);
            emit noiseControlModeChangedInt(static_cast<int>(mode));
        }
    }
    int noiseControlModeInt() const { return static_cast<int>(noiseControlMode()); }
    void setNoiseControlModeInt(int mode) { setNoiseControlMode(static_cast<NoiseControlMode>(mode)); }

    bool conversationalAwareness() const { return m_conversationalAwareness; }
    void setConversationalAwareness(bool enabled)
    {
        if (m_conversationalAwareness != enabled)
        {
            m_conversationalAwareness = enabled;
            emit conversationalAwarenessChanged(enabled);
        }
    }

    bool hearingAidEnabled() const { return m_hearingAidEnabled; }
    void setHearingAidEnabled(bool enabled)
    {
        if (m_hearingAidEnabled != enabled)
        {
            m_hearingAidEnabled = enabled;
            emit hearingAidEnabledChanged(enabled);
        }
    }

    int adaptiveNoiseLevel() const { return m_adaptiveNoiseLevel; }
    void setAdaptiveNoiseLevel(int level)
    {
        if (m_adaptiveNoiseLevel != level)
        {
            m_adaptiveNoiseLevel = level;
            emit adaptiveNoiseLevelChanged(level);
        }
    }

    QString deviceName() const { return m_deviceName; }
    void setDeviceName(const QString &name)
    {
        if (m_deviceName != name)
        {
            m_deviceName = name;
            emit deviceNameChanged(name);
        }
    }

    Battery *getBattery() const { return m_battery; }

    bool oneBudANCMode() const { return m_oneBudANCMode; }
    void setOneBudANCMode(bool enabled)
    {
        if (m_oneBudANCMode != enabled)
        {
            m_oneBudANCMode = enabled;
            emit oneBudANCModeChanged(enabled);
        }
    }

    AirPodsModel model() const { return m_model; }
    void setModel(AirPodsModel model)
    {
        if (m_model != model)
        {
            m_model = model;
            emit modelChanged();
        }
    }

    QByteArray magicAccIRK() const { return m_magicAccIRK; }
    void setMagicAccIRK(const QByteArray &irk) { m_magicAccIRK = irk; }
    QString magicAccIRKHex() const { return QString::fromUtf8(m_magicAccIRK.toHex()); }

    QByteArray magicAccEncKey() const { return m_magicAccEncKey; }
    void setMagicAccEncKey(const QByteArray &key) { m_magicAccEncKey = key; }
    QString magicAccEncKeyHex() const { return QString::fromUtf8(m_magicAccEncKey.toHex()); }

    QString modelNumber() const { return m_modelNumber; }
    void setModelNumber(const QString &modelNumber) { m_modelNumber = modelNumber; }

    QString manufacturer() const { return m_manufacturer; }
    void setManufacturer(const QString &manufacturer) { m_manufacturer = manufacturer; }

    QString bluetoothAddress() const { return m_bluetoothAddress; }
    void setBluetoothAddress(const QString &address)
    {
        if (m_bluetoothAddress != address)
        {
            m_bluetoothAddress = address;
            emit bluetoothAddressChanged(address);
        }
    }

    QString podIcon() const { return getModelIcon(model()).first; }
    QString caseIcon() const { return getModelIcon(model()).second; }
    bool isLeftPodInEar() const
    {
        if (getBattery()->getPrimaryPod() == Battery::Component::Left) return getEarDetection()->isPrimaryInEar();
        else return getEarDetection()->isSecondaryInEar();
    }
    bool isRightPodInEar() const
    {
        if (getBattery()->getPrimaryPod() == Battery::Component::Right) return getEarDetection()->isPrimaryInEar();
        else return getEarDetection()->isSecondaryInEar();
    }

    bool adaptiveModeActive() const { return noiseControlMode() == NoiseControlMode::Adaptive; }

    EarDetection *getEarDetection() const { return m_earDetection; }

    void reset()
    {
        setDeviceName("");
        setModel(AirPodsModel::Unknown);
        m_battery->reset();
        setBatteryStatus("");
        setNoiseControlMode(NoiseControlMode::Off);
        setBluetoothAddress("");
        getEarDetection()->reset();
        setHearingAidEnabled(false);
    }

    void saveToSettings(QSettings &settings)
    {
        settings.beginGroup("DeviceInfo");
        settings.setValue("deviceName", deviceName());
        settings.setValue("model", static_cast<int>(model()));
        settings.setValue("magicAccIRK", magicAccIRK());
        settings.setValue("magicAccEncKey", magicAccEncKey());
        settings.setValue("hearingAidEnabled", hearingAidEnabled());
        settings.endGroup();
    }
    void loadFromSettings(const QSettings &settings)
    {
        setDeviceName(settings.value("DeviceInfo/deviceName", "").toString());
        setModel(static_cast<AirPodsModel>(settings.value("DeviceInfo/model", (int)(AirPodsModel::Unknown)).toInt()));
        setMagicAccIRK(settings.value("DeviceInfo/magicAccIRK", QByteArray()).toByteArray());
        setMagicAccEncKey(settings.value("DeviceInfo/magicAccEncKey", QByteArray()).toByteArray());
        setHearingAidEnabled(settings.value("DeviceInfo/hearingAidEnabled", false).toBool());
    }

    void updateBatteryStatus()
    {
        int leftLevel = getBattery()->getState(Battery::Component::Left).level;
        int rightLevel = getBattery()->getState(Battery::Component::Right).level;
        int caseLevel = getBattery()->getState(Battery::Component::Case).level;
        if (getBattery()->getPrimaryPod() == Battery::Component::Headset) {
            int headsetLevel = getBattery()->getState(Battery::Component::Headset).level;
            setBatteryStatus(QString("Headset: %1%").arg(headsetLevel));
        } else {
            setBatteryStatus(QString("Left: %1%, Right: %2%, Case: %3%").arg(leftLevel).arg(rightLevel).arg(caseLevel));
        }
    }

signals:
    void batteryStatusChanged(const QString &status);
    void noiseControlModeChanged(NoiseControlMode mode);
    void noiseControlModeChangedInt(int mode);
    void conversationalAwarenessChanged(bool enabled);
    void hearingAidEnabledChanged(bool enabled);
    void adaptiveNoiseLevelChanged(int level);
    void deviceNameChanged(const QString &name);
    void primaryChanged();
    void oneBudANCModeChanged(bool enabled);
    void modelChanged();
    void bluetoothAddressChanged(const QString &address);

private:
    QString m_batteryStatus;
    NoiseControlMode m_noiseControlMode = NoiseControlMode::Transparency;
    bool m_conversationalAwareness = false;
    bool m_hearingAidEnabled = false;
    int m_adaptiveNoiseLevel = 50;
    QString m_deviceName;
    Battery *m_battery;
    QByteArray m_magicAccIRK;
    QByteArray m_magicAccEncKey;
    bool m_oneBudANCMode = false;
    AirPodsModel m_model = AirPodsModel::Unknown;
    QString m_modelNumber;
    QString m_manufacturer;
    QString m_bluetoothAddress;
    EarDetection *m_earDetection;
};
