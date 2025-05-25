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
        val header = "Destination".padEnd(15) + "Next Hop".padEnd(15) + "Cost".padEnd(10) + "Last Updated".padEnd(20)
        val separator = "-".repeat(60)
        val rows = routes.map { (destination: String, entry: RouteEntry) ->
            destination.padEnd(15) +
                    entry.nextHop.padEnd(15) +
                    entry.cost.toString().padEnd(10) +
                    entry.timestamp.toString().padEnd(20)
        }.joinToString("\n")

        return "$header\n$separator\n$rows"
    }
}