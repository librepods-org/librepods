#include "mediacontroller.h"
#include "logger.h"
#include "eardetection.hpp"
#include "playerstatuswatcher.h"
#include "pulseaudiocontroller.h"

#include <QDebug>
#include <QProcess>
#include <QThread>
#include <QRegularExpression>
#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QTimer>

MediaController::MediaController(QObject *parent) : QObject(parent) {
  m_pulseAudio = new PulseAudioController(this);
  if (!m_pulseAudio->initialize())
  {
    LOG_ERROR("Failed to initialize PulseAudio controller");
  }

  // The BlueZ connect signal fires before WirePlumber has created the audio
  // card, so the first activation attempt can miss it. Retry briefly.
  m_a2dpRetryTimer = new QTimer(this);
  m_a2dpRetryTimer->setSingleShot(true);
  m_a2dpRetryTimer->setInterval(kA2dpRetryIntervalMs);
  connect(m_a2dpRetryTimer, &QTimer::timeout, this, &MediaController::activateA2dpProfile);
}

void MediaController::handleEarDetection(EarDetection *earDetection)
{
  if (earDetectionBehavior == Disabled)
  {
    LOG_DEBUG("Ear detection is disabled, ignoring status");
    return;
  }

  bool primaryInEar = earDetection->isPrimaryInEar();
  bool secondaryInEar = earDetection->isSecondaryInEar();

  LOG_DEBUG("Ear detection status: primaryInEar="
            << primaryInEar << ", secondaryInEar=" << secondaryInEar
            << ", isAirPodsActive=" << isActiveOutputDeviceAirPods());

  // First handle playback pausing based on selected behavior
  bool shouldPause = false;
  bool shouldResume = false;

  if (earDetectionBehavior == PauseWhenOneRemoved)
  {
    shouldPause = !primaryInEar || !secondaryInEar;
    shouldResume = primaryInEar && secondaryInEar;
  }
  else if (earDetectionBehavior == PauseWhenBothRemoved)
  {
    shouldPause = !primaryInEar && !secondaryInEar;
    shouldResume = primaryInEar || secondaryInEar;
  }

  if (shouldPause && isActiveOutputDeviceAirPods())
  {
    if (getCurrentMediaState() == Playing)
    {
      LOG_DEBUG("Pausing playback for ear detection");
      pause();
    }
  }

  // Then handle device profile switching
  if (primaryInEar || secondaryInEar)
  {
    LOG_INFO("At least one AirPod is in ear");
    activateA2dpProfile();

    // Resume if conditions are met and we previously paused
    if (shouldResume && !pausedByAppServices.isEmpty() && isActiveOutputDeviceAirPods())
    {
      play();
    }
  }
  else
  {
    LOG_INFO("Both AirPods are out of ear");
    removeAudioOutputDevice();
  }
}

void MediaController::setEarDetectionBehavior(EarDetectionBehavior behavior)
{
  earDetectionBehavior = behavior;
  LOG_INFO("Set ear detection behavior to: " << behavior);
}

void MediaController::followMediaChanges() {
  playerStatusWatcher = new PlayerStatusWatcher("", this);
  connect(playerStatusWatcher, &PlayerStatusWatcher::playbackStatusChanged,
          this, [this](const QString &status)
          {
            LOG_DEBUG("Playback status changed: " << status);
            MediaState state = mediaStateFromPlayerctlOutput(status);
            emit mediaStateChanged(state);
          });
}

bool MediaController::isActiveOutputDeviceAirPods() {
  QString defaultSink = m_pulseAudio->getDefaultSink();
  LOG_DEBUG("Default sink: " << defaultSink);
  return defaultSink.contains(connectedDeviceMacAddress);
}

void MediaController::handleConversationalAwareness(const QByteArray &data) {
    if (data.size() < 10) {
        LOG_ERROR("Invalid conversational awareness packet");
        return;
    }

    uint8_t flag = (uint8_t)data[9];

    switch (flag) {
    case 0x01:
        LOG_INFO("Conversational awareness event: voice detected");

        if (initialVolume == -1 && isActiveOutputDeviceAirPods()) {
            QString sink = m_pulseAudio->getDefaultSink();
            initialVolume = m_pulseAudio->getSinkVolume(sink);
            LOG_DEBUG("Initial volume saved: " << initialVolume << "%");
        }

        if (initialVolume != -1) {
            QString sink = m_pulseAudio->getDefaultSink();
            int target = initialVolume * 0.20;
            m_pulseAudio->setSinkVolume(sink, target);
            LOG_INFO("Volume lowered to " << target << "%");
        }
        break;

    case 0x08:
        LOG_INFO("Conversational awareness disabled");
        initialVolume = -1;
        break;

    case 0x09:
        LOG_INFO("Conversational awareness enabled");
        break;

    default:
        LOG_INFO("Conversational awareness event: voice ended");

        if (initialVolume != -1 && isActiveOutputDeviceAirPods()) {
            QString sink = m_pulseAudio->getDefaultSink();
            m_pulseAudio->setSinkVolume(sink, initialVolume);
            LOG_INFO("Volume restored to " << initialVolume << "%");
            initialVolume = -1;
        }
        break;
    }
}


bool MediaController::isA2dpProfileAvailable() {
  if (m_deviceOutputName.isEmpty()) {
    return false;
  }

  return m_pulseAudio->isProfileAvailable(m_deviceOutputName, "a2dp-sink-sbc_xq") || 
         m_pulseAudio->isProfileAvailable(m_deviceOutputName, "a2dp-sink-sbc") ||
         m_pulseAudio->isProfileAvailable(m_deviceOutputName, "a2dp-sink");
}

QString MediaController::getPreferredA2dpProfile() {
  if (m_deviceOutputName.isEmpty()) {
    return QString();
  }

  if (!m_cachedA2dpProfile.isEmpty() && 
      m_pulseAudio->isProfileAvailable(m_deviceOutputName, m_cachedA2dpProfile)) {
    return m_cachedA2dpProfile;
  }

  QStringList profiles = {"a2dp-sink-sbc_xq", "a2dp-sink-sbc", "a2dp-sink"};

  for (const QString &profile : profiles) {
    if (m_pulseAudio->isProfileAvailable(m_deviceOutputName, profile)) {
      LOG_INFO("Selected best available A2DP profile: " << profile);
      m_cachedA2dpProfile = profile;
      return profile;
    }
  }

  m_cachedA2dpProfile.clear();
  return QString();
}

bool MediaController::restartWirePlumber() {
  LOG_INFO("Restarting WirePlumber to rediscover A2DP profiles");
  int result = QProcess::execute("systemctl", QStringList() << "--user" << "restart" << "wireplumber");
  if (result == 0) {
    LOG_INFO("WirePlumber restarted successfully");
    QThread::sleep(2);
    return true;
  } else {
    LOG_ERROR("Failed to restart WirePlumber. Do you use wireplumber?");
    return false;
  }
}

bool MediaController::ensureDeviceOutputName() {
  if (connectedDeviceMacAddress.isEmpty()) {
    return false;
  }
  // Never trust a cached empty lookup: the card may have appeared since.
  if (m_deviceOutputName.isEmpty()) {
    m_deviceOutputName = getAudioDeviceName();
    if (!m_deviceOutputName.isEmpty()) {
      LOG_INFO("Resolved device output name: " << m_deviceOutputName);
    }
  }
  return !m_deviceOutputName.isEmpty();
}

void MediaController::scheduleA2dpRetry() {
  if (m_a2dpRetryTimer->isActive()) {
    return;
  }
  if (m_a2dpRetriesLeft <= 0) {
    LOG_ERROR("Giving up on A2DP activation after " << kA2dpRetryAttempts << " retries");
    return;
  }
  --m_a2dpRetriesLeft;
  LOG_INFO("Audio card not ready yet, retrying A2DP activation in "
           << kA2dpRetryIntervalMs << "ms (" << m_a2dpRetriesLeft << " retries left)");
  m_a2dpRetryTimer->start();
}

void MediaController::activateA2dpProfile() {
  if (connectedDeviceMacAddress.isEmpty()) {
    LOG_WARN("Connected device MAC address is empty, cannot activate A2DP profile");
    return;
  }

  if (!ensureDeviceOutputName()) {
    scheduleA2dpRetry();
    return;
  }

  if (!isA2dpProfileAvailable() && m_a2dpRetriesLeft > 0) {
    // A freshly created card may not list its A2DP profiles yet; wait before
    // falling back to the heavyweight WirePlumber restart below.
    scheduleA2dpRetry();
    return;
  }

  m_a2dpRetryTimer->stop();
  m_a2dpRetriesLeft = 0;

  if (!isA2dpProfileAvailable()) {
    if (m_wirePlumberRestarted) {
      LOG_ERROR("A2DP profile still not available after WirePlumber restart");
      return;
    }
    LOG_WARN("A2DP profile not available, attempting to restart WirePlumber");
    m_wirePlumberRestarted = true;
    if (!restartWirePlumber()) {
      LOG_ERROR("Could not restart WirePlumber, A2DP profile unavailable");
      return;
    }
    // The restart tears down and recreates the bluez card, which takes a few
    // seconds; a single check right after it always lost that race. Start a
    // fresh retry window instead.
    m_deviceOutputName.clear();
    m_cachedA2dpProfile.clear();
    m_a2dpRetriesLeft = kA2dpRetryAttempts;
    scheduleA2dpRetry();
    return;
  }

  QString preferredProfile = getPreferredA2dpProfile();
  if (preferredProfile.isEmpty()) {
    LOG_ERROR("No suitable A2DP profile found");
    return;
  }

  LOG_INFO("Activating A2DP profile for AirPods: " << preferredProfile);
  if (!m_pulseAudio->setCardProfile(m_deviceOutputName, preferredProfile)) {
    LOG_ERROR("Failed to activate A2DP profile: " << preferredProfile);
    // The card may have been recreated under us; re-resolve next time.
    m_deviceOutputName.clear();
    return;
  }
  LOG_INFO("A2DP profile activated successfully");
}

void MediaController::removeAudioOutputDevice() {
  // Buds came out while a connect-time retry was pending: don't turn audio
  // back on behind the user's back.
  m_a2dpRetryTimer->stop();
  m_a2dpRetriesLeft = 0;

  if (!ensureDeviceOutputName()) {
    LOG_WARN("Audio card for AirPods not found, cannot remove audio output device");
    return;
  }

  LOG_INFO("Removing AirPods as audio output device");
  if (!m_pulseAudio->setCardProfile(m_deviceOutputName, "off")) {
    LOG_ERROR("Failed to remove AirPods as audio output device");
  }
}

void MediaController::setConnectedDeviceMacAddress(const QString &macAddress) {
  connectedDeviceMacAddress = macAddress;
  m_deviceOutputName.clear();
  m_cachedA2dpProfile.clear();
  m_a2dpRetryTimer->stop();
  m_a2dpRetriesLeft = kA2dpRetryAttempts;
  m_wirePlumberRestarted = false;
  ensureDeviceOutputName();
  LOG_INFO("Device output name set to: " << m_deviceOutputName);
}

MediaController::MediaState MediaController::mediaStateFromPlayerctlOutput(
    const QString &output) const {
  if (output == "Playing") {
    return MediaState::Playing;
  } else if (output == "Paused") {
    return MediaState::Paused;
  } else {
    return MediaState::Stopped;
  }
}

MediaController::MediaState MediaController::getCurrentMediaState() const
{
  return mediaStateFromPlayerctlOutput(PlayerStatusWatcher::getCurrentPlaybackStatus(""));
}

QStringList MediaController::getPlayingMediaPlayers()
{
  QStringList playingServices;
  QDBusConnection bus = QDBusConnection::sessionBus();

  QStringList services = bus.interface()->registeredServiceNames().value();
  for (const QString &service : services)
  {
    if (!service.startsWith("org.mpris.MediaPlayer2."))
    {
      continue;
    }

    QDBusInterface playerInterface(
        service,
        "/org/mpris/MediaPlayer2",
        "org.mpris.MediaPlayer2.Player",
        bus);

    if (!playerInterface.isValid())
    {
      continue;
    }

    QVariant playbackStatus = playerInterface.property("PlaybackStatus");
    if (playbackStatus.isValid() && playbackStatus.toString() == "Playing")
    {
      playingServices << service;
      LOG_DEBUG("Found playing service: " << service);
    }
  }

  return playingServices;
}

void MediaController::play()
{
  if (pausedByAppServices.isEmpty())
  {
    LOG_INFO("No services to resume");
    return;
  }

  QDBusConnection bus = QDBusConnection::sessionBus();
  int resumedCount = 0;

  for (const QString &service : pausedByAppServices)
  {
    QDBusInterface playerInterface(
        service,
        "/org/mpris/MediaPlayer2",
        "org.mpris.MediaPlayer2.Player",
        bus);

    if (!playerInterface.isValid())
    {
      LOG_WARN("Service no longer available: " << service);
      continue;
    }

    QDBusReply<void> reply = playerInterface.call("Play");
    if (reply.isValid())
    {
      LOG_INFO("Resumed playback for: " << service);
      resumedCount++;
    }
    else
    {
      LOG_ERROR("Failed to resume " << service << ": " << reply.error().message());
    }
  }

  if (resumedCount > 0)
  {
    LOG_INFO("Resumed " << resumedCount << " media player(s) via DBus");
    pausedByAppServices.clear();
  }
  else
  {
    LOG_ERROR("Failed to resume any media players via DBus");
  }
}

void MediaController::pause()
{
  QDBusConnection bus = QDBusConnection::sessionBus();
  QStringList services = bus.interface()->registeredServiceNames().value();

  pausedByAppServices.clear();
  int pausedCount = 0;

  for (const QString &service : services)
  {
    if (!service.startsWith("org.mpris.MediaPlayer2."))
    {
      continue;
    }

    QDBusInterface playerInterface(
        service,
        "/org/mpris/MediaPlayer2",
        "org.mpris.MediaPlayer2.Player",
        bus);

    if (!playerInterface.isValid())
    {
      continue;
    }

    QVariant playbackStatus = playerInterface.property("PlaybackStatus");
    LOG_DEBUG("PlaybackStatus for " << service << ": " << playbackStatus.toString());
    if (!playbackStatus.isValid() || playbackStatus.toString() != "Playing")
    {
      continue;
    }

    QDBusReply<void> reply = playerInterface.call("Pause");
    LOG_DEBUG("Pausing service: " << service);
    if (reply.isValid())
    {
      LOG_INFO("Paused playback for: " << service);
      pausedByAppServices << service;
      pausedCount++;
    }
    else
    {
      LOG_ERROR("Failed to pause " << service << ": " << reply.error().message());
    }
  }

  if (pausedCount > 0)
  {
    LOG_INFO("Paused " << pausedCount << " media player(s) via DBus");
  }
  else
  {
    LOG_INFO("No playing media players found to pause");
  }
}

MediaController::~MediaController() {
}

QString MediaController::getAudioDeviceName()
{
  if (connectedDeviceMacAddress.isEmpty()) { return QString(); }

  QString cardName = m_pulseAudio->getCardNameForDevice(connectedDeviceMacAddress);
  if (cardName.isEmpty()) {
    LOG_ERROR("No matching Bluetooth card found for MAC address: " << connectedDeviceMacAddress);
  }
  return cardName;
}