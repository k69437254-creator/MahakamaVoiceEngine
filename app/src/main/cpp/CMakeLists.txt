cmake_minimum_required(VERSION 3.22.1)
project(ishtar_tokenizer LANGUAGES CXX)

include(FetchContent)

# Use host protoc during the Android cross-build.
find_program(HOST_PROTOC_EXECUTABLE protoc REQUIRED)
set(
    SPM_PROTOC_EXECUTABLE
    "${HOST_PROTOC_EXECUTABLE}"
    CACHE FILEPATH "Host protoc used to generate SentencePiece sources"
    FORCE
)

set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_LITE OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG v0.2.3
    GIT_SHALLOW TRUE
)

FetchContent_MakeAvailable(sentencepiece)

add_library(ishtar_tokenizer SHARED sentencepiece_jni.cpp)

target_compile_features(ishtar_tokenizer PRIVATE cxx_std_20)

target_link_libraries(
    ishtar_tokenizer
    PRIVATE
    sentencepiece-static
    log
)
