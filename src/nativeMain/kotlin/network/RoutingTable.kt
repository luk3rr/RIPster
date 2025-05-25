package network

import model.RouteEntry
import utils.TimeUtils

class RoutingTable() {
    val routes = mutableMapOf<String, RouteEntry>()

    fun existsRoute(ip: String): Boolean = routes.containsKey(ip)

    fun addRoute(to: String, nextHop: String, cost: Int) {
        val currentEntry = routes[to]
        if (currentEntry == null || cost < currentEntry.cost || nextHop != currentEntry.nextHop) {
            routes[to] = RouteEntry(nextHop, cost, TimeUtils.getTimeMillis())
        }
    }

    fun removeRoute(ip: String) { routes.remove(ip) }

    fun getNextHop(ip: String): String? = routes[ip]?.nextHop

    fun getRoute(ip: String): RouteEntry? = routes[ip]

    override fun toString(): String {
        val header = listOf("Destination", "Next Hop", "Cost", "Last Updated")
        val colWidths = listOf(15, 15, 10, 20)
        val separator = "+${colWidths.joinToString("+") { "-".repeat(it) }}+"

        fun formatRow(values: List<String>): String {
            return values.mapIndexed { index, value ->
                value.padEnd(colWidths[index])
            }.joinToString(" | ", prefix = "| ", postfix = " |")
        }

        val headerRow = formatRow(header)
        val rows = routes.map { (destination, entry) ->
            formatRow(
                listOf(
                    destination,
                    entry.nextHop,
                    entry.cost.toString(),
                    entry.timestamp.toString()
                )
            )
        }.joinToString("\n")

        return "$separator\n$headerRow\n$separator\n$rows\n$separator"
    }
}