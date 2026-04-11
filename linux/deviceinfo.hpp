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
    Q_PROPERTY(QString earStatusSummary READ earStatusSummary NOTIFY primaryChanged)
    Q_PROPERTY(int noiseControlMode READ noiseControlModeInt WRITE setNoiseControlModeInt NOTIFY noiseControlModeChangedInt)
    Q_PROPERTY(QString noiseControlLabel READ noiseControlLabel NOTIFY noiseControlModeChangedInt)
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
    Q_PROPERTY(QString magicAccIRK READ magicAccIRKHex NOTIFY magicCloudKeysChanged)
    Q_PROPERTY(QString magicAccEncKey READ magicAccEncKeyHex NOTIFY magicCloudKeysChanged)
    Q_PROPERTY(bool hasMagicCloudKeys READ hasMagicCloudKeys NOTIFY magicCloudKeysChanged)
    // New features
    Q_PROPERTY(bool allowOffOption READ allowOffOption WRITE setAllowOffOption NOTIFY allowOffOptionChanged)
    Q_PROPERTY(bool volumeSwipeEnabled READ volumeSwipeEnabled WRITE setVolumeSwipeEnabled NOTIFY volumeSwipeEnabledChanged)
    Q_PROPERTY(int volumeSwipeInterval READ volumeSwipeInterval WRITE setVolumeSwipeInterval NOTIFY volumeSwipeIntervalChanged)
    Q_PROPERTY(bool adaptiveVolumeEnabled READ adaptiveVolumeEnabled WRITE setAdaptiveVolumeEnabled NOTIFY adaptiveVolumeEnabledChanged)
    Q_PROPERTY(bool caseChargingSoundsEnabled READ caseChargingSoundsEnabled WRITE setCaseChargingSoundsEnabled NOTIFY caseChargingSoundsEnabledChanged)
    Q_PROPERTY(int stemLongPressModes READ stemLongPressModes WRITE setStemLongPressModes NOTIFY stemLongPressModesChanged)
    Q_PROPERTY(bool customizeTransparencyEnabled READ customizeTransparencyEnabled WRITE setCustomizeTransparencyEnabled NOTIFY customizeTransparencyEnabledChanged)
    Q_PROPERTY(bool headphoneAccomPhoneEnabled READ headphoneAccomPhoneEnabled WRITE setHeadphoneAccomPhoneEnabled NOTIFY headphoneAccomChanged)
    Q_PROPERTY(bool headphoneAccomMediaEnabled READ headphoneAccomMediaEnabled WRITE setHeadphoneAccomMediaEnabled NOTIFY headphoneAccomChanged)

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
    QString noiseControlLabel() const
    {
        switch (noiseControlMode())
        {
        case NoiseControlMode::Off:
            return QStringLiteral("Off");
        case NoiseControlMode::NoiseCancellation:
            return QStringLiteral("Noise Cancellation");
        case NoiseControlMode::Transparency:
            return QStringLiteral("Transparency");
        case NoiseControlMode::Adaptive:
            return QStringLiteral("Adaptive");
        }

        return QStringLiteral("Unknown");
    }

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
    void setMagicAccIRK(const QByteArray &irk)
    {
        if (m_magicAccIRK != irk)
        {
            m_magicAccIRK = irk;
            emit magicCloudKeysChanged();
        }
    }
    QString magicAccIRKHex() const { return QString::fromUtf8(m_magicAccIRK.toHex()); }

    QByteArray magicAccEncKey() const { return m_magicAccEncKey; }
    void setMagicAccEncKey(const QByteArray &key)
    {
        if (m_magicAccEncKey != key)
        {
            m_magicAccEncKey = key;
            emit magicCloudKeysChanged();
        }
    }
    QString magicAccEncKeyHex() const { return QString::fromUtf8(m_magicAccEncKey.toHex()); }
    bool hasMagicCloudKeys() const { return m_magicAccIRK.size() == 16 && m_magicAccEncKey.size() == 16; }

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
    QString earStatusSummary() const
    {
        auto formatStatus = [](EarDetection::EarDetectionStatus status) -> QString
        {
            switch (status)
            {
            case EarDetection::EarDetectionStatus::InEar:
                return QStringLiteral("in ear");
            case EarDetection::EarDetectionStatus::NotInEar:
                return QStringLiteral("out of ear");
            case EarDetection::EarDetectionStatus::InCase:
                return QStringLiteral("in case");
            case EarDetection::EarDetectionStatus::Disconnected:
            default:
                return QStringLiteral("disconnected");
            }
        };

        const EarDetection::EarDetectionStatus leftStatus =
            getBattery()->getPrimaryPod() == Battery::Component::Left
                ? getEarDetection()->getprimaryStatus()
                : getEarDetection()->getsecondaryStatus();
        const EarDetection::EarDetectionStatus rightStatus =
            getBattery()->getPrimaryPod() == Battery::Component::Right
                ? getEarDetection()->getprimaryStatus()
                : getEarDetection()->getsecondaryStatus();

        if (leftStatus == EarDetection::EarDetectionStatus::Disconnected &&
            rightStatus == EarDetection::EarDetectionStatus::Disconnected)
        {
            return QStringLiteral("Waiting for live status from AirPods");
        }

        return QStringLiteral("Left %1, Right %2")
            .arg(formatStatus(leftStatus), formatStatus(rightStatus));
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
        QStringList parts;

        if (getBattery()->getPrimaryPod() == Battery::Component::Headset)
        {
            if (getBattery()->isHeadsetAvailable())
            {
                parts << QStringLiteral("Headset: %1%").arg(getBattery()->getState(Battery::Component::Headset).level);
            }
        }
        else
        {
            if (getBattery()->isLeftPodAvailable())
            {
                parts << QStringLiteral("Left: %1%").arg(getBattery()->getState(Battery::Component::Left).level);
            }

            if (getBattery()->isRightPodAvailable())
            {
                parts << QStringLiteral("Right: %1%").arg(getBattery()->getState(Battery::Component::Right).level);
            }
        }

        if (getBattery()->isCaseAvailable())
        {
            parts << QStringLiteral("Case: %1%").arg(getBattery()->getState(Battery::Component::Case).level);
        }

        if (parts.isEmpty())
        {
            setBatteryStatus(QStringLiteral("Battery status unavailable"));
        }
        else
        {
            setBatteryStatus(parts.join(QStringLiteral(", ")));
        }
    }

    // New feature getters/setters
    bool allowOffOption() const { return m_allowOffOption; }
    void setAllowOffOption(bool v) { if (m_allowOffOption != v) { m_allowOffOption = v; emit allowOffOptionChanged(v); } }

    bool volumeSwipeEnabled() const { return m_volumeSwipeEnabled; }
    void setVolumeSwipeEnabled(bool v) { if (m_volumeSwipeEnabled != v) { m_volumeSwipeEnabled = v; emit volumeSwipeEnabledChanged(v); } }

    int volumeSwipeInterval() const { return m_volumeSwipeInterval; }
    void setVolumeSwipeInterval(int v) { if (m_volumeSwipeInterval != v) { m_volumeSwipeInterval = v; emit volumeSwipeIntervalChanged(v); } }

    bool adaptiveVolumeEnabled() const { return m_adaptiveVolumeEnabled; }
    void setAdaptiveVolumeEnabled(bool v) { if (m_adaptiveVolumeEnabled != v) { m_adaptiveVolumeEnabled = v; emit adaptiveVolumeEnabledChanged(v); } }

    bool caseChargingSoundsEnabled() const { return m_caseChargingSoundsEnabled; }
    void setCaseChargingSoundsEnabled(bool v) { if (m_caseChargingSoundsEnabled != v) { m_caseChargingSoundsEnabled = v; emit caseChargingSoundsEnabledChanged(v); } }

    int stemLongPressModes() const { return m_stemLongPressModes; }
    void setStemLongPressModes(int v) { if (m_stemLongPressModes != v) { m_stemLongPressModes = v; emit stemLongPressModesChanged(v); } }

    bool customizeTransparencyEnabled() const { return m_customizeTransparencyEnabled; }
    void setCustomizeTransparencyEnabled(bool v) { if (m_customizeTransparencyEnabled != v) { m_customizeTransparencyEnabled = v; emit customizeTransparencyEnabledChanged(v); } }

    bool headphoneAccomPhoneEnabled() const { return m_headphoneAccomPhoneEnabled; }
    void setHeadphoneAccomPhoneEnabled(bool v) { if (m_headphoneAccomPhoneEnabled != v) { m_headphoneAccomPhoneEnabled = v; emit headphoneAccomChanged(); } }

    bool headphoneAccomMediaEnabled() const { return m_headphoneAccomMediaEnabled; }
    void setHeadphoneAccomMediaEnabled(bool v) { if (m_headphoneAccomMediaEnabled != v) { m_headphoneAccomMediaEnabled = v; emit headphoneAccomChanged(); } }

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
    void magicCloudKeysChanged();
    void allowOffOptionChanged(bool enabled);
    void volumeSwipeEnabledChanged(bool enabled);
    void volumeSwipeIntervalChanged(int interval);
    void adaptiveVolumeEnabledChanged(bool enabled);
    void caseChargingSoundsEnabledChanged(bool enabled);
    void stemLongPressModesChanged(int modes);
    void customizeTransparencyEnabledChanged(bool enabled);
    void headphoneAccomChanged();

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
    // New features
    bool m_allowOffOption = false;
    bool m_volumeSwipeEnabled = false;
    int  m_volumeSwipeInterval = 30;
    bool m_adaptiveVolumeEnabled = false;
    bool m_caseChargingSoundsEnabled = true;
    int  m_stemLongPressModes = 0x06; // ANC + Transparency by default
    bool m_customizeTransparencyEnabled = false;
    bool m_headphoneAccomPhoneEnabled = false;
    bool m_headphoneAccomMediaEnabled = false;
};
