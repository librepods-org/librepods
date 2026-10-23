#include "BluetoothMonitor.h"
#include "logger.h"

#include <QDebug>
#include <QDBusObjectPath>
#include <QDBusMetaType>

BluetoothMonitor::BluetoothMonitor(QObject *parent)
    : QObject(parent), m_dbus(QDBusConnection::systemBus())
{
    // Register meta-types for D-Bus interaction
    qDBusRegisterMetaType<QDBusObjectPath>();
    qDBusRegisterMetaType<ManagedObjectList>();

    if (!m_dbus.isConnected())
    {
        LOG_WARN("Failed to connect to system D-Bus");
        return;
    }

    registerDBusService();
    checkAlreadyConnectedDevices(); // Check for already connected devices on startup
}

BluetoothMonitor::~BluetoothMonitor()
{
    m_dbus.disconnectFromBus(m_dbus.name());
}

void BluetoothMonitor::registerDBusService()
{
    // Match signals for PropertiesChanged on any BlueZ Device interface
    if (!m_dbus.connect("", "", "org.freedesktop.DBus.Properties", "PropertiesChanged",
                        this, SLOT(onPropertiesChanged(QString, QVariantMap, QStringList))))
    {
        LOG_WARN("Failed to connect to D-Bus PropertiesChanged signal");
    }
}

bool BluetoothMonitor::isAirPodsDevice(const QString &devicePath)
{
    QDBusInterface deviceInterface("org.bluez", devicePath, "org.freedesktop.DBus.Properties", m_dbus);

    // Get UUIDs to check if it's an AirPods device
    QDBusReply<QVariant> uuidsReply = deviceInterface.call("Get", "org.bluez.Device1", "UUIDs");
    if (!uuidsReply.isValid())
    {
        return false;
    }

    QStringList uuids = uuidsReply.value().toStringList();
    return uuids.contains("74ec2172-0bad-4d01-8f77-997b2be0722a");
}

QString BluetoothMonitor::getDeviceName(const QString &devicePath)
{
    QDBusInterface deviceInterface("org.bluez", devicePath, "org.freedesktop.DBus.Properties", m_dbus);
    QDBusReply<QVariant> nameReply = deviceInterface.call("Get", "org.bluez.Device1", "Name");
    if (nameReply.isValid())
    {
        return nameReply.value().toString();
    }
    return "Unknown";
}

bool BluetoothMonitor::checkAlreadyConnectedDevices()
{
    QDBusInterface objectManager("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", m_dbus);
    QDBusMessage reply = objectManager.call("GetManagedObjects");

    if (reply.type() == QDBusMessage::ErrorMessage)
    {
        LOG_WARN("Failed to get managed objects: " << reply.errorMessage());
        return false;
    }

    QVariant firstArg = reply.arguments().constFirst();
    QDBusArgument arg = firstArg.value<QDBusArgument>();
    ManagedObjectList managedObjects;
    arg >> managedObjects;

    bool deviceFound = false;

    for (auto it = managedObjects.constBegin(); it != managedObjects.constEnd(); ++it)
    {
        const QDBusObjectPath &objPath = it.key();
        const QMap<QString, QVariantMap> &interfaces = it.value();

        if (interfaces.contains("org.bluez.Device1"))
        {
            const QVariantMap &deviceProps = interfaces.value("org.bluez.Device1");

            // Check if the device has the necessary properties
            if (!deviceProps.contains("UUIDs") || !deviceProps.contains("Connected") ||
                !deviceProps.contains("Address") || !deviceProps.contains("Name"))
            {
                continue;
            }

            QStringList uuids = deviceProps["UUIDs"].toStringList();
            bool isAirPods = uuids.contains("74ec2172-0bad-4d01-8f77-997b2be0722a");

            if (isAirPods)
            {
                bool connected = deviceProps["Connected"].toBool();
                if (connected)
                {
                    QString macAddress = deviceProps["Address"].toString();
                    QString deviceName = deviceProps["Name"].toString();
                    emit deviceConnected(macAddress, deviceName);
                    LOG_DEBUG("Found already connected AirPods: " << macAddress << " Name: " << deviceName);
                    deviceFound = true;
                }
            }
        }
    }
    return deviceFound;
}

bool BluetoothMonitor::connectPairedAirPods()
{
    QDBusInterface objectManager("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", m_dbus);
    QDBusMessage reply = objectManager.call("GetManagedObjects");

    if (reply.type() == QDBusMessage::ErrorMessage)
    {
        LOG_WARN("Failed to get managed objects: " << reply.errorMessage());
        return false;
    }

    QDBusArgument arg = reply.arguments().constFirst().value<QDBusArgument>();
    ManagedObjectList managedObjects;
    arg >> managedObjects;

    for (auto it = managedObjects.constBegin(); it != managedObjects.constEnd(); ++it)
    {
        const QVariantMap deviceProps = it.value().value("org.bluez.Device1");
        if (deviceProps.isEmpty() ||
            !deviceProps.value("UUIDs").toStringList().contains("74ec2172-0bad-4d01-8f77-997b2be0722a") ||
            !deviceProps.value("Paired").toBool() || deviceProps.value("Connected").toBool())
        {
            continue;
        }

        const QString path = it.key().path();
        const QString deviceName = deviceProps.value("Name").toString();
        LOG_INFO("Connecting paired AirPods: " << deviceName);

        // Connect() returns once the profiles are up, which can take seconds:
        // call it asynchronously and let PropertiesChanged report the connection.
        QDBusInterface device("org.bluez", path, "org.bluez.Device1", m_dbus);
        auto *watcher = new QDBusPendingCallWatcher(device.asyncCall("Connect"), this);
        connect(watcher, &QDBusPendingCallWatcher::finished, this,
                [this, deviceName](QDBusPendingCallWatcher *call)
                {
                    QDBusPendingReply<> result = *call;
                    if (result.isError())
                    {
                        LOG_WARN("Failed to connect " << deviceName << ": " << result.error().message());
                        emit connectFailed(deviceName, result.error().message());
                    }
                    call->deleteLater();
                });
        return true;
    }

    LOG_INFO("No paired AirPods waiting to be connected");
    return false;
}

bool BluetoothMonitor::disconnectAirPods()
{
    QDBusInterface objectManager("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", m_dbus);
    QDBusMessage reply = objectManager.call("GetManagedObjects");

    if (reply.type() == QDBusMessage::ErrorMessage)
    {
        LOG_WARN("Failed to get managed objects: " << reply.errorMessage());
        return false;
    }

    QDBusArgument arg = reply.arguments().constFirst().value<QDBusArgument>();
    ManagedObjectList managedObjects;
    arg >> managedObjects;

    for (auto it = managedObjects.constBegin(); it != managedObjects.constEnd(); ++it)
    {
        const QVariantMap deviceProps = it.value().value("org.bluez.Device1");
        if (deviceProps.isEmpty() ||
            !deviceProps.value("UUIDs").toStringList().contains("74ec2172-0bad-4d01-8f77-997b2be0722a") ||
            !deviceProps.value("Connected").toBool())
        {
            continue;
        }

        const QString path = it.key().path();
        const QString deviceName = deviceProps.value("Name").toString();
        LOG_INFO("Disconnecting AirPods: " << deviceName);

        // As with Connect(): asynchronous, and PropertiesChanged reports the disconnection.
        QDBusInterface device("org.bluez", path, "org.bluez.Device1", m_dbus);
        auto *watcher = new QDBusPendingCallWatcher(device.asyncCall("Disconnect"), this);
        connect(watcher, &QDBusPendingCallWatcher::finished, this,
                [this, deviceName](QDBusPendingCallWatcher *call)
                {
                    QDBusPendingReply<> result = *call;
                    if (result.isError())
                    {
                        LOG_WARN("Failed to disconnect " << deviceName << ": " << result.error().message());
                        emit disconnectFailed(deviceName, result.error().message());
                    }
                    call->deleteLater();
                });
        return true;
    }

    LOG_INFO("No connected AirPods to disconnect");
    return false;
}

void BluetoothMonitor::onPropertiesChanged(const QString &interface, const QVariantMap &changedProps, const QStringList &invalidatedProps)
{
    Q_UNUSED(invalidatedProps);

    if (interface != "org.bluez.Device1")
    {
        return;
    }

    if (changedProps.contains("Connected"))
    {
        bool connected = changedProps["Connected"].toBool();
        QString path = QDBusContext::message().path();

        if (!isAirPodsDevice(path))
        {
            return;
        }

        QDBusInterface deviceInterface("org.bluez", path, "org.freedesktop.DBus.Properties", m_dbus);

        // Get the device address
        QDBusReply<QVariant> addrReply = deviceInterface.call("Get", "org.bluez.Device1", "Address");
        if (!addrReply.isValid())
        {
            return;
        }
        QString macAddress = addrReply.value().toString();
        QString deviceName = getDeviceName(path);

        if (connected)
        {
            emit deviceConnected(macAddress, deviceName);
            LOG_DEBUG("AirPods device connected:" << macAddress << " Name:" << deviceName);
        }
        else
        {
            emit deviceDisconnected(macAddress, deviceName);
            LOG_DEBUG("AirPods device disconnected:" << macAddress << " Name:" << deviceName);
        }
    }
}