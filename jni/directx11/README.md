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
libraries are part of the MinGW-w64 headers:

```
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=<your mingw-w64 toolchain file>
cmake --build build
```

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
  canvas, using the BitBlt presentation model so that it coexists with the
  OpenGL pixel format AWT already set on that window. jpcsp never swaps the
  OpenGL buffers while Direct3D 11 is active.

## Not implemented

* Scaling `dx11Blit()`: only a 1:1 copy is supported, a scaling blit would need
  a full screen quad and its own shader.
* Color logic operations: they need `ID3D11Device1` and are ignored.
