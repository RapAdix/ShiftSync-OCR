cmake_minimum_required(VERSION 3.21)
project(line_detector_native_desktop LANGUAGES CXX)

# This target is separate from Android.mk: it builds a Windows DLL for local
# JVM tests, while Android continues to package only its ABI-specific .so files.
if(NOT WIN32)
    message(FATAL_ERROR "line_detector_native_desktop is intended for Windows local tests.")
endif()

set(JAVA_HOME "" CACHE PATH "JDK/JBR home containing include/jni.h")
if(NOT JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(JAVA_HOME "$ENV{JAVA_HOME}")
    else()
        message(FATAL_ERROR "Set JAVA_HOME or pass -DJAVA_HOME=<JDK-or-JBR-home>.")
    endif()
endif()

set(JNI_INCLUDE_DIR "${JAVA_HOME}/include")
set(JNI_PLATFORM_INCLUDE_DIR "${JNI_INCLUDE_DIR}/win32")
if(NOT EXISTS "${JNI_INCLUDE_DIR}/jni.h" OR NOT EXISTS "${JNI_PLATFORM_INCLUDE_DIR}/jni_md.h")
    message(FATAL_ERROR "JNI headers were not found under ${JAVA_HOME}/include.")
endif()

add_library(line_detector_native SHARED "${CMAKE_CURRENT_LIST_DIR}/../../../main/cpp/line_detector_native.cpp")
target_compile_features(line_detector_native PRIVATE cxx_std_17)
target_include_directories(line_detector_native PRIVATE "${JNI_INCLUDE_DIR}" "${JNI_PLATFORM_INCLUDE_DIR}")
set_target_properties(line_detector_native PROPERTIES
    PREFIX ""
    OUTPUT_NAME "line_detector_native"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/Release"
)
