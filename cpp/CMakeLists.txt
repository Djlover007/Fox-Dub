# Whisper (Spracherkennung) als native Bibliothek fuer Fox Dub.
# Der Quelltext von whisper.cpp wird im GitHub-Workflow nach ./whisper.cpp geladen.
cmake_minimum_required(VERSION 3.10)
project(foxwhisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(W ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${W}/ggml)
FetchContent_MakeAvailable(ggml)

set(SRC ${W}/src/whisper.cpp ${CMAKE_CURRENT_SOURCE_DIR}/foxwhisper.cpp)
if (EXISTS ${W}/src/aneforge/whisper-aneforge.cpp)
    list(APPEND SRC ${W}/src/aneforge/whisper-aneforge.cpp)
endif ()

add_library(foxwhisper SHARED ${SRC})
target_compile_definitions(foxwhisper PUBLIC GGML_USE_CPU)
target_compile_definitions(foxwhisper PRIVATE WHISPER_VERSION="foxdub")
target_compile_options(foxwhisper PRIVATE -O3)
target_include_directories(foxwhisper PRIVATE ${W} ${W}/src ${W}/include ${W}/ggml/include ${W}/ggml/src ${W}/ggml/src/ggml-cpu)
if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(foxwhisper ${LOG_LIB} android ggml)
else ()
    target_link_libraries(foxwhisper ggml)
endif ()
