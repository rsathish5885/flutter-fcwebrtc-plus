cmake_minimum_required(VERSION 3.22)
project(rnnoise_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# xiph/rnnoise — the C library that TGX-Android/rnnoise is based on.
# We fetch the sources and compile them directly (rnnoise uses autotools,
# not cmake, so we can't use FetchContent_MakeAvailable).
FetchContent_Declare(
    rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG        master
    GIT_SHALLOW    ON
)
FetchContent_GetProperties(rnnoise)
if(NOT rnnoise_POPULATED)
    FetchContent_Populate(rnnoise)
endif()

set(RNNOISE_SRC     ${rnnoise_SOURCE_DIR}/src)
set(RNNOISE_INCLUDE ${rnnoise_SOURCE_DIR}/include)

add_library(rnnoise STATIC
    ${RNNOISE_SRC}/denoise.c
    ${RNNOISE_SRC}/rnn.c
    ${RNNOISE_SRC}/rnn_data.c
    ${RNNOISE_SRC}/kiss_fft.c
    ${RNNOISE_SRC}/pitch.c
    ${RNNOISE_SRC}/celt_lpc.c
)

target_include_directories(rnnoise PUBLIC
    ${RNNOISE_INCLUDE}
    ${RNNOISE_SRC}
)

# Required when building rnnoise outside of Speex/Opus:
# OUTSIDE_SPEEX  → use rnnoise's own opus_types.h for integer typedefs
# RANDOM_PREFIX  → avoids symbol clashes if multiple rnnoise copies are linked
target_compile_definitions(rnnoise PRIVATE
    OUTSIDE_SPEEX
    RANDOM_PREFIX=rnnoise
)

# ---- JNI shared library ----
add_library(rnnoise_jni SHARED rnnoise_jni.cpp)

find_library(log-lib log)

target_include_directories(rnnoise_jni PRIVATE ${RNNOISE_INCLUDE})

target_link_libraries(rnnoise_jni PRIVATE
    rnnoise
    ${log-lib}
)
