package me.timschneeberger.rootlessjamesdsp.audio

import android.content.Context
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/** Framed PCM16 transport. Only the render thread sends lease acknowledgements. */
internal interface RoutedPcmSource : AutoCloseable {
    fun begin(rate: Int)
    fun read(output: ShortArray): Int
    fun rendered()
}

internal class ShellPcmSource private constructor(
    private val input: InputStream,
    private val acknowledgements: OutputStream,
    private val terminate: () -> Unit,
    private val channels: Int = 2
) : RoutedPcmSource {
    private val data = DataInputStream(input)
    private val closed = AtomicBoolean()
    private val bytes = ByteArray(1920)
    override fun begin(rate: Int) {
        if (data.readInt() != ShellRoutingWorker.MAGIC || data.readInt() != rate || data.readInt() != channels)
            throw IOException("Invalid shell PCM format")
    }
    override fun read(output: ShortArray): Int {
        val n = data.readInt()
        if (n <= 0 || n > 960 || n > output.size || n % channels != 0) throw IOException("Invalid stereo PCM packet=$n")
        data.readFully(bytes, 0, n * 2)
        for (i in 0 until n) output[i] = ((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort()
        return n
    }
    override fun rendered() {
        if (closed.get()) throw IOException("Retired shell capture")
        acknowledgements.write(1); acknowledgements.flush()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // stdin EOF restores routing even if the app's reader or DSP is stuck.
        try { acknowledgements.close() } catch (_: Exception) {}
        try { terminate() } finally { try { input.close() } catch (_: Exception) {} }
    }
    companion object {
        fun authorized(): Boolean = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED && Shizuku.getUid() == 2000
        } catch (_: Exception) { false }
        fun open(context: Context, rate: Int, uids: Set<Int>, monitor: Boolean = false): ShellPcmSource {
            check(authorized()) { "Authorize Shizuku in ADB mode for the full engine" }
            require(uids.isNotEmpty() && uids.all { it >= 10000 && it != android.os.Process.myUid() })
            val command = arrayOf("/system/bin/app_process", "/system/bin", ShellRoutingWorker::class.java.name, rate.toString()) + (if (monitor) listOf("monitor") else emptyList()) + uids.sorted().map { it.toString() }
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            val released = java.util.concurrent.CountDownLatch(1)
            val releaseFailed = AtomicBoolean()
            val process = method.invoke(null, command, arrayOf("CLASSPATH=" + context.applicationInfo.sourceDir), null) as ShizukuRemoteProcess
            Thread({
                try { process.errorStream.bufferedReader().useLines { lines ->
                    var reported = 0
                    lines.forEach {
                        if (it.startsWith("SHELL_ROUTE_RELEASE_FAILED")) releaseFailed.set(true)
                        if (it == "SHELL_ROUTE_RELEASED") released.countDown()
                        if (reported++ < 12 || it.startsWith("SHELL_ROUTE_RELEASE_FAILED")) timber.log.Timber.w("SHELL_WORKER: %s", it.take(300))
                    }
                } } catch (_: Exception) {}
            }, "ShellCaptureDiagnostics").apply { isDaemon = true; start() }
            return ShellPcmSource(process.inputStream, process.outputStream, {
                var confirmed = false
                try { confirmed = released.await(250, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                finally { process.destroy() }
                if (!confirmed || releaseFailed.get()) timber.log.Timber.e("SHELL_ROUTE_RELEASE_UNCONFIRMED: terminating policy-owner process; Binder-death cleanup is required")
            }, if (monitor) 1 else 2)
        }
        /** Package-private transport seam; instrumentation supplies a disposable shell relay. */
        internal fun connected(input: InputStream, output: OutputStream, terminate: () -> Unit) = ShellPcmSource(input, output, terminate)
        internal fun connected(input: InputStream, output: OutputStream, terminate: () -> Unit, channels: Int) = ShellPcmSource(input, output, terminate, channels)
    }
}
