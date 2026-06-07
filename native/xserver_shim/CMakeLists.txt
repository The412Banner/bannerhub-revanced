cmake_minimum_required(VERSION 3.18)
project(xserver_shim C)

# Output MUST be named libxserver.so — the per-game legacy swap loads this wrapper
# in place of the raw libxserver_legacy.so. The wrapper dlopen()s the legacy lib
# (which must be co-located in the same nativeLibraryDir under its own name).
add_library(xserver SHARED xserver_shim.c)

target_link_libraries(xserver log)

# arm64-v8a only (the legacy GLES2 pair is arm64-only).
set_target_properties(xserver PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
