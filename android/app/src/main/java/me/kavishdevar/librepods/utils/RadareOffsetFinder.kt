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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.content.Context
import android.util.Log
import androidx.compose.runtime.NoLiveLiterals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.services.ServiceManager
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.ExperimentalEncodingApi

@NoLiveLiterals
class RadareOffsetFinder(context: Context) {
    companion object {
        private const val TAG = "RadareOffsetFinder"
        private const val RADARE2_URL = "https://github.com/devnoname120/radare2/releases/download/5.9.8-android-aln/radare2-5.9.9-android-aarch64-aln.tar.gz"
        private const val HOOK_OFFSET_PROP = "persist.librepods.hook_offset"
        private const val CFG_REQ_OFFSET_PROP = "persist.librepods.cfg_req_offset"
        private const val CSM_CONFIG_OFFSET_PROP = "persist.librepods.csm_config_offset"
        private const val PEER_INFO_REQ_OFFSET_PROP = "persist.librepods.peer_info_req_offset"
        private const val SDP_OFFSET_PROP = "persist.librepods.sdp_offset"
        private const val EXTRACT_DIR = "/"

        private const val RADARE2_BIN_PATH = "$EXTRACT_DIR/data/local/tmp/aln_unzip/org.radare.radare2installer/radare2/bin"
        private const val RADARE2_LIB_PATH = "$EXTRACT_DIR/data/local/tmp/aln_unzip/org.radare.radare2installer/radare2/lib"
        private const val BUSYBOX_PATH = "$EXTRACT_DIR/data/local/tmp/aln_unzip/busybox"

        private val LIBRARY_PATHS = listOf(
            "/apex/com.android.bt/lib64/libbluetooth_jni.so",
            "/apex/com.android.btservices/lib64/libbluetooth_jni.so",
            "/system/lib64/libbluetooth_jni.so",
            "/system/lib64/libbluetooth_qti.so",
            "/system_ext/lib64/libbluetooth_qti.so"
        )

        fun findBluetoothLibraryPath(): String? {
            for (path in LIBRARY_PATHS) {
                if (File(path).exists()) {
                    Log.d(TAG, "Found Bluetooth library at $path")
                    return path
                }
            }
            Log.e(TAG, "Could not find Bluetooth library")
            return null
        }

        fun clearHookOffsets(): Boolean {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "su", "-c",
                    "/system/bin/setprop $HOOK_OFFSET_PROP '' && " +
                    "/system/bin/setprop $CFG_REQ_OFFSET_PROP '' && " +
                    "/system/bin/setprop $CSM_CONFIG_OFFSET_PROP '' && " +
                    "/system/bin/setprop $PEER_INFO_REQ_OFFSET_PROP '' &&" +
                    "/system/bin/setprop $SDP_OFFSET_PROP ''"
                ))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    Log.d(TAG, "Successfully cleared hook offset properties")
                    return true
                } else {
                    Log.e(TAG, "Failed to clear hook offset properties, exit code: $exitCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing hook offset properties", e)
            }
            return false
        }

        fun clearSdpOffset(): Boolean {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "su", "-c", "/system/bin/setprop $SDP_OFFSET_PROP ''"
                ))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    Log.d(TAG, "Successfully cleared SDP offset property")
                    return true
                } else {
                    Log.e(TAG, "Failed to clear SDP offset property, exit code: $exitCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing SDP offset property", e)
            }
            return false
        }

        fun isSdpOffsetAvailable(): Boolean {
            val sharedPreferences = ServiceManager.getService()?.applicationContext?.getSharedPreferences("settings", Context.MODE_PRIVATE) // ik not good practice- too lazy
            if (sharedPreferences?.getBoolean("skip_setup", false) == true) {
                Log.d(TAG, "Setup skipped, returning true for SDP offset.")
                return true
            }
            try {
                val process = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", SDP_OFFSET_PROP))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val propValue = reader.readLine()
                process.waitFor()

                if (propValue != null && propValue.isNotEmpty()) {
                    Log.d(TAG, "SDP offset property exists: $propValue")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking if SDP offset property exists", e)
            }

            Log.d(TAG, "No SDP offset available")
            return false
        }
    }

    private val radare2TarballFile = File(context.cacheDir, "radare2.tar.gz")

    private val _progressState = MutableStateFlow<ProgressState>(ProgressState.Idle)
    val progressState: StateFlow<ProgressState> = _progressState

    sealed class ProgressState {
        object Idle : ProgressState()
        object CheckingExisting : ProgressState()
        object Downloading : ProgressState()
        data class DownloadProgress(val progress: Float) : ProgressState()
        object Extracting : ProgressState()
        object MakingExecutable : ProgressState()
        object FindingOffset : ProgressState()
        object SavingOffset : ProgressState()
        object Cleaning : ProgressState()
        data class Error(val message: String) : ProgressState()
        data class Success(val offset: Long) : ProgressState()
    }


    fun isHookOffsetAvailable(): Boolean {
        Log.d(TAG, "Setup Skipped? " + ServiceManager.getService()?.applicationContext?.getSharedPreferences("settings", Context.MODE_PRIVATE)?.getBoolean("skip_setup", false).toString())
        if (ServiceManager.getService()?.applicationContext?.getSharedPreferences("settings", Context.MODE_PRIVATE)?.getBoolean("skip_setup", false) == true) {
            Log.d(TAG, "Setup skipped, returning true.")
            return true
        }
        _progressState.value = ProgressState.CheckingExisting
        try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", HOOK_OFFSET_PROP))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val propValue = reader.readLine()
            process.waitFor()

            if (propValue != null && propValue.isNotEmpty()) {
                Log.d(TAG, "Hook offset property exists: $propValue")
                _progressState.value = ProgressState.Idle
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if offset property exists", e)
            _progressState.value = ProgressState.Error("Failed to check if offset property exists: ${e.message}")
        }

        Log.d(TAG, "No hook offset available")
        _progressState.value = ProgressState.Idle
        return false
    }

    suspend fun setupAndFindOffset(): Boolean {
        val offset = findOffset()
        return offset > 0
    }

    suspend fun findOffset(): Long = withContext(Dispatchers.IO) {
        try {
            _progressState.value = ProgressState.Downloading
            if (!downloadRadare2TarballIfNeeded()) {
                _progressState.value = ProgressState.Error("Failed to download radare2 tarball")
                Log.e(TAG, "Failed to download radare2 tarball")
                return@withContext 0L
            }

            _progressState.value = ProgressState.Extracting
            if (!extractRadare2Tarball()) {
                _progressState.value = ProgressState.Error("Failed to extract radare2 tarball")
                Log.e(TAG, "Failed to extract radare2 tarball")
                return@withContext 0L
            }

            _progressState.value = ProgressState.MakingExecutable
            if (!makeExecutable()) {
                _progressState.value = ProgressState.Error("Failed to make binaries executable")
                Log.e(TAG, "Failed to make binaries executable")
                return@withContext 0L
            }

            _progressState.value = ProgressState.FindingOffset
            val offset = findFunctionOffset()
            if (offset == 0L) {
                _progressState.value = ProgressState.Error("Failed to find function offset")
                Log.e(TAG, "Failed to find function offset")
                return@withContext 0L
            }

            _progressState.value = ProgressState.SavingOffset
            if (!saveOffset(offset)) {
                _progressState.value = ProgressState.Error("Failed to save offset")
                Log.e(TAG, "Failed to save offset")
                return@withContext 0L
            }

            _progressState.value = ProgressState.Cleaning
            cleanupExtractedFiles()

            _progressState.value = ProgressState.Success(offset)
            return@withContext offset

        } catch (e: Exception) {
            _progressState.value = ProgressState.Error("Error: ${e.message}")
            Log.e(TAG, "Error in findOffset", e)
            return@withContext 0L
        }
    }

    private suspend fun downloadRadare2TarballIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (radare2TarballFile.exists() && radare2TarballFile.length() > 0) {
            Log.d(TAG, "Radare2 tarball already downloaded to ${radare2TarballFile.absolutePath}")
            return@withContext true
        }

        try {
            val url = URL(RADARE2_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 60000
            connection.readTimeout = 60000

            val contentLength = connection.contentLength.toFloat()
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(radare2TarballFile)

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = totalBytesRead.toFloat() / contentLength
                    _progressState.value = ProgressState.DownloadProgress(progress)
                }
            }

            outputStream.close()
            inputStream.close()

            Log.d(TAG, "Download successful to ${radare2TarballFile.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download radare2 tarball", e)
            return@withContext false
        }
    }

    private suspend fun extractRadare2Tarball(): Boolean = withContext(Dispatchers.IO) {
        try {
            val isAlreadyExtracted = checkIfAlreadyExtracted()

            if (isAlreadyExtracted) {
                Log.d(TAG, "Radare2 files already extracted correctly, skipping extraction")
                return@withContext true
            }

            Log.d(TAG, "Removing existing extract directory")
            Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf $EXTRACT_DIR/data/local/tmp/aln_unzip")).waitFor()

            Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p $EXTRACT_DIR/data/local/tmp/aln_unzip")).waitFor()

            Log.d(TAG, "Extracting ${radare2TarballFile.absolutePath} to $EXTRACT_DIR")

            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "tar xvf ${radare2TarballFile.absolutePath} -C $EXTRACT_DIR")
            )

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "Extract output: $line")
            }

            while (errorReader.readLine().also { line = it } != null) {
                Log.e(TAG, "Extract error: $line")
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.d(TAG, "Extraction completed successfully")
                return@withContext true
            } else {
                Log.e(TAG, "Extraction failed with exit code $exitCode")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract radare2", e)
            return@withContext false
        }
    }

    private suspend fun checkIfAlreadyExtracted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val checkDirProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "[ -d $EXTRACT_DIR/data/local/tmp/aln_unzip ] && echo 'exists'")
            )
            val dirExists = BufferedReader(InputStreamReader(checkDirProcess.inputStream)).readLine() == "exists"
            checkDirProcess.waitFor()

            if (!dirExists) {
                Log.d(TAG, "Extract directory doesn't exist, need to extract")
                return@withContext false
            }

            val tarProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "tar tf ${radare2TarballFile.absolutePath}")
            )
            val tarFiles = BufferedReader(InputStreamReader(tarProcess.inputStream)).readLines()
                .filter { it.isNotEmpty() }
                .map { it.trim() }
                .toSet()
            tarProcess.waitFor()

            if (tarFiles.isEmpty()) {
                Log.e(TAG, "Failed to get file list from tarball")
                return@withContext false
            }

            val findProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "find $EXTRACT_DIR/data/local/tmp/aln_unzip -type f | sort")
            )
            val extractedFiles = BufferedReader(InputStreamReader(findProcess.inputStream)).readLines()
                .filter { it.isNotEmpty() }
                .map { it.trim() }
                .toSet()
            findProcess.waitFor()

            if (extractedFiles.isEmpty()) {
                Log.d(TAG, "No files found in extract directory, need to extract")
                return@withContext false
            }

            for (tarFile in tarFiles) {
                if (tarFile.endsWith("/")) continue

                val filePathInExtractDir = "$EXTRACT_DIR/$tarFile"
                val fileCheckProcess = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "[ -f $filePathInExtractDir ] && echo 'exists'")
                )
                val fileExists = BufferedReader(InputStreamReader(fileCheckProcess.inputStream)).readLine() == "exists"
                fileCheckProcess.waitFor()

                if (!fileExists) {
                    Log.d(TAG, "File $filePathInExtractDir from tarball missing in extract directory")
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf $EXTRACT_DIR/data/local/tmp/aln_unzip")).waitFor()
                    return@withContext false
                }
            }

            Log.d(TAG, "All ${tarFiles.size} files from tarball exist in extract directory")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking extraction status", e)
            return@withContext false
        }
    }

    private suspend fun makeExecutable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Making binaries executable in $RADARE2_BIN_PATH")
            val chmod1Result = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "chmod -R 755 $RADARE2_BIN_PATH")
            ).waitFor()

            Log.d(TAG, "Making binaries executable in $BUSYBOX_PATH")

            val chmod2Result = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "chmod -R 755 $BUSYBOX_PATH")
            ).waitFor()

            if (chmod1Result == 0 && chmod2Result == 0) {
                Log.d(TAG, "Successfully made binaries executable")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to make binaries executable, exit codes: $chmod1Result, $chmod2Result")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error making binaries executable", e)
            return@withContext false
        }
    }

    private suspend fun findFunctionOffset(): Long = withContext(Dispatchers.IO) {
        val libraryPath = findBluetoothLibraryPath() ?: return@withContext 0L
        var offset = 0L

        try {
            @Suppress("LocalVariableName") val currentLD_LIBRARY_PATH = ProcessBuilder().command("su", "-c", "printenv LD_LIBRARY_PATH").start().inputStream.bufferedReader().readText().trim()
            val currentPATH = ProcessBuilder().command("su", "-c", "printenv PATH").start().inputStream.bufferedReader().readText().trim()
            val envSetup = """
                export LD_LIBRARY_PATH="$RADARE2_LIB_PATH:$currentLD_LIBRARY_PATH"
                export PATH="$BUSYBOX_PATH:$RADARE2_BIN_PATH:$currentPATH"
            """.trimIndent()

            val command = "$envSetup && $RADARE2_BIN_PATH/rabin2 -q -E $libraryPath | grep fcr_chk_chan"
            Log.d(TAG, "Running command: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?

            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 output: $line")
                if (line?.contains("fcr_chk_chan") == true) {
                    val parts = line.split(" ")
                    if (parts.isNotEmpty() && parts[0].startsWith("0x")) {
                        offset = parts[0].substring(2).toLong(16)
                        Log.d(TAG, "Found offset at ${parts[0]}")
                        break
                    }
                }
            }

            while (errorReader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 error: $line")
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "rabin2 command failed with exit code $exitCode")
            }

//            findAndSaveL2cuProcessCfgReqOffset(libraryPath, envSetup)
//            findAndSaveL2cCsmConfigOffset(libraryPath, envSetup)
//            findAndSaveL2cuSendPeerInfoReqOffset(libraryPath, envSetup)

            // findAndSaveSdpOffset(libraryPath, envSetup) Should not be run by default, only when user asks for it.

        } catch (e: Exception) {
            Log.e(TAG, "Failed to find function offset", e)
            return@withContext 0L
        }

        if (offset == 0L) {
            Log.e(TAG, "Failed to extract function offset from output, aborting")
            return@withContext 0L
        }

        Log.d(TAG, "Successfully found offset: 0x${offset.toString(16)}")
        return@withContext offset
    }

    private suspend fun findAndSaveL2cuProcessCfgReqOffset(libraryPath: String, envSetup: String) = withContext(Dispatchers.IO) {
        try {
            val command = "$envSetup && $RADARE2_BIN_PATH/rabin2 -q -E $libraryPath | grep l2cu_process_our_cfg_req"
            Log.d(TAG, "Running command: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            var offset = 0L

            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 output: $line")
                if (line?.contains("l2cu_process_our_cfg_req") == true) {
                    val parts = line.split(" ")
                    if (parts.isNotEmpty() && parts[0].startsWith("0x")) {
                        offset = parts[0].substring(2).toLong(16)
                        Log.d(TAG, "Found l2cu_process_our_cfg_req offset at ${parts[0]}")
                        break
                    }
                }
            }

            while (errorReader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 error: $line")
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "rabin2 command failed with exit code $exitCode")
            }

            if (offset > 0L) {
                val hexString = "0x${offset.toString(16)}"
                Runtime.getRuntime().exec(arrayOf(
                    "su", "-c", "/system/bin/setprop $CFG_REQ_OFFSET_PROP $hexString"
                )).waitFor()
                Log.d(TAG, "Saved l2cu_process_our_cfg_req offset: $hexString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find or save l2cu_process_our_cfg_req offset", e)
        }
    }

    private suspend fun findAndSaveL2cCsmConfigOffset(libraryPath: String, envSetup: String) = withContext(Dispatchers.IO) {
        try {
            val command = "$envSetup && $RADARE2_BIN_PATH/rabin2 -q -E $libraryPath | grep l2c_csm_config"
            Log.d(TAG, "Running command: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            var offset = 0L

            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 output: $line")
                if (line?.contains("l2c_csm_config") == true) {
                    val parts = line.split(" ")
                    if (parts.isNotEmpty() && parts[0].startsWith("0x")) {
                        offset = parts[0].substring(2).toLong(16)
                        Log.d(TAG, "Found l2c_csm_config offset at ${parts[0]}")
                        break
                    }
                }
            }

            while (errorReader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 error: $line")
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "rabin2 command failed with exit code $exitCode")
            }

            if (offset > 0L) {
                val hexString = "0x${offset.toString(16)}"
                Runtime.getRuntime().exec(arrayOf(
                    "su", "-c", "/system/bin/setprop $CSM_CONFIG_OFFSET_PROP $hexString"
                )).waitFor()
                Log.d(TAG, "Saved l2c_csm_config offset: $hexString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find or save l2c_csm_config offset", e)
        }
    }

    private suspend fun findAndSaveL2cuSendPeerInfoReqOffset(libraryPath: String, envSetup: String) = withContext(Dispatchers.IO) {
        try {
            val command = "$envSetup && $RADARE2_BIN_PATH/rabin2 -q -E $libraryPath | grep l2cu_send_peer_info_req"
            Log.d(TAG, "Running command: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            var offset = 0L

            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 output: $line")
                if (line?.contains("l2cu_send_peer_info_req") == true) {
                    val parts = line.split(" ")
                    if (parts.isNotEmpty() && parts[0].startsWith("0x")) {
                        offset = parts[0].substring(2).toLong(16)
                        Log.d(TAG, "Found l2cu_send_peer_info_req offset at ${parts[0]}")
                        break
                    }
                }
            }

            while (errorReader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 error: $line")
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "rabin2 command failed with exit code $exitCode")
            }

            if (offset > 0L) {
                val hexString = "0x${offset.toString(16)}"
                Runtime.getRuntime().exec(arrayOf(
                    "su", "-c", "/system/bin/setprop $PEER_INFO_REQ_OFFSET_PROP $hexString"
                )).waitFor()
                Log.d(TAG, "Saved l2cu_send_peer_info_req offset: $hexString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find or save l2cu_send_peer_info_req offset", e)
        }
    }

    private suspend fun findAndSaveSdpOffset(libraryPath: String, envSetup: String) = withContext(Dispatchers.IO) {
        try {
            val command = "$envSetup && $RADARE2_BIN_PATH/rabin2 -q -E $libraryPath | grep DmSetLocalDiRecord"
            Log.d(TAG, "Running command: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            var offset = 0L

            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 output: $line")
                if (line?.contains("DmSetLocalDiRecord") == true) {
                    val parts = line.split(" ")
                    if (parts.isNotEmpty() && parts[0].startsWith("0x")) {
                        offset = parts[0].substring(2).toLong(16)
                        Log.d(TAG, "Found DmSetLocalDiRecord offset at ${parts[0]}")
                        break
                    }
                }
            }

            while (errorReader.readLine().also { line = it } != null) {
                Log.d(TAG, "rabin2 error: $line")
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "rabin2 command failed with exit code $exitCode")
            }

            if (offset > 0L) {
                val hexString = "0x${offset.toString(16)}"
                Runtime.getRuntime().exec(arrayOf(
                    "su", "-c", "/system/bin/setprop $SDP_OFFSET_PROP $hexString"
                )).waitFor()
                Log.d(TAG, "Saved DmSetLocalDiRecord offset: $hexString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find or save DmSetLocalDiRecord offset", e)
        }
    }

    private suspend fun saveOffset(offset: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val hexString = "0x${offset.toString(16)}"
            Log.d(TAG, "Saving offset to system property: $hexString")

            val process = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "/system/bin/setprop $HOOK_OFFSET_PROP $hexString"
            ))

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val verifyProcess = Runtime.getRuntime().exec(arrayOf(
                    "/system/bin/getprop", HOOK_OFFSET_PROP
                ))
                val propValue = BufferedReader(InputStreamReader(verifyProcess.inputStream)).readLine()
                verifyProcess.waitFor()

                if (propValue != null && propValue.isNotEmpty()) {
                    Log.d(TAG, "Successfully saved offset to system property: $propValue")
                    return@withContext true
                } else {
                    Log.e(TAG, "Property was set but couldn't be verified")
                }
            } else {
                Log.e(TAG, "Failed to set property, exit code: $exitCode")
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save offset", e)
            return@withContext false
        }
    }

    private fun cleanupExtractedFiles() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf $EXTRACT_DIR/data/local/tmp/aln_unzip")).waitFor()
            Log.d(TAG, "Cleaned up extracted files at $EXTRACT_DIR/data/local/tmp/aln_unzip")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup extracted files", e)
        }
    }

    suspend fun findSdpOffset(): Boolean = withContext(Dispatchers.IO) {
        try {
            _progressState.value = ProgressState.Downloading
            if (!downloadRadare2TarballIfNeeded()) {
                _progressState.value = ProgressState.Error("Failed to download radare2 tarball")
                Log.e(TAG, "Failed to download radare2 tarball")
                return@withContext false
            }

            _progressState.value = ProgressState.Extracting
            if (!extractRadare2Tarball()) {
                _progressState.value = ProgressState.Error("Failed to extract radare2 tarball")
                Log.e(TAG, "Failed to extract radare2 tarball")
                return@withContext false
            }

            _progressState.value = ProgressState.MakingExecutable
            if (!makeExecutable()) {
                _progressState.value = ProgressState.Error("Failed to make binaries executable")
                Log.e(TAG, "Failed to make binaries executable")
                return@withContext false
            }

            _progressState.value = ProgressState.FindingOffset
            val libraryPath = findBluetoothLibraryPath()
            if (libraryPath == null) {
                _progressState.value = ProgressState.Error("Failed to find Bluetooth library")
                Log.e(TAG, "Failed to find Bluetooth library")
                return@withContext false
            }

            @Suppress("LocalVariableName") val currentLD_LIBRARY_PATH = ProcessBuilder().command("su", "-c", "printenv LD_LIBRARY_PATH").start().inputStream.bufferedReader().readText().trim()
            val currentPATH = ProcessBuilder().command("su", "-c", "printenv PATH").start().inputStream.bufferedReader().readText().trim()
            val envSetup = """
                export LD_LIBRARY_PATH="$RADARE2_LIB_PATH:$currentLD_LIBRARY_PATH"
                export PATH="$BUSYBOX_PATH:$RADARE2_BIN_PATH:$currentPATH"
            """.trimIndent()

            findAndSaveSdpOffset(libraryPath, envSetup)

            _progressState.value = ProgressState.Cleaning
            cleanupExtractedFiles()

            _progressState.value = ProgressState.Success(0L)
            return@withContext true

        } catch (e: Exception) {
            _progressState.value = ProgressState.Error("Error: ${e.message}")
            Log.e(TAG, "Error in findSdpOffset", e)
            return@withContext false
        }
    }
}
