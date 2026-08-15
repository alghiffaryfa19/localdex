package com.localdex

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import java.io.InputStream

/**
 * ADB plumbing over the device's own wireless debugging, ported from anyapk.
 *
 * Pairing and status checks go through the shared [AdbConnectionManager] singleton;
 * long-lived work (the scrcpy session) creates its own manager via [createManager]
 * so a stale shared connection can't take the session down with it.
 */
object Adb {

    private const val SHELL_READ_TIMEOUT_MS = 5000L

    /** Wireless debugging pairing codes are always exactly six digits. */
    private val PAIRING_CODE_PATTERN = Regex("\\d{6}")

    /** A real pairing handshake completes in well under a second on the loopback. */
    private const val PAIRING_TIMEOUT_MS = 20_000L

    private const val RETRY_HINT =
        "Tap \"Pair device with pairing code\" again for a fresh code, then reply with the new one."

    /** Printed by the remote shell once a pushed file is fully written. */
    private const val PUSH_MARKER = "LOCALDEX_PUSH_DONE"

    /**
     * Hosts pairing attempts that may outlive the caller. Detached on purpose: cancelling
     * a coroutine cannot interrupt a thread parked in a blocking socket read.
     */
    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class ConnectionStatus {
        CONNECTED,
        NEEDS_PAIRING,
    }

    @Volatile
    private var lastConnectionCheck: Long = 0
    @Volatile
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING
    private const val CONNECTION_CHECK_CACHE_MS = 2000

    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {
        val now = System.currentTimeMillis()
        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        var stream: AdbStream? = null
        val status = try {
            val manager = AdbConnectionManager.getInstance(context)
            if (!manager.autoConnect(context, 3000)) {
                ConnectionStatus.NEEDS_PAIRING
            } else {
                try {
                    stream = manager.openStream("shell:echo test")
                    val buffer = ByteArray(128)
                    val bytesRead = stream.openInputStream().read(buffer)
                    stream.close()
                    if (bytesRead > 0) ConnectionStatus.CONNECTED else ConnectionStatus.NEEDS_PAIRING
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        stream?.close()
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    ConnectionStatus.NEEDS_PAIRING
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionStatus.NEEDS_PAIRING
        }

        lastConnectionCheck = now
        lastConnectionStatus = status
        return status
    }

    fun invalidateStatusCache() {
        lastConnectionCheck = 0
    }

    /**
     * Pairs with the local wireless debugging service. See anyapk for the full story:
     * the handshake underneath is a blocking socket read with no timeout of its own,
     * so everything here exists to guarantee this returns.
     */
    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> {
        val code = pairingCode.trim()
        if (!PAIRING_CODE_PATTERN.matches(code)) {
            return Result.failure(
                Exception("That is not a pairing code. Enter the 6 digits shown in the \"Pair device with pairing code\" dialog.")
            )
        }
        if (pairingPort !in 1..65535) {
            return Result.failure(
                Exception("No pairing port found yet. Tap \"Pair device with pairing code\" in Wireless debugging, then try again.")
            )
        }

        val attempt = pairingScope.async {
            AdbConnectionManager.getInstance(context).pair("127.0.0.1", pairingPort, code)
        }

        val paired = try {
            withTimeoutOrNull(PAIRING_TIMEOUT_MS) { attempt.await() }
        } catch (e: CancellationException) {
            attempt.cancel()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            attempt.cancel()
            return Result.failure(Exception("Pairing failed. $RETRY_HINT", e))
        }

        return when (paired) {
            true -> Result.success(true)
            false -> Result.failure(Exception("The device rejected that code. $RETRY_HINT"))
            null -> {
                attempt.cancel()
                Result.failure(Exception("Pairing timed out. $RETRY_HINT"))
            }
        }
    }

    /**
     * A fresh manager for a long-lived operation, sharing the singleton's identity keys.
     */
    fun createManager(context: Context): AbsAdbConnectionManager {
        val manager = object : AbsAdbConnectionManager() {
            private val delegate = AdbConnectionManager.getInstance(context)

            override fun getPrivateKey() = delegate.getPrivateKey()
            override fun getCertificate() = delegate.getCertificate()
            override fun getDeviceName() = delegate.getDeviceName()
        }
        manager.setApi(android.os.Build.VERSION.SDK_INT)
        return manager
    }

    /**
     * Runs a shell command over the shared connection. Convenience wrapper used by
     * pairing-adjacent code; session code passes its own manager to [runShell].
     */
    suspend fun runShellCommand(context: Context, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val manager = AdbConnectionManager.getInstance(context)
                if (!manager.autoConnect(context, 10000)) {
                    return@withContext Result.failure(Exception("Could not connect to ADB."))
                }
                Result.success(runShell(manager, command))
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    /**
     * Runs a command via `exec:` and returns everything it printed, reading until the
     * stream ends or [timeoutMs] elapses. `available()` is not reliable on ADB piped
     * streams, so this blocks on read and leans on the timeout instead of polling.
     */
    suspend fun runShell(
        manager: AbsAdbConnectionManager,
        command: String,
        timeoutMs: Long = SHELL_READ_TIMEOUT_MS
    ): String = withContext(Dispatchers.IO) {
        manager.openStream("exec:$command").use { stream ->
            readAll(stream, timeoutMs)
        }
    }

    private suspend fun readAll(stream: AdbStream, timeoutMs: Long): String {
        val output = StringBuilder()
        val input = stream.openInputStream()
        val buffer = ByteArray(4096)

        withTimeoutOrNull(timeoutMs) {
            runInterruptible {
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (e: java.io.IOException) {
                        -1
                    }
                    if (read <= 0) break
                    output.append(String(buffer, 0, read))
                }
            }
        }

        return output.toString().trim()
    }

    /**
     * Copies [input] to [remotePath] on the device via the shell. The ADB protocol has
     * no way to half-close a stream, so `head -c <size>` is what ends the command:
     * it stops after exactly that many bytes. The marker proves the shell got that far.
     */
    suspend fun pushFile(
        manager: AbsAdbConnectionManager,
        input: InputStream,
        size: Long,
        remotePath: String
    ): Unit = withContext(Dispatchers.IO) {
        manager.openStream("exec:head -c $size > $remotePath && echo $PUSH_MARKER")
            .use { stream ->
                val output = stream.openOutputStream()
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        currentCoroutineContext().ensureActive()
                    }
                }
                output.flush()

                val response = readAll(stream, 30_000L)
                if (!response.contains(PUSH_MARKER)) {
                    throw java.io.IOException("Push to $remotePath was not confirmed: $response")
                }
            }
    }
}
