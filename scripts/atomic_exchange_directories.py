#!/usr/bin/env python3
"""Atomically swaps two same-filesystem directories without an absent-path interval."""

from __future__ import annotations

import argparse
import ctypes
import os
import sys
from pathlib import Path

LINUX_AT_FDCWD = -100
DARWIN_AT_FDCWD = -2
RENAME_EXCHANGE = 2
RENAME_SWAP = 2


def exchange_directories(first_directory: Path, second_directory: Path) -> None:
    """Exchanges two existing directories with the platform's atomic rename primitive."""
    first_directory_bytes = os.fsencode(first_directory)
    second_directory_bytes = os.fsencode(second_directory)
    platform_library = ctypes.CDLL(None, use_errno=True)

    if sys.platform == "darwin":
        rename_operation = platform_library.renameatx_np
        rename_operation.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        rename_operation.restype = ctypes.c_int
        exchange_status = rename_operation(
            DARWIN_AT_FDCWD,
            first_directory_bytes,
            DARWIN_AT_FDCWD,
            second_directory_bytes,
            RENAME_SWAP,
        )
    elif sys.platform.startswith("linux"):
        rename_operation = platform_library.renameat2
        rename_operation.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        rename_operation.restype = ctypes.c_int
        exchange_status = rename_operation(
            LINUX_AT_FDCWD,
            first_directory_bytes,
            LINUX_AT_FDCWD,
            second_directory_bytes,
            RENAME_EXCHANGE,
        )
    else:
        raise RuntimeError(f"Atomic directory exchange is unsupported on {sys.platform}")

    if exchange_status != 0:
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number))


def main() -> int:
    """Parses directory arguments and performs one atomic exchange."""
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("first_directory", type=Path)
    argument_parser.add_argument("second_directory", type=Path)
    arguments = argument_parser.parse_args()

    first_directory = arguments.first_directory.resolve(strict=True)
    second_directory = arguments.second_directory.resolve(strict=True)
    if not first_directory.is_dir() or not second_directory.is_dir():
        raise NotADirectoryError("Atomic exchange requires two existing directories")
    if first_directory.stat().st_dev != second_directory.stat().st_dev:
        raise OSError("Atomic exchange requires directories on the same filesystem")

    exchange_directories(first_directory, second_directory)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
