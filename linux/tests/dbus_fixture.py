#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""External BlueZ fixture; all traffic stays on the supplied private bus."""

import os
import sys

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

DBusGMainLoop(set_as_default=True)
address = os.environ["DBUS_SYSTEM_BUS_ADDRESS"]
bluez_bus = dbus.bus.BusConnection(address)
noise_bus = dbus.bus.BusConnection(address)
bluez_name = dbus.service.BusName("org.bluez", bus=bluez_bus)
noise_name = dbus.service.BusName("org.librepods.TestNoise", bus=noise_bus)
DEVICE_PATH = "/org/bluez/hci7/dev_00_11_22_33_44_55"
DEVICE_INTERFACE = "org.bluez.Device1"
PROPERTIES = "org.freedesktop.DBus.Properties"
UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"


class Device(dbus.service.Object):
    def __init__(self):
        super().__init__(bluez_bus, DEVICE_PATH)
        self.delay_ms = 0
        self.properties = {
            "UUIDs": dbus.Array([UUID], signature="s"),
            "Connected": dbus.Boolean(False),
            "Address": dbus.String("00:11:22:33:44:55"),
            "Name": dbus.String("LibrePods test device"),
        }

    @dbus.service.method(PROPERTIES, in_signature="ss", out_signature="v",
                         async_callbacks=("reply", "error"))
    def Get(self, interface, name, reply, error):
        if interface != DEVICE_INTERFACE or name not in self.properties:
            error(dbus.exceptions.DBusException(
                "Unknown property", name="org.freedesktop.DBus.Error.InvalidArgs"))
            return
        value = self.properties[name]
        if not self.delay_ms:
            reply(value)
            return

        def finish():
            reply(value)
            return GLib.SOURCE_REMOVE

        GLib.timeout_add(self.delay_ms, finish)

    @dbus.service.signal(PROPERTIES, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changes, invalidated):
        pass


class Noise(dbus.service.Object):
    @dbus.service.signal(PROPERTIES, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changes, invalidated):
        pass


device = Device()
noise = Noise(noise_bus, "/org/librepods/TestNoise")
bluez_noise = Noise(bluez_bus, "/org/bluez/hci7")


class Manager(dbus.service.Object):
    @dbus.service.method("org.freedesktop.DBus.ObjectManager", out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        return {dbus.ObjectPath(DEVICE_PATH): {DEVICE_INTERFACE: device.properties}}

    @dbus.service.method("org.librepods.Test", in_signature="", out_signature="")
    def CycleConnections(self):
        transitions = iter([True, False, True, False, True])
        device.delay_ms = 0

        def transition():
            try:
                connected = next(transitions)
            except StopIteration:
                print("CONNECTION_CYCLES_SENT", flush=True)
                return GLib.SOURCE_REMOVE
            device.properties["Connected"] = dbus.Boolean(connected)
            device.PropertiesChanged(DEVICE_INTERFACE,
                                     {"Connected": dbus.Boolean(connected)}, [])
            return GLib.SOURCE_CONTINUE

        GLib.timeout_add(250, transition)

    @dbus.service.method("org.librepods.Test", in_signature="uuus", out_signature="")
    def Start(self, delay_ms, message_count, payload_bytes, noise_sender):
        emitter = {"foreign": noise, "bluez": bluez_noise}[str(noise_sender)]
        noise_interface = ("org.bluez.Adapter1" if str(noise_sender) == "bluez"
                           else "org.librepods.Unrelated")
        device.delay_ms = int(delay_ms)
        device.properties["Connected"] = dbus.Boolean(True)
        device.PropertiesChanged(DEVICE_INTERFACE,
                                 {"Connected": dbus.Boolean(True)}, [])
        remaining = int(message_count)
        payload = dbus.String("x" * int(payload_bytes))

        def send_batch():
            nonlocal remaining
            for _ in range(min(8, remaining)):
                emitter.PropertiesChanged(noise_interface,
                                          {"Payload": payload}, [])
                remaining -= 1
            if not remaining:
                print("LOAD_SENT", flush=True)
                return GLib.SOURCE_REMOVE
            return GLib.SOURCE_CONTINUE

        GLib.timeout_add(100, lambda: (GLib.timeout_add(2, send_batch),
                                      GLib.SOURCE_REMOVE)[1])


manager = Manager(bluez_bus, "/")
print("FIXTURE_READY", flush=True)
GLib.MainLoop().run()
