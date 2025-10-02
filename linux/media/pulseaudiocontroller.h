#ifndef PULSEAUDIOCONTROLLER_H
#define PULSEAUDIOCONTROLLER_H

#include <QString>
#include <QObject>
#include <pulse/pulseaudio.h>

class PulseAudioController : public QObject
{
    Q_OBJECT

public:
    explicit PulseAudioController(QObject *parent = nullptr);
    ~PulseAudioController();

    bool initialize();
    QString getDefaultSink();
    int getSinkVolume(const QString &sinkName);
    bool setSinkVolume(const QString &sinkName, int volumePercent);
    bool setCardProfile(const QString &cardName, const QString &profileName);
    QString getCardNameForDevice(const QString &macAddress);
    bool isProfileAvailable(const QString &cardName, const QString &profileName);

private:
    pa_threaded_mainloop *m_mainloop;
    pa_context *m_context;
    bool m_initialized;

    static void contextStateCallback(pa_context *c, void *userdata);
    static void sinkInfoCallback(pa_context *c, const pa_sink_info *info, int eol, void *userdata);
    static void cardInfoCallback(pa_context *c, const pa_card_info *info, int eol, void *userdata);
    static void serverInfoCallback(pa_context *c, const pa_server_info *info, void *userdata);

    bool waitForOperation(pa_operation *op);
};

#endif // PULSEAUDIOCONTROLLER_H
