// Adapted from sing-box-for-android (SagerNet, GPL-3.0): ktx/Continuations.kt, ktx/Wrappers.kt.
package com.slmtune.app.service

import android.net.IpPrefix
import android.os.Build
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.StringIterator
import java.net.InetAddress
import kotlin.coroutines.Continuation

fun <T> Continuation<T>.tryResume(value: T) {
    try {
        resumeWith(Result.success(value))
    } catch (ignored: IllegalStateException) {
    }
}

fun <T> Continuation<T>.tryResumeWithException(exception: Throwable) {
    try {
        resumeWith(Result.failure(exception))
    } catch (ignored: IllegalStateException) {
    }
}

fun Iterable<String>.toStringIterator(): StringIterator = object : StringIterator {
    val iterator = this@toStringIterator.iterator()
    override fun len(): Int = 0 // not used by the core
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): String = iterator.next()
}

fun StringIterator.toList(): List<String> = mutableListOf<String>().apply {
    while (hasNext()) add(next())
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RoutePrefix.toIpPrefix() = IpPrefix(InetAddress.getByName(address()), prefix())
