#include <QList>
#include <QObject>
#include <QString>
#include <QSystemTrayIcon>

#include "enums.h"

class QMenu;
class QAction;
class QActionGroup;

class TrayIconManager : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool notificationsEnabled READ notificationsEnabled WRITE setNotificationsEnabled NOTIFY notificationsEnabledChanged)

public:
    explicit TrayIconManager(QObject *parent = nullptr);

    // Per-device battery tracking. Devices are keyed by their Bluetooth
    // address so several connected AirPods can be shown at once; the active
    // device (the one the app has an AACP connection to) drives the icon.
    void setActiveDevice(const QString &deviceKey);
    void updateDeviceBattery(const QString &deviceKey, const QString &name, const QString &status);
    void updateDeviceName(const QString &deviceKey, const QString &name);
    void removeDevice(const QString &deviceKey);

    void updateNoiseControlState(AirpodsTrayApp::Enums::NoiseControlMode);

    void updateConversationalAwareness(bool enabled);

    void showNotification(const QString &title, const QString &message);

    bool notificationsEnabled() const { return m_notificationsEnabled; }
    void setNotificationsEnabled(bool enabled)
    {
        if (m_notificationsEnabled != enabled)
        {
            m_notificationsEnabled = enabled;
            emit notificationsEnabledChanged(enabled);
        }
    }

    void resetTrayIcon();

signals:
    void notificationsEnabledChanged(bool enabled);

private slots:
    void onTrayIconActivated(QSystemTrayIcon::ActivationReason reason);

private:
    struct DeviceBatteryEntry
    {
        QString key;    // Bluetooth address of the device
        QString name;   // last known device name
        QString status; // formatted battery status string
    };

    QSystemTrayIcon *trayIcon;
    QMenu *trayMenu;
    QAction *caToggleAction;
    QActionGroup *noiseControlGroup;
    bool m_notificationsEnabled = true;
    QList<DeviceBatteryEntry> m_deviceEntries;
    QString m_activeDeviceKey;

    void setupMenuActions();

    void updateIconFromBattery(const QString &status);

    int findDeviceEntry(const QString &deviceKey) const;
    void renderBatteryTooltip();

signals:
    void trayClicked();
    void noiseControlChanged(AirpodsTrayApp::Enums::NoiseControlMode);
    void conversationalAwarenessToggled(bool enabled);
    void openApp();
    void openSettings();
};
