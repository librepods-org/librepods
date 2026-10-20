#pragma once

#include <QObject>
#include <QTimer>
#include <QDir>
#include <QSaveFile>
#include <QJsonObject>
#include <QJsonDocument>
#include <QDateTime>
#include <QStandardPaths>
#include <functional>
#include "deviceinfo.hpp"

// Mirrors the live AirPods state into $XDG_RUNTIME_DIR/librepods/state.json so
// other programs (e.g. desktop shell widgets) can show it without talking to
// the AirPods themselves.
class StateExporter : public QObject
{
    Q_OBJECT

public:
    StateExporter(DeviceInfo *info, std::function<bool()> isConnected, QObject *parent = nullptr)
        : QObject(parent), m_info(info), m_isConnected(std::move(isConnected))
    {
        QString runtimeDir = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
        QDir().mkpath(runtimeDir + "/librepods");
        m_path = runtimeDir + "/librepods/state.json";

        m_timer.setSingleShot(true);
        m_timer.setInterval(100);
        connect(&m_timer, &QTimer::timeout, this, &StateExporter::write);

        connect(info->getBattery(), &Battery::batteryStatusChanged, this, &StateExporter::schedule);
        connect(info, &DeviceInfo::primaryChanged, this, &StateExporter::schedule);
        connect(info, &DeviceInfo::noiseControlModeChanged, this, &StateExporter::schedule);
        connect(info, &DeviceInfo::deviceNameChanged, this, &StateExporter::schedule);
        connect(info, &DeviceInfo::modelChanged, this, &StateExporter::schedule);
        connect(info, &DeviceInfo::bluetoothAddressChanged, this, &StateExporter::schedule);
        write();
    }

    ~StateExporter() override { QFile::remove(m_path); }

public slots:
    void schedule() { m_timer.start(); }

private:
    static QJsonObject component(int level, bool charging, bool available)
    {
        return QJsonObject{{"level", level}, {"charging", charging}, {"available", available}};
    }

    static QString noiseModeName(NoiseControlMode mode)
    {
        switch (mode) {
        case NoiseControlMode::Off: return "off";
        case NoiseControlMode::NoiseCancellation: return "anc";
        case NoiseControlMode::Transparency: return "transparency";
        case NoiseControlMode::Adaptive: return "adaptive";
        }
        return "unknown";
    }

    void write()
    {
        Battery *b = m_info->getBattery();
        QJsonObject state{
            {"connected", m_isConnected()},
            {"name", m_info->deviceName()},
            {"address", m_info->bluetoothAddress()},
            {"model", static_cast<int>(m_info->model())},
            {"noiseMode", noiseModeName(m_info->noiseControlMode())},
            {"left", component(b->getLeftPodLevel(), b->isLeftPodCharging(), b->isLeftPodAvailable())},
            {"right", component(b->getRightPodLevel(), b->isRightPodCharging(), b->isRightPodAvailable())},
            {"case", component(b->getCaseLevel(), b->isCaseCharging(), b->isCaseAvailable())},
            {"headset", component(b->getHeadsetLevel(), b->isHeadsetCharging(), b->isHeadsetAvailable())},
            {"inEar", QJsonObject{{"left", m_info->isLeftPodInEar()}, {"right", m_info->isRightPodInEar()}}},
            {"updated", QDateTime::currentMSecsSinceEpoch()},
        };

        QSaveFile file(m_path);
        if (!file.open(QIODevice::WriteOnly))
            return;
        file.write(QJsonDocument(state).toJson(QJsonDocument::Compact));
        file.commit();
    }

    DeviceInfo *m_info;
    std::function<bool()> m_isConnected;
    QString m_path;
    QTimer m_timer;
};
