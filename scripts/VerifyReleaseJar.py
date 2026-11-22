#!/usr/bin/env python3
"""Verify a Leavesclip release JAR with Java 25 or newer on PATH.

Usage: python scripts/VerifyReleaseJar.py <release.jar>
The cold run may download the Mojang server into a temporary directory.
The real server entrypoint is exercised with --version, without starting a world.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import zlib


class VerificationError(Exception):
    pass


def check_zip(archive, label):
    with zipfile.ZipFile(archive) as jar:
        entries = jar.infolist()
        if not entries:
            raise VerificationError(f"Empty JAR: {label}")
        for entry in entries:
            try:
                # Reading each entry to EOF verifies its CRC without loading it into memory.
                with jar.open(entry) as stream:
                    while stream.read(1024 * 1024):
                        pass
            except (zipfile.BadZipFile, EOFError, zlib.error) as exc:
                raise VerificationError(f"{label}, entry {entry.filename}: {exc}") from exc


def read_index(jar, folder):
    name = f"META-INF/{folder}.list"
    with jar.open(name) as source:
        lines = source.read().decode("utf-8").splitlines()
    entries = {}
    for number, line in enumerate(lines, 1):
        columns = line.split("\t")
        if len(columns) != 3:
            raise VerificationError(f"{name}:{number}: expected SHA-256, ID and path")
        digest, identifier, relative = columns
        parts = relative.split("/")
        if (not re.fullmatch(r"[0-9a-fA-F]{64}", digest) or not identifier.strip()
                or not relative.endswith(".jar") or any(
                    not re.fullmatch(r"[0-9A-Za-z._+\-]+", part) or part.endswith(".")
                    for part in parts
                )):
            raise VerificationError(f"{name}:{number}: invalid SHA-256, ID or JAR path")
        key = relative.casefold()  # Also reject paths that would alias on Windows.
        if key in entries:
            raise VerificationError(f"{name}:{number}: duplicate output path {relative}")
        entries[key] = (Path(folder, *parts), digest.lower(), identifier)
    if not entries:
        raise VerificationError(f"Empty index: {name}")
    return list(entries.values())


def sha256(path):
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
        return digest.hexdigest()


def verify_outputs(work, entries, phase):
    for relative, expected, _ in entries:
        path = work / relative
        if not path.is_file():
            raise VerificationError(f"{phase}: missing output {relative}")
        if path.resolve() != path:
            raise VerificationError(f"{phase}: unexpected redirected output {relative}")
        actual = sha256(path)
        if actual != expected:
            raise VerificationError(
                f"{phase}: SHA-256 mismatch for {relative}: expected {expected}, got {actual}"
            )
    print(f"{phase}: verified SHA-256 for {len(entries)} outputs", flush=True)


def java_environment():
    env = os.environ.copy()
    # Keep locally injected JVM flags from redirecting the launcher out of the temp directory.
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    return env


def require_supported_java():
    java = shutil.which("java")
    if not java:
        raise VerificationError("Java not found on PATH; use Java 25 or newer")
    java = str(Path(java).resolve())
    try:
        result = subprocess.run(
            [java, "-XshowSettings:properties", "-version"], env=java_environment(),
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            encoding="utf-8", errors="replace", timeout=15,
        )
    except subprocess.TimeoutExpired as exc:
        raise VerificationError("Checking the Java version timed out") from exc
    match = re.search(r"(?m)^[ \t]*java\.specification\.version[ \t]*=[ \t]*(\S+)", result.stdout)
    if result.returncode or match is None:
        raise VerificationError(f"Could not determine Java version for {java}: {result.stdout.strip()}")
    version = match[1]
    feature = re.match(r"(?:1\.)?(\d+)(?:\.|$)", version)
    if feature is None or int(feature[1]) < 25:
        raise VerificationError(f"SnowBall requires Java 25 or newer; {java} reports Java {version}")
    return java


def run_release(java, artifact, work, phase, jvm_args=(), args=(), expected_error=None):
    try:
        result = subprocess.run(
            [java, "-Dleavesclip.disable.auto-update=true", *jvm_args, "-jar", str(artifact), *args],
            cwd=work, env=java_environment(), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, encoding="utf-8", errors="replace", timeout=300,
        )
    except subprocess.TimeoutExpired as exc:
        print((exc.stdout or b"").decode("utf-8", errors="replace"), file=sys.stderr)
        raise VerificationError(f"{phase}: Leavesclip timed out after 300 seconds") from exc
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n", flush=True)
    if expected_error is not None:
        if result.returncode == 0 or expected_error not in result.stdout:
            raise VerificationError(f"{phase}: expected nonzero exit with {expected_error!r}")
        return result.stdout
    if result.returncode:
        raise VerificationError(f"{phase}: Java exited with status {result.returncode}")
    # Also reject errors that the packaged server or launcher might log without propagating.
    if re.search(r"(?im)^\s*(?:\[[^\]\r\n]*\b(?:ERROR|FATAL)\]:|Exception in thread |"
                 r"Caused by: |[\w.$]*(?:Exception|Error)(?::|$))", result.stdout):
        raise VerificationError(f"{phase}: Leavesclip logged an error despite Java exiting 0")
    return result.stdout


def patch(java, artifact, work, phase, expected_error=None):
    print(f"{phase}: running Leavesclip in patch-only mode", flush=True)
    run_release(java, artifact, work, phase, jvm_args=("-Dleavesclip.patchonly=true",),
                expected_error=expected_error)


def verify_entrypoint(java, artifact, work, versions):
    expected_versions = set()
    for relative, _, _ in versions:
        with zipfile.ZipFile(work / relative) as server:
            manifest = server.read("META-INF/MANIFEST.MF").replace(b"\r\n", b"\n").replace(b"\n ", b"")
        main_section = manifest.split(b"\n\n", 1)[0].decode("utf-8")
        version = re.search(r"(?mi)^Implementation-Version: (\S+)$", main_section)
        if version is not None:
            expected_versions.add(version[1])
    output = run_release(java, artifact, work, "Server entrypoint", args=("--version",))
    if not expected_versions.intersection(line.strip() for line in output.splitlines()):
        raise VerificationError("Server entrypoint: --version did not report the extracted server version")

    missing_class = "org.snowballmc.verifier.IntentionallyMissingEntrypoint"
    run_release(java, artifact, work, "Rejected server entrypoint",
                jvm_args=(f"-DbundlerMainClass={missing_class}",), args=("--version",),
                expected_error=f"ClassNotFoundException: {missing_class}")


def verify_rejected_dependency(java, artifact, work, relative, expected):
    # Change just the selected library's expected digest, leaving its source bytes unchanged.
    rejected = work / "invalid-library-hash.jar"
    fake_digest = ("0" if expected[0] != "0" else "1") + expected[1:]
    index = "META-INF/libraries.list"
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(rejected, "w") as target:
        for entry in source.infolist():
            data = source.read(entry)
            if entry.filename == index:
                data = data.replace(expected.encode() + b"\torg.joml:joml:",
                                    fake_digest.encode() + b"\torg.joml:joml:", 1)
                if fake_digest.encode() not in data:
                    raise VerificationError(f"Could not change {relative}'s expected hash in test fixture")
            target.writestr(entry.filename, data, compress_type=entry.compress_type)
    patch(java, rejected, work, "Rejected dependency", f"got {expected}")
    if sha256(work / relative) != expected:
        raise VerificationError(f"Rejected dependency replaced existing verified output {relative}")
    (work / relative).unlink()
    patch(java, rejected, work, "Rejected dependency with no existing file", f"got {expected}")
    if (work / relative).exists() or list((work / relative).parent.glob(".leavesclip-*.part")):
        raise VerificationError(f"Rejected dependency left an output or temporary file for {relative}")
    print(f"Mismatched JOML rejected without publishing any invalid file: {relative}", flush=True)


def verify(artifact):
    artifact = artifact.resolve(strict=True)
    java = require_supported_java()
    check_zip(artifact, "Outer release JAR")
    print(f"Outer JAR CRC OK: {artifact.name}", flush=True)
    with zipfile.ZipFile(artifact) as jar:
        libraries = read_index(jar, "libraries")
        versions = read_index(jar, "versions")
    joml = [(relative, digest) for relative, digest, identifier in libraries
            if identifier.startswith("org.joml:joml:")]
    if len(joml) != 1:
        raise VerificationError(f"Expected one org.joml:joml library in the index, found {len(joml)}")
    joml_relative, joml_digest = joml[0]

    with tempfile.TemporaryDirectory(prefix="snowball-release-check-") as directory:
        work = Path(directory).resolve()
        entries = libraries + versions
        patch(java, artifact, work, "Cold run")
        verify_outputs(work, entries, "Cold run")
        for relative, _, _ in versions:
            check_zip(work / relative, f"Server JAR {relative}")
            print(f"Server JAR CRC OK: {relative}", flush=True)
        verify_entrypoint(java, artifact, work, versions)

        joml_path = work / joml_relative
        with joml_path.open("r+b") as stream:
            stream.truncate(stream.seek(0, os.SEEK_END) // 2)
        if sha256(joml_path) == joml_digest:
            raise VerificationError(f"Could not induce a SHA-256 mismatch for {joml_relative}")
        print(f"Truncated temporary library: {joml_relative}", flush=True)
        patch(java, artifact, work, "Recovery run")
        verify_outputs(work, entries, "Recovery run")
        print(f"JOML automatically restored: {joml_relative}", flush=True)
        verify_rejected_dependency(java, artifact, work, joml_relative, joml_digest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, help="path to the Leavesclip release JAR")
    args = parser.parse_args()
    try:
        verify(args.artifact)
    except (VerificationError, OSError, zipfile.BadZipFile, RuntimeError,
            KeyError, UnicodeError, EOFError) as exc:
        print(f"Release JAR verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
