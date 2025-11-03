package vision.salient.adb

import vision.salient.config.AppConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Simple result model encapsulating an executed ADB command.
 */
data class AdbCommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

interface ProcessRunner {
    fun run(command: List<String>, timeoutMillis: Long?): ProcessOutput
}

fun ProcessRunner.run(command: List<String>): ProcessOutput = run(command, null)

data class ProcessOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class DefaultProcessRunner : ProcessRunner {
    override fun run(command: List<String>, timeoutMillis: Long?): ProcessOutput {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        fun BufferedReader.consumeTo(builder: StringBuilder) {
            var line: String?
            while (readLine().also { line = it } != null) {
                builder.appendLine(line)
            }
        }

        val stdoutThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { it.consumeTo(stdout) }
        }
        val stderrThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { it.consumeTo(stderr) }
        }
        stdoutThread.start()
        stderrThread.start()

        if (timeoutMillis != null) {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                stdoutThread.join()
                stderrThread.join()
                return ProcessOutput(-1, stdout.toString(), "Command timed out after ${timeoutMillis}ms")
            }
        } else {
            process.waitFor()
        }

        stdoutThread.join()
        stderrThread.join()

        return ProcessOutput(process.exitValue(), stdout.toString(), stderr.toString())
    }
}

interface AdbClient {
    fun run(args: List<String>, timeoutMillis: Long? = null): AdbCommandResult

    fun run(vararg args: String, timeoutMillis: Long? = null): AdbCommandResult =
        run(args.toList(), timeoutMillis)

    fun shell(vararg args: String, timeoutMillis: Long? = null): AdbCommandResult =
        run(listOf("shell") + args.toList(), timeoutMillis)
}

class RealAdbClient(
    private val config: AppConfig,
    private val processRunner: ProcessRunner = DefaultProcessRunner()
) : AdbClient {

    override fun run(args: List<String>, timeoutMillis: Long?): AdbCommandResult {
        val command = buildCommand(args)
        val output = processRunner.run(command, timeoutMillis)
        return AdbCommandResult(command, output.exitCode, output.stdout, output.stderr)
    }

    private fun buildCommand(args: List<String>): List<String> {
        val command = mutableListOf(config.adbPath.toString())
        config.deviceId?.takeIf { it.isNotBlank() }?.let {
            command += listOf("-s", it)
        }
        command += args
        return command
    }
}

class FakeAdbClient : AdbClient {
    val commands = mutableListOf<List<String>>()
    var nextResult: AdbCommandResult? = null

    override fun run(args: List<String>, timeoutMillis: Long?): AdbCommandResult {
        commands += args
        return nextResult ?: AdbCommandResult(args, 0, stdout = "", stderr = "")
    }
}
