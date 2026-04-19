/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data

import me.kavishdevar.librepods.utils.AACPManager

class ControlCommandRepository(
    private val aacpManager: AACPManager
) {
    fun getValue(
        identifier: AACPManager.Companion.ControlCommandIdentifiers
    ): ByteArray? {
        return aacpManager.controlCommandStatusList
            .find { it.identifier == identifier }
            ?.value
    }

    fun setValue(
        id: AACPManager.Companion.ControlCommandIdentifiers,
        value: ByteArray
    ) {
        aacpManager.sendControlCommand(id.value, value)
    }


    fun observe(
        identifier: AACPManager.Companion.ControlCommandIdentifiers,
        onChange: (ByteArray) -> Unit
    ): AACPManager.ControlCommandListener {

        val listener = object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                onChange(controlCommand.value)
            }
        }

        aacpManager.registerControlCommandListener(identifier, listener)
        return listener
    }

    fun remove(
        identifier: AACPManager.Companion.ControlCommandIdentifiers,
        listener: AACPManager.ControlCommandListener
    ) {
        aacpManager.unregisterControlCommandListener(identifier, listener)
    }
}
