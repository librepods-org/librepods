#include <QObject>
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

    void updateBatteryStatus(const QString &status);

    void updateNoiseControlState(AirpodsTrayApp::Enums::NoiseControlMode);

    void updateConversationalAwareness(bool enabled);

    // Shows "Connect AirPods" while they are not connected, "Disconnect AirPods" while they are.
    void setAirPodsConnected(bool connected);

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
        trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
        trayIcon->setToolTip("");
    }

signals:
    void notificationsEnabledChanged(bool enabled);

private slots:
    void onTrayIconActivated(QSystemTrayIcon::ActivationReason reason);

private:
    QSystemTrayIcon *trayIcon;
    QMenu *trayMenu;
    QAction *connectAction;
    QAction *disconnectAction;
    QAction *connectSeparator;
    QAction *caToggleAction;
    QActionGroup *noiseControlGroup;
    bool m_notificationsEnabled = true;

    void setupMenuActions();

    void updateIconFromBattery(const QString &status);

signals:
    void trayClicked();
    void noiseControlChanged(AirpodsTrayApp::Enums::NoiseControlMode);
    void conversationalAwarenessToggled(bool enabled);
    void openApp();
    void openSettings();
    void connectRequested();
    void disconnectRequested();
};