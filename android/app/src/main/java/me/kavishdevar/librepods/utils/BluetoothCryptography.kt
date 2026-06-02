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

package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Utilities for Bluetooth cryptography operations, particularly for
 * verifying Resolvable Private Addresses (RPA) used by AirPods.
 */
object BluetoothCryptography {

    /**
     * Verifies if the provided Bluetooth address is an RPA that matches the given Identity Resolving Key (IRK)
     *
     * @param addr The Bluetooth address to verify
     * @param irk The Identity Resolving Key to use for verification
     * @return true if the address is verified as an RPA matching the IRK
     */
    fun verifyRPA(addr: String, irk: ByteArray): Boolean {
        val rpa = addr.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
        val prand = rpa.copyOfRange(3, 6)
        val hash = rpa.copyOfRange(0, 3)
        val computedHash = ah(irk, prand)
        return hash.contentEquals(computedHash)
    }

    /**
     * Performs E function (AES-128) as specified in Bluetooth Core Specification
     *
     * @param key The key for encryption
     * @param data The data to encrypt
     * @return The encrypted data
     */
    @SuppressLint("GetInstance")
    fun e(key: ByteArray, data: ByteArray): ByteArray {
        val swappedKey = key.reversedArray()
        val swappedData = data.reversedArray()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val secretKey = SecretKeySpec(swappedKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(swappedData).reversedArray()
    }

    /**
     * Performs the ah function as specified in Bluetooth Core Specification
     *
     * @param k The IRK key
     * @param r The random part of the address
     * @return The hash part of the address
     */
    fun ah(k: ByteArray, r: ByteArray): ByteArray {
        val rPadded = ByteArray(16)
        r.copyInto(rPadded, 0, 0, 3)
        val encrypted = e(k, rPadded)
        return encrypted.copyOfRange(0, 3)
    }
}
