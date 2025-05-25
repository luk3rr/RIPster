import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import network.Router

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: <address> <period> [startup]")
        return
    }
    KotlinLoggingConfiguration.logLevel = Level.DEBUG

    val ip = args[0]
    val period = args[1].toIntOrNull() ?: run {
        println("Invalid period: ${args[1]}")
        return
    }

    val startup = args.getOrNull(2) == "startup"
    val router = Router(ip, period, startup)
    router.start()
}