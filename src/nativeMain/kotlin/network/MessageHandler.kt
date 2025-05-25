package network

import kotlinx.serialization.json.Json
import model.Message
import utils.Logger
import utils.isInfinity

class MessageHandler(
    private val myIp: String,
    private val routingTable: RoutingTable,
    private val neighbors: Map<String, Int>,
    private val networkInterface: NetworkInterface,
) {
    private val logger = Logger(
        tag = "MessageHandler"
    )

    fun handle(message: Message) {
        when (message.type) {
            "update" -> handleUpdate(message)
            "data" -> handleData(message)
            "trace" -> handleTrace(message)
            else -> logger.warn { "Unknown message type: ${message.type}" }
        }
    }

    private fun handleData(message: Message) {
        logger.debug { "Data received from ${message.source} to ${message.destination}: ${message.payload}" }

        if (message.destination == myIp) {
            println()
            println("${message.payload}")
            println()
        } else {
            val nextHop = routingTable.getNextHop(message.destination)

            if (nextHop != null) {
                logger.debug { "Forwarding data to $nextHop" }
                networkInterface.sendMessage(message, nextHop)
            } else {
                logger.warn { "No route to ${message.destination}. Dropping packet." }
            }
        }
    }

    private fun handleTrace(message: Message) {
        logger.debug { "Trace received from ${message.source} to ${message.destination}" }

        val updatedRouters = message.routers.orEmpty().toMutableList().apply { add(myIp) }
        val traceMessageWithSelf = message.copy(routers = updatedRouters)

        if (traceMessageWithSelf.destination == myIp) {
            logger.info { "Trace reached destination: $myIp" }
            logger.debug { "Trace message: $traceMessageWithSelf" }

            val traceResultPayload = Json.encodeToString(Message.serializer(), traceMessageWithSelf)
            val responseDataMessage = Message(
                type = "data",
                source = myIp,
                destination = traceMessageWithSelf.source,
                payload = traceResultPayload
            )

            logger.debug { "Sending response data message: $responseDataMessage" }
            networkInterface.sendMessage(responseDataMessage, responseDataMessage.destination)
        } else {
            val route = routingTable.getRoute(traceMessageWithSelf.destination)

            if (route != null) {
                logger.info {
                    "Forwarding trace to ${traceMessageWithSelf.destination} via ${route.nextHop}. Route: ${
                        traceMessageWithSelf.routers?.joinToString(" -> ")
                    }"
                }
                networkInterface.sendMessage(traceMessageWithSelf, route.nextHop)
            } else {
                logger.warn { "No route to ${traceMessageWithSelf.destination}. Dropping trace message from ${traceMessageWithSelf.source}." }
            }
        }
    }

    private fun handleUpdate(message: Message) {
        val neighborIp = message.source
        val receivedDistances = message.distances ?: return

        val costToNeighbor = neighbors[neighborIp] ?: run {
            logger.warn { "Received update from unknown neighbor: $neighborIp. Ignoring..." }
            return
        }

        updateDirectRouteToNeighbor(neighborIp, costToNeighbor)
        processReceivedDistances(neighborIp, costToNeighbor, receivedDistances)
    }

    private fun updateDirectRouteToNeighbor(neighborIp: String, costToNeighbor: Int) {
        routingTable.addRoute(neighborIp, neighborIp, costToNeighbor)
        logger.debug { "Updating timestamp for neighbor $neighborIp" }
    }

    private fun processReceivedDistances(
        neighborIp: String,
        costToNeighbor: Int,
        receivedDistances: Map<String, Int>
    ) {
        receivedDistances.forEach { (destinationIp, reportedDistance) ->
            if (destinationIp == myIp) return@forEach

            if (reportedDistance.isInfinity()) {
                removeRouteIfViaNeighbor(destinationIp, neighborIp)
            } else {
                updateOrDiscardRoute(destinationIp, neighborIp, costToNeighbor, reportedDistance)
            }
        }
    }

    private fun removeRouteIfViaNeighbor(destinationIp: String, neighborIp: String) {
        val current = routingTable.getRoute(destinationIp)
        if (current?.nextHop == neighborIp) {
            routingTable.removeRoute(destinationIp)
            logger.debug { "Route to $destinationIp removed, neighbor $neighborIp reported unreachable" }
        }
    }

    private fun updateOrDiscardRoute(
        destinationIp: String,
        neighborIp: String,
        costToNeighbor: Int,
        reportedDistance: Int
    ) {
        val newCost = costToNeighbor + reportedDistance
        val current = routingTable.getRoute(destinationIp)

        when {
            current == null -> {
                routingTable.addRoute(destinationIp, neighborIp, newCost)
                logger.debug { "New route: to $destinationIp via $neighborIp with cost $newCost" }
            }
            current.nextHop == neighborIp && newCost != current.cost -> {
                routingTable.addRoute(destinationIp, neighborIp, newCost)
                logger.debug { "Cost updated: to $destinationIp via $neighborIp, cost $newCost (was ${current.cost})" }
            }
            newCost < current.cost -> {
                routingTable.addRoute(destinationIp, neighborIp, newCost)
                logger.debug { "Better route found: to $destinationIp via $neighborIp, cost $newCost (was ${current.cost})" }
            }
            else -> {
                logger.debug { "Route to $destinationIp via $neighborIp discarded, cost $newCost (current is ${current.cost})" }
            }
        }
    }
}
