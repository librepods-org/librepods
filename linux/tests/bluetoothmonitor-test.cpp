#include "BluetoothMonitor.h"
#include <QSignalSpy>
#include <QtTest>
#include <QLoggingCategory>

Q_LOGGING_CATEGORY(librepods, "librepods")

class BluetoothMonitorTest : public QObject
{
    Q_OBJECT

    const QString path = "/org/bluez/hci0/dev_00_11_22_33_44_55";
    const QString address = "00:11:22:33:44:55";
    const QString service = "74ec2172-0bad-4d01-8f77-997b2be0722a";

    QVariantMap airPods() const
    {
        return {{"Connected", true}, {"Address", address}, {"Name", "Test AirPods"},
                {"UUIDs", QStringList{service}}};
    }

private slots:
    void servicesDiscoveredAfterConnection()
    {
        BluetoothMonitor monitor(nullptr, QDBusConnection("disconnected-test-bus"));
        QSignalSpy connected(&monitor, &BluetoothMonitor::deviceConnected);
        auto properties = airPods();
        properties["UUIDs"] = QStringList{};
        properties["ServicesResolved"] = false;
        QVERIFY(!monitor.updateDevice(path, properties));
        QCOMPARE(connected.count(), 0);

        properties["UUIDs"] = QStringList{service};
        properties["ServicesResolved"] = true;
        QVERIFY(monitor.updateDevice(path, properties));
        QCOMPARE(connected.count(), 1);
        QCOMPARE(connected.at(0).at(0).toString(), address);
        QCOMPARE(connected.at(0).at(1).toString(), QString("Test AirPods"));

        // UUIDs and ServicesResolved may arrive as separate updates.
        QVERIFY(monitor.updateDevice(path, properties));
        QCOMPARE(connected.count(), 1);
    }

    void explicitRescanRetriesKnownDevice()
    {
        BluetoothMonitor monitor(nullptr, QDBusConnection("disconnected-test-bus"));
        QSignalSpy connected(&monitor, &BluetoothMonitor::deviceConnected);
        QVERIFY(monitor.updateDevice(path, airPods()));
        QVERIFY(monitor.updateDevice(path, airPods(), true));
        QCOMPARE(connected.count(), 2);
    }

    void disconnectAfterServicesDisappear()
    {
        BluetoothMonitor monitor(nullptr, QDBusConnection("disconnected-test-bus"));
        QSignalSpy connected(&monitor, &BluetoothMonitor::deviceConnected);
        QSignalSpy disconnected(&monitor, &BluetoothMonitor::deviceDisconnected);
        QVERIFY(monitor.updateDevice(path, airPods()));

        // BlueZ can clear services before changing Connected to false.
        auto properties = airPods();
        properties.remove("UUIDs");
        QVERIFY(!monitor.updateDevice(path, properties));
        QCOMPARE(disconnected.count(), 0);
        properties["Connected"] = false;
        properties.remove("Address");
        QVERIFY(!monitor.updateDevice(path, properties));
        QCOMPARE(disconnected.count(), 1);
        QCOMPARE(disconnected.at(0).at(0).toString(), address);
        QVERIFY(!monitor.updateDevice(path, properties));
        QCOMPARE(disconnected.count(), 1);

        QVERIFY(monitor.updateDevice(path, airPods()));
        QCOMPARE(connected.count(), 2);
    }

    void incompleteReadDoesNotDisconnect()
    {
        BluetoothMonitor monitor(nullptr, QDBusConnection("disconnected-test-bus"));
        QSignalSpy disconnected(&monitor, &BluetoothMonitor::deviceDisconnected);
        QVERIFY(monitor.updateDevice(path, airPods()));
        QVERIFY(!monitor.updateDevice(path, {}));
        QCOMPARE(disconnected.count(), 0);
        QCOMPARE(monitor.m_connectedDevices.value(path), address);
    }

    void rejectsUnrelatedOrIncompleteDevices()
    {
        BluetoothMonitor monitor(nullptr, QDBusConnection("disconnected-test-bus"));
        QSignalSpy connected(&monitor, &BluetoothMonitor::deviceConnected);
        QSignalSpy disconnected(&monitor, &BluetoothMonitor::deviceDisconnected);
        auto properties = airPods();
        properties["UUIDs"] = QStringList{"0000110b-0000-1000-8000-00805f9b34fb"};
        QVERIFY(!monitor.updateDevice(path, properties));
        properties = airPods();
        properties.remove("Address");
        QVERIFY(!monitor.updateDevice(path, properties));
        properties = airPods();
        properties["Connected"] = false;
        QVERIFY(!monitor.updateDevice(path, properties));
        QCOMPARE(connected.count(), 0);
        QCOMPARE(disconnected.count(), 0);
    }

    void missingNameDoesNotPreventDetection()
    {
        BluetoothMonitor monitor(nullptr, QDBusConnection("disconnected-test-bus"));
        QSignalSpy connected(&monitor, &BluetoothMonitor::deviceConnected);
        auto properties = airPods();
        properties.remove("Name");
        QVERIFY(monitor.updateDevice(path, properties));
        QCOMPARE(connected.count(), 1);
        QCOMPARE(connected.at(0).at(1).toString(), QString("Unknown"));
    }
};

QTEST_GUILESS_MAIN(BluetoothMonitorTest)
#include "bluetoothmonitor-test.moc"
