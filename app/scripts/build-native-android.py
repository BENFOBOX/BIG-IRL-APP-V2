#!/usr/bin/env python3

import argparse
import io
import os
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path


LIBRIST_REVISION = "4f45ef8f78983892d52ccd52d9f675435b23738f"
SRT_REVISION = "09f35c0f1743e23f514cb41444504a7faeacf89e"
ABI = "arm64-v8a"
ANDROID_API = 26
SRTLA_TARGET = "aarch64-linux-android"


def run(command, cwd=None, env=None):
    subprocess.run(command, cwd=cwd, env=env, check=True)


def download_source(url, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=60) as response:
        archive_data = response.read()
    with tarfile.open(fileobj=io.BytesIO(archive_data), mode="r:gz") as archive:
        archive.extractall(destination, filter="data")
    roots = [path for path in destination.iterdir()]
    if len(roots) != 1 or not roots[0].is_dir():
        raise RuntimeError(f"Unexpected source archive layout from {url}")
    source = roots[0]
    for path in source.iterdir():
        shutil.move(str(path), destination / path.name)
    source.rmdir()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--build", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    ndk = args.ndk.resolve()
    project_source = args.source.resolve()
    build_root = args.build.resolve()
    staged_source = build_root / "rist-source"
    librist_source = staged_source / "subprojects" / "librist"
    meson_build = build_root / f"meson-{ABI}"
    srt_source = build_root / "srt-source"
    srt_build = build_root / f"srt-cmake-{ABI}"
    output = args.output.resolve() / ABI

    staged_source.mkdir(parents=True, exist_ok=True)
    (staged_source / "subprojects").mkdir(exist_ok=True)
    librist_source.parent.mkdir(parents=True, exist_ok=True)

    if librist_source.exists():
        shutil.rmtree(librist_source)
    download_source(
        "https://code.videolan.org/api/v4/projects/rist%2Flibrist/"
        f"repository/archive.tar.gz?sha={LIBRIST_REVISION}",
        librist_source,
    )

    shutil.copy2(project_source / "meson.build", staged_source / "meson.build")
    shutil.copy2(project_source / "native_rist_sender.c", staged_source / "native_rist_sender.c")

    ndk_bin = ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin"
    clang = ndk_bin / f"aarch64-linux-android{ANDROID_API}-clang"
    archiver = ndk_bin / "llvm-ar"
    stripper = ndk_bin / "llvm-strip"
    cargo_target = build_root / "cargo"
    cargo_env = os.environ.copy()
    cargo_env["CARGO_TARGET_DIR"] = str(cargo_target)
    cargo_env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(clang)
    cargo_env["CARGO_TARGET_AARCH64_LINUX_ANDROID_AR"] = str(archiver)
    cargo_env["CC_aarch64_linux_android"] = str(clang)
    cargo_env["AR_aarch64_linux_android"] = str(archiver)
    srtla_source = build_root / "srtla-send-source"
    if srtla_source.exists():
        shutil.rmtree(srtla_source)
    download_source(
        "https://codeload.github.com/irlserver/srtla_send/tar.gz/"
        "8677bb49f6af925cb14a3059af5af9f7dc0eb664",
        srtla_source,
    )
    cargo_config = build_root / "cargo-source-overrides.toml"
    cargo_config.write_text(
        "\n".join(
            [
                '[patch."https://github.com/irlserver/srtla_send.git"]',
                f'srtla_send = {{ path = "{srtla_source}" }}',
                f'srtla-core = {{ path = "{srtla_source / "crates" / "srtla-core"}" }}',
                "",
            ]
        ),
        encoding="utf-8",
    )
    run([
        "cargo", "build",
        "--config", str(cargo_config),
        "--manifest-path", str(srtla_source / "Cargo.toml"),
        "--target", SRTLA_TARGET,
        "--release",
    ], env=cargo_env)
    srtla_library = cargo_target / SRTLA_TARGET / "release" / "libsrtla_android.so"
    if not srtla_library.is_file():
        raise RuntimeError(f"SRTLA Android library was not built: {srtla_library}")

    cross_file = build_root / f"android-{ABI}.ini"
    cross_file.write_text(
        "\n".join(
            [
                "[binaries]",
                f"c = '{clang}'",
                f"ar = '{archiver}'",
                f"strip = '{stripper}'",
                "",
                "[host_machine]",
                "system = 'android'",
                "cpu_family = 'aarch64'",
                "cpu = 'aarch64'",
                "endian = 'little'",
                "",
                "[properties]",
                "needs_exe_wrapper = true",
                "",
                "[built-in options]",
                "b_staticpic = true",
                "",
            ]
        ),
        encoding="utf-8",
    )

    setup_command = [
        "meson", "setup", str(meson_build), str(staged_source),
        "--cross-file", str(cross_file),
        "--buildtype", "release",
        "--prefix", str(build_root / "rist-install"),
    ]
    if meson_build.exists():
        shutil.rmtree(meson_build)
    run(setup_command)
    run(["meson", "compile", "-C", str(meson_build), "bigirl_transport"])

    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(meson_build / "libbigirl_transport.so", output / "libbigirl_transport.so")

    if srt_source.exists():
        shutil.rmtree(srt_source)
    download_source(
        f"https://codeload.github.com/Haivision/srt/tar.gz/{SRT_REVISION}",
        srt_source,
    )

    run([
        "python3",
        str(project_source.parent.parent.parent / "scripts" / "patch-srt-android.py"),
        "--source",
        str(srt_source),
    ])
    if srt_build.exists():
        shutil.rmtree(srt_build)
    run([
        "cmake",
        "-S", str(project_source / "srt-android"),
        "-B", str(srt_build),
        f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build' / 'cmake' / 'android.toolchain.cmake'}",
        f"-DANDROID_ABI={ABI}",
        f"-DANDROID_PLATFORM=android-{ANDROID_API}",
        "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DSRT_SOURCE_DIR={srt_source}",
        f"-DSRTLA_LIBRARY={srtla_library}",
    ])
    run(["cmake", "--build", str(srt_build), "--target", "bigirl_srt_transport", "--parallel"])
    shutil.copy2(
        srt_build / "libbigirl_srt_transport.so",
        output / "libbigirl_srt_transport.so",
    )
    shutil.copy2(srtla_library, output / "libsrtla_android.so")

if __name__ == "__main__":
    main()
