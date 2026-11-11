package dev.pocketpaas.data.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.system.RuntimeFileEntry
import dev.pocketpaas.domain.system.RuntimeFileStore
import dev.pocketpaas.domain.system.RuntimeScriptSource
import java.io.File
import java.io.IOException
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/** All filesystem operations execute inside the selected runtime, never through APK-private paths. */
@Singleton
class ShellRuntimeFileStore @Inject constructor(
    private val shell: ProcessExecutor,
    private val scripts: RuntimeScriptSource,
) : RuntimeFileStore {
    override suspend fun writeTextAtomic(path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Runtime configuration exceeds 32 KiB." }
        invoke("write", path, Base64.getEncoder().encodeToString(bytes)).requireSuccess()
    }

    override suspend fun readTextOrNull(path: String): String? {
        val value = invoke("read", path).get("content") ?: throw IOException("Runtime file response is missing content.")
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw IOException("Invalid runtime file content.")
        val bytes = try {
            Base64.getDecoder().decode(value.asString)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid runtime file encoding.", failure)
        }
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Runtime file exceeds 32 KiB.")
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IOException("Runtime configuration is not valid UTF-8.", failure)
        }
    }

    override suspend fun delete(path: String) = invoke("delete", path).requireSuccess()

    override suspend fun ensureDirectory(path: String) = invoke("mkdir", path).requireSuccess()

    override suspend fun listFiles(directory: String): List<String> {
        val value = invoke("list", directory).get("files") ?: throw IOException("Runtime file response is missing its list.")
        if (!value.isJsonArray || value.asJsonArray.size() > 128) throw IOException("Invalid runtime file list.")
        return value.asJsonArray.map { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) throw IOException("Invalid runtime file path.")
            element.asString
        }
    }

    override suspend fun listEntries(directory: String): List<RuntimeFileEntry> {
        val value = invoke("list_entries", directory).get("entries")
            ?: throw IOException("Runtime file response is missing its entries.")
        if (!value.isJsonArray || value.asJsonArray.size() > 128) throw IOException("Invalid runtime directory entries.")
        return value.asJsonArray.map { element ->
            if (!element.isJsonObject) throw IOException("Invalid runtime directory entry.")
            val entry = element.asJsonObject
            val name = entry.get("name")
            val kind = entry.get("kind")
            val size = entry.get("sizeBytes")
            if (name == null || !name.isJsonPrimitive || !name.asJsonPrimitive.isString ||
                kind == null || !kind.isJsonPrimitive || !kind.asJsonPrimitive.isString ||
                kind.asString !in setOf("file", "directory")
            ) throw IOException("Invalid runtime directory entry.")
            val sizeBytes = when {
                size == null || size.isJsonNull -> null
                size.isJsonPrimitive && size.asJsonPrimitive.isNumber -> size.asLong.takeIf { it >= 0 }
                    ?: throw IOException("Invalid runtime entry size.")
                else -> throw IOException("Invalid runtime entry size.")
            }
            if ((kind.asString == "file") != (sizeBytes != null)) throw IOException("Invalid runtime entry size.")
            RuntimeFileEntry(name.asString, kind.asString == "directory", sizeBytes)
        }
    }

    private suspend fun invoke(operation: String, path: String, content: String? = null): JsonObject {
        require(path.isNotBlank() && path.none { it == '\u0000' || it.isISOControl() }) { "Invalid runtime file path." }
        val layout = shell.runtimeLayout()
        val python = shell.resolveBinary("python")
        val script = scripts.readFileScript().toByteArray(Charsets.UTF_8)
        require(script.size <= MAX_FILE_BYTES) { "Runtime file helper exceeds its bootstrap size limit." }
        val encodedScript = Base64.getEncoder().encodeToString(script)
        val bootstrap = "import base64;exec(compile(base64.b64decode('$encodedScript'),'pocketpaas_files.py','exec'))"
        val arguments = listOf(python, "-B", "-c", bootstrap, layout.rootPath, operation, path) +
            listOfNotNull(content)
        val result = shell.execute(
            ProcessRequest(ProcessCommand.Arguments(arguments), emptyMap(), File(layout.rootPath)),
            timeoutMillis = 10_000,
        )
        if (!result.isSuccessful) throw IOException("Runtime file $operation failed: ${result.stderr.takeLast(512)}")
        if (result.stdoutTruncated || result.stderrTruncated) throw IOException("Runtime file response was truncated.")
        try {
            val json = JsonParser.parseString(result.stdout)
            if (!json.isJsonObject) throw IOException("Runtime file response must be a JSON object.")
            return json.asJsonObject
        } catch (failure: com.google.gson.JsonParseException) {
            throw IOException("Runtime file response contains invalid JSON.", failure)
        }
    }

    private fun JsonObject.requireSuccess() {
        val value = get("ok")
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean || !value.asBoolean) {
            throw IOException("Runtime file operation did not confirm completion.")
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 32 * 1024
    }
}
