// SPDX-License-Identifier: AGPL-3.0-only

#include "BluetoothMonitor.h"
#include "logger.h"

#include <QCoreApplication>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QElapsedTimer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTextStream>
#include <QTimer>

Q_LOGGING_CATEGORY(librepods, "librepods")

int main(int argc, char **argv)
{
  QCoreApplication app(argc, argv);
  QLoggingCategory::setFilterRules(QStringLiteral("librepods.debug=false"));
  QElapsedTimer clock;
  clock.start();
  qint64 lastTick = 0;
  qint64 largestGap = 0;
  int ticks = 0;
  int replies = 0;
  int connected = 0;
  int disconnected = 0;
  QJsonArray deviceEvents;

  BluetoothMonitor monitor;
  QObject::connect(&monitor, &BluetoothMonitor::deviceConnected, &app,
                   [&](const QString &address, const QString &name) {
    ++connected;
    deviceEvents.append(QJsonObject{{QStringLiteral("connected"), true},
                                   {QStringLiteral("address"), address},
                                   {QStringLiteral("name"), name}});
  });
  QObject::connect(&monitor, &BluetoothMonitor::deviceDisconnected, &app,
                   [&](const QString &address, const QString &name) {
    ++disconnected;
    deviceEvents.append(QJsonObject{{QStringLiteral("connected"), false},
                                   {QStringLiteral("address"), address},
                                   {QStringLiteral("name"), name}});
  });

  QTimer heartbeat;
  QObject::connect(&heartbeat, &QTimer::timeout, &app, [&] {
    const qint64 now = clock.elapsed();
    largestGap = qMax(largestGap, now - lastTick);
    lastTick = now;
    ++ticks;
  });
  heartbeat.start(50);

  QTimer report;
  QObject::connect(&report, &QTimer::timeout, &app, [&] {
    const QJsonObject observation{
      {QStringLiteral("qt_version"), QString::fromLatin1(qVersion())},
      {QStringLiteral("elapsed_ms"), clock.elapsed()},
      {QStringLiteral("ticks"), ticks},
      {QStringLiteral("largest_event_gap_ms"), largestGap},
      {QStringLiteral("bus_replies"), replies},
      {QStringLiteral("connected"), connected},
      {QStringLiteral("disconnected"), disconnected},
      {QStringLiteral("device_events"), deviceEvents},
    };
    QTextStream(stdout) << QJsonDocument(observation).toJson(QJsonDocument::Compact)
                        << Qt::endl;
  });
  report.start(500);

  QTimer ping;
  QObject::connect(&ping, &QTimer::timeout, &app, [&] {
    const auto message = QDBusMessage::createMethodCall(
      QStringLiteral("org.freedesktop.DBus"), QStringLiteral("/org/freedesktop/DBus"),
      QStringLiteral("org.freedesktop.DBus"), QStringLiteral("GetId"));
    auto *watcher = new QDBusPendingCallWatcher(
      QDBusConnection::systemBus().asyncCall(message, 2'000), &app);
    QObject::connect(watcher, &QDBusPendingCallWatcher::finished, &app,
                     [&](QDBusPendingCallWatcher *completed) {
      const QDBusPendingReply<QString> reply(*completed);
      if (!reply.isError()) ++replies;
      completed->deleteLater();
    });
  });
  ping.start(500);

  QTimer::singleShot(40'000, &app, &QCoreApplication::quit);
  QTextStream(stdout) << "MONITOR_READY" << Qt::endl;
  return app.exec();
}
