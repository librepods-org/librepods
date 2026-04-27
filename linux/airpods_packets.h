// airpods_packets.h
#ifndef AIRPODS_PACKETS_H
#define AIRPODS_PACKETS_H

#include <QByteArray>
#include <optional>
#include <climits>

#include "enums.h"
#include "BasicControlCommand.hpp"

namespace AirPodsPackets
{
    // Noise Control Mode Packets
    namespace NoiseControl
    {
        using NoiseControlMode = AirpodsTrayApp::Enums::NoiseControlMode;
        static const QByteArray HEADER = ControlCommand::HEADER + 0x0D;
        static const QByteArray OFF = ControlCommand::createCommand(0x0D, 0x01);
        static const QByteArray NOISE_CANCELLATION = ControlCommand::createCommand(0x0D, 0x02);
        static const QByteArray TRANSPARENCY = ControlCommand::createCommand(0x0D, 0x03);
        static const QByteArray ADAPTIVE = ControlCommand::createCommand(0x0D, 0x04);

        static const QByteArray getPacketForMode(AirpodsTrayApp::Enums::NoiseControlMode mode)
        {
            switch (mode)
            {
            case NoiseControlMode::Off:
                return OFF;
            case NoiseControlMode::NoiseCancellation:
                return NOISE_CANCELLATION;
            case NoiseControlMode::Transparency:
                return TRANSPARENCY;
            case NoiseControlMode::Adaptive:
                return ADAPTIVE;
            default:
                return QByteArray();
            }
        }

        inline std::optional<NoiseControlMode> parseMode(const QByteArray &data)
        {
            char mode = ControlCommand::parseActive(data).value_or(CHAR_MAX) - 1;
            if (mode < static_cast<quint8>(NoiseControlMode::MinValue) ||
                mode > static_cast<quint8>(NoiseControlMode::MaxValue))
            {
                return std::nullopt;
            }
            return static_cast<NoiseControlMode>(mode);
        }
    }

    // One Bud ANC Mode
    namespace OneBudANCMode
    {
        using Type = BasicControlCommand<0x1B>;
        static const QByteArray ENABLED = Type::ENABLED;
        static const QByteArray DISABLED = Type::DISABLED;
        static const QByteArray HEADER = Type::HEADER;
        inline std::optional<bool> parseState(const QByteArray &data) { return Type::parseState(data); }
    }

    // Volume Swipe (partial - still needs custom interval function)
    namespace VolumeSwipe
    {
        using Type = BasicControlCommand<0x25>;
        static const QByteArray ENABLED = Type::ENABLED;
        static const QByteArray DISABLED = Type::DISABLED;
        static const QByteArray HEADER = Type::HEADER;
        inline std::optional<bool> parseState(const QByteArray &data) { return Type::parseState(data); }

        // Keep custom interval function
        static QByteArray getIntervalPacket(quint8 interval)
        {
            return ControlCommand::createCommand(0x23, interval);
        }
    }

    // Adaptive Volume Config
    namespace AdaptiveVolume
    {
        using Type = BasicControlCommand<0x26>;
        static const QByteArray ENABLED = Type::ENABLED;
        static const QByteArray DISABLED = Type::DISABLED;
        static const QByteArray HEADER = Type::HEADER;
        inline std::optional<bool> parseState(const QByteArray &data) { return Type::parseState(data); }
    }

    // Conversational Awareness
    namespace ConversationalAwareness
    {
        using Type = BasicControlCommand<0x28>;
        static const QByteArray ENABLED = Type::ENABLED;
        static const QByteArray DISABLED = Type::DISABLED;
        static const QByteArray HEADER = Type::HEADER;
        static const QByteArray DATA_HEADER = QByteArray::fromHex("040004004B00020001");
        inline std::optional<bool> parseState(const QByteArray &data) { return Type::parseState(data); }
    }

    // Hearing Assist
    namespace HearingAssist
    {
        using Type = BasicControlCommand<0x33>;
        static const QByteArray ENABLED = Type::ENABLED;
        static const QByteArray DISABLED = Type::DISABLED;
        static const QByteArray HEADER = Type::HEADER;
        inline std::optional<bool> parseState(const QByteArray &data) { return Type::parseState(data); }
    }

    // Hearing Aid
    namespace HearingAid
    {
        static const QByteArray HEADER = ControlCommand::HEADER + static_cast<char>(0x2C);
        static const QByteArray ENABLED = ControlCommand::createCommand(0x2C, 0x01, 0x01);
        static const QByteArray DISABLED = ControlCommand::createCommand(0x2C, 0x02, 0x02);

        inline std::optional<bool> parseState(const QByteArray &data)
        {
            if (!data.startsWith(HEADER) || data.size() < HEADER.size() + 2)
                return std::nullopt;

            QByteArray value = data.mid(HEADER.size(), 2);
            if (value.size() != 2)
                return std::nullopt;

            char b1 = value.at(0);
            char b2 = value.at(1);

            if (b1 == 0x01 && b2 == 0x01)
                return true;
            if (b1 == 0x02 || b2 == 0x02)
                return false;

            return std::nullopt;
        }
    }

    // Allow Off Option
    namespace AllowOffOption
    {
        using Type = BasicControlCommand<0x34>;
        static const QByteArray ENABLED = Type::ENABLED;
        static const QByteArray DISABLED = Type::DISABLED;
        static const QByteArray HEADER = Type::HEADER;
        inline std::optional<bool> parseState(const QByteArray &data) { return Type::parseState(data); }
    }

    // Connection Packets
    namespace Connection
    {
        static const QByteArray HANDSHAKE = QByteArray::fromHex("00000400010002000000000000000000");
        static const QByteArray SET_SPECIFIC_FEATURES = QByteArray::fromHex("040004004d00d700000000000000");
        static const QByteArray REQUEST_NOTIFICATIONS = QByteArray::fromHex("040004000f00ffffffffff");
        static const QByteArray AIRPODS_DISCONNECTED = QByteArray::fromHex("00010000");
    }

    // Phone Communication Packets
    namespace Phone
    {
        static const QByteArray NOTIFICATION = QByteArray::fromHex("00040001");
        static const QByteArray CONNECTED = QByteArray::fromHex("00010001");
        static const QByteArray DISCONNECTED = QByteArray::fromHex("00010000");
        static const QByteArray STATUS_REQUEST = QByteArray::fromHex("00020003");
        static const QByteArray DISCONNECT_REQUEST = QByteArray::fromHex("00020000");
    }

    // Adaptive Noise Packets
    namespace AdaptiveNoise
    {
        const QByteArray HEADER = QByteArray::fromHex("0400040009002E");

        inline QByteArray getPacket(int level)
        {
            return HEADER + static_cast<char>(level) + QByteArray::fromHex("000000");
        }
    }

    namespace Rename
    {
        static QByteArray getPacket(const QString &newName)
        {
            QByteArray nameBytes = newName.toUtf8();                   // Convert name to UTF-8
            quint8 size = static_cast<char>(nameBytes.size());         // Name length (1 byte)
            QByteArray packet = QByteArray::fromHex("040004001A0001"); // Header
            packet.append(size);                                       // Append size byte
            packet.append('\0');                                       // Append null byte
            packet.append(nameBytes);                                  // Append name bytes
            return packet;
        }
    }

    namespace MagicPairing {
        static const QByteArray REQUEST_MAGIC_CLOUD_KEYS = QByteArray::fromHex("0400040030000500");
        static const QByteArray MAGIC_CLOUD_KEYS_HEADER = QByteArray::fromHex("04000400310002");

        struct MagicCloudKeys {
            QByteArray magicAccIRK;      // 16 bytes
            QByteArray magicAccEncKey;    // 16 bytes
        };

        inline MagicCloudKeys parseMagicCloudKeysPacket(const QByteArray &data)
        {
            MagicCloudKeys keys;

            if (data.size() < 47 || !data.startsWith(MAGIC_CLOUD_KEYS_HEADER))
            {
                return keys;
            }

            int index = MAGIC_CLOUD_KEYS_HEADER.size();

            // First TLV block (MagicAccIRK)
            if (static_cast<quint8>(data.at(index)) != 0x01)
                return keys;
            index += 1;

            quint16 len1 = (static_cast<quint8>(data.at(index)) << 8) | static_cast<quint8>(data.at(index + 1));
            if (len1 != 16)
                return keys;
            index += 3; // Skip length (2 bytes) and reserved byte (1 byte)

            keys.magicAccIRK = data.mid(index, 16);
            index += 16;

            // Second TLV block (MagicAccEncKey)
            if (static_cast<quint8>(data.at(index)) != 0x04)
                return keys;
            index += 1;

            quint16 len2 = (static_cast<quint8>(data.at(index)) << 8) | static_cast<quint8>(data.at(index + 1));
            if (len2 != 16)
                return keys;
            index += 3; // Skip length (2 bytes) and reserved byte (1 byte)

            keys.magicAccEncKey = data.mid(index, 16);

            return keys;
        }
    }

    // Case Charging Sounds (AirPods Pro 2 / AirPods 4 only)
    namespace CaseChargingSounds
    {
        static QByteArray getPacket(bool enabled)
        {
            // 12 3A 00 01 00 08 [00=On, 01=Off]
            QByteArray packet = QByteArray::fromHex("123A00010008");
            packet.append(enabled ? static_cast<char>(0x00) : static_cast<char>(0x01));
            return packet;
        }
    }

    // Stem Long Press Configuration
    // Bitmask: bit0=Off(0x01), bit1=ANC(0x02), bit2=Transparency(0x04), bit3=Adaptive(0x08)
    // Min 2 bits must be set. Must be re-sent on every connection.
    namespace StemLongPress
    {
        static const QByteArray HEADER = ControlCommand::HEADER + static_cast<char>(0x1A);
        static const quint8 BIT_OFF          = 0x01;
        static const quint8 BIT_ANC          = 0x02;
        static const quint8 BIT_TRANSPARENCY = 0x04;
        static const quint8 BIT_ADAPTIVE     = 0x08;

        static QByteArray getPacket(quint8 modes)
        {
            return ControlCommand::createCommand(0x1A, modes);
        }

        inline std::optional<quint8> parseModes(const QByteArray &data)
        {
            if (!data.startsWith(HEADER) || data.size() < 8)
                return std::nullopt;
            return static_cast<quint8>(data.at(7));
        }
    }

    // Customize Transparency Mode (per-bud EQ + parameters as IEEE 754 floats LE)
    namespace CustomizeTransparency
    {
        static const QByteArray HEADER = QByteArray::fromHex("121800");

        struct BudSettings {
            float eq[8]              = {0,0,0,0,0,0,0,0}; // 0-100
            float amplification      = 0.0f;  // 0-2
            float tone               = 0.0f;  // 0-2
            float conversationBoost  = 0.0f;  // 0 or 1
            float ambientNoise       = 0.0f;  // 0-1
        };

        static QByteArray getPacket(bool enabled, const BudSettings &left, const BudSettings &right)
        {
            QByteArray packet = HEADER;

            auto appendF = [&](float f) {
                char bytes[4];
                memcpy(bytes, &f, 4);
                packet.append(bytes, 4);
            };

            appendF(enabled ? 1.0f : 0.0f);

            for (const BudSettings *b : {&left, &right}) {
                for (int i = 0; i < 8; i++) appendF(b->eq[i]);
                appendF(b->amplification);
                appendF(b->tone);
                appendF(b->conversationBoost);
                appendF(b->ambientNoise);
            }

            return packet;
        }
    }

    // Headphone Accommodation (8-band EQ for Phone/Media, uint16 LE per band, triplicated)
    namespace HeadphoneAccommodation
    {
        static QByteArray getPacket(bool phoneEnabled, bool mediaEnabled, const QList<int> &eq8)
        {
            QByteArray packet = QByteArray::fromHex("04000400530084000202");
            packet.append(phoneEnabled  ? static_cast<char>(0x01) : static_cast<char>(0x02));
            packet.append(mediaEnabled  ? static_cast<char>(0x01) : static_cast<char>(0x02));

            QByteArray eqBytes;
            for (int i = 0; i < 8; i++) {
                quint16 v = static_cast<quint16>((i < eq8.size()) ? eq8[i] : 0);
                eqBytes.append(static_cast<char>(v & 0xFF));
                eqBytes.append(static_cast<char>((v >> 8) & 0xFF));
            }
            packet.append(eqBytes);
            packet.append(eqBytes);
            packet.append(eqBytes);
            return packet;
        }
    }

    // Parsing Headers
    namespace Parse
    {
        static const QByteArray EAR_DETECTION = QByteArray::fromHex("040004000600");
        static const QByteArray BATTERY_STATUS = QByteArray::fromHex("040004000400");
        static const QByteArray METADATA = QByteArray::fromHex("040004001d");
        static const QByteArray HANDSHAKE_ACK = QByteArray::fromHex("01000400");
        static const QByteArray FEATURES_ACK = QByteArray::fromHex("040004002b00"); // Note: Only tested with airpods pro 2
    }
}

#endif // AIRPODS_PACKETS_H
