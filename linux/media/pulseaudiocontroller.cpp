#include "pulseaudiocontroller.h"
#include "logger.h"
#include <QThread>

PulseAudioController::PulseAudioController(QObject *parent)
    : QObject(parent), m_mainloop(nullptr), m_context(nullptr), m_initialized(false)
{
}

PulseAudioController::~PulseAudioController()
{
    if (m_context)
    {
        pa_context_disconnect(m_context);
        pa_context_unref(m_context);
    }
    if (m_mainloop)
    {
        pa_threaded_mainloop_stop(m_mainloop);
        pa_threaded_mainloop_free(m_mainloop);
    }
}

bool PulseAudioController::initialize()
{
    m_mainloop = pa_threaded_mainloop_new();
    if (!m_mainloop)
    {
        LOG_ERROR("Failed to create PulseAudio mainloop");
        return false;
    }

    pa_mainloop_api *api = pa_threaded_mainloop_get_api(m_mainloop);
    m_context = pa_context_new(api, "LibrePods");
    if (!m_context)
    {
        LOG_ERROR("Failed to create PulseAudio context");
        return false;
    }

    pa_context_set_state_callback(m_context, contextStateCallback, this);
    
    if (pa_threaded_mainloop_start(m_mainloop) < 0)
    {
        LOG_ERROR("Failed to start PulseAudio mainloop");
        return false;
    }

    pa_threaded_mainloop_lock(m_mainloop);
    
    if (pa_context_connect(m_context, nullptr, PA_CONTEXT_NOFLAGS, nullptr) < 0)
    {
        LOG_ERROR("Failed to connect to PulseAudio");
        pa_threaded_mainloop_unlock(m_mainloop);
        return false;
    }

    // Wait for context to be ready
    while (pa_context_get_state(m_context) != PA_CONTEXT_READY)
    {
        if (!PA_CONTEXT_IS_GOOD(pa_context_get_state(m_context)))
        {
            LOG_ERROR("PulseAudio context failed");
            pa_threaded_mainloop_unlock(m_mainloop);
            return false;
        }
        pa_threaded_mainloop_wait(m_mainloop);
    }

    pa_threaded_mainloop_unlock(m_mainloop);
    m_initialized = true;
    LOG_INFO("PulseAudio controller initialized");
    return true;
}

void PulseAudioController::contextStateCallback(pa_context *c, void *userdata)
{
    PulseAudioController *controller = static_cast<PulseAudioController*>(userdata);
    pa_threaded_mainloop_signal(controller->m_mainloop, 0);
}

QString PulseAudioController::getDefaultSink()
{
    if (!m_initialized) return QString();

    struct CallbackData {
        QString sinkName;
        pa_threaded_mainloop *mainloop;
    } data;
    data.mainloop = m_mainloop;

    auto callback = [](pa_context *c, const pa_server_info *info, void *userdata) {
        CallbackData *d = static_cast<CallbackData*>(userdata);
        if (info && info->default_sink_name)
        {
            d->sinkName = QString::fromUtf8(info->default_sink_name);
        }
        pa_threaded_mainloop_signal(d->mainloop, 0);
    };

    pa_threaded_mainloop_lock(m_mainloop);
    pa_operation *op = pa_context_get_server_info(m_context, callback, &data);
    if (op)
    {
        waitForOperation(op);
        pa_operation_unref(op);
    }
    pa_threaded_mainloop_unlock(m_mainloop);

    return data.sinkName;
}

int PulseAudioController::getSinkVolume(const QString &sinkName)
{
    if (!m_initialized) return -1;

    struct CallbackData {
        int volume;
        QString targetSink;
        pa_threaded_mainloop *mainloop;
    } data;
    data.volume = -1;
    data.targetSink = sinkName;
    data.mainloop = m_mainloop;

    auto callback = [](pa_context *c, const pa_sink_info *info, int eol, void *userdata) {
        CallbackData *d = static_cast<CallbackData*>(userdata);
        if (eol > 0)
        {
            pa_threaded_mainloop_signal(d->mainloop, 0);
            return;
        }
        if (info && QString::fromUtf8(info->name) == d->targetSink)
        {
            d->volume = (pa_cvolume_avg(&info->volume) * 100) / PA_VOLUME_NORM;
            pa_threaded_mainloop_signal(d->mainloop, 0);
        }
    };

    pa_threaded_mainloop_lock(m_mainloop);
    pa_operation *op = pa_context_get_sink_info_by_name(m_context, sinkName.toUtf8().constData(), callback, &data);
    if (op)
    {
        waitForOperation(op);
        pa_operation_unref(op);
    }
    pa_threaded_mainloop_unlock(m_mainloop);

    return data.volume;
}

bool PulseAudioController::setSinkVolume(const QString &sinkName, int volumePercent)
{
    if (!m_initialized) return false;

    pa_cvolume volume;
    pa_cvolume_set(&volume, 2, (volumePercent * PA_VOLUME_NORM) / 100);

    pa_threaded_mainloop_lock(m_mainloop);
    
    auto successCallback = [](pa_context *c, int success, void *userdata) {
        pa_threaded_mainloop *mainloop = static_cast<pa_threaded_mainloop*>(userdata);
        pa_threaded_mainloop_signal(mainloop, 0);
    };
    
    pa_operation *op = pa_context_set_sink_volume_by_name(m_context, sinkName.toUtf8().constData(), &volume, successCallback, m_mainloop);

    bool success = waitForOperation(op);
    if (op) pa_operation_unref(op);
    pa_threaded_mainloop_unlock(m_mainloop);

    return success;
}

bool PulseAudioController::setCardProfile(const QString &cardName, const QString &profileName)
{
    if (!m_initialized) return false;

    pa_threaded_mainloop_lock(m_mainloop);
    
    auto successCallback = [](pa_context *c, int success, void *userdata) {
        pa_threaded_mainloop *mainloop = static_cast<pa_threaded_mainloop*>(userdata);
        pa_threaded_mainloop_signal(mainloop, 0);
    };
    
    pa_operation *op = pa_context_set_card_profile_by_name(m_context, 
        cardName.toUtf8().constData(), 
        profileName.toUtf8().constData(), 
        successCallback, m_mainloop);
    bool success = waitForOperation(op);
    if (op) pa_operation_unref(op);
    pa_threaded_mainloop_unlock(m_mainloop);

    return success;
}

QString PulseAudioController::getCardNameForDevice(const QString &macAddress)
{
    if (!m_initialized) return QString();

    struct CallbackData {
        QString cardName;
        QString targetMac;
        pa_threaded_mainloop *mainloop;
    } data;
    data.targetMac = macAddress;
    data.mainloop = m_mainloop;

    auto callback = [](pa_context *c, const pa_card_info *info, int eol, void *userdata) {
        CallbackData *d = static_cast<CallbackData*>(userdata);
        if (eol > 0)
        {
            pa_threaded_mainloop_signal(d->mainloop, 0);
            return;
        }
        if (info)
        {
            QString name = QString::fromUtf8(info->name);
            if (name.startsWith("bluez") && name.contains(d->targetMac))
            {
                d->cardName = name;
                pa_threaded_mainloop_signal(d->mainloop, 0);
            }
        }
    };

    pa_threaded_mainloop_lock(m_mainloop);
    pa_operation *op = pa_context_get_card_info_list(m_context, callback, &data);
    if (op)
    {
        waitForOperation(op);
        pa_operation_unref(op);
    }
    pa_threaded_mainloop_unlock(m_mainloop);

    return data.cardName;
}

bool PulseAudioController::isProfileAvailable(const QString &cardName, const QString &profileName)
{
    if (!m_initialized) return false;

    struct CallbackData {
        bool available;
        QString targetCard;
        QString targetProfile;
        pa_threaded_mainloop *mainloop;
    } data;
    data.available = false;
    data.targetCard = cardName;
    data.targetProfile = profileName;
    data.mainloop = m_mainloop;

    auto callback = [](pa_context *c, const pa_card_info *info, int eol, void *userdata) {
        CallbackData *d = static_cast<CallbackData*>(userdata);
        if (eol > 0)
        {
            pa_threaded_mainloop_signal(d->mainloop, 0);
            return;
        }
        if (info && QString::fromUtf8(info->name) == d->targetCard)
        {
            for (uint32_t i = 0; i < info->n_profiles; i++)
            {
                if (QString::fromUtf8(info->profiles[i].name) == d->targetProfile)
                {
                    d->available = true;
                    break;
                }
            }
            pa_threaded_mainloop_signal(d->mainloop, 0);
        }
    };

    pa_threaded_mainloop_lock(m_mainloop);
    pa_operation *op = pa_context_get_card_info_by_name(m_context, cardName.toUtf8().constData(), callback, &data);
    if (op)
    {
        waitForOperation(op);
        pa_operation_unref(op);
    }
    pa_threaded_mainloop_unlock(m_mainloop);

    return data.available;
}

bool PulseAudioController::waitForOperation(pa_operation *op)
{
    if (!op) return false;

    while (pa_operation_get_state(op) == PA_OPERATION_RUNNING)
    {
        pa_threaded_mainloop_wait(m_mainloop);
    }

    return pa_operation_get_state(op) == PA_OPERATION_DONE;
}
