cmake_minimum_required(VERSION 3.21)

set(AR_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party")

option(AR_WITH_GRPC "Build worker-server (needs gRPC + protobuf)" ON)

if(CMAKE_HOST_WIN32 AND AR_WITH_GRPC AND NOT CMAKE_TOOLCHAIN_FILE
   AND EXISTS "${AR_THIRD_PARTY}/vcpkg/scripts/buildsystems/vcpkg.cmake")
  set(CMAKE_TOOLCHAIN_FILE "${AR_THIRD_PARTY}/vcpkg/scripts/buildsystems/vcpkg.cmake" CACHE FILEPATH "")
  set(VCPKG_OVERLAY_TRIPLETS "${CMAKE_CURRENT_SOURCE_DIR}/triplets" CACHE STRING "")
  set(VCPKG_TARGET_TRIPLET "x64-windows-static-md-release" CACHE STRING "")
  set(VCPKG_HOST_TRIPLET "x64-windows-static-md-release" CACHE STRING "")
endif()

if(CMAKE_HOST_WIN32)
  set(CMAKE_CONFIGURATION_TYPES "Release;RelWithDebInfo" CACHE STRING "" FORCE)
endif()

project(ar_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_property(AR_MULTI_CONFIG GLOBAL PROPERTY GENERATOR_IS_MULTI_CONFIG)
if(NOT AR_MULTI_CONFIG AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

if(WIN32 AND NOT OpenCV_DIR AND EXISTS "${AR_THIRD_PARTY}/opencv/x64/vc16/lib")
  set(OpenCV_DIR "${AR_THIRD_PARTY}/opencv/x64/vc16/lib")
endif()
# apt opencv on linux and the prebuilt one on windows
find_package(OpenCV QUIET COMPONENTS core imgcodecs imgproc)
if(OpenCV_FOUND)
  set(AR_OPENCV_LIBS ${OpenCV_LIBS})
else()
  find_path(AR_OPENCV_INCLUDE opencv2/core.hpp PATH_SUFFIXES opencv4 REQUIRED)
  set(AR_OPENCV_LIBS "")
  foreach(m core imgproc imgcodecs)
    find_library(AR_OPENCV_${m} opencv_${m} REQUIRED)
    list(APPEND AR_OPENCV_LIBS ${AR_OPENCV_${m}})
  endforeach()
  message(STATUS "OpenCV (no CMake config): ${AR_OPENCV_INCLUDE} ${AR_OPENCV_LIBS}")
endif()

if(WIN32)
  set(AR_ORT_DEFAULT "${AR_THIRD_PARTY}/onnxruntime")
else()
  # onnx runtime comes as a prebuilt release and not from apt
  set(AR_ORT_DEFAULT "$ENV{HOME}/ar-deps/onnxruntime")
endif()
set(ONNXRUNTIME_ROOT "${AR_ORT_DEFAULT}" CACHE PATH "ONNX Runtime package root")
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

add_library(ar_detector STATIC
  src/detector.cpp
  src/ort_model.cpp
  src/yolov8_detector.cpp
  src/yolos_detector.cpp)
target_include_directories(ar_detector PUBLIC src)
if(AR_OPENCV_INCLUDE)
  target_include_directories(ar_detector SYSTEM PUBLIC "${AR_OPENCV_INCLUDE}")
endif()
target_link_libraries(ar_detector PUBLIC ${AR_OPENCV_LIBS} onnxruntime)

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

if(AR_WITH_GRPC)
  find_package(Protobuf CONFIG QUIET)
  if(NOT Protobuf_FOUND)
    find_package(Protobuf REQUIRED)
  endif()
  find_package(gRPC CONFIG REQUIRED)

  if(TARGET protobuf::protoc)
    set(AR_PROTOC "$<TARGET_FILE:protobuf::protoc>")
  else()
    set(AR_PROTOC "${Protobuf_PROTOC_EXECUTABLE}")
  endif()
  if(TARGET gRPC::grpc_cpp_plugin)
    set(AR_GRPC_PLUGIN "$<TARGET_FILE:gRPC::grpc_cpp_plugin>")
  else()
    find_program(AR_GRPC_PLUGIN_EXE grpc_cpp_plugin REQUIRED)
    set(AR_GRPC_PLUGIN "${AR_GRPC_PLUGIN_EXE}")
  endif()

  set(AR_PROTO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../proto")
  set(AR_PROTO_FILE "${AR_PROTO_DIR}/ar/v1/detection.proto")
  set(AR_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/gen")
  set(AR_GEN_FILES
    "${AR_GEN_DIR}/ar/v1/detection.pb.h"      "${AR_GEN_DIR}/ar/v1/detection.pb.cc"
    "${AR_GEN_DIR}/ar/v1/detection.grpc.pb.h" "${AR_GEN_DIR}/ar/v1/detection.grpc.pb.cc")
  file(MAKE_DIRECTORY "${AR_GEN_DIR}")
  add_custom_command(
    OUTPUT ${AR_GEN_FILES}
    COMMAND "${AR_PROTOC}"
            "--proto_path=${AR_PROTO_DIR}"
            "--cpp_out=${AR_GEN_DIR}"
            "--grpc_out=${AR_GEN_DIR}"
            "--plugin=protoc-gen-grpc=${AR_GRPC_PLUGIN}"
            "${AR_PROTO_FILE}"
    DEPENDS "${AR_PROTO_FILE}"
    COMMENT "Generating C++ code from detection.proto"
    VERBATIM)

  add_library(ar_proto STATIC ${AR_GEN_FILES})
  target_link_libraries(ar_proto PUBLIC protobuf::libprotobuf gRPC::grpc++)
  target_include_directories(ar_proto PUBLIC "${AR_GEN_DIR}")
  if(MSVC)
    target_compile_options(ar_proto PRIVATE /W0)
  endif()

  add_executable(worker-server src/server.cpp)
  target_link_libraries(worker-server PRIVATE ar_detector ar_proto)
  list(APPEND AR_EXECUTABLES worker-server)
endif()

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
