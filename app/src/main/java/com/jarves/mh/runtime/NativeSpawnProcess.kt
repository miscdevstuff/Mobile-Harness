package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class NativeSpawnProcess private constructor(
    private val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val outputPump: Thread? = null,
) : Process() {
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        result?.let { return it }
        return NativeSpawn.waitFor(pid, false).also {
            result = it
            outputPump?.join(1_000)
        }
    }

    override fun exitValue(): Int {
        result?.let { return it }
        val status = NativeSpawn.waitFor(pid, true)
        if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
        return status.also { result = it }
    }

    override fun destroy() {
        NativeSpawn.kill(pid, 15)
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        NativeSpawn.kill(pid, 2)
    }

    override fun destroyForcibly(): Process {
        NativeSpawn.kill(pid, 9)
        return this
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (pseudoTerminal) outputFile.delete()
            val spawned = NativeSpawn.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                outputFile.absolutePath,
                pseudoTerminal,
                ptyRows,
                ptyColumns,
            )
            check(spawned.size == 3 && spawned[0] > 0) { "Native runtime launch failed" }
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val pump = spawned[2].takeIf { it >= 0 }?.let { outputFd ->
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(outputFd)).use { source ->
                            FileOutputStream(outputFile, false).use { destination -> source.copyTo(destination) }
                        }
                    }
                }, "pocket-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(spawned[0], outputFile, input, pump)
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    init {
        System.loadLibrary("pocketspawn")
    }

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray
    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
}

/**
 * Streams lines from [NativeSpawnProcess.outputFile] as the process writes them,
 * decoding only new byte chunks instead of re-splitting the entire accumulated output.
 */
internal suspend fun NativeSpawnProcess.forEachOutputLine(
    pollIntervalMs: Long = 50L,
    maxPendingChars: Int = 256 * 1024,
    onIdleTick: (suspend () -> Unit)? = null,
    onLine: suspend (String) -> Boolean,
) {
    var offset = 0L
    val pending = StringBuilder()
    val buffer = ByteArray(16 * 1024)
    var stopEarly = false
    while (!stopEarly && (isAlive || outputFile.length() > offset)) {
        coroutineContext.ensureActive()
        val available = outputFile.length() - offset
        if (available <= 0L) {
            onIdleTick?.invoke()
            delay(pollIntervalMs)
            continue
        }
        val toRead = minOf(available, buffer.size.toLong()).toInt()
        val count = RandomAccessFile(outputFile, "r").use { raf ->
            raf.seek(offset)
            raf.read(buffer, 0, toRead)
        }
        if (count <= 0) {
            delay(pollIntervalMs)
            continue
        }
        offset += count
        pending.append(buffer.decodeToString(0, count).replace('\r', '\n'))
        var newline = pending.indexOf("\n")
        while (newline >= 0) {
            val line = pending.substring(0, newline)
            pending.delete(0, newline + 1)
            if (onLine(line)) {
                stopEarly = true
                break
            }
            newline = pending.indexOf("\n")
        }
        if (pending.length > maxPendingChars) {
            pending.delete(0, pending.length - maxPendingChars)
        }
    }
    if (!stopEarly && pending.isNotEmpty()) {
        val tail = pending.toString().trimEnd('\r', '\n')
        if (tail.isNotEmpty()) onLine(tail)
    }
}
