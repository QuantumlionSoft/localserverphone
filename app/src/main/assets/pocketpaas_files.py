"""Bounded filesystem operations inside the owner-trusted Termux runtime.

Linux operations anchor every descendant to an opened directory descriptor and
never follow descendant symlinks. The root is supplied by the authenticated
runtime layout. This helper is not an isolation boundary against other programs
running with the owner's Termux UID. Windows support exists for host verification;
it rejects reparse points but does not provide POSIX descriptor guarantees.
"""

import base64
import binascii
import contextlib
import json
import os
from pathlib import Path
import stat
import sys
import uuid


MAX_FILE_BYTES = 32 * 1024
MAX_FILES = 128
MAX_PATH_CHARACTERS = 4096
MAX_RESPONSE_BYTES = 48 * 1024


class FileStoreError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _reject_reparse(attributes):
    if (stat.S_ISLNK(attributes.st_mode) or
            getattr(attributes, "st_file_attributes", 0) &
            getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)):
        raise FileStoreError("symlink_or_reparse_target")


def _require_regular(attributes):
    _reject_reparse(attributes)
    if not stat.S_ISREG(attributes.st_mode):
        raise FileStoreError("target_is_not_a_regular_file")


class RuntimeFiles:
    def __init__(self, root):
        self.root = Path(root)
        if not self.root.is_absolute():
            raise FileStoreError("runtime_root_must_be_absolute")
        attributes = self.root.lstat()
        _reject_reparse(attributes)
        if not stat.S_ISDIR(attributes.st_mode):
            raise FileStoreError("runtime_root_is_not_a_directory")
        self.resolved_root = self.root.resolve(strict=True)
        self.root_fd = None
        if os.name == "posix":
            if not hasattr(os, "O_NOFOLLOW"):
                raise FileStoreError("safe_directory_descriptors_unavailable")
            self.root_fd = os.open(str(self.resolved_root), self._directory_flags())

    def close(self):
        if self.root_fd is not None:
            os.close(self.root_fd)
            self.root_fd = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    @staticmethod
    def _directory_flags():
        return (os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW |
                getattr(os, "O_CLOEXEC", 0))

    def _parts(self, value):
        if (not isinstance(value, str) or not value or
                len(value) > MAX_PATH_CHARACTERS or
                any(ord(character) < 32 or ord(character) == 127 for character in value)):
            raise FileStoreError("invalid_runtime_path")
        if os.name == "posix" and "\\" in value:
            raise FileStoreError("invalid_runtime_path")
        components = value.replace("\\", "/").split("/")
        if any(component in (".", "..") for component in components):
            raise FileStoreError("invalid_runtime_path")
        path = Path(value)
        if not path.is_absolute():
            raise FileStoreError("runtime_path_must_be_absolute")
        try:
            return path.relative_to(self.root).parts
        except ValueError:
            raise FileStoreError("runtime_path_is_outside_root") from None

    @contextlib.contextmanager
    def _directory(self, parts, create=False):
        if self.root_fd is not None:
            descriptor = os.dup(self.root_fd)
            try:
                for component in parts:
                    try:
                        child = os.open(component, self._directory_flags(), dir_fd=descriptor)
                    except FileNotFoundError:
                        if not create:
                            raise
                        try:
                            os.mkdir(component, mode=0o700, dir_fd=descriptor)
                            os.fsync(descriptor)
                        except FileExistsError:
                            pass
                        child = os.open(component, self._directory_flags(), dir_fd=descriptor)
                    os.close(descriptor)
                    descriptor = child
                yield descriptor
            finally:
                os.close(descriptor)
        else:
            directory = self.resolved_root
            for component in parts:
                directory = directory / component
                if create:
                    try:
                        directory.mkdir(mode=0o700)
                    except FileExistsError:
                        pass
                attributes = directory.lstat()
                _reject_reparse(attributes)
                if not stat.S_ISDIR(attributes.st_mode):
                    raise FileStoreError("path_component_is_not_a_directory")
                directory.resolve(strict=True).relative_to(self.resolved_root)
            yield directory

    @staticmethod
    def _attributes(directory, name):
        if isinstance(directory, int):
            return os.stat(name, dir_fd=directory, follow_symlinks=False)
        return (directory / name).lstat()

    @staticmethod
    def _sync_directory(directory):
        if isinstance(directory, int):
            os.fsync(directory)

    def read(self, target):
        parts = self._parts(target)
        if not parts:
            raise FileStoreError("target_is_not_a_regular_file")
        try:
            with self._directory(parts[:-1]) as directory:
                _require_regular(self._attributes(directory, parts[-1]))
                if isinstance(directory, int):
                    descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW |
                                         os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0),
                                         dir_fd=directory)
                else:
                    descriptor = os.open(str(directory / parts[-1]), os.O_RDONLY | os.O_BINARY)
                try:
                    attributes = os.fstat(descriptor)
                    _require_regular(attributes)
                    if attributes.st_size > MAX_FILE_BYTES:
                        raise FileStoreError("file_exceeds_size_limit")
                    content = bytearray()
                    while len(content) <= MAX_FILE_BYTES:
                        block = os.read(descriptor, min(8192, MAX_FILE_BYTES + 1 - len(content)))
                        if not block:
                            break
                        content.extend(block)
                    if len(content) > MAX_FILE_BYTES:
                        raise FileStoreError("file_exceeds_size_limit")
                    return {"content": base64.b64encode(content).decode("ascii")}
                finally:
                    os.close(descriptor)
        except FileNotFoundError:
            return {"content": None}

    def write(self, target, encoded_content):
        parts = self._parts(target)
        if not parts:
            raise FileStoreError("target_is_not_a_regular_file")
        if not isinstance(encoded_content, str) or len(encoded_content) > 4 * ((MAX_FILE_BYTES + 2) // 3):
            raise FileStoreError("file_exceeds_size_limit")
        try:
            content = base64.b64decode(encoded_content, validate=True)
        except (ValueError, binascii.Error):
            raise FileStoreError("invalid_base64_content") from None
        if len(content) > MAX_FILE_BYTES:
            raise FileStoreError("file_exceeds_size_limit")
        with self._directory(parts[:-1]) as directory:
            try:
                _require_regular(self._attributes(directory, parts[-1]))
            except FileNotFoundError:
                pass
            temporary = ".pocketpaas-" + uuid.uuid4().hex + ".tmp"
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0)
            if isinstance(directory, int):
                descriptor = os.open(temporary, flags | os.O_NOFOLLOW, mode=0o600, dir_fd=directory)
            else:
                descriptor = os.open(str(directory / temporary), flags | os.O_BINARY, mode=0o600)
            try:
                try:
                    offset = 0
                    while offset < len(content):
                        written = os.write(descriptor, memoryview(content)[offset:])
                        if written <= 0:
                            raise FileStoreError("atomic_write_failed")
                        offset += written
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
                if isinstance(directory, int):
                    os.replace(temporary, parts[-1], src_dir_fd=directory, dst_dir_fd=directory)
                else:
                    os.replace(directory / temporary, directory / parts[-1])
                self._sync_directory(directory)
            finally:
                try:
                    if isinstance(directory, int):
                        os.unlink(temporary, dir_fd=directory)
                    else:
                        (directory / temporary).unlink()
                except FileNotFoundError:
                    pass
        return {"ok": True}

    def delete(self, target):
        parts = self._parts(target)
        if not parts:
            raise FileStoreError("target_is_not_a_regular_file")
        try:
            with self._directory(parts[:-1]) as directory:
                _require_regular(self._attributes(directory, parts[-1]))
                if isinstance(directory, int):
                    os.unlink(parts[-1], dir_fd=directory)
                else:
                    (directory / parts[-1]).unlink()
                self._sync_directory(directory)
        except FileNotFoundError:
            pass
        return {"ok": True}

    def mkdir(self, target):
        with self._directory(self._parts(target), create=True):
            pass
        return {"ok": True}

    def list(self, target):
        parts = self._parts(target)
        names = []
        with self._directory(parts) as directory:
            with os.scandir(directory) as entries:
                for entry in entries:
                    attributes = entry.stat(follow_symlinks=False)
                    _reject_reparse(attributes)
                    if stat.S_ISREG(attributes.st_mode):
                        names.append(entry.name)
                        if len(names) > MAX_FILES:
                            raise FileStoreError("directory_exceeds_file_limit")
        return {"files": [str(self.root.joinpath(*parts, name)) for name in sorted(names)]}

    def list_entries(self, target):
        parts = self._parts(target)
        entries = []
        with self._directory(parts) as directory:
            with os.scandir(directory) as children:
                for child in children:
                    attributes = child.stat(follow_symlinks=False)
                    _reject_reparse(attributes)
                    if stat.S_ISDIR(attributes.st_mode):
                        kind = "directory"
                    elif stat.S_ISREG(attributes.st_mode):
                        kind = "file"
                    else:
                        continue
                    entries.append({"name": child.name, "kind": kind,
                                    "sizeBytes": attributes.st_size if kind == "file" else None})
                    if len(entries) > MAX_FILES:
                        raise FileStoreError("directory_exceeds_file_limit")
        entries.sort(key=lambda item: (item["kind"] != "directory", item["name"].casefold(), item["name"]))
        return {"entries": entries}


def operate(root, operation, target, content=None):
    if operation not in ("read", "write", "delete", "mkdir", "list", "list_entries"):
        raise FileStoreError("unsupported_file_operation")
    if (operation == "write") != (content is not None):
        raise FileStoreError("invalid_file_arguments")
    with RuntimeFiles(root) as files:
        if operation == "write":
            return files.write(target, content)
        if operation == "list_entries":
            return files.list_entries(target)
        return getattr(files, operation)(target)


def main(arguments=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    try:
        if len(arguments) not in (3, 4):
            raise FileStoreError("invalid_file_arguments")
        result = operate(arguments[0], arguments[1], arguments[2],
                         arguments[3] if len(arguments) == 4 else None)
        encoded = json.dumps(result, separators=(",", ":")) + "\n"
        if len(encoded.encode("utf-8")) > MAX_RESPONSE_BYTES:
            raise FileStoreError("response_exceeds_size_limit")
        sys.stdout.write(encoded)
        return 0
    except FileStoreError as failure:
        sys.stderr.write("Runtime file operation failed: " + failure.code + "\n")
        return 2
    except (OSError, ValueError):
        sys.stderr.write("Runtime file operation failed: filesystem_operation_rejected\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
