package model

data class RouteEntry(
    val nextHop: String,
    val cost: Int,
    val timestamp: Long
)
