project(
    'bigirl-native-transport',
    'c',
    default_options: [
        'b_staticpic=true',
        'c_std=c11',
        'default_library=static',
        'warning_level=2',
    ],
)

rist = subproject(
    'librist',
    default_options: [
        'builtin_cjson=true',
        'builtin_lz4=true',
        'built_tools=false',
        'test=false',
        'use_gnutls=false',
        'use_mbedtls=false',
        'use_nettle=false',
    ],
)

native_transport = shared_library(
    'bigirl_transport',
    'native_rist_sender.c',
    dependencies: [
        rist.get_variable('librist_dep'),
        meson.get_compiler('c').find_library('android'),
        meson.get_compiler('c').find_library('log'),
    ],
    install: true,
)
