package model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val type: String,
    val source: String,
    val destination: String,
    val payload: String? = null,
    val distances: Map<String, Int>? = null,
    val routers: List<String>? = null
)
