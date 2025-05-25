package utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.strftime
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.tm

@OptIn(ExperimentalForeignApi::class)
object TimeUtils {
    fun getTimeMillis(): Long {
        val timeVal = nativeHeap.alloc<timeval>()
        gettimeofday(timeVal.ptr, null)
        val millis = timeVal.tv_sec * 1000L + timeVal.tv_usec / 1000L
        nativeHeap.free(timeVal)
        return millis
    }

    fun getCurrentTimestamp(): String {
        memScoped {
            val timeVar = alloc<time_tVar>()
            timeVar.value = time(null)
            val tmVar = alloc<tm>()
            localtime_r(timeVar.ptr, tmVar.ptr)
            val buffer = ByteArray(30)
            strftime(buffer.refTo(0), buffer.size.convert(), "%Y-%m-%d %H:%M:%S", tmVar.ptr)
            return buffer.toKString()
        }
    }

    const val SECOND_IN_MILLIS: Long = 1000
}