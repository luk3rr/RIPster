import network.Router
import utils.LoggerManager

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: <address> <period> [topology]")
        return
    }


    val ip = args[0]
    val period = args[1].toIntOrNull() ?: run {
        println("Invalid period: ${args[1]}")
        return
    }

    LoggerManager.init("log","router_$ip.log")

    val startupTopology = args.getOrNull(2)
    val router = Router(ip, period, startupTopology)
    router.start()
}