"""Standard-library verification of the bounded runtime filesystem helper."""

import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock


ASSET = Path(__file__).resolve().parents[2] / "app/src/main/assets/pocketpaas_files.py"
SPEC = importlib.util.spec_from_file_location("pocketpaas_files", ASSET)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class RuntimeFilesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("PPAAS_TEST_TMP"))
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "root"
        self.root.mkdir()
        self.outside = self.directory / "outside"
        self.outside.mkdir()
        if os.name == "nt":
            # Windows AppContainer denies strict GetFinalPathNameByHandle even
            # for writable fixtures. Production stays strict; only non-reparse
            # existing test paths get this narrowly bounded host adaptation.
            original_resolve = runtime.Path.resolve
            fixture_root = original_resolve(self.directory)

            def fixture_resolve(path, strict=False):
                try:
                    return original_resolve(path, strict=strict)
                except PermissionError:
                    if not strict or not path.exists():
                        raise
                    normalized = original_resolve(path)
                    normalized.relative_to(fixture_root)
                    for component in [normalized] + list(normalized.parents):
                        if component == fixture_root.parent:
                            break
                        runtime._reject_reparse(component.lstat())
                    return normalized

            resolver_patch = mock.patch.object(runtime.Path, "resolve", fixture_resolve)
            resolver_patch.start()
            self.addCleanup(resolver_patch.stop)

    def call(self, operation, path=None, content=None):
        return runtime.operate(str(self.root), operation, str(path or self.root), content)

    def write(self, target, content):
        return self.call("write", target, base64.b64encode(content).decode("ascii"))

    def symlink(self, path, destination, directory=False):
        try:
            path.symlink_to(destination, target_is_directory=directory)
        except (OSError, NotImplementedError):
            self.skipTest("This host cannot create a real symlink fixture.")

    def test_binary_round_trip_at_exact_limit(self):
        target = self.root / "site.conf"
        content = bytes(range(256)) * 128
        self.assertEqual({"ok": True}, self.write(target, content))
        self.assertEqual(content, base64.b64decode(self.call("read", target)["content"]))

    def test_empty_file_is_distinct_from_missing(self):
        target = self.root / "empty.conf"
        self.write(target, b"")
        self.assertEqual({"content": ""}, self.call("read", target))
        self.assertEqual({"content": None}, self.call("read", self.root / "missing.conf"))

    def test_missing_parent_read_and_delete_are_idempotent(self):
        target = self.root / "missing" / "site.conf"
        self.assertEqual({"content": None}, self.call("read", target))
        self.assertEqual({"ok": True}, self.call("delete", target))

    def test_delete_file_twice(self):
        target = self.root / "site.conf"
        self.write(target, b"server {}")
        self.assertEqual({"ok": True}, self.call("delete", target))
        self.assertFalse(target.exists())
        self.assertEqual({"ok": True}, self.call("delete", target))

    def test_oversized_write_does_not_change_existing_file(self):
        target = self.root / "site.conf"
        self.write(target, b"before")
        with self.assertRaises(runtime.FileStoreError):
            self.write(target, b"a" * (runtime.MAX_FILE_BYTES + 1))
        self.assertEqual(b"before", target.read_bytes())

    def test_oversized_read_is_rejected(self):
        target = self.root / "large.conf"
        target.write_bytes(b"a" * (runtime.MAX_FILE_BYTES + 1))
        with self.assertRaises(runtime.FileStoreError):
            self.call("read", target)

    def test_invalid_base64_is_rejected_without_file(self):
        target = self.root / "site.conf"
        for encoded in ("!", "YQ=", "Y Q==", "\u00e9"):
            with self.subTest(encoded=encoded), self.assertRaises(runtime.FileStoreError):
                self.call("write", target, encoded)
        self.assertFalse(target.exists())

    def test_mkdir_recursively_and_repeatedly(self):
        directory = self.root / "nginx" / "conf.d"
        self.assertEqual({"ok": True}, self.call("mkdir", directory))
        self.assertEqual({"ok": True}, self.call("mkdir", directory))
        self.write(directory / "site.conf", b"server {}")
        self.assertTrue((directory / "site.conf").is_file())

    def test_write_requires_existing_parent(self):
        with self.assertRaises(FileNotFoundError):
            self.write(self.root / "missing" / "site.conf", b"content")

    def test_file_cannot_be_a_directory_component(self):
        parent = self.root / "parent"
        parent.write_bytes(b"file")
        with self.assertRaises((OSError, runtime.FileStoreError)):
            self.call("mkdir", parent / "child")
        with self.assertRaises((OSError, runtime.FileStoreError)):
            self.call("read", parent / "child")

    def test_directory_and_root_file_operations_are_rejected(self):
        directory = self.root / "directory"
        directory.mkdir()
        for target in (self.root, directory):
            for operation in ("read", "delete", "write"):
                with self.subTest(target=target.name, operation=operation):
                    with self.assertRaises(runtime.FileStoreError):
                        self.call(operation, target, "" if operation == "write" else None)
        self.assertTrue(directory.is_dir())

    def test_list_returns_sorted_regular_absolute_paths(self):
        (self.root / "b.conf").write_bytes(b"b")
        (self.root / "a.conf").write_bytes(b"a")
        (self.root / "child").mkdir()
        self.assertEqual({"files": [str(self.root / "a.conf"), str(self.root / "b.conf")]},
                         self.call("list"))

    def test_list_file_count_limit(self):
        for number in range(runtime.MAX_FILES):
            (self.root / (str(number) + ".conf")).write_bytes(b"")
        self.assertEqual(runtime.MAX_FILES, len(self.call("list")["files"]))
        (self.root / "too-many.conf").write_bytes(b"")
        with self.assertRaises(runtime.FileStoreError):
            self.call("list")

    def test_list_entries_includes_sorted_regular_files_and_directories(self):
        (self.root / "z-file.txt").write_bytes(b"1234")
        (self.root / "a-dir").mkdir()
        (self.root / "m-file.txt").write_bytes(b"x")
        (self.root / "hidden").symlink_to(self.outside, target_is_directory=True)
        with self.assertRaises(runtime.FileStoreError):
            self.call("list_entries")
        (self.root / "hidden").unlink()
        self.assertEqual(
            {"entries": [
                {"name": "a-dir", "kind": "directory", "sizeBytes": None},
                {"name": "m-file.txt", "kind": "file", "sizeBytes": 1},
                {"name": "z-file.txt", "kind": "file", "sizeBytes": 4},
            ]},
            self.call("list_entries"),
        )

    def test_list_entries_shares_bounded_entry_limit(self):
        for number in range(runtime.MAX_FILES):
            (self.root / (str(number) + ".txt")).write_bytes(b"")
        self.assertEqual(runtime.MAX_FILES, len(self.call("list_entries")["entries"]))
        (self.root / "too-many").mkdir()
        with self.assertRaises(runtime.FileStoreError):
            self.call("list_entries")

    def test_paths_cannot_escape_root_or_use_dot_segments(self):
        invalid = [str(self.outside / "escape"), str(self.root) + "/../outside/escape",
                   str(self.root) + "/./escape", str(self.root) + "-other/escape",
                   "relative.conf", str(self.root) + "/nul\0file", str(self.root) + "/bad\nfile"]
        for target in invalid:
            with self.subTest(target=repr(target)), self.assertRaises(runtime.FileStoreError):
                self.call("read", target)

    def test_arguments_and_operation_are_strict(self):
        for operation, content in (("unknown", None), ("read", "YQ=="), ("write", None)):
            with self.assertRaises(runtime.FileStoreError):
                self.call(operation, self.root / "site.conf", content)

    def test_symlink_target_never_reads_writes_or_deletes_outside(self):
        secret = self.outside / "secret.conf"
        secret.write_bytes(b"private")
        target = self.root / "link.conf"
        self.symlink(target, secret)
        for operation in ("read", "write", "delete"):
            with self.subTest(operation=operation), self.assertRaises(runtime.FileStoreError):
                self.call(operation, target, "YQ==" if operation == "write" else None)
        self.assertEqual(b"private", secret.read_bytes())
        self.assertTrue(target.is_symlink())

    def test_symlink_parent_rejected_for_every_operation(self):
        target = self.root / "link"
        self.symlink(target, self.outside, directory=True)
        for operation, path in (("read", target / "file"), ("write", target / "file"),
                                ("delete", target / "file"), ("mkdir", target / "child"),
                                ("list", target)):
            with self.subTest(operation=operation), self.assertRaises((OSError, runtime.FileStoreError)):
                self.call(operation, path, "YQ==" if operation == "write" else None)
        self.assertEqual([], list(self.outside.iterdir()))

    def test_list_rejects_symlinks(self):
        target = self.root / "link"
        self.symlink(target, self.outside, directory=True)
        with self.assertRaises(runtime.FileStoreError):
            self.call("list")

    def test_runtime_root_cannot_itself_be_a_symlink(self):
        linked_root = self.directory / "linked-root"
        self.symlink(linked_root, self.root, directory=True)
        with self.assertRaises(runtime.FileStoreError):
            runtime.operate(str(linked_root), "list", str(linked_root))

    @unittest.skipUnless(os.name == "posix", "POSIX FIFO and directory descriptor semantics.")
    def test_fifo_is_rejected_without_opening_a_blocking_stream(self):
        target = self.root / "fifo"
        os.mkfifo(target)
        with mock.patch.object(runtime.os, "open", wraps=os.open) as opened:
            with self.assertRaises(runtime.FileStoreError):
                self.call("read", target)
            self.assertTrue(all(str(call.args[0]) != "fifo" for call in opened.call_args_list))
        self.assertEqual({"files": []}, self.call("list"))

    @unittest.skipUnless(os.name == "posix", "POSIX private file mode semantics.")
    def test_atomic_replacement_has_private_mode(self):
        target = self.root / "site.conf"
        target.write_bytes(b"before")
        target.chmod(0o644)
        self.write(target, b"after")
        self.assertEqual(b"after", target.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
        self.assertEqual(["site.conf"], sorted(path.name for path in self.root.iterdir()))

    def test_failed_replace_preserves_old_file_and_removes_temporary(self):
        target = self.root / "site.conf"
        self.write(target, b"before")
        with mock.patch.object(runtime.os, "replace", side_effect=OSError("secret diagnostic")):
            with self.assertRaises(OSError):
                self.write(target, b"after")
        self.assertEqual(b"before", target.read_bytes())
        self.assertEqual(["site.conf"], sorted(path.name for path in self.root.iterdir()))

    @unittest.skipUnless(os.name == "posix", "POSIX anchored directory descriptor semantics.")
    def test_parent_symlink_swap_does_not_redirect_atomic_write(self):
        parent = self.root / "configs"
        parent.mkdir()
        target = parent / "site.conf"
        self.write(target, b"before")
        moved = self.root / "old-configs"
        original_replace = os.replace

        def swap_then_replace(source, destination, **keywords):
            parent.rename(moved)
            parent.symlink_to(self.outside, target_is_directory=True)
            return original_replace(source, destination, **keywords)

        with mock.patch.object(runtime.os, "replace", side_effect=swap_then_replace):
            self.write(target, b"after")
        self.assertEqual(b"after", (moved / "site.conf").read_bytes())
        self.assertFalse((self.outside / "site.conf").exists())

    def test_cli_single_json_success_and_sanitized_error(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = runtime.main([str(self.root), "read", str(self.root / "missing")])
        self.assertEqual(0, result)
        self.assertEqual({"content": None}, json.loads(stdout.getvalue()))
        self.assertEqual("", stderr.getvalue())
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.object(runtime, "operate", side_effect=PermissionError("secret-content")):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = runtime.main([str(self.root), "read", str(self.root / "missing")])
        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertNotIn("secret-content", stderr.getvalue())
        self.assertIn("filesystem_operation_rejected", stderr.getvalue())

    def test_cli_rejects_incorrect_argument_counts(self):
        for arguments in ([], ["root", "read"], ["root", "read", "path", "content", "extra"]):
            with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, runtime.main(arguments))

    def test_cli_bounds_response_before_writing_stdout(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        oversized = {"files": ["a" * runtime.MAX_RESPONSE_BYTES]}
        with mock.patch.object(runtime, "operate", return_value=oversized):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = runtime.main([str(self.root), "list", str(self.root)])
        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertIn("response_exceeds_size_limit", stderr.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
