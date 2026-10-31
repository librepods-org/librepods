package me.kavishdevar.librepods.core.data.apple

data class AppleMotionReport(
    val version: Int,
    val flags: Int,
    val sensorTimestampUs: ULong,
    val quaternion: List<Float>,
    val rotationRate: List<Float>,
    val biasCovariance: List<Float>,
    val auxiliaryStatus: Int?,
    val gyroBias: List<Float>?,
    val userAcceleration: List<Float>,
    val filteredAcceleration: List<Float>,
    val quiescentNoise: Float,
    val quiescentMode: Int,
    val sequence: Int,
    val pitchDegrees: Float,
    val yawDegrees: Float,
    val dropped: Boolean
) {
    fun toDebugString(): String = buildString {
        append("v=$version size=${if (version == 3) 49 else 47} seq=$sequence timestamp=${sensorTimestampUs}us ")
        append(
            "flags=0x${
                flags.toString(16).padStart(4, '0')
            } dropped=$dropped quiescentMode=$quiescentMode "
        )
        append("q(x,y,z,w)=${quaternion.fmt()} pitch=${pitchDegrees.fmt()}° yaw=${yawDegrees.fmt()}° ")
        append("rotationRate(x,y,z)=${rotationRate.fmt()} ")
        append("userAcceleration(x,y,z)=${userAcceleration.fmt()} ")
        append("filteredAcceleration(x,y,z)=${filteredAcceleration.fmt()} ")
        append("biasCovariance=${biasCovariance.fmt()} ")
        append("gyroBias=${gyroBias?.fmt() ?: "unavailable"} ")
        append("auxiliaryStatus=${auxiliaryStatus ?: "unavailable"} quiescentNoise=${quiescentNoise.fmt()}")
    }
}

private fun List<Float>.fmt(): String = joinToString(prefix = "[", postfix = "]") { it.fmt() }
private fun Float.fmt(): String = "%.6f".format(java.util.Locale.US, this)
