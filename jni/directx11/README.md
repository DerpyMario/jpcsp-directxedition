# jpcsp Direct3D 11 wrapper

This directory contains the native wrapper backing the DirectX 11 rendering
engine of jpcsp (`jpcsp.graphics.RE.directx`).

Java cannot talk to COM interfaces, so the Direct3D 11 device, its immediate
context and all its resources live here, behind a flat C API where every
resource is identified by a small `int` handle, exactly like an OpenGL object
name. The Java side binds to that API with JNA.

## Files

| File | Contents |
| --- | --- |
| `jpcsp_directx11.h` | the flat C API, mirrored by `DirectX11NativeWrapper.Api` |
| `jpcsp_directx11.cpp` | the Direct3D 11 implementation |
| `CMakeLists.txt` | the build script |

## Building

The wrapper is Windows only. With the Visual Studio toolchain:

```
cmake -S . -B build -A x64
cmake --build build --config Release
```

The build copies `jpcsp-directx11.dll` into `lib/windows-amd64` (or
`lib/windows-x86` for a 32 bit build), which is on the `java.library.path` used
by the jpcsp start scripts. Nothing else has to be installed: `d3d11.dll`,
`d3dcompiler_47.dll` and `dxgi.dll` ship with Windows.

Cross compiling from Linux with MinGW-w64 also works, the Direct3D import
libraries are part of the MinGW-w64 headers. With
`apt install g++-mingw-w64-x86-64` and this toolchain file:

```cmake
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER   x86_64-w64-mingw32-gcc)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++)
set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
```

```
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=mingw.cmake
cmake --build build
```

The CI workflow builds the wrapper for both architectures on a Windows runner
and bundles the result into the published Windows builds, so a release archive
already contains the DLL.

## Using it

When the DLL is not present, `DirectX11NativeWrapper.isAvailable()` returns
false, jpcsp logs one informational line and the OpenGL rendering engine is
used instead. Selecting "Use DirectX 11 renderer" in the video settings without
the DLL installed is therefore harmless.

## Design notes

* **Handles.** `0` always means "no resource". Every function tolerates an
  unknown handle: it reports the problem through `dx11GetLastError()` and
  otherwise does nothing, so that a wrapper bug cannot take the emulator down.
* **State objects.** Direct3D 11 groups the render state into four immutable
  objects (blend, depth/stencil, rasterizer, sampler). The Java side accumulates
  the individual OpenGL style state changes and pushes a complete descriptor;
  the wrapper caches the created state objects, keyed by that descriptor, so a
  repeated descriptor never allocates anything.
* **Uniforms.** The GLSL to HLSL translator emits one
  `cbuffer JpcspUniforms : register(b0)` per shader, so the same uniform can sit
  at a different offset in the vertex and in the pixel shader. `dx11LinkProgram`
  reflects the constant buffers of every attached shader and records one entry
  per stage for each uniform; writing a uniform updates all of them. The shadow
  copies are uploaded lazily, right before a draw call.
* **Vertex layout.** The translator gives every vertex shader input a
  `TEXCOORD` semantic whose index is its GLSL `layout(location=...)`, which is
  what `dx11AddInputElement()` uses to bind an attribute to an input element.
* **Swap chain.** The swap chain is created on the window of the jpcsp display
  canvas, using the BitBlt presentation model. When this renderer is selected,
  jpcsp does not create an OpenGL context on that window at all: it only passes
  the window handle here. That is the point of the whole exercise, since a
  process that never loads the OpenGL driver cannot be brought down by it.
* **Blitting.** `dx11Blit()` copies with `CopySubresourceRegion` when the source
  and the destination rectangles have the same size, and otherwise draws the
  source as a full screen triangle generated from `SV_VertexID`, so a rescaling
  blit needs neither a vertex buffer nor an input layout. It runs on its own
  private pipeline, and the caller re-pushes the emulator state afterwards.

## Status

The wrapper compiles and links cleanly (`-Wall -Wextra`, no warnings) and
exports all of its functions undecorated, which is what JNA needs to bind them.
It has however never been exercised against a real GPU: expect to have to
debug it before it draws a correct frame.

## Not implemented

* Rescaling a depth/stencil buffer: a pixel shader cannot write depth or
  stencil, so a depth/stencil blit is limited to a 1:1 copy.
* Color logic operations: they need `ID3D11Device1` and are ignored.
