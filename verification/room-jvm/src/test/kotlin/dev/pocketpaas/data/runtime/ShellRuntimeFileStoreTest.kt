package dev.pocketpaas.data.runtime

import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.data.process.LocalProcessLauncher
import dev.pocketpaas.data.process.ShellExecutor
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.system.RuntimeFileEntry
import dev.pocketpaas.domain.system.RuntimeScriptSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ShellRuntimeFileStoreTest {
    @Test(timeout = 30_000)
    fun actualPythonCommandsRoundTripBoundedUnicodeAndManageFiles() = runBlocking<Unit> {
        fixture { store, root ->
            val directory = File(root, "config with spaces")
            store.ensureDirectory(directory.path)
            val path = File(directory, "unicode.conf").path
            assertNull(store.readTextOrNull(path))
            val text = "İstanbul 😀\n".repeat(500)
            store.writeTextAtomic(path, text)
            assertEquals(text, store.readTextOrNull(path))
            assertEquals(listOf(path), store.listFiles(directory.path))
            assertEquals(listOf(RuntimeFileEntry("unicode.conf", false, text.toByteArray().size.toLong())), store.listEntries(directory.path))
            store.writeTextAtomic(path, "replacement")
            assertEquals("replacement", store.readTextOrNull(path))
            store.delete(path)
            store.delete(path)
            assertNull(store.readTextOrNull(path))
        }
    }

    @Test(timeout = 30_000)
    fun invalidUtf8AndOversizedWritesFailBeforeTheyCanCorruptRollback() = runBlocking<Unit> {
        fixture { store, root ->
            val path = File(root, "invalid.conf")
            path.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
            assertTrue(runCatching { store.readTextOrNull(path.path) }.exceptionOrNull() is IOException)
            path.writeText("old")
            assertTrue(runCatching { store.writeTextAtomic(path.path, "x".repeat(32 * 1024 + 1)) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals("old", path.readText())
            assertTrue(runCatching { store.readTextOrNull(File(root.parentFile, "escape.conf").path) }.exceptionOrNull() is IOException)
        }
    }

    private suspend fun fixture(block: suspend (ShellRuntimeFileStore, File) -> Unit) {
        val python = System.getProperty("pocketpaas.test.python")
        val production = System.getProperty("pocketpaas.production.root")
        assumeTrue("Python and production paths must be configured.", python != null && production != null)
        val scratch = File(requireNotNull(production), "verification/room-jvm/build/file-store-test")
        check(scratch.isDirectory || scratch.mkdirs())
        val root = Files.createTempDirectory(scratch.toPath(), "runtime-").toFile()
        val layout = RuntimeLayout(root.path, File(root, "htdocs").path, File(root, "nginx/nginx.conf").path,
            File(root, "nginx/conf.d").path, File(root, "tmp").path, File(root, "logs").path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = ShellExecutor(LocalProcessLauncher(layout, listOf(File(requireNotNull(python)).parentFile)), ProcessPolicy(), scope)
        val source = File(production, "app/src/main/assets/pocketpaas_files.py").readText()
        // AppContainer denies strict realpath even for ordinary files. Only the host fixture
        // adapter changes that call; production retains its strict POSIX descriptor checks.
        val script = if (File.separatorChar == '\\') WINDOWS_FIXTURE_RESOLVER + "\n" + source else source
        try {
            block(ShellRuntimeFileStore(executor, RuntimeScriptSource { script }), root)
        } finally {
            withContext(NonCancellable) { scope.cancel(); scope.coroutineContext.job.join() }
            val parent = scratch.canonicalFile.toPath()
            val target = root.canonicalFile.toPath()
            check(target != parent && target.startsWith(parent))
            root.deleteRecursively()
        }
    }

    companion object {
        private val WINDOWS_FIXTURE_RESOLVER = """
            import pathlib,sys
            _resolve=pathlib.Path.resolve
            _fixture=_resolve(pathlib.Path(sys.argv[1]))
            def _fixture_resolve(path,strict=False):
                try:
                    return _resolve(path,strict=strict)
                except PermissionError:
                    if not strict or not path.exists():
                        raise
                    resolved=_resolve(path)
                    resolved.relative_to(_fixture)
                    for component in [resolved]+list(resolved.parents):
                        if component==_fixture.parent:
                            break
                        if component.is_symlink() or (hasattr(component,'is_junction') and component.is_junction()):
                            raise PermissionError('Reparse paths require strict resolution.')
                    return resolved
            pathlib.Path.resolve=_fixture_resolve
        """.trimIndent()
    }
}
