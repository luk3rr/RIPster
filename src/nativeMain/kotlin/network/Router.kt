package network

import kotlinx.cinterop.*
import kotlinx.cinterop.nativeHeap.alloc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import model.Message
import model.RouteEntry
import platform.linux.inet_addr
import platform.posix.*

class Router(
    private val myIp: String,
    private val period: Int,
    startup: Boolean
) {
    private val socketFd = socket(AF_INET, SOCK_DGRAM, 0)
    private val routingTable = mutableMapOf<String, RouteEntry>()
    private val neighbors = mutableMapOf<String, Int>()
    private val json = Json

    companion object {
        const val PACKET_SIZE = 1024
        const val ROUTER_PORT = 55151
    }

    init {
        bindSocket()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun bindSocket() = memScoped {
        val addr = nativeHeap.alloc<sockaddr_in>().apply {
            sin_family = AF_INET.convert()
            sin_port = htons(ROUTER_PORT.toUShort()).convert()
            sin_addr.s_addr = inet_addr(myIp)
        }

        val result = bind(socketFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        check(result == 0) { "Erro ao dar bind: $errno" }
        println("Socket ligado em $myIp:$ROUTER_PORT")
    }

    fun start() = runBlocking {
        launch { receiveLoop() }
        launch { updateLoop() }
        commandLoop()
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun updateLoop() {
        while (true) {
            delay((period * 1000).toLong())
            // Para cada vizinho, envie pacote `update` com routingTable (split horizon aplicado)
            println("Enviando update para vizinhos...")

            for ((ip, weight) in neighbors) {
                val distances = routingTable.filterKeys { it != ip }.mapValues { it.value.cost }
                val message = Message(
                    type = "update",
                    source = myIp,
                    destination = ip,
                    distances = distances
                )

                val jsonMessage = json.encodeToString(Message.serializer(), message)

                val messageBytes = jsonMessage.encodeToByteArray()

                memScoped {
                    val messageBuffer = allocArray<ByteVar>(messageBytes.size)
                    messageBytes.forEachIndexed { i, byte ->
                        messageBuffer[i] = byte
                    }

                    val addr = alloc<sockaddr_in>().apply {
                        sin_family = AF_INET.convert()
                        sin_port = htons(ROUTER_PORT.toUShort()).convert()
                        sin_addr.s_addr = inet_addr(ip)
                    }

                    val result = sendto(
                        socketFd,
                        messageBuffer,
                        messageBytes.size.convert(),
                        0,
                        addr.ptr.reinterpret(),
                        sizeOf<sockaddr_in>().convert()
                    )

                    if (result.toInt() == -1) {
                        println("Erro ao enviar update para $ip: $errno")
                    } else {
                        println("Update enviado para $ip")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun receiveLoop() {
        while (true) {
            // Ler pacote UDP
            // Decodificar mensagem JSON
            // Chamar handleUpdate, handleData ou handleTrace conforme o tipo
            memScoped {
                val buffer = ByteArray(PACKET_SIZE)
                val addr = nativeHeap.alloc<sockaddr_in>()
                val addrLen = nativeHeap.alloc<socklen_tVar>()
                addrLen.value = sizeOf<sockaddr_in>().convert()

                val bytesRead = recvfrom(
                    socketFd,
                    buffer.refTo(0),
                    buffer.size.convert(),
                    0,
                    addr.ptr.reinterpret(),
                    addrLen.ptr
                )

                if (bytesRead == -1L) {
                    println("Erro ao receber pacote: $errno")
                    return@memScoped
                }

                val messageBytes = buffer.copyOf(bytesRead.toInt())
                val messageString = messageBytes.decodeToString()
                val message = json.decodeFromString(Message.serializer(), messageString)

                when (message.type) {
                    "update" -> handleUpdate(message)
                    "data" -> handleData(message)
                    "trace" -> handleTrace(message)
                    else -> println("Tipo de mensagem desconhecido: ${message.type}")
                }
            }
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

    private fun handleData(message: Message) {
        // Implementar lógica de manipulação de dados
        println("Dados recebidos de ${message.source} para ${message.destination}: ${message.payload}")
    }

    private fun handleUpdate(message: Message) {
        // Atualizar tabela de roteamento com as distâncias recebidas
        val distances = message.distances ?: return
        for ((ip, cost) in distances) {
            val entry = routingTable[ip]
            if (entry == null || cost < entry.cost) {
                routingTable[ip] = RouteEntry(message.source, cost, getTimeMillis())
                println("Rota atualizada para $ip: custo $cost")
            }
        }
    }

    private fun handleTrace(message: Message) {
        // Implementar lógica de trace
        println("Trace recebido de ${message.source} para ${message.destination}")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getTimeMillis(): Long {
        val timeVal = nativeHeap.alloc<timeval>()
        gettimeofday(timeVal.ptr, null)
        return (timeVal.tv_sec * 1000L + timeVal.tv_usec / 1000L)
    }
}
