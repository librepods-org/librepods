#ifndef BLUETOOTHMONITOR_H
#define BLUETOOTHMONITOR_H

#include <QObject>
#include <QtDBus/QtDBus>

// Forward declarations for D-Bus types
typedef QMap<QDBusObjectPath, QMap<QString, QVariantMap>> ManagedObjectList;
Q_DECLARE_METATYPE(ManagedObjectList)

class BluetoothMonitor : public QObject, protected QDBusContext
{
    Q_OBJECT
public:
    explicit BluetoothMonitor(QObject *parent = nullptr,
                              const QDBusConnection &connection = QDBusConnection::systemBus());
    ~BluetoothMonitor();

    bool checkAlreadyConnectedDevices();

signals:
    void deviceConnected(const QString &macAddress, const QString &deviceName);
    void deviceDisconnected(const QString &macAddress, const QString &deviceName);

private slots:
    void onPropertiesChanged(const QString &interface, const QVariantMap &changedProps, const QStringList &invalidatedProps);

private:
    friend class BluetoothMonitorTest;

    QDBusConnection m_dbus;
    QHash<QString, QString> m_connectedDevices;
    void registerDBusService();
    bool updateDevice(const QString &devicePath, const QVariantMap &properties, bool forceNotify = false);
};

#endif // BLUETOOTHMONITOR_H
