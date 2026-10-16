#include "playerstatuswatcher.h"
#include <QDBusConnection>
#include <QDBusPendingReply>
#include <QVariantMap>
#include <QDBusReply>
#include <QDBusConnectionInterface>

PlayerStatusWatcher::PlayerStatusWatcher(const QString &playerService, QObject *parent)
    : QObject(parent),
      m_playerService(playerService),
      m_iface(new QDBusInterface(playerService, "/org/mpris/MediaPlayer2",
                                 "org.mpris.MediaPlayer2.Player", QDBusConnection::sessionBus(), this)),
      m_serviceWatcher(new QDBusServiceWatcher(playerService, QDBusConnection::sessionBus(),
                                               QDBusServiceWatcher::WatchForOwnerChange, this))
{
    QDBusConnection::sessionBus().connect(
        playerService, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties",
        "PropertiesChanged", this, SLOT(onPropertiesChanged(QString,QVariantMap,QStringList))
    );
    connect(m_serviceWatcher, &QDBusServiceWatcher::serviceOwnerChanged,
            this, &PlayerStatusWatcher::onServiceOwnerChanged);
    updateStatus();
}

void PlayerStatusWatcher::onPropertiesChanged(const QString &interface,
                                              const QVariantMap &changed,
                                              const QStringList &)
{
    if (interface == "org.mpris.MediaPlayer2.Player" && changed.contains("PlaybackStatus")) {
        emit playbackStatusChanged(changed.value("PlaybackStatus").toString());
    }
}

void PlayerStatusWatcher::updateStatus() {
    QVariant reply = m_iface->property("PlaybackStatus");
    if (reply.isValid()) {
        emit playbackStatusChanged(reply.toString());
    }
}

void PlayerStatusWatcher::onServiceOwnerChanged(const QString &name, const QString &, const QString &newOwner)
{
    if (name == m_playerService && newOwner.isEmpty()) {
        emit playbackStatusChanged(""); // player disappeared
    } else if (name == m_playerService && !newOwner.isEmpty()) {
        updateStatus(); // player appeared/reappeared
    }
}

QString PlayerStatusWatcher::playbackStatusOf(const QString &playerService)
{
    if (playerService.isEmpty()) {
        return QString();
    }

    QDBusInterface props(playerService, "/org/mpris/MediaPlayer2",
                         "org.freedesktop.DBus.Properties", QDBusConnection::sessionBus());
    if (!props.isValid()) {
        return QString();
    }

    QDBusReply<QVariant> reply = props.call("Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus");
    if (!reply.isValid()) {
        return QString();
    }

    return reply.value().toString();
}

QString PlayerStatusWatcher::getCurrentPlaybackStatus(const QString &playerService)
{
    QDBusConnection bus = QDBusConnection::sessionBus();
    QStringList services = bus.interface()->registeredServiceNames().value();

    for (const QString &service : services) {
        if (service.startsWith("org.mpris.MediaPlayer2.")) {
            if (playbackStatusOf(service) == "Playing") {
                return QStringLiteral("Playing");
            }
        }
    }

    return QString();
}