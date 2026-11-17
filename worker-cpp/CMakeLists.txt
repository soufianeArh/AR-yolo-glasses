cmake_minimum_required(VERSION 3.21)

# C++ inference worker.
#   worker-cli / worker-bench : the detector alone (Step 2)
#   worker-server             : the detector as a gRPC service (Step 3)
# Builds on Windows (MSVC) and Linux (GCC) from this same file.

set(AR_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party")

option(AR_WITH_GRPC "Build worker-server (needs gRPC + protobuf, e.g. from vcpkg)" ON)

# gRPC + protobuf come from vcpkg (third_party/vcpkg), release-only static build.
# Must be set BEFORE project(). Only applies on the first configure of a build folder.
if(AR_WITH_GRPC AND NOT CMAKE_TOOLCHAIN_FILE AND EXISTS "${AR_THIRD_PARTY}/vcpkg/scripts/buildsystems/vcpkg.cmake")
  set(CMAKE_TOOLCHAIN_FILE "${AR_THIRD_PARTY}/vcpkg/scripts/buildsystems/vcpkg.cmake" CACHE FILEPATH "")
  set(VCPKG_OVERLAY_TRIPLETS "${CMAKE_CURRENT_SOURCE_DIR}/triplets" CACHE STRING "")
  if(WIN32)
    # Same triplet for the libraries AND the host tools (protoc, grpc_cpp_plugin),
    # so vcpkg builds gRPC once instead of twice.
    set(VCPKG_TARGET_TRIPLET "x64-windows-static-md-release" CACHE STRING "")
    set(VCPKG_HOST_TRIPLET "x64-windows-static-md-release" CACHE STRING "")
  endif()
endif()

# The vcpkg libraries are release-only, so only offer release-type configurations.
# RelWithDebInfo = optimized + debug symbols: use it to profile in Visual Studio.
set(CMAKE_CONFIGURATION_TYPES "Release;RelWithDebInfo" CACHE STRING "" FORCE)

project(ar_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_property(AR_MULTI_CONFIG GLOBAL PROPERTY GENERATOR_IS_MULTI_CONFIG)
if(NOT AR_MULTI_CONFIG AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)  # single-config generators (Ninja/Makefiles on Linux)
endif()

# ---------------------------------------------------------------- OpenCV
# Only the 3 modules the worker needs: core, imgcodecs (JPEG decode), imgproc (resize).
# Windows: the prebuilt package in third_party/opencv (ships as one "world" library).
# Linux:   apt install libopencv-dev, found automatically.
if(WIN32 AND NOT OpenCV_DIR AND EXISTS "${AR_THIRD_PARTY}/opencv/x64/vc16/lib")
  set(OpenCV_DIR "${AR_THIRD_PARTY}/opencv/x64/vc16/lib")
endif()
find_package(OpenCV REQUIRED COMPONENTS core imgcodecs imgproc)

# ---------------------------------------------------------------- ONNX Runtime
# Microsoft's prebuilt package, unpacked into third_party/onnxruntime (same layout on Windows and Linux).
set(ONNXRUNTIME_ROOT "${AR_THIRD_PARTY}/onnxruntime" CACHE PATH "ONNX Runtime package root")
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
  INTERFACE_INCLUDE_DIRECTORIES "${ONNXRUNTIME_ROOT}/include")
if(WIN32)
  set_target_properties(onnxruntime PROPERTIES
    IMPORTED_IMPLIB   "${ONNXRUNTIME_ROOT}/lib/onnxruntime.lib"
    IMPORTED_LOCATION "${ONNXRUNTIME_ROOT}/lib/onnxruntime.dll")
else()
  set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNXRUNTIME_ROOT}/lib/libonnxruntime.so")
endif()

# ---------------------------------------------------------------- targets
add_library(ar_detector STATIC
  src/detector.cpp
  src/yolov8_detector.cpp)
target_include_directories(ar_detector PUBLIC src)
target_link_libraries(ar_detector PUBLIC ${OpenCV_LIBS} onnxruntime)

if(MSVC)
  target_compile_options(ar_detector PUBLIC /W3 /utf-8 /permissive-)
  target_compile_definitions(ar_detector PUBLIC NOMINMAX)
else()
  target_compile_options(ar_detector PUBLIC -Wall -Wextra)
endif()

add_executable(worker-cli   src/cli.cpp)
add_executable(worker-bench src/bench.cpp)
target_link_libraries(worker-cli   PRIVATE ar_detector)
target_link_libraries(worker-bench PRIVATE ar_detector)
set(AR_EXECUTABLES worker-cli worker-bench)

# ---------------------------------------------------------------- gRPC server
if(AR_WITH_GRPC)
  find_package(Protobuf CONFIG REQUIRED)
  find_package(gRPC CONFIG REQUIRED)

  # C++ code generated from the shared contract, proto/ar/v1/detection.proto.
  set(AR_PROTO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../proto")
  set(AR_PROTO_FILE "${AR_PROTO_DIR}/ar/v1/detection.proto")
  set(AR_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/gen")
  file(MAKE_DIRECTORY "${AR_GEN_DIR}")

  add_library(ar_proto STATIC)
  target_link_libraries(ar_proto PUBLIC protobuf::libprotobuf gRPC::grpc++)
  target_include_directories(ar_proto PUBLIC "${AR_GEN_DIR}")
  protobuf_generate(TARGET ar_proto LANGUAGE cpp
    PROTOS "${AR_PROTO_FILE}" IMPORT_DIRS "${AR_PROTO_DIR}" PROTOC_OUT_DIR "${AR_GEN_DIR}")
  protobuf_generate(TARGET ar_proto LANGUAGE grpc
    GENERATE_EXTENSIONS .grpc.pb.h .grpc.pb.cc
    PLUGIN "protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>"
    PROTOS "${AR_PROTO_FILE}" IMPORT_DIRS "${AR_PROTO_DIR}" PROTOC_OUT_DIR "${AR_GEN_DIR}")
  if(MSVC)
    target_compile_options(ar_proto PRIVATE /W0)  # generated code: not ours to warn about
  endif()

  add_executable(worker-server src/server.cpp)
  target_link_libraries(worker-server PRIVATE ar_detector ar_proto)
  list(APPEND AR_EXECUTABLES worker-server)
endif()

# Windows: copy the OpenCV and ONNX Runtime DLLs next to the executables so they run directly.
if(WIN32)
  foreach(exe ${AR_EXECUTABLES})
    add_custom_command(TARGET ${exe} POST_BUILD
      COMMAND ${CMAKE_COMMAND} -E copy_if_different
              $<TARGET_RUNTIME_DLLS:${exe}>
              "${ONNXRUNTIME_ROOT}/lib/onnxruntime_providers_shared.dll"
              $<TARGET_FILE_DIR:${exe}>
      COMMAND_EXPAND_LISTS)
  endforeach()
endif()
