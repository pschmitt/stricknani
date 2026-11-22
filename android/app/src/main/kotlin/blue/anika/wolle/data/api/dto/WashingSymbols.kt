package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WashingSymbols(
    val temperature: WashTemperature? = null,
    @SerialName("hand_wash_only") val handWashOnly: Boolean? = null,
    val drying: Drying? = null,
) {
    fun isEmpty(): Boolean = temperature == null && handWashOnly != true && drying == null
}

@Serializable
enum class WashTemperature(val degrees: Int) {
    @SerialName("30") THIRTY(30),
    @SerialName("40") FORTY(40),
    @SerialName("60") SIXTY(60),
}

@Serializable
enum class Drying {
    @SerialName("flat") FLAT,
    @SerialName("line") LINE,
    @SerialName("no_tumble") NO_TUMBLE,
}
