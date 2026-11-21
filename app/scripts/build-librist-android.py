#!/usr/bin/env python3

import argparse
import shutil
import subprocess
from pathlib import Path


LIBRIST_REVISION = "4f45ef8f78983892d52ccd52d9f675435b23738f"
LIBRIST_TAG = "v0.2.20"
ABI = "arm64-v8a"
ANDROID_API = 26


def run(command, cwd=None):
    subprocess.run(command, cwd=cwd, check=True)


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
    output = args.output.resolve() / ABI

    staged_source.mkdir(parents=True, exist_ok=True)
    (staged_source / "subprojects").mkdir(exist_ok=True)
    librist_source.parent.mkdir(parents=True, exist_ok=True)

    if librist_source.exists() and not (librist_source / ".git").exists():
        shutil.rmtree(librist_source)
    if not (librist_source / ".git").exists():
        run([
            "git", "clone", "--filter=blob:none", "--depth", "1",
            "--branch", LIBRIST_TAG,
            "https://github.com/rist/librist.git", str(librist_source),
        ])
    if subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=librist_source, text=True
    ).strip() != LIBRIST_REVISION:
        raise RuntimeError(f"libRIST {LIBRIST_TAG} does not match pinned revision")

    shutil.copy2(project_source / "meson.build", staged_source / "meson.build")
    shutil.copy2(project_source / "native_rist_sender.c", staged_source / "native_rist_sender.c")

    ndk_bin = ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin"
    clang = ndk_bin / f"aarch64-linux-android{ANDROID_API}-clang"
    archiver = ndk_bin / "llvm-ar"
    stripper = ndk_bin / "llvm-strip"
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


if __name__ == "__main__":
    main()
