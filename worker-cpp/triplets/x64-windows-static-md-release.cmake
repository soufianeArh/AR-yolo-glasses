# Static libraries, dynamic CRT (/MD), release only.
# Release-only halves build time and disk use; gRPC only supports static linkage on Windows anyway.
set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_BUILD_TYPE release)
