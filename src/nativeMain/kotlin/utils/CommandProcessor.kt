package utils

import network.Router

class CommandProcessor(
    private val router: Router
) {
    private val logger = Logger("CommandProcessor")

    fun commandLoop() {
        print("> ")
        while (true) {
            val line = readlnOrNull()?.trim() ?: continue
            if (!processCommand(line)) break
            print("> ")
        }

        logger.info { "Router shut down." }
        println("Router shut down.")

    }

    fun help() {
        println("Available commands:")
        println("  add <ip> <weight> - Add a link to the routing table")
        println("  del <ip> - Remove a link from the routing table")
        println("  trace <ip> - Send a trace message to the specified IP")
        println("  show <routing|neighbors|all> - Show routing table or neighbors")
        println("  beacon <start|stop> - Start or stop sending beacons")
        println("  help - Show this help message")
        println("  quit - Exit the command loop")
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
                    println("Usage: add <ip> <weight>")
                }
            }

            "del" -> {
                val ip = parts.getOrNull(1)

                if (ip != null) {
                    router.removeLink(ip)
                } else {
                   println("Usage: del <ip>")
                }
            }

            "trace" -> {
                val ip = parts.getOrNull(1)
                if (ip != null) {
                    router.sendTrace(ip)
                } else {
                    println("Usage: trace <ip>")
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
                    else -> println("Usage: show <routing|neighbors|all>")
                }
            }

            "beacon" -> {
                val command = parts.getOrNull(1)
                when (command) {
                    "start" -> router.startBeacon()
                    "stop" -> router.stopBeacon()
                    else -> println("Usage: beacon <start|stop>")
                }
            }

            "help" -> {
                help()
            }

            "quit" -> {
                return false
            }

            else -> println("Unknown command: $command")
        }

        return true
    }
}
