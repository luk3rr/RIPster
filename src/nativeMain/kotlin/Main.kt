import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import network.Router

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Uso: <address> <period> [startup]")
        return
    }
    KotlinLoggingConfiguration.logLevel = Level.ERROR

    val ip = args[0]
    val period = args[1].toIntOrNull() ?: run {
        println("Período inválido")
        return
    }

    val startup = args.getOrNull(2) == "startup"
    val router = Router(ip, period, startup)
    router.start()
}
