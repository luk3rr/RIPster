package network

import kotlinx.cinterop.*
import kotlinx.serialization.json.Json
import model.Message
import platform.linux.inet_addr
import platform.posix.*
import utils.Logger

@OptIn(ExperimentalForeignApi::class)
class NetworkInterface(
    private val myIp: String,
    private val routerPort: Int,
) {
    private val json = Json
    private val socketFd = socket(AF_INET, SOCK_DGRAM, 0)

    private val logger = Logger("NetworkInterface")

    companion object {
        const val PACKET_SIZE = 1024
    }

    init {
        memScoped {
            val addr = nativeHeap.alloc<sockaddr_in>().apply {
                sin_family = AF_INET.convert()
                sin_port = htons(routerPort.toUShort()).convert()
                sin_addr.s_addr = inet_addr(myIp)
            }

            val result = bind(socketFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(result == 0) { "Failed to bind socket: $errno" }
            logger.debug { "Socket bound to $myIp:$routerPort" }
        }
    }

    fun sendMessage(message: Message, targetIp: String) = memScoped {
        val jsonMessage = json.encodeToString(Message.serializer(), message)
        val messageBytes = jsonMessage.encodeToByteArray()

        val messageBuffer = allocArray<ByteVar>(messageBytes.size)
        messageBytes.forEachIndexed { i, byte -> messageBuffer[i] = byte }

        val addr = alloc<sockaddr_in>().apply {
            sin_family = AF_INET.convert()
            sin_port = htons(routerPort.toUShort()).convert()
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
            logger.error { "Failed to send message of type ${message.type} to $targetIp: $errno" }
        } else {
            logger.debug { "Message of type ${message.type} sent to $targetIp" }
        }
    }

    fun receiveBlocking(): Message? {
        val buffer = ByteArray(PACKET_SIZE)
        val addr = nativeHeap.alloc<sockaddr_in>()
        val addrLen = nativeHeap.alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_in>().convert()

        try {
            val bytesRead = recvfrom(
                socketFd,
                buffer.refTo(0),
                buffer.size.convert(),
                0,
                addr.ptr.reinterpret(),
                addrLen.ptr
            )

            val messageBytes = buffer.copyOf(bytesRead.toInt())
            val messageString = messageBytes.decodeToString()
            return json.decodeFromString(Message.serializer(), messageString)
        } catch (_: Exception) {
            return null
        }
    }

    fun stop() {
        close(socketFd)
        logger.debug { "Socket closed" }
    }
}
