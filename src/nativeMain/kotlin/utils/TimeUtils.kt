package utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.ptr

@OptIn(ExperimentalForeignApi::class)
object TimeUtils {
    fun getTimeMillis(): Long {
        val timeVal = nativeHeap.alloc<timeval>()
        gettimeofday(timeVal.ptr, null)
        val millis = timeVal.tv_sec * 1000L + timeVal.tv_usec / 1000L
        nativeHeap.free(timeVal)
        return millis
    }

    val SECOND_IN_MILLIS: Long = 1000
}