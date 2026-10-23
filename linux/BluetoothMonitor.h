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
    explicit BluetoothMonitor(QObject *parent = nullptr);
    ~BluetoothMonitor();

    bool checkAlreadyConnectedDevices();

    // Asks BlueZ to connect the first paired AirPods that are not connected yet,
    // the same as connecting them from the system's Bluetooth settings.
    // Returns false when there is no such device.
    bool connectPairedAirPods();

    // Asks BlueZ to disconnect the connected AirPods, the same as disconnecting them from
    // the system's Bluetooth settings. Returns false when no AirPods are connected.
    bool disconnectAirPods();

signals:
    void deviceConnected(const QString &macAddress, const QString &deviceName);
    void deviceDisconnected(const QString &macAddress, const QString &deviceName);
    void connectFailed(const QString &deviceName, const QString &error);
    void disconnectFailed(const QString &deviceName, const QString &error);

private slots:
    void onPropertiesChanged(const QString &interface, const QVariantMap &changedProps, const QStringList &invalidatedProps);

private:
    QDBusConnection m_dbus;
    void registerDBusService();
    bool isAirPodsDevice(const QString &devicePath);
    QString getDeviceName(const QString &devicePath);
};

#endif // BLUETOOTHMONITOR_H