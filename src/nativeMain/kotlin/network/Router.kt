package network

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import model.Message
import utils.CommandProcessor
import utils.FileManager
import utils.Logger
import utils.TimeUtils

@OptIn(ExperimentalForeignApi::class)
class Router(
    private val myIp: String,
    private val period: Int,
    private val startupTopology: String?
) {
    companion object {
        const val ROUTER_PORT = 12345
        const val TOPOLOGY_PATH = "topology"
        const val STARTUP_FILE_NAME_PREFIX = "router_"
        const val STALE_THRESHOLD_SECONDS = 5
    }

    private val neighbors = mutableMapOf<String, Int>()
    private val routingTable = RoutingTable()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val networkInterface = NetworkInterface(myIp, ROUTER_PORT)

    private val messageHandler = MessageHandler(
        myIp,
        routingTable,
        neighbors,
        networkInterface
    )

    private val commandProcessor = CommandProcessor(this)

    private val fileManager = FileManager()

    private val logger = Logger(
        tag = "Router"
    )

    init {
        routingTable.addRoute(myIp, myIp, 0)
        logger.info { "Router started on $myIp" }
    }

    fun start() = runBlocking {
        if (startupTopology != null) {
            val path = "$TOPOLOGY_PATH/$startupTopology/$STARTUP_FILE_NAME_PREFIX${myIp.replace('.', '_')}.txt"
            processStartupFile(path)
        }

        scope.launch { receiveLoop() }
        scope.launch { updateLoop() }
        scope.launch { cleanStaleRoutes() }

        commandProcessor.commandLoop()
        networkInterface.stop()
        job.cancel()
    }

    fun showRoutingTable() {
        println(routingTable)
    }

    fun showNeighbors() {
        println(neighbors)
    }

    fun addLink(ip: String, weight: Int) {
        neighbors[ip] = weight
        logger.info { "Link established with $ip" }
    }

    fun removeLink(ip: String) {
        neighbors.remove(ip)

        val routesToInvalidate = routingTable.routes.filterValues { it.nextHop == ip }.keys

        routesToInvalidate.forEach { dest ->
            routingTable.removeRoute(dest)
            logger.info { "Route to $dest invalidated due to removal of neighbor $ip." }
        }

        logger.info { "Link removed with $ip" }
    }

    fun sendTrace(destinationIp: String) {
        logger.info { "Starting trace to $destinationIp" }

        val initialTraceMessage = Message(
            type = "trace",
            source = myIp,
            destination = destinationIp,
            routers = listOf(myIp)
        )

        if (initialTraceMessage.destination == myIp) {
            messageHandler.handle(initialTraceMessage)
        } else {
            val route = routingTable.getRoute(destinationIp)

            if (route != null) {
                networkInterface.sendMessage(initialTraceMessage, route.nextHop)
            } else {
                logger.warn { "No route to $destinationIp. Unable to start trace." }
            }
        }
    }

    private fun receiveLoop() {
        while (true) {
            val message = networkInterface.receiveBlocking()

            if (message != null) {
                messageHandler.handle(message)
            }
        }
    }

    private suspend fun updateLoop() {
        while (true) {
            delay((period * TimeUtils.SECOND_IN_MILLIS).toLong())
            logger.debug { "Sending update to neighbors" }

            neighbors.forEach { (ip, _) ->
                val distances = routingTable.routes
                    .filter { (_, entry) ->
                        entry.nextHop != ip
                    }
                    .mapValues { it.value.cost }

                val message = Message(
                    type = "update",
                    source = myIp,
                    destination = ip,
                    distances = distances
                )

                networkInterface.sendMessage(message, ip)
            }
        }
    }

    private fun processStartupFile(path: String) {
        val lines = fileManager.readLinesFromFile(path)

        if (lines.isEmpty()) {
            logger.warn { "Startup file $path not found" }
            return
        }

        lines.forEach { line ->
            val parts = line.split(" ")

            if (parts.size == 3 && parts[0] == "add") {
                val ip = parts[1]
                val weight = parts[2].toIntOrNull()

                if (weight != null) {
                    neighbors[ip] = weight
                    logger.info { "Link created with $ip" }
                } else {
                    logger.warn { "Invalid weight for neighbor $ip" }
                }
            } else {
                logger.warn { "Invalid line in startup file: $line" }
            }
        }
    }

    private suspend fun cleanStaleRoutes() {
        val staleThreshold = (STALE_THRESHOLD_SECONDS * TimeUtils.SECOND_IN_MILLIS)
        while (true) {
            delay(staleThreshold)
            logger.info { "Starting stale route check..." }

            val currentTime = TimeUtils.getTimeMillis()
            val neighborsToConsiderDown = mutableSetOf<String>()
            val routesToRemove = mutableSetOf<String>()

            // 1. Identify down neighbors (no updates for 4 * period)
            neighbors.keys.forEach { neighborIp ->
                val directRouteToNeighbor = routingTable.getRoute(neighborIp)

                if (directRouteToNeighbor == null || (currentTime - directRouteToNeighbor.timestamp > staleThreshold)) {
                    logger.warn { "Neighbor $neighborIp considered DOWN (no updates for more than ${4 * period} seconds)." }
                    neighborsToConsiderDown.add(neighborIp)
                }
            }

            // 2. Identify routes using down neighbors as next hop
            routingTable.routes.forEach { (destIp, entry) ->
                if (neighborsToConsiderDown.contains(entry.nextHop)) {
                    routesToRemove.add(destIp)
                }
            }

            // 3. Remove down neighbors and their direct routes
            neighborsToConsiderDown.forEach { neighborIp ->
                neighbors.remove(neighborIp)
                routingTable.removeRoute(neighborIp)
                logger.info { "$neighborIp removed from direct neighbors and its direct route deleted." }
            }

            // 4. Remove stale routes associated with down neighbors
            routesToRemove.forEach { destIp ->
                routingTable.removeRoute(destIp)
                logger.info { "Route to $destIp removed due to down neighbor or lack of updates." }
            }

            // 5. Optionally remove generally stale routes
            val generalStaleRoutes = routingTable.routes.filter { (destIp, entry) ->
                entry.nextHop != myIp &&
                        !neighbors.containsKey(destIp) &&
                        (currentTime - entry.timestamp > staleThreshold)
            }.keys

            generalStaleRoutes.forEach { destIp ->
                routingTable.removeRoute(destIp)
                logger.info { "General stale route to $destIp removed." }
            }

            if (routesToRemove.isNotEmpty() || neighborsToConsiderDown.isNotEmpty() || generalStaleRoutes.isNotEmpty()) {
                logger.info { "Stale route check completed." }
            } else {
                logger.debug { "No stale routes found." }
            }
        }
    }
}