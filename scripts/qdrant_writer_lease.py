#!/usr/bin/env python3
"""Claims or validates one inherited Qdrant writer lease descriptor."""

from __future__ import annotations

import argparse
import errno
import fcntl
import os
import pathlib
import stat
import sysconfig


def require_safe_lease_path(writer_lease_path: pathlib.Path, writer_lease_descriptor: int) -> None:
    """Requires the descriptor to identify the owned, non-symlink lease file."""
    writer_lease_directory = writer_lease_path.parent
    writer_lease_directory_state = writer_lease_directory.lstat()
    if (
        not stat.S_ISDIR(writer_lease_directory_state.st_mode)
        or writer_lease_directory_state.st_uid != os.geteuid()
        or writer_lease_directory_state.st_mode & 0o022
    ):
        raise PermissionError("Qdrant writer lease directory must be private and user-owned")

    writer_lease_path_state = writer_lease_path.lstat()
    writer_lease_descriptor_state = os.fstat(writer_lease_descriptor)
    if (
        not stat.S_ISREG(writer_lease_path_state.st_mode)
        or writer_lease_path_state.st_uid != os.geteuid()
        or not os.path.samestat(writer_lease_path_state, writer_lease_descriptor_state)
    ):
        raise PermissionError("Qdrant writer lease descriptor does not match its canonical file")
    if stat.S_IMODE(writer_lease_descriptor_state.st_mode) != 0o600:
        os.fchmod(writer_lease_descriptor, 0o600)


def claim_writer_lease(writer_lease_path: pathlib.Path, writer_lease_descriptor: int) -> None:
    """Claims the kernel-managed lease without waiting for another writer."""
    if sysconfig.get_config_var("HAVE_FLOCK") != 1:
        raise OSError("Python must expose the operating system flock implementation")
    require_safe_lease_path(writer_lease_path, writer_lease_descriptor)
    try:
        fcntl.flock(writer_lease_descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as lock_failure:
        if lock_failure.errno in (errno.EACCES, errno.EAGAIN):
            raise BlockingIOError("Another Qdrant ingestion writer owns the shared lease") from lock_failure
        raise


def main() -> int:
    """Validates arguments and claims the supplied inherited descriptor."""
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("--lock-path", required=True, type=pathlib.Path)
    argument_parser.add_argument("--descriptor", required=True, type=int)
    parsed_arguments = argument_parser.parse_args()
    if not parsed_arguments.lock_path.is_absolute():
        argument_parser.error("--lock-path must be absolute")
    if parsed_arguments.descriptor < 3:
        argument_parser.error("--descriptor must not overlap standard streams")
    try:
        claim_writer_lease(parsed_arguments.lock_path, parsed_arguments.descriptor)
    except (OSError, ValueError) as lease_failure:
        argument_parser.exit(1, f"qdrant_writer_lease.py: {lease_failure}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
