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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LogCollector(private val context: Context) {
    private var isCollecting = false
    private var logProcess: Process? = null

    suspend fun openXposedSettings(context: Context) {
        withContext(Dispatchers.IO) {
            val command = if (android.os.Build.VERSION.SDK_INT >= 29) {
                "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
            } else {
                "am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://5776733 android"
            }

            executeRootCommand(command)
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            executeRootCommand("logcat -c")
        }
    }

    suspend fun killBluetoothService() {
        withContext(Dispatchers.IO) {
            executeRootCommand("killall com.android.bluetooth")
        }
    }

    private suspend fun getBluetoothUID(): String? {
        val pkgs = listOf("com.android.bluetooth", "com.google.android.bluetooth")
        for (pkg in pkgs) {
            val uid = executeRootCommand(
                "dumpsys package $pkg | grep -m 1 \"uid=\" | sed -E 's/.*uid=([0-9]+).*/\\1/'"
            ).trim()
            if (uid.isNotEmpty()) return uid
        }
        return null
    }

    private suspend fun getPackageUIDs(): Pair<String?, String?> {
        return withContext(Dispatchers.IO) {
            val btUid = getBluetoothUID()
            val appUid = executeRootCommand("dumpsys package me.kavishdevar.librepods | grep -m 1 \"uid=\" | sed -E 's/.*uid=([0-9]+).*/\\1/'")
                .trim()
                .takeIf { it.isNotEmpty() }

            Pair(btUid, appUid)
        }
    }

    suspend fun startLogCollection(listener: (String) -> Unit, connectionDetectedCallback: () -> Unit): String {
        return withContext(Dispatchers.IO) {
            isCollecting = true
            val (btUid, appUid) = getPackageUIDs()

            val uidFilter = buildString {
                if (!btUid.isNullOrEmpty() && !appUid.isNullOrEmpty()) {
                    append("$btUid,$appUid")
                } else if (!btUid.isNullOrEmpty()) {
                    append(btUid)
                } else if (!appUid.isNullOrEmpty()) {
                    append(appUid)
                }
            }

            val command = if (uidFilter.isNotEmpty()) {
                "su -c logcat --uid=$uidFilter -v threadtime"
            } else {
                "su -c logcat -v threadtime"
            }

            val logs = StringBuilder()
            try {
                logProcess = Runtime.getRuntime().exec(command)
                val reader = BufferedReader(InputStreamReader(logProcess!!.inputStream))
                var line: String? = null
                var connectionDetected = false

                while (isCollecting && reader.readLine().also { line = it } != null) {
                    line?.let {
                        if (it.contains("<LogCollector:")) {
                            logs.append("\n=============\n")
                        }

                        logs.append(it).append("\n")
                        listener(it)

                        if (it.contains("<LogCollector:")) {
                            logs.append("=============\n\n")
                        }

                        if (!connectionDetected) {
                            if (it.contains("<LogCollector:Complete:Success>")) {
                                connectionDetected = true
                                connectionDetectedCallback()
                            } else if (it.contains("<LogCollector:Complete:Failed>")) {
                                connectionDetected = true
                                connectionDetectedCallback()
                            } else if (it.contains("<LogCollector:Start>")) {
                            }
                            else if (it.contains("AirPodsService") && it.contains("Connected to device")) {
                                connectionDetected = true
                                connectionDetectedCallback()
                            } else if (it.contains("AirPodsService") && it.contains("Connection failed")) {
                                connectionDetected = true
                                connectionDetectedCallback()
                            } else if (it.contains("AirPodsService") && it.contains("Device disconnected")) {
                            }
                            else if (it.contains("BluetoothService") && it.contains("CONNECTION_STATE_CONNECTED")) {
                                connectionDetected = true
                                connectionDetectedCallback()
                            } else if (it.contains("BluetoothService") && it.contains("CONNECTION_STATE_DISCONNECTED")) {
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logs.append("Error collecting logs: ${e.message}").append("\n")
                e.printStackTrace()
            }

            logs.toString()
        }
    }

    fun stopLogCollection() {
        isCollecting = false
        logProcess?.destroy()
        logProcess = null
    }

    suspend fun saveLogToInternalStorage(fileName: String, content: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) {
                    logsDir.mkdir()
                }

                val file = File(logsDir, fileName)
                file.writeText(content)
                return@withContext file
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            }
        }
    }

    suspend fun addLogMarker(markerType: LogMarkerType, details: String = "") {
        withContext(Dispatchers.IO) {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())

            val marker = when (markerType) {
                LogMarkerType.START -> "<LogCollector:Start> [$timestamp] Beginning connection test"
                LogMarkerType.SUCCESS -> "<LogCollector:Complete:Success> [$timestamp] Connection test completed successfully"
                LogMarkerType.FAILURE -> "<LogCollector:Complete:Failed> [$timestamp] Connection test failed"
                LogMarkerType.CUSTOM -> "<LogCollector:Custom:$details> [$timestamp]"
            }

            val command = "log -t AirPodsService \"$marker\""
            executeRootCommand(command)
        }
    }

    enum class LogMarkerType {
        START,
        SUCCESS,
        FAILURE,
        CUSTOM
    }

    private suspend fun executeRootCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec("su -c $command")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                process.waitFor()
                output.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }
}
