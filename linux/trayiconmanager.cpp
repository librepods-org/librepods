#include "trayiconmanager.h"

#include <QSystemTrayIcon>
#include <QMenu>
#include <QAction>
#include <QApplication>
#include <QPainter>
#include <QFont>
#include <QColor>
#include <QActionGroup>
#include <QRegularExpression>

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

void TrayIconManager::updateBatteryStatus(const QString &status)
{
    trayIcon->setToolTip(tr("Battery Status: ") + status);
    updateIconFromBattery(status);
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

void TrayIconManager::setAirPodsControlsEnabled(bool enabled)
{
    for (QAction *action : m_airPodsControlActions)
    {
        if (action)
        {
            action->setEnabled(enabled);
        }
    }
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
    m_airPodsControlActions.append(caToggleAction);
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
        m_airPodsControlActions.append(action);
        connect(action, &QAction::triggered, this, [this, mode = option.second]()
                { emit noiseControlChanged(mode); });
    }

    setAirPodsControlsEnabled(false);

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
        const QRegularExpression batteryPattern(QStringLiteral("(\\d+)%"));
        QRegularExpressionMatchIterator iterator = batteryPattern.globalMatch(status);
        QList<int> levels;
        while (iterator.hasNext())
        {
            const QRegularExpressionMatch match = iterator.next();
            levels.append(match.captured(1).toInt());
        }

        if (!levels.isEmpty())
        {
            minLevel = levels.first();
            for (int level : levels)
            {
                minLevel = qMin(minLevel, level);
            }
        }
    }

    if (status.isEmpty() || minLevel <= 0)
    {
        trayIcon->setIcon(QIcon(":/icons/assets/airpods.png"));
        return;
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
