#pragma once

#include <QObject>
#include <QSystemTrayIcon>
#include <QList>
#include <QString>

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

    // ------------------------------------------------------------------
    // Per-device battery API (replaces the old single updateBatteryStatus)
    // ------------------------------------------------------------------

    /// Insert-or-update a device entry; also updates the icon and tooltip.
    void updateDeviceBattery(const QString &key, const QString &name, const QString &status);

    /// Refresh the display name of an already-registered device.
    void updateDeviceName(const QString &key, const QString &name);

    /// Mark which device drives the numeric battery icon.
    void setActiveDevice(const QString &key);

    /// Remove a device from the registry (call on disconnect).
    void removeDevice(const QString &key);

    // ------------------------------------------------------------------
    // Kept for backwards-compat — wraps updateDeviceBattery with a
    // synthetic key ("default") so single-device users see no change.
    // ------------------------------------------------------------------
    void updateBatteryStatus(const QString &status);

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

    void resetTrayIcon()
    {
        m_devices.clear();
        m_activeDeviceKey.clear();
        trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
        trayIcon->setToolTip("");
    }

signals:
    void notificationsEnabledChanged(bool enabled);
    void trayClicked();
    void noiseControlChanged(AirpodsTrayApp::Enums::NoiseControlMode);
    void conversationalAwarenessToggled(bool enabled);
    void openApp();
    void openSettings();

private slots:
    void onTrayIconActivated(QSystemTrayIcon::ActivationReason reason);

private:
    // Per-device record ------------------------------------------------
    struct DeviceEntry {
        QString key;    ///< Bluetooth address (authoritative ID)
        QString name;   ///< Human-readable label, e.g. "AirPods Pro 2"
        QString status; ///< Formatted battery string, e.g. "L: 80%, R: 100%, C: --%"
    };

    QList<DeviceEntry> m_devices;
    QString            m_activeDeviceKey;

    // Helpers ----------------------------------------------------------
    int  findDevice(const QString &key) const; ///< Returns index or -1
    void renderTooltip();
    void updateIconFromBattery(const QString &status);

    // Qt widgets -------------------------------------------------------
    QSystemTrayIcon *trayIcon;
    QMenu           *trayMenu;
    QAction         *caToggleAction;
    QActionGroup    *noiseControlGroup;
    bool             m_notificationsEnabled = true;

    void setupMenuActions();
};