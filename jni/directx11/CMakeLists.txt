# Build the jpcsp Direct3D 11 wrapper.
#
# The result is a single DLL, jpcsp-directx11.dll, which jpcsp loads with JNA.
# It has to be placed in a directory of the java.library.path, which is
# lib/windows-amd64 for a 64 bit JVM and lib/windows-x86 for a 32 bit one.
#
# Configure and build with the Visual Studio toolchain:
#   cmake -S . -B build -A x64
#   cmake --build build --config Release
#
# Or cross compile from Linux with MinGW-w64:
#   cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=<mingw toolchain>
#   cmake --build build

cmake_minimum_required(VERSION 3.13)
project(jpcsp-directx11 CXX)

if(NOT WIN32)
	message(FATAL_ERROR "The jpcsp Direct3D 11 wrapper can only be built for Windows")
endif()

set(CMAKE_CXX_STANDARD 11)

add_library(jpcsp-directx11 SHARED jpcsp_directx11.cpp)

# JNA looks the library up by its plain name, keep the "lib" prefix out
set_target_properties(jpcsp-directx11 PROPERTIES PREFIX "" OUTPUT_NAME "jpcsp-directx11")

# dxguid provides IID_ID3D11ShaderReflection, needed to reflect the shader uniforms
target_link_libraries(jpcsp-directx11 PRIVATE d3d11 d3dcompiler dxgi dxguid)

if(MSVC)
	target_compile_options(jpcsp-directx11 PRIVATE /W3)
else()
	# MinGW: export the undecorated cdecl names JNA expects
	target_compile_options(jpcsp-directx11 PRIVATE -Wall -O2)
	target_link_options(jpcsp-directx11 PRIVATE -Wl,--kill-at -static-libgcc -static-libstdc++)
endif()

if(CMAKE_SIZEOF_VOID_P EQUAL 8)
	set(JPCSP_NATIVE_DIR "windows-amd64")
else()
	set(JPCSP_NATIVE_DIR "windows-x86")
endif()

add_custom_command(TARGET jpcsp-directx11 POST_BUILD
	COMMAND ${CMAKE_COMMAND} -E copy_if_different
		$<TARGET_FILE:jpcsp-directx11>
		${CMAKE_CURRENT_SOURCE_DIR}/../../lib/${JPCSP_NATIVE_DIR}/jpcsp-directx11.dll
	COMMENT "Copying jpcsp-directx11.dll into lib/${JPCSP_NATIVE_DIR}")
