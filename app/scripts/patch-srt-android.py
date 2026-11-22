#!/usr/bin/env python3

import argparse
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"Expected one patch point in {path}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    source = parser.parse_args().source

    replace_once(
        source / "srtcore/srt.h",
        "    SRT_SOCKOPT_CONFIG* config;\n"
        "    int errorcode;\n"
        "    int token;\n"
        "} SRT_SOCKGROUPCONFIG;",
        "    SRT_SOCKOPT_CONFIG* config;\n"
        "    int errorcode;\n"
        "    int token;\n"
        "    uint64_t android_network_handle;\n"
        "} SRT_SOCKGROUPCONFIG;",
    )
    replace_once(
        source / "srtcore/srt_c_api.cpp",
        "    data.weight = 0;\n"
        "    data.config = NULL;\n"
        "    if (src)",
        "    data.weight = 0;\n"
        "    data.config = NULL;\n"
        "    data.android_network_handle = 0;\n"
        "    if (src)",
    )
    replace_once(
        source / "srtcore/socketconfig.h",
        "    bool bReuseAddr; // reuse an exiting port or not, for UDP multiplexer\n",
        "    bool bReuseAddr; // reuse an exiting port or not, for UDP multiplexer\n"
        "    uint64_t android_network_handle;\n",
    )
    replace_once(
        source / "srtcore/socketconfig.h",
        "            && CEQUAL(bReuseAddr)\n",
        "            && CEQUAL(bReuseAddr)\n"
        "            && CEQUAL(android_network_handle)\n",
    )
    replace_once(
        source / "srtcore/socketconfig.h",
        "        , bReuseAddr(true) // This is default in SRT\n",
        "        , bReuseAddr(true) // This is default in SRT\n"
        "        , android_network_handle(0)\n",
    )
    replace_once(
        source / "srtcore/api.cpp",
        "            if (config)\n"
        "            {\n"
        "                error_reason = \"user option\";\n"
        "                ns->core().applyMemberConfigObject(*config);\n"
        "            }\n"
        "\n"
        "            error_reason = \"bound address\";",
        "            if (config)\n"
        "            {\n"
        "                error_reason = \"user option\";\n"
        "                ns->core().applyMemberConfigObject(*config);\n"
        "            }\n"
        "\n"
        "            ns->core().m_config.android_network_handle = targets[tii].android_network_handle;\n"
        "            error_reason = \"bound address\";",
    )
    replace_once(
        source / "srtcore/channel.cpp",
        '#include "platform_sys.h"\n',
        '#include "platform_sys.h"\n'
        "#ifdef __ANDROID__\n"
        "#include <android/multinetwork.h>\n"
        "#include <errno.h>\n"
        "#endif\n",
    )
    replace_once(
        source / "srtcore/channel.cpp",
        "#endif // ENABLE_SOCK_CLOEXEC\n"
        "\n"
        "    if (m_iSocket == INVALID_SOCKET)\n"
        "        throw CUDTException(MJ_SETUP, MN_NONE, NET_ERROR);\n"
        "\n"
        "#if ENABLE_SOCK_CLOEXEC\n",
        "#endif // ENABLE_SOCK_CLOEXEC\n"
        "\n"
        "    if (m_iSocket == INVALID_SOCKET)\n"
        "        throw CUDTException(MJ_SETUP, MN_NONE, NET_ERROR);\n"
        "\n"
        "#ifdef __ANDROID__\n"
        "    if (m_mcfg.android_network_handle != 0 &&\n"
        "        android_setsocknetwork((net_handle_t)m_mcfg.android_network_handle, m_iSocket) != 0)\n"
        "    {\n"
        "        const int error = errno;\n"
        "        ::close(m_iSocket);\n"
        "        m_iSocket = INVALID_SOCKET;\n"
        "        throw CUDTException(MJ_SETUP, MN_NONE, error);\n"
        "    }\n"
        "#endif\n"
        "\n"
        "#if ENABLE_SOCK_CLOEXEC\n",
    )


if __name__ == "__main__":
    main()
