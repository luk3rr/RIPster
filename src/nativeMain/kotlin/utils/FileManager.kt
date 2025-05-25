package utils

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

@OptIn(ExperimentalForeignApi::class)
class FileManager() {
    fun readLinesFromFile(path: String): List<String> {
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