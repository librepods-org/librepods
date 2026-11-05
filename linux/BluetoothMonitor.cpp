#include "BluetoothMonitor.h"
#include "logger.h"

#include <QDebug>
#include <QDBusObjectPath>
#include <QDBusMetaType>

BluetoothMonitor::BluetoothMonitor(QObject *parent, const QDBusConnection &connection)
    : QObject(parent), m_dbus(connection)
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

bool BluetoothMonitor::updateDevice(const QString &devicePath, const QVariantMap &properties, bool forceNotify)
{
    // A failed/incomplete read must not look like a disconnection.
    if (!properties.contains("Connected"))
    {
        return false;
    }
    const QString name = properties.value("Name", "Unknown").toString();
    if (!properties.value("Connected").toBool())
    {
        // UUIDs can disappear during disconnection. Remember the address of
        // devices we announced instead of requiring their services again.
        const QString address = m_connectedDevices.take(devicePath);
        if (!address.isEmpty())
            emit deviceDisconnected(address, name);
        return false;
    }

    const QStringList uuids = qdbus_cast<QStringList>(properties.value("UUIDs"));
    const QString address = properties.value("Address").toString();
    if (!uuids.contains("74ec2172-0bad-4d01-8f77-997b2be0722a") || address.isEmpty())
        return false;

    const bool alreadyAnnounced = m_connectedDevices.contains(devicePath);
    m_connectedDevices.insert(devicePath, address);
    if (!alreadyAnnounced || forceNotify)
    {
        emit deviceConnected(address, name);
        LOG_DEBUG("Found connected AirPods: " << address << " Name: " << name);
    }
    return true;
}

bool BluetoothMonitor::checkAlreadyConnectedDevices()
{
    QDBusInterface objectManager("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", m_dbus);
    QDBusMessage reply = objectManager.call("GetManagedObjects");

    if (reply.type() == QDBusMessage::ErrorMessage || reply.arguments().isEmpty())
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
        const QMap<QString, QVariantMap> &interfaces = it.value();

        if (interfaces.contains("org.bluez.Device1"))
        {
            // Explicit rescans also retry a previously announced device whose
            // control socket may have failed to connect.
            if (updateDevice(it.key().path(), interfaces.value("org.bluez.Device1"), true))
                deviceFound = true;
        }
    }
    return deviceFound;
}

void BluetoothMonitor::onPropertiesChanged(const QString &interface, const QVariantMap &changedProps, const QStringList &invalidatedProps)
{
    if (interface != "org.bluez.Device1")
    {
        return;
    }

    // BlueZ may report Connected before SDP has populated UUIDs. Revisit the
    // device when service discovery completes, even without another connection.
    const QStringList relevantProperties = {"Connected", "UUIDs", "ServicesResolved"};
    bool relevant = false;
    for (const QString &property : relevantProperties)
        relevant |= changedProps.contains(property) || invalidatedProps.contains(property);
    if (!relevant)
        return;

    const QString path = QDBusContext::message().path();
    QDBusInterface deviceInterface("org.bluez", path, "org.freedesktop.DBus.Properties", m_dbus);
    QDBusReply<QVariantMap> reply = deviceInterface.call("GetAll", "org.bluez.Device1");
    if (reply.isValid())
        updateDevice(path, reply.value());
}
