#pragma once

#include <QObject>
#include <QByteArray>
#include <QPair>
#include "logger.h"

class EarDetection : public QObject
{
    Q_OBJECT

public:
    enum class EarDetectionStatus
    {
        InEar,
        NotInEar,
        InCase,
        Disconnected,
    };
    Q_ENUM(EarDetectionStatus)

    explicit EarDetection(QObject *parent = nullptr) : QObject(parent)
    {
        reset();
    }

    void reset()
    {
        primaryStatus = EarDetectionStatus::Disconnected;
        secondaryStatus = EarDetectionStatus::Disconnected;
        emit statusChanged();
    }

    bool parseData(const QByteArray &data)
    {
        if (data.size() < 2)
        {
            return false;
        }

        auto [newprimaryStatus, newsecondaryStatus] = parseStatusBytes(data);

        primaryStatus = newprimaryStatus;
        secondaryStatus = newsecondaryStatus;
        LOG_DEBUG("Parsed Ear Detection Status: Primary - " << primaryStatus
                  << ", Secondary - " << secondaryStatus);
        emit statusChanged();

        return true;
    }
    void overrideEarDetectionStatus(bool primaryInEar, bool secondaryInEar, bool primaryInCase = false, bool secondaryInCase = false)
    {
        auto status = [](bool inEar, bool inCase) {
            return inEar ? EarDetectionStatus::InEar : inCase ? EarDetectionStatus::InCase : EarDetectionStatus::NotInEar;
        };
        primaryStatus = status(primaryInEar, primaryInCase);
        secondaryStatus = status(secondaryInEar, secondaryInCase);
        emit statusChanged();
    }

    bool isPrimaryInEar() const { return primaryStatus == EarDetectionStatus::InEar; }
    bool isSecondaryInEar() const { return secondaryStatus == EarDetectionStatus::InEar; }
    bool oneOrMorePodsInCase() const { return primaryStatus == EarDetectionStatus::InCase || secondaryStatus == EarDetectionStatus::InCase; }
    bool oneOrMorePodsInEar() const { return isPrimaryInEar() || isSecondaryInEar(); }

    EarDetectionStatus getprimaryStatus() const { return primaryStatus; }
    EarDetectionStatus getsecondaryStatus() const { return secondaryStatus; }

signals:
    void statusChanged();

private:
    QPair<EarDetectionStatus, EarDetectionStatus> parseStatusBytes(const QByteArray &data) const
    {
        quint8 primaryByte = static_cast<quint8>(data[6]);
        quint8 secondaryByte = static_cast<quint8>(data[7]);

        auto primaryStatus = parseStatusByte(primaryByte);
        auto secondaryStatus = parseStatusByte(secondaryByte);

        return qMakePair(primaryStatus, secondaryStatus);
    }

    EarDetectionStatus parseStatusByte(quint8 byte) const
    {
        if (byte == 0x00)
            return EarDetectionStatus::InEar;
        if (byte == 0x01)
            return EarDetectionStatus::NotInEar;
        if (byte == 0x02)
            return EarDetectionStatus::InCase;
        return EarDetectionStatus::Disconnected;
    }

    EarDetectionStatus primaryStatus = EarDetectionStatus::Disconnected;
    EarDetectionStatus secondaryStatus = EarDetectionStatus::Disconnected;
};