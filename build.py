#!/usr/bin/env python3
"""Build the DevEco Assistant patch on Windows, macOS, or Linux."""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP_VERSION = "1.2.1.600"
ORIGINAL_SHA256 = "1215e81240a65336c4adb3149aa29a4af6755c032a16366972e1e29b116c39b6"


class BuildError(Exception):
    pass


def run(step: str, args: list[object]) -> None:
    print(step, flush=True)
    try:
        subprocess.run(
            [str(arg) for arg in args],
            cwd=ROOT,
            stdin=subprocess.DEVNULL,
            check=True,
        )
    except subprocess.CalledProcessError as exc:
        raise BuildError(f"{step} failed (exit code {exc.returncode})") from None


def require_file(path: Path, label: str) -> Path:
    if not path.is_file():
        raise BuildError(f"{label} was not found: {path}")
    return path


def executable(directory: Path, name: str) -> Path:
    suffixes = (".exe", ".bat", ".cmd", "") if os.name == "nt" else ("",)
    for suffix in suffixes:
        path = directory / (name + suffix)
        if path.is_file():
            return path
    raise BuildError(f"{name} was not found in {directory}")


def sdk_directory(argument: str | None) -> Path:
    candidates = [
        argument,
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
    ]
    system = platform.system()
    if system == "Darwin":
        candidates.append(str(Path.home() / "Library/Android/sdk"))
    elif system == "Windows":
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            candidates.append(str(Path(local_app_data) / "Android/Sdk"))
    else:
        candidates.append(str(Path.home() / "Android/Sdk"))
    for candidate in candidates:
        if candidate and Path(candidate).is_dir():
            return Path(candidate).resolve()
    raise BuildError("Android SDK not found; pass --sdk-root")


def build_tools_directory(sdk: Path, argument: str | None) -> Path:
    if argument:
        directory = Path(argument).resolve()
        if not directory.is_dir():
            raise BuildError("The --build-tools directory does not exist")
        return directory
    root = sdk / "build-tools"
    if not root.is_dir():
        raise BuildError("Android SDK build-tools not found")

    def version(directory: Path) -> tuple[int, ...]:
        try:
            return tuple(int(piece) for piece in directory.name.split("."))
        except ValueError:
            return ()

    candidates = sorted((p for p in root.iterdir() if p.is_dir() and version(p)), key=version, reverse=True)
    if not candidates:
        raise BuildError("No numbered Android SDK build-tools directory found")
    return candidates[0]


def jdk_executables(argument: str | None) -> tuple[Path, Path, Path]:
    homes = [argument, os.environ.get("JAVA_HOME")]
    system = platform.system()
    if system == "Windows":
        program_files = os.environ.get("ProgramFiles")
        if program_files:
            homes.append(str(Path(program_files) / "Android/Android Studio/jbr"))
    elif system == "Darwin":
        homes.append("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    for home in homes:
        if home:
            directory = Path(home) / "bin"
            try:
                return tuple(executable(directory, name) for name in ("java", "javac", "jar"))
            except BuildError:
                continue
    found = [shutil.which(name) for name in ("java", "javac", "jar")]
    if all(found):
        return tuple(Path(path) for path in found if path)
    raise BuildError("JDK tools not found; pass --jdk or set JAVA_HOME")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def java_sources(directory: Path) -> list[Path]:
    files = sorted(directory.rglob("*.java"))
    if not files:
        raise BuildError(f"No Java sources found in {directory}")
    return files


def prepare_decode(apktool: str, original: Path, cache: Path) -> Path:
    decode = cache / "decode"
    marker = cache / "original.sha256"
    if decode.exists():
        if not marker.is_file() or marker.read_text(encoding="ascii").strip() != ORIGINAL_SHA256:
            raise BuildError(f"Unverified decode cache: {decode}; use a new --cache-dir")
        if not (decode / "AndroidManifest.xml").is_file():
            raise BuildError(f"Incomplete decode cache: {decode}; use a new --cache-dir")
        return decode
    cache.mkdir(parents=True, exist_ok=True)
    run("Decode original APK into external cache", [apktool, "d", "-s", original, "-o", decode])
    marker.write_text(ORIGINAL_SHA256 + "\n", encoding="ascii")
    return decode


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-apk", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".cache/deveco-assistant-mod")
    parser.add_argument("--sdk-root")
    parser.add_argument("--build-tools")
    parser.add_argument("--jdk")
    parser.add_argument("--apktool")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--password-file", type=Path)
    parser.add_argument("--ks-key-alias")
    args = parser.parse_args()

    if bool(args.keystore) != bool(args.password_file):
        raise BuildError("Pass both --keystore and --password-file, or neither")
    if args.ks_key_alias and not args.keystore:
        raise BuildError("--ks-key-alias requires --keystore")

    original = require_file(args.original_apk.resolve(), "Original APK")
    if sha256(original) != ORIGINAL_SHA256:
        raise BuildError("Original APK SHA-256 does not match DevEco Assistant 1.2.1.600")
    cache = args.cache_dir.resolve()
    if cache.is_relative_to(ROOT) or original.is_relative_to(ROOT):
        raise BuildError("Original APK and decode cache must be outside the source repository")
    sdk = sdk_directory(args.sdk_root)
    tools = build_tools_directory(sdk, args.build_tools)
    android_jar = require_file(sdk / "platforms/android-35/android.jar", "Android platform 35")
    d8_jar = require_file(tools / "lib/d8.jar", "D8")
    zipalign = executable(tools, "zipalign")
    apksigner = executable(tools, "apksigner") if args.keystore else None
    java, javac, jar = jdk_executables(args.jdk)
    apktool = args.apktool or shutil.which("apktool")
    if not apktool:
        raise BuildError("Apktool not found; pass --apktool or add it to PATH")

    decode = prepare_decode(apktool, original, cache)
    builds = cache / "builds"
    builds.mkdir(parents=True, exist_ok=True)
    build = Path(tempfile.mkdtemp(prefix="build-", dir=builds))
    classes = build / "classes"
    stub_classes = build / "stub-classes"
    dex_directory = build / "dex"
    for directory in (classes, stub_classes, dex_directory):
        directory.mkdir()
    stub_jar = build / "compile-only-stubs.jar"
    app_jar = build / "mod.jar"
    output = ROOT / "output"
    output.mkdir(exist_ok=True)
    base = f"deveco-assistant-{APP_VERSION}-mod"
    apktool_built = build / f"{base}-apktool.apk"
    unsigned = build / f"{base}-unsigned.apk"
    aligned = build / f"{base}-aligned.apk"
    signed = build / f"{base}-signed.apk"

    run("Patch manifest and layout", [sys.executable, ROOT / "patch.py", decode])
    run("Compile SDK stubs", [
        javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-cp", android_jar, "-d", stub_classes, *java_sources(ROOT / "stubs/java"),
    ])
    run("Package SDK stubs", [jar, "cf", stub_jar, "-C", stub_classes, "."])
    run("Compile patch", [
        javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-cp", os.pathsep.join((str(android_jar), str(stub_jar))),
        "-d", classes, *java_sources(ROOT / "src/java"),
    ])
    run("Package patch", [jar, "cf", app_jar, "-C", classes, "."])
    run("Create DEX", [
        java, "-cp", d8_jar, "com.android.tools.r8.D8", "--min-api", "24",
        "--lib", android_jar, "--lib", stub_jar, "--output", dex_directory, app_jar,
    ])
    dex = require_file(dex_directory / "classes.dex", "Patch DEX")
    run("Build patched resources", [apktool, "b", decode, "-o", apktool_built])
    run("Repack original APK", [
        sys.executable, ROOT / "repack.py", original, apktool_built, dex, unsigned,
    ])
    run("Align APK", [zipalign, "-P", "16", "-f", "4", unsigned, aligned])
    run("Verify alignment", [zipalign, "-c", "-P", "16", "4", aligned])

    if args.keystore:
        keystore = require_file(args.keystore.resolve(), "Keystore")
        password_file = require_file(args.password_file.resolve(), "Password file")
        command = [
            apksigner, "sign", "--ks", keystore,
            "--ks-pass", f"file:{password_file}",
        ]
        if args.ks_key_alias:
            command.extend(("--ks-key-alias", args.ks_key_alias))
        command.extend((
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false",
            "--out", signed, aligned,
        ))
        run("Sign APK", command)
        run("Verify signature", [apksigner, "verify", "--verbose", signed])
        built_apk = signed
        result = output / f"deveco-assistant-{APP_VERSION}-mod-signed.apk"
    else:
        built_apk = aligned
        result = output / f"deveco-assistant-{APP_VERSION}-mod-unsigned.apk"

    shutil.copy2(built_apk, result)
    for temporary_apk in (apktool_built, unsigned, aligned, signed):
        temporary_apk.unlink(missing_ok=True)
    for older_apk in output.glob(f"deveco-assistant-{APP_VERSION}-*.apk"):
        if older_apk != result:
            older_apk.unlink()

    print(result)


if __name__ == "__main__":
    try:
        main()
    except BuildError as error:
        print(f"Build error: {error}", file=sys.stderr)
        sys.exit(1)
