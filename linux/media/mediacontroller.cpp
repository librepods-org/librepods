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

MediaController::MediaController(QObject *parent) : QObject(parent) {
  m_pulseAudio = new PulseAudioController(this);
  if (!m_pulseAudio->initialize())
  {
    LOG_ERROR("Failed to initialize PulseAudio controller");
  }
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
      pause();
    }
  }

  // Then handle device profile switching
  if (primaryInEar || secondaryInEar)
  {
    LOG_INFO("At least one AirPod is in ear");
    activateA2dpProfile();

    // Resume if conditions are met and we previously paused
    if (shouldResume && wasPausedByApp && isActiveOutputDeviceAirPods())
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
  LOG_DEBUG("Handling conversational awareness data: " << data.toHex());
  bool lowered = data[9] == 0x01;
  LOG_INFO("Conversational awareness: " << (lowered ? "enabled" : "disabled"));

  if (lowered) {
    if (initialVolume == -1 && isActiveOutputDeviceAirPods()) {
      QString defaultSink = m_pulseAudio->getDefaultSink();
      initialVolume = m_pulseAudio->getSinkVolume(defaultSink);
      if (initialVolume == -1) {
        LOG_ERROR("Failed to get initial volume");
        return;
      }
      LOG_DEBUG("Initial volume: " << initialVolume << "%");
    }
    QString defaultSink = m_pulseAudio->getDefaultSink();
    int targetVolume = initialVolume * 0.20;
    if (m_pulseAudio->setSinkVolume(defaultSink, targetVolume)) {
      LOG_INFO("Volume lowered to 0.20 of initial which is " << targetVolume << "%");
    } else {
      LOG_ERROR("Failed to lower volume");
    }
  } else {
    if (initialVolume != -1 && isActiveOutputDeviceAirPods()) {
      QString defaultSink = m_pulseAudio->getDefaultSink();
      if (m_pulseAudio->setSinkVolume(defaultSink, initialVolume)) {
        LOG_INFO("Volume restored to " << initialVolume << "%");
      } else {
        LOG_ERROR("Failed to restore volume");
      }
      initialVolume = -1;
    }
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

void MediaController::activateA2dpProfile() {
  if (connectedDeviceMacAddress.isEmpty() || m_deviceOutputName.isEmpty()) {
    LOG_WARN("Connected device MAC address or output name is empty, cannot activate A2DP profile");
    return;
  }

  if (!isA2dpProfileAvailable()) {
    LOG_WARN("A2DP profile not available, attempting to restart WirePlumber");
    if (restartWirePlumber()) {
      m_deviceOutputName = getAudioDeviceName();
      if (!isA2dpProfileAvailable()) {
        LOG_ERROR("A2DP profile still not available after WirePlumber restart");
        return;
      }
    } else {
      LOG_ERROR("Could not restart WirePlumber, A2DP profile unavailable");
      return;
    }
  }

  QString preferredProfile = getPreferredA2dpProfile();
  if (preferredProfile.isEmpty()) {
    LOG_ERROR("No suitable A2DP profile found");
    return;
  }

  LOG_INFO("Activating A2DP profile for AirPods: " << preferredProfile);
  if (!m_pulseAudio->setCardProfile(m_deviceOutputName, preferredProfile)) {
    LOG_ERROR("Failed to activate A2DP profile: " << preferredProfile);
  }
}

void MediaController::removeAudioOutputDevice() {
  if (connectedDeviceMacAddress.isEmpty() || m_deviceOutputName.isEmpty()) {
    LOG_WARN("Connected device MAC address or output name is empty, cannot remove audio output device");
    return;
  }
  
  LOG_INFO("Removing AirPods as audio output device");
  if (!m_pulseAudio->setCardProfile(m_deviceOutputName, "off")) {
    LOG_ERROR("Failed to remove AirPods as audio output device");
  }
}

void MediaController::setConnectedDeviceMacAddress(const QString &macAddress) {
  connectedDeviceMacAddress = macAddress;
  m_deviceOutputName = getAudioDeviceName();
  m_cachedA2dpProfile.clear();
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

bool MediaController::sendMediaPlayerCommand(const QString &method)
{
  // Connect to the session bus
  QDBusConnection bus = QDBusConnection::sessionBus();

  // Find available MPRIS-compatible media players
  QStringList services = bus.interface()->registeredServiceNames().value();
  QStringList mprisServices;
  for (const QString &service : services)
  {
    if (service.startsWith("org.mpris.MediaPlayer2."))
    {
      mprisServices << service;
    }
  }

  if (mprisServices.isEmpty())
  {
    LOG_ERROR("No MPRIS-compatible media players found on DBus");
    return false;
  }

  bool success = false;
  // Try each MPRIS service until one succeeds
  for (const QString &service : mprisServices)
  {
    QDBusInterface playerInterface(
        service,
        "/org/mpris/MediaPlayer2",
        "org.mpris.MediaPlayer2.Player",
        bus);

    if (!playerInterface.isValid())
    {
      LOG_ERROR("Invalid DBus interface for service: " << service);
      continue;
    }

    // Send the Play or Pause command
    if (method == "Play" || method == "Pause")
    {
      QDBusReply<void> reply = playerInterface.call(method);
      if (reply.isValid())
      {
        LOG_INFO("Successfully sent " << method << " to " << service);
        success = true;
        break; // Exit after the first successful command
      }
      else
      {
        LOG_ERROR("Failed to send " << method << " to " << service
                                    << ": " << reply.error().message());
      }
    }
    else
    {
      LOG_ERROR("Unsupported method: " << method);
      return false;
    }
  }

  if (!success)
  {
    LOG_ERROR("No media player responded successfully to " << method);
  }
  return success;
}

void MediaController::play()
{
  if (sendMediaPlayerCommand("Play"))
  {
    LOG_INFO("Resumed playback via DBus");
    wasPausedByApp = false;
  }
  else
  {
    LOG_ERROR("Failed to resume playback via DBus");
  }
}

void MediaController::pause()
{
  if (sendMediaPlayerCommand("Pause"))
  {
    LOG_INFO("Paused playback via DBus");
    wasPausedByApp = true;
  }
  else
  {
    LOG_ERROR("Failed to pause playback via DBus");
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