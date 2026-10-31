package me.kavishdevar.librepods.core.data.headtracking


data class NeckPosture(
    val absolutePitchDeg: Float,
    val absoluteRollDeg: Float,
    val neckFlexionDeg: Float,
    val isSlouching: Boolean
) {
    fun toDebugString(): String {
        return "NeckPosture(absolutePitchDeg=$absolutePitchDeg, absoluteRollDeg=$absoluteRollDeg, neckFlexionDeg=$neckFlexionDeg, isSlouching=$isSlouching)"
    }
}
