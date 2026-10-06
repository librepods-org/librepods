#include "trayiconmanager.h"

#include <QSystemTrayIcon>
#include <QMenu>
#include <QAction>
#include <QApplication>
#include <QPainter>
#include <QFont>
#include <QColor>
#include <QActionGroup>

using namespace AirpodsTrayApp::Enums;

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

TrayIconManager::TrayIconManager(QObject *parent) : QObject(parent)
{
    trayIcon = new QSystemTrayIcon(QIcon(":/icons/assets/airpods.png"), this);
    trayMenu = new QMenu();

    setupMenuActions();

    trayIcon->setContextMenu(trayMenu);
    connect(trayIcon, &QSystemTrayIcon::activated, this, &TrayIconManager::onTrayIconActivated);

    trayIcon->show();
}

// ---------------------------------------------------------------------------
// Per-device registry helpers
// ---------------------------------------------------------------------------

int TrayIconManager::findDevice(const QString &key) const
{
    for (int i = 0; i < m_devices.size(); ++i)
        if (m_devices[i].key == key)
            return i;
    return -1;
}

void TrayIconManager::updateDeviceBattery(const QString &key,
                                           const QString &name,
                                           const QString &status)
{
    if (key.isEmpty() || status.isEmpty())
        return;

    int idx = findDevice(key);
    if (idx == -1) {
        // New device – append it
        DeviceEntry entry;
        entry.key    = key;
        entry.name   = name.isEmpty() ? tr("AirPods") : name;
        entry.status = status;
        m_devices.append(entry);
    } else {
        if (!name.isEmpty())
            m_devices[idx].name = name;
        m_devices[idx].status = status;
    }

    // If no active device is set yet, promote this one
    if (m_activeDeviceKey.isEmpty())
        m_activeDeviceKey = key;

    // Update numeric icon only for the active device
    if (key == m_activeDeviceKey)
        updateIconFromBattery(status);

    renderTooltip();
}

void TrayIconManager::updateDeviceName(const QString &key, const QString &name)
{
    if (key.isEmpty() || name.isEmpty())
        return;

    int idx = findDevice(key);
    if (idx != -1)
        m_devices[idx].name = name;

    renderTooltip();
}

void TrayIconManager::setActiveDevice(const QString &key)
{
    if (m_activeDeviceKey == key)
        return;

    m_activeDeviceKey = key;

    int idx = findDevice(key);
    if (idx != -1)
        updateIconFromBattery(m_devices[idx].status);

    renderTooltip();
}

void TrayIconManager::removeDevice(const QString &key)
{
    int idx = findDevice(key);
    if (idx == -1)
        return;

    m_devices.removeAt(idx);

    // If we removed the active device, promote the first remaining one (if any)
    if (m_activeDeviceKey == key) {
        m_activeDeviceKey.clear();
        if (!m_devices.isEmpty()) {
            m_activeDeviceKey = m_devices.first().key;
            updateIconFromBattery(m_devices.first().status);
        } else {
            trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
        }
    }

    renderTooltip();
}

// ---------------------------------------------------------------------------
// Tooltip rendering
// ---------------------------------------------------------------------------

void TrayIconManager::renderTooltip()
{
    if (m_devices.isEmpty()) {
        trayIcon->setToolTip("");
        return;
    }

    if (m_devices.size() == 1) {
        // Preserve original single-device format for existing users
        trayIcon->setToolTip(tr("Battery Status: ") + m_devices.first().status);
        return;
    }

    // Multi-device: one line per device, active first
    QStringList lines;
    // Active device first
    for (const DeviceEntry &e : m_devices) {
        if (e.key == m_activeDeviceKey) {
            QString label = e.name.isEmpty() ? tr("AirPods") : e.name;
            lines.prepend(label + ": " + e.status);
        } else {
            QString label = e.name.isEmpty() ? tr("AirPods") : e.name;
            lines.append(label + ": " + e.status);
        }
    }
    trayIcon->setToolTip(lines.join("\n"));
}

// ---------------------------------------------------------------------------
// Backwards-compat wrapper (single-device path)
// ---------------------------------------------------------------------------

void TrayIconManager::updateBatteryStatus(const QString &status)
{
    updateDeviceBattery("default", "", status);
}

// ---------------------------------------------------------------------------
// Noise control / CA
// ---------------------------------------------------------------------------

void TrayIconManager::updateNoiseControlState(NoiseControlMode mode)
{
    QList<QAction *> actions = noiseControlGroup->actions();
    for (QAction *action : actions)
        action->setChecked(action->data().toInt() == (int)mode);
}

void TrayIconManager::updateConversationalAwareness(bool enabled)
{
    caToggleAction->setChecked(enabled);
}

// ---------------------------------------------------------------------------
// Notification
// ---------------------------------------------------------------------------

void TrayIconManager::showNotification(const QString &title, const QString &message)
{
    if (!m_notificationsEnabled)
        return;
    trayIcon->showMessage(title, message, QSystemTrayIcon::Information, 3000);
}

// ---------------------------------------------------------------------------
// Menu setup
// ---------------------------------------------------------------------------

void TrayIconManager::setupMenuActions()
{
    QAction *openAction = new QAction(tr("Open"), trayMenu);
    trayMenu->addAction(openAction);
    connect(openAction, &QAction::triggered, qApp, [this]() { emit openApp(); });

    QAction *settingsMenu = new QAction(tr("Settings"), trayMenu);
    trayMenu->addAction(settingsMenu);
    connect(settingsMenu, &QAction::triggered, qApp, [this]() { emit openSettings(); });

    trayMenu->addSeparator();

    caToggleAction = new QAction(tr("Toggle Conversational Awareness"), trayMenu);
    caToggleAction->setCheckable(true);
    trayMenu->addAction(caToggleAction);
    connect(caToggleAction, &QAction::triggered, this,
            [this](bool checked) { emit conversationalAwarenessToggled(checked); });

    trayMenu->addSeparator();

    noiseControlGroup = new QActionGroup(trayMenu);
    const QPair<QString, NoiseControlMode> noiseOptions[] = {
        {tr("Adaptive"),           NoiseControlMode::Adaptive},
        {tr("Transparency"),       NoiseControlMode::Transparency},
        {tr("Noise Cancellation"), NoiseControlMode::NoiseCancellation},
        {tr("Off"),                NoiseControlMode::Off}};

    for (auto option : noiseOptions) {
        QAction *action = new QAction(option.first, trayMenu);
        action->setCheckable(true);
        action->setData((int)option.second);
        noiseControlGroup->addAction(action);
        trayMenu->addAction(action);
        connect(action, &QAction::triggered, this,
                [this, mode = option.second]() { emit noiseControlChanged(mode); });
    }

    trayMenu->addSeparator();

    QAction *quitAction = new QAction(tr("Quit"), trayMenu);
    trayMenu->addAction(quitAction);
    connect(quitAction, &QAction::triggered, qApp, &QApplication::quit);
}

// ---------------------------------------------------------------------------
// Icon painter (unchanged logic — feeds only the active device's status)
// ---------------------------------------------------------------------------

void TrayIconManager::updateIconFromBattery(const QString &status)
{
    int leftLevel  = 0;
    int rightLevel = 0;
    int minLevel   = 0;

    if (!status.isEmpty()) {
        QStringList parts = status.split(", ");
        if (parts.size() >= 2) {
            leftLevel  = parts[0].split(": ")[1].replace("%", "").toInt();
            rightLevel = parts[1].split(": ")[1].replace("%", "").toInt();
            minLevel   = (leftLevel == 0) ? rightLevel
                       : (rightLevel == 0) ? leftLevel
                       : qMin(leftLevel, rightLevel);
        } else if (parts.size() == 1) {
            minLevel = parts[0].split(": ")[1].replace("%", "").toInt();
        }
    }

    QPixmap pixmap(32, 32);
    pixmap.fill(Qt::transparent);
    QPainter painter(&pixmap);
    painter.setPen(Qt::white);
    painter.setFont(QFont("Arial", 12, QFont::Bold));
    painter.drawText(pixmap.rect(), Qt::AlignCenter, QString::number(minLevel) + "%");
    painter.end();

    trayIcon->setIcon(QIcon(pixmap));
}

// ---------------------------------------------------------------------------
// Tray click
// ---------------------------------------------------------------------------

void TrayIconManager::onTrayIconActivated(QSystemTrayIcon::ActivationReason reason)
{
    if (reason == QSystemTrayIcon::Trigger)
        emit trayClicked();
}
