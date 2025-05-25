package utils

import network.Router

class CommandProcessor(
    private val router: Router
) {
    private val logger = Logger("CommandProcessor")

    fun commandLoop() {
        while (true) {
            print("> ")
            val line = readlnOrNull()?.trim() ?: continue
            if (!processCommand(line)) break
        }

        logger.info { "Router shut down." }

    }

    private fun processCommand(command: String): Boolean {
        val parts = command.split(" ")

        when (parts[0]) {
            "add" -> {
                val ip = parts.getOrNull(1)
                val weight = parts.getOrNull(2)?.toIntOrNull()

                if (ip != null && weight != null) {
                    router.addLink(ip, weight)
                }
                else {
                    logger.info { "Usage: add <ip> <weight>" }
                }
            }

            "del" -> {
                val ip = parts.getOrNull(1)

                if (ip != null) {
                    router.removeLink(ip)
                } else {
                    logger.info { "Usage: del <ip>" }
                }
            }

            "trace" -> {
                val ip = parts.getOrNull(1)
                if (ip != null) {
                    router.sendTrace(ip)
                } else {
                    logger.info { "Usage: trace <ip>" }
                }
            }

            "show" -> {
                val table = parts.getOrNull(1)
                when (table) {
                    "routing" -> router.showRoutingTable()
                    "neighbors" -> router.showNeighbors()
                    "all" -> {
                        println("Routing Table:")
                        router.showRoutingTable()
                        println("Neighbors:")
                        router.showNeighbors()
                    }
                    else -> logger.info { "Usage: show <routing|neighbors|all>" }
                }
            }

            "quit" -> {
                return false
            }

            else -> logger.warn { "Unknown command: $command" }
        }

        return true
    }
}
