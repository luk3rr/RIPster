package network

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import model.RouteEntry
import platform.linux.inet_addr
import platform.posix.*

class Router(private val myIp: String, private val period: Int, startup: Boolean) {
    private val socketFd = socket(AF_INET, SOCK_DGRAM, 0)
    private val routingTable = mutableMapOf<String, RouteEntry>()
    private val neighbors = mutableMapOf<String, Int>()
    private val json = Json

    init {
        bindSocket()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun bindSocket() = memScoped {
        val addr = nativeHeap.alloc<sockaddr_in>().apply {
            sin_family = AF_INET.convert()
            sin_port = htons(55151u).convert()
            sin_addr.s_addr = inet_addr(myIp)
        }

        val result = bind(socketFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        check(result == 0) { "Erro ao dar bind: $errno" }
        println("Socket ligado em $myIp:55151")
    }

    fun start() = runBlocking {
        launch { receiveLoop() }
        launch { updateLoop() }
        commandLoop()
    }

    private suspend fun updateLoop() {
        while (true) {
            delay((period * 1000).toLong())
            // Para cada vizinho, envie pacote `update` com routingTable (split horizon aplicado)
            println("Enviando update para vizinhos...")
        }
    }

    private fun receiveLoop() {
        while (true) {
            // Ler pacote UDP
            // Decodificar mensagem JSON
            // Chamar handleUpdate, handleData ou handleTrace conforme o tipo
        }
    }

    private fun commandLoop() {
        while (true) {
            print("> ")
            val line = readln().trim()
            val parts = line.split(" ")
            when (parts[0]) {
                "add" -> {
                    val ip = parts.getOrNull(1)
                    val weight = parts.getOrNull(2)?.toIntOrNull()
                    if (ip != null && weight != null) {
                        neighbors[ip] = weight
                        println("Vizinho $ip adicionado com peso $weight")
                    } else {
                        println("Uso: add <ip> <peso>")
                    }
                }

                "del" -> {
                    val ip = parts.getOrNull(1)
                    if (ip != null) {
                        neighbors.remove(ip)
                        println("Vizinho $ip removido")
                    } else {
                        println("Uso: del <ip>")
                    }
                }

                "trace" -> {
                    val ip = parts.getOrNull(1)
                    if (ip != null) {
                        println("Iniciando trace para $ip")
                        // Enviar mensagem trace
                    } else {
                        println("Uso: trace <ip>")
                    }
                }

                "quit" -> {
                    println("Encerrando roteador")
                    close(socketFd)
                    break
                }

                else -> println("Comando desconhecido")
            }
        }
    }
}
