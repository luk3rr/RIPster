package model

data class RouteEntry(
    val nextHop: String,
    val cost: Int,
    val lastUpdate: Long
)
