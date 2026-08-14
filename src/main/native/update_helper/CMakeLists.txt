cmake_minimum_required(VERSION 3.16)
project(korosoft-update-helper CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT WIN32)
    message(FATAL_ERROR "korosoft-update-helper only builds on Windows")
endif()

add_executable(korosoft-update-helper WIN32 main.cpp)
set_target_properties(korosoft-update-helper PROPERTIES
    OUTPUT_NAME "korosoft-update-helper"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)
