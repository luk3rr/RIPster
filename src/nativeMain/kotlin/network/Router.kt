package network

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val startup: Boolean
) {
    private val socketFd = socket(AF_INET, SOCK_DGRAM, 0)
    private val routingTable = mutableMapOf<String, RouteEntry>()
    private val neighbors = mutableMapOf<String, Int>()
    private val json = Json
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val logger = KotlinLogging.logger { }

    companion object {
        const val PACKET_SIZE = 1024
        const val ROUTER_PORT = 55151
        const val INFINITY = Int.MAX_VALUE
        const val STARTUP_FILES_PATH = "startup_files"
        const val STARTUP_FILE_NAME_PREFIX = "router_"
    }

    init {
        bindSocket()
        routingTable[myIp] = RouteEntry(myIp, 0, getTimeMillis())
        logger.info { "Roteador iniciado em $myIp" }
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
        logger.debug { "Socket ligado em $myIp:$ROUTER_PORT" }
    }

    fun start() = runBlocking {
        if (startup) {
            val path = "$STARTUP_FILES_PATH/$STARTUP_FILE_NAME_PREFIX${myIp.replace('.', '_')}.txt"
            processStartupFile(path)
        }

        scope.launch { receiveLoop() }
        scope.launch { updateLoop() }
        scope.launch { cleanStaleRoutesPeriodically() }
        commandLoop()
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun updateLoop() {
        while (true) {
            delay((period * 1000).toLong())
            logger.debug { "Enviando update para vizinhos" }

            for ((ip, _) in neighbors) {
                val distances = routingTable
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
                sendMessage(message, ip)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun receiveLoop() {
        while (true) {
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
                    logger.error { "Erro ao receber pacote: $errno" }
                    return@memScoped
                }

                val messageBytes = buffer.copyOf(bytesRead.toInt())
                val messageString = messageBytes.decodeToString()
                val message = json.decodeFromString(Message.serializer(), messageString)

                when (message.type) {
                    "update" -> handleUpdate(message)
                    "data" -> handleData(message)
                    "trace" -> handleTrace(message)
                    else -> logger.warn { "Tipo de mensagem desconhecido: ${message.type}. Descartando" }
                }
            }
        }
    }

    private fun commandLoop() {
        while (true) {
            print("> ")
            val line = readlnOrNull()?.trim() ?: continue
            if (!processCommand(line)) break
        }

        logger.info { "Roteador encerrado." }
    }

    private fun sendTrace(destinationIp: String) {
        logger.info { "Iniciando trace para $destinationIp" }

        val initialTraceMessage = Message(
            type = "trace",
            source = myIp,
            destination = destinationIp,
            routers = listOf(myIp)
        )

        if (initialTraceMessage.destination == myIp) {
            handleTrace(initialTraceMessage)
        } else {
            val route = routingTable[destinationIp]

            if (route != null) {
                sendMessage(initialTraceMessage, route.nextHop)
            } else {
                logger.warn { "Não há rota para $destinationIp. Não é possível iniciar o trace." }
            }
        }
    }

    private fun handleData(message: Message) {
        logger.debug { "Dados recebidos de ${message.source} para ${message.destination}: ${message.payload}" }

        if (message.destination == myIp) {
            println("${message.payload}")
        } else {
            val nextHop = routingTable[message.destination]?.nextHop

            if (nextHop != null) {
                logger.debug { "Encaminhando dados para $nextHop" }
                sendMessage(message, nextHop)
            } else {
                logger.warn { "Não há rota para ${message.destination}. Pacote descartado" }
            }
        }
    }

    private fun handleUpdate(message: Message) {
        val neighborIp = message.source
        val receivedDistances = message.distances ?: return

        val costToNeighbor = neighbors[neighborIp]

        if (costToNeighbor == null) {
            logger.warn { "Recebido update de um vizinho desconhecido: $neighborIp. Ignorando..." }
            return
        }

        val currentTime = getTimeMillis()

        // 2. Atualizar a rota direta para o próprio vizinho na routingTable
        routingTable[neighborIp] = RouteEntry(neighborIp, costToNeighbor, currentTime)
        logger.debug { "Atualizando timestamp para o vizinho $neighborIp" }


        // 3. Processar cada distância reportada pelo vizinho
        for ((destinationIp, distanceReportedByNeighbor) in receivedDistances) {
            if (destinationIp == myIp) {
                continue
            }

            // Se a distância reportada é "infinito" (um valor muito alto), significa que o vizinho não alcança.
            // Se a distância for INFINITY, não tente somar, pois pode estourar.
            if (distanceReportedByNeighbor >= INFINITY) {
                // Trate rotas "infinitas" como inalcançáveis através deste vizinho
                // Se sua rota atual para destinationIp é através deste vizinho e ele agora reporta INFINITY, remova essa rota.
                val currentEntryForDest = routingTable[destinationIp]

                if (currentEntryForDest != null && currentEntryForDest.nextHop == neighborIp) {
                    routingTable.remove(destinationIp)
                    logger.debug { "Rota para $destinationIp removida, vizinho $neighborIp reportou inalcançável" }
                }

                continue
            }

            val newPotentialCost = costToNeighbor + distanceReportedByNeighbor

            val currentRoute = routingTable[destinationIp]

            if (currentRoute == null) {
                // Caso 1: Rota para este destino é nova para mim
                routingTable[destinationIp] = RouteEntry(neighborIp, newPotentialCost, currentTime)

                logger.debug { "Nova rota: Para $destinationIp via $neighborIp com custo $newPotentialCost" }
            } else {
                // Caso 2: Já tenho uma rota para este destino
                if (currentRoute.nextHop == neighborIp) {
                    if (newPotentialCost != currentRoute.cost) {
                        routingTable[destinationIp] = RouteEntry(neighborIp, newPotentialCost, currentTime)
                        logger.debug { "Custo atualizado: Para $destinationIp via $neighborIp, custo $newPotentialCost (era ${currentRoute.cost})" }
                    } else {
                        routingTable[destinationIp] = currentRoute.copy(lastUpdate = currentTime)
                    }
                } else if (newPotentialCost < currentRoute.cost) {
                    routingTable[destinationIp] = RouteEntry(neighborIp, newPotentialCost, currentTime)
                    logger.debug { "Rota melhor encontrada: Para $destinationIp via $neighborIp, custo $newPotentialCost (era ${currentRoute.cost})" }
                }
            }
        }

        printRoutingTable()
    }

    private fun handleTrace(message: Message) {
        logger.debug { "Trace recebido de ${message.source} para ${message.destination}" }

        val updatedRouters = message.routers.orEmpty().toMutableList().apply { add(myIp) }
        val traceMessageWithSelf = message.copy(routers = updatedRouters)

        if (traceMessageWithSelf.destination == myIp) {
            logger.info { "Trace atingiu o destino: $myIp. Enviando resposta para ${traceMessageWithSelf.source}" }
            val traceResultPayload = Json.encodeToString(Message.serializer(), traceMessageWithSelf)
            val responseDataMessage = Message(
                type = "data",
                source = myIp,
                destination = traceMessageWithSelf.source,
                payload = traceResultPayload
            )
            sendMessage(responseDataMessage, responseDataMessage.destination)
        } else {
            val route = routingTable[traceMessageWithSelf.destination]
            if (route != null) {
                logger.info {
                    "Encaminhando trace para ${traceMessageWithSelf.destination} via ${route.nextHop}. Rota: ${
                        traceMessageWithSelf.routers?.joinToString(
                            " -> "
                        )
                    }"
                }
                sendMessage(traceMessageWithSelf, route.nextHop)
            } else {
                logger.warn { "Não há rota para ${traceMessageWithSelf.destination}. Descartando mensagem de trace de ${traceMessageWithSelf.source}." }
            }
        }
    }

    private suspend fun cleanStaleRoutesPeriodically() {
        val staleThreshold = (4 * period * 1000).toLong()
        while (true) {
            delay(staleThreshold)
            logger.info { "Iniciando verificação de rotas obsoletas..." }

            val currentTime = getTimeMillis()
            val neighborsToConsiderDown = mutableSetOf<String>()
            val routesToRemove = mutableSetOf<String>()

            // 1. Identificar vizinhos caídos (não mandaram update por 4*pi)
            neighbors.keys.forEach { neighborIp ->
                val directRouteToNeighbor = routingTable[neighborIp]
                if (directRouteToNeighbor == null || (currentTime - directRouteToNeighbor.lastUpdate > staleThreshold)) {
                    logger.warn { "Vizinho $neighborIp considerado CAÍDO (sem updates por mais de ${4 * period} segundos)." }
                    neighborsToConsiderDown.add(neighborIp)
                }
            }

            // 2. Remover rotas que usam um vizinho caído como nextHop
            routingTable.forEach { (destIp, entry) ->
                if (neighborsToConsiderDown.contains(entry.nextHop)) {
                    routesToRemove.add(destIp)
                }
            }

            // 3. Remover os vizinhos caídos do mapa de vizinhos diretos e suas rotas diretas
            neighborsToConsiderDown.forEach { neighborIp ->
                neighbors.remove(neighborIp)
                routingTable.remove(neighborIp) // Remove a rota direta para o vizinho
                logger.info { "Removido $neighborIp do mapa de vizinhos diretos e sua rota direta." }
            }

            // 4. Remover as rotas identificadas como obsoletas (incluindo as de vizinhos caídos)
            routesToRemove.forEach { destIp ->
                routingTable.remove(destIp)
                logger.info { "Rota para $destIp removida devido a vizinho caído ou ausência de update." }
            }

            // 5. Opcional: Remover rotas que não são de vizinhos diretos e estão obsoletas
            val generalStaleRoutes = routingTable.filter { (destIp, entry) ->
                entry.nextHop != myIp && // Não é uma rota para mim mesmo
                        !neighbors.containsKey(destIp) && // Não é uma rota direta para um vizinho (já tratado acima)
                        (currentTime - entry.lastUpdate > staleThreshold) // E está obsoleta
            }.keys

            generalStaleRoutes.forEach { destIp ->
                routingTable.remove(destIp)
                logger.info { "Rota geral obsoleta para $destIp removida." }
            }

            if (routesToRemove.isNotEmpty() || neighborsToConsiderDown.isNotEmpty() || generalStaleRoutes.isNotEmpty()) {
                logger.info { "Verificação de rotas obsoletas concluída" }
                printRoutingTable()
            } else {
                logger.debug { "Nenhuma rota obsoleta encontrada." }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getTimeMillis(): Long {
        val timeVal = nativeHeap.alloc<timeval>()
        gettimeofday(timeVal.ptr, null)
        return (timeVal.tv_sec * 1000L + timeVal.tv_usec / 1000L)
    }

    private fun printRoutingTable() {
        if (routingTable.isEmpty()) {
            println("  Tabela de roteamento vazia.")
            return
        }

        println("  Destino\tPróximo Salto\tCusto\tÚltima Atualização")
        println("  -------\t-------------\t-----\t-----------------")
        routingTable.forEach { (destination, entry) ->
            println(
                "  ${destination.padEnd(15)}\t${entry.nextHop.padEnd(15)}\t${
                    entry.cost.toString().padEnd(5)
                }\t${entry.lastUpdate}"
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun sendMessage(message: Message, targetIp: String) = memScoped {
        val jsonMessage = json.encodeToString(Message.serializer(), message)
        val messageBytes = jsonMessage.encodeToByteArray()

        val messageBuffer = allocArray<ByteVar>(messageBytes.size)
        messageBytes.forEachIndexed { i, byte -> messageBuffer[i] = byte }

        val addr = alloc<sockaddr_in>().apply {
            sin_family = AF_INET.convert()
            sin_port = htons(ROUTER_PORT.toUShort()).convert()
            sin_addr.s_addr = inet_addr(targetIp)
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
            logger.error { "Erro ao enviar mensagem tipo ${message.type} para $targetIp: $errno" }
        } else {
            logger.debug { "Mensagem tipo ${message.type} enviada para $targetIp" }
        }
    }

    private fun processCommand(command: String): Boolean {
        val parts = command.split(" ")

        when (parts[0]) {
            "add" -> {
                val ip = parts.getOrNull(1)
                val weight = parts.getOrNull(2)?.toIntOrNull()

                if (ip != null && weight != null) {
                    neighbors[ip] = weight
                    routingTable[ip] = RouteEntry(ip, weight, getTimeMillis())
                    logger.info { "Vizinho $ip adicionado com peso $weight. Rota direta inicializada." }
                } else {
                    logger.info { "Uso: add <ip> <peso>" }
                }
            }

            "del" -> {
                val ip = parts.getOrNull(1)

                if (ip != null) {
                    neighbors.remove(ip)
                    logger.debug { "Vizinho $ip removido" }

                    val routesToInvalidate = routingTable.filterValues { it.nextHop == ip }.keys
                    routesToInvalidate.forEach { dest ->
                        routingTable.remove(dest)
                        logger.info { "Rota para $dest invalidada devido à remoção do vizinho $ip." }
                    }

                    routingTable.remove(ip)
                    logger.info { "Rota direta para $ip também removida." }
                } else {
                    logger.info { "Uso: del <ip>" }
                }
            }

            "trace" -> {
                val ip = parts.getOrNull(1)
                if (ip != null) {
                    sendTrace(ip)
                } else {
                    logger.info { "Uso: trace <ip>" }
                }
            }

            "quit" -> {
                logger.info { "Encerrando roteador" }
                close(socketFd)
                job.cancel()
                return false
            }

            else -> logger.warn { "Comando desconhecido" }
        }

        return true
    }

    private fun processStartupFile(path: String) {
        val lines = readLinesFromFile(path)

        if (lines.isEmpty()) {
            logger.warn { "Arquivo de inicialização $path não encontrado" }
            return
        }

        lines.forEach { line ->
            val parts = line.split(" ")

            if (parts.size == 3 && parts[0] == "add") {
                val ip = parts[1]
                val weight = parts[2].toIntOrNull()

                if (weight != null) {
                    neighbors[ip] = weight
                    routingTable[ip] = RouteEntry(ip, weight, getTimeMillis())
                    logger.info { "Vizinho $ip adicionado com peso $weight. Rota direta inicializada." }
                } else {
                    logger.warn { "Peso inválido para o vizinho $ip" }
                }
            } else {
                logger.warn { "Linha inválida no arquivo de inicialização: $line" }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readLinesFromFile(path: String): List<String> {
        val file = fopen(path, "r") ?: return emptyList()
        val lines = mutableListOf<String>()

        memScoped {
            val buffer = allocArray<ByteVar>(1024)
            while (fgets(buffer, 1024, file) != null) {
                val line = buffer.toKString().trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    lines.add(line)
                }
            }
        }

        fclose(file)
        return lines
    }
}