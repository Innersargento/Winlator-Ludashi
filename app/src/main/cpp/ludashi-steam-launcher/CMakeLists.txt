# ----------------------------------------------------------------------------
# ludashi-steam-launcher
# steam.exe / steam32.exe: the Steam Launcher in-Wine host (Windows PE).
#
# This is NOT part of the Android Gradle build. It is a standalone MinGW-w64
# cross build equivalent to build.sh. Usage:
#
# 64-bit (steam.exe):
#   cmake -S . -B build -DCMAKE_SYSTEM_NAME=Windows \
#         -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc-posix \
#         -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++-posix
#   cmake --build build
#
# 32-bit (steam32.exe): same with i686-w64-mingw32-*-posix and a separate build
# directory. Needs the POSIX-threads MinGW variant (clean_shutdown.cpp uses
# std::thread).
#
# Options:
#   -DLUDASHI_STEAM_STAGE=ON   copy the result to assets/ludashisteam/bionic
#   -DBUILD_PROBES=ON          also build ludashi-iface-probe(32).exe
# ----------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)
project(ludashi-steam-launcher LANGUAGES C CXX)

if(NOT WIN32)
    message(FATAL_ERROR
        "ludashi-steam-launcher targets Windows (Wine). Cross-compile with MinGW-w64, "
        "e.g. -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++-posix")
endif()

option(LUDASHI_STEAM_STAGE "Copy built executables into the APK assets" OFF)
option(BUILD_PROBES "Build the interface probe executables" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(CMAKE_SIZEOF_VOID_P EQUAL 8)
    set(LAUNCHER_NAME "steam")
    set(PROBE_NAME "ludashi-iface-probe")
else()
    set(LAUNCHER_NAME "steam32")
    set(PROBE_NAME "ludashi-iface-probe32")
endif()

set(LAUNCHER_COMMON_FLAGS -O2 -Wall -Wextra -Wno-unused-parameter)
# Static runtime so no MinGW DLLs end up in the wine prefix.
set(LAUNCHER_STATIC_LINK -static -static-libgcc -static-libstdc++)

# GUI subsystem: no console window, so Wine doesn't map a transient X11 window
# at startup. No timestamp keeps the output reproducible.
add_executable(${LAUNCHER_NAME}
    src/main.cpp
    clean_shutdown.cpp)
set_target_properties(${LAUNCHER_NAME} PROPERTIES SUFFIX ".exe")
target_include_directories(${LAUNCHER_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(${LAUNCHER_NAME} PRIVATE ${LAUNCHER_COMMON_FLAGS})
target_link_options(${LAUNCHER_NAME} PRIVATE
    ${LAUNCHER_STATIC_LINK}
    -Wl,--subsystem,windows
    -Wl,--no-insert-timestamp
    -s)
target_link_libraries(${LAUNCHER_NAME} PRIVATE advapi32 kernel32 user32 ws2_32)

set(LAUNCHER_TARGETS ${LAUNCHER_NAME})

if(BUILD_PROBES)
    add_executable(${PROBE_NAME} src/iface_probe.cpp)
    set_target_properties(${PROBE_NAME} PROPERTIES SUFFIX ".exe")
    target_include_directories(${PROBE_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(${PROBE_NAME} PRIVATE ${LAUNCHER_COMMON_FLAGS})
    target_link_options(${PROBE_NAME} PRIVATE ${LAUNCHER_STATIC_LINK} -s)
    target_link_libraries(${PROBE_NAME} PRIVATE kernel32 crypt32 iphlpapi)
    list(APPEND LAUNCHER_TARGETS ${PROBE_NAME})
endif()

if(LUDASHI_STEAM_STAGE)
    set(LUDASHI_STEAM_ASSET_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../assets/ludashisteam/bionic")
    foreach(tgt IN LISTS LAUNCHER_TARGETS)
        add_custom_command(TARGET ${tgt} POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "$<TARGET_FILE:${tgt}>" "${LUDASHI_STEAM_ASSET_DIR}/"
            COMMENT "Staging ${tgt}.exe into APK assets"
            VERBATIM)
    endforeach()
endif()
