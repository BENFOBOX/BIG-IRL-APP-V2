cmake_minimum_required(VERSION 3.22)
project(bigirl_srt_transport LANGUAGES C CXX)

if(NOT DEFINED SRT_SOURCE_DIR)
  message(FATAL_ERROR "SRT_SOURCE_DIR must point to the patched SRT source")
endif()
if(NOT DEFINED SRTLA_LIBRARY)
  message(FATAL_ERROR "SRTLA_LIBRARY must point to the Android SRTLA sender library")
endif()

set(ENABLE_BONDING ON CACHE BOOL "" FORCE)
set(ENABLE_ENCRYPTION OFF CACHE BOOL "" FORCE)
set(ENABLE_APPS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory("${SRT_SOURCE_DIR}" srt)

add_library(srtla_android SHARED IMPORTED GLOBAL)
set_target_properties(srtla_android PROPERTIES IMPORTED_LOCATION "${SRTLA_LIBRARY}")

add_library(bigirl_srt_transport SHARED
  "${CMAKE_CURRENT_LIST_DIR}/../native_srt_bonded_sender.c"
  "${CMAKE_CURRENT_LIST_DIR}/../native_srtla_bridge.c"
)
target_include_directories(bigirl_srt_transport PRIVATE
  "${SRT_SOURCE_DIR}/srtcore"
  "${CMAKE_CURRENT_BINARY_DIR}/srt"
)
target_link_libraries(bigirl_srt_transport PRIVATE srt_static srtla_android android log)
set_target_properties(bigirl_srt_transport PROPERTIES LINKER_LANGUAGE CXX)
