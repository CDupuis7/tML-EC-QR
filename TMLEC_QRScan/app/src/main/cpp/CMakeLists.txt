cmake_minimum_required(VERSION 3.22.1)

project(tmlec_qr_scan)

# Set OpenCV directory path relative to this file
set(OpenCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../opencv/src/main/jniLibs)

# Find the OpenCV package
find_package(OpenCV QUIET)

if(OpenCV_FOUND)
    message(STATUS "OpenCV found: ${OpenCV_VERSION}")
    
    # Add OpenCV include directories
    include_directories(${OpenCV_INCLUDE_DIRS})
    
    # Create a native library target
    add_library(${CMAKE_PROJECT_NAME} SHARED
        # Add your native code sources here if needed
        # For example: native_opencv_bridge.cpp
        dummy.cpp
    )
    
    # Link against OpenCV libraries
    target_link_libraries(${CMAKE_PROJECT_NAME} ${OpenCV_LIBS})
else()
    message(WARNING "OpenCV not found, skipping native library build")
    
    # Create an empty native library to satisfy build requirements
    add_library(${CMAKE_PROJECT_NAME} SHARED
        # Add a dummy source file
        dummy.cpp
    )
endif() 