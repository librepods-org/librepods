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

TrayIconManager::TrayIconManager(QObject *parent) : QObject(parent)
{
    // Initialize tray icon
    trayIcon = new QSystemTrayIcon(QIcon(":/icons/assets/airpods.png"), this);
    trayMenu = new QMenu();

    // Setup basic menu actions
    setupMenuActions();

    // Connect signals
    trayIcon->setContextMenu(trayMenu);
    connect(trayIcon, &QSystemTrayIcon::activated, this, &TrayIconManager::onTrayIconActivated);

    trayIcon->show();
}

void TrayIconManager::showNotification(const QString &title, const QString &message)
{
    if (!m_notificationsEnabled)
        return;
    trayIcon->showMessage(title, message, QSystemTrayIcon::Information, 3000);
}

void TrayIconManager::setActiveDevice(const QString &deviceKey)
{
    if (m_activeDeviceKey == deviceKey)
        return;

    m_activeDeviceKey = deviceKey;

    int index = findDeviceEntry(deviceKey);
    if (index >= 0)
    {
        updateIconFromBattery(m_deviceEntries.at(index).status);
    }
    else
    {
        // No battery data for this device yet; don't keep showing the
        // previous device's level on the icon
        trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
    }
    renderBatteryTooltip();
}

void TrayIconManager::updateDeviceBattery(const QString &deviceKey, const QString &name, const QString &status)
{
    if (deviceKey.isEmpty() || status.isEmpty())
        return;

    int index = findDeviceEntry(deviceKey);
    if (index < 0)
    {
        m_deviceEntries.append({deviceKey, name, status});
    }
    else
    {
        if (!name.isEmpty())
            m_deviceEntries[index].name = name;
        m_deviceEntries[index].status = status;
    }

    if (deviceKey == m_activeDeviceKey)
        updateIconFromBattery(status);
    renderBatteryTooltip();
}

void TrayIconManager::updateDeviceName(const QString &deviceKey, const QString &name)
{
    int index = findDeviceEntry(deviceKey);
    if (index < 0 || name.isEmpty() || m_deviceEntries.at(index).name == name)
        return;

    m_deviceEntries[index].name = name;
    renderBatteryTooltip();
}

void TrayIconManager::removeDevice(const QString &deviceKey)
{
    int index = findDeviceEntry(deviceKey);
    if (index < 0)
        return;

    m_deviceEntries.removeAt(index);

    if (deviceKey == m_activeDeviceKey)
    {
        m_activeDeviceKey.clear();
        trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
    }
    renderBatteryTooltip();
}

void TrayIconManager::resetTrayIcon()
{
    m_deviceEntries.clear();
    m_activeDeviceKey.clear();
    trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
    trayIcon->setToolTip("");
}

int TrayIconManager::findDeviceEntry(const QString &deviceKey) const
{
    for (int i = 0; i < m_deviceEntries.size(); ++i)
    {
        if (m_deviceEntries.at(i).key == deviceKey)
            return i;
    }
    return -1;
}

void TrayIconManager::renderBatteryTooltip()
{
    if (m_deviceEntries.isEmpty())
    {
        trayIcon->setToolTip("");
        return;
    }

    if (m_deviceEntries.size() == 1)
    {
        // Keep the familiar single-device format
        trayIcon->setToolTip(tr("Battery Status: ") + m_deviceEntries.first().status);
        return;
    }

    // Multiple devices: one line per device, active device first
    QStringList lines;
    auto appendEntry = [&lines, this](const DeviceBatteryEntry &entry)
    {
        const QString label = entry.name.isEmpty() ? tr("AirPods") : entry.name;
        lines.append(label + ": " + entry.status);
    };

    const int activeIndex = findDeviceEntry(m_activeDeviceKey);
    if (activeIndex >= 0)
        appendEntry(m_deviceEntries.at(activeIndex));
    for (int i = 0; i < m_deviceEntries.size(); ++i)
    {
        if (i != activeIndex)
            appendEntry(m_deviceEntries.at(i));
    }

    trayIcon->setToolTip(lines.join(QStringLiteral("\n")));
}

void TrayIconManager::updateNoiseControlState(NoiseControlMode mode)
{
    QList<QAction *> actions = noiseControlGroup->actions();
    for (QAction *action : actions)
    {
        action->setChecked(action->data().toInt() == (int)mode);
    }
}

void TrayIconManager::updateConversationalAwareness(bool enabled)
{
    caToggleAction->setChecked(enabled);
}

void TrayIconManager::setupMenuActions()
{
    // Open action
    QAction *openAction = new QAction(tr("Open"), trayMenu);
    trayMenu->addAction(openAction);
    connect(openAction, &QAction::triggered, qApp, [this](){emit openApp();});

    // Settings Menu

    QAction *settingsMenu = new QAction(tr("Settings"), trayMenu);
    trayMenu->addAction(settingsMenu);
    connect(settingsMenu, &QAction::triggered, qApp, [this](){emit openSettings();});

    trayMenu->addSeparator();

    // Conversational Awareness Toggle
    caToggleAction = new QAction(tr("Toggle Conversational Awareness"), trayMenu);
    caToggleAction->setCheckable(true);
    trayMenu->addAction(caToggleAction);
    connect(caToggleAction, &QAction::triggered, this, [this](bool checked)
            { emit conversationalAwarenessToggled(checked); });

    trayMenu->addSeparator();

    // Noise Control Options
    noiseControlGroup = new QActionGroup(trayMenu);
    const QPair<QString, NoiseControlMode> noiseOptions[] = {
        {tr("Adaptive"), NoiseControlMode::Adaptive},
        {tr("Transparency"), NoiseControlMode::Transparency},
        {tr("Noise Cancellation"), NoiseControlMode::NoiseCancellation},
        {tr("Off"), NoiseControlMode::Off}};

    for (auto option : noiseOptions)
    {
        QAction *action = new QAction(option.first, trayMenu);
        action->setCheckable(true);
        action->setData((int)option.second);
        noiseControlGroup->addAction(action);
        trayMenu->addAction(action);
        connect(action, &QAction::triggered, this, [this, mode = option.second]()
                { emit noiseControlChanged(mode); });
    }

    trayMenu->addSeparator();

    // Quit action
    QAction *quitAction = new QAction(tr("Quit"), trayMenu);
    trayMenu->addAction(quitAction);
    connect(quitAction, &QAction::triggered, qApp, &QApplication::quit);
}

void TrayIconManager::updateIconFromBattery(const QString &status)
{
    int leftLevel = 0;
    int rightLevel = 0;
    int minLevel = 0;

    if (!status.isEmpty())
    {
        // Parse the battery status string
        QStringList parts = status.split(", ");
        if (parts.size() >= 2) {
            leftLevel = parts[0].split(": ")[1].replace("%", "").toInt();
            rightLevel = parts[1].split(": ")[1].replace("%", "").toInt();
            minLevel = (leftLevel == 0) ? rightLevel : (rightLevel == 0) ? leftLevel
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

void TrayIconManager::onTrayIconActivated(QSystemTrayIcon::ActivationReason reason)
{
    if (reason == QSystemTrayIcon::Trigger)
    {
        emit trayClicked();
    }
}

