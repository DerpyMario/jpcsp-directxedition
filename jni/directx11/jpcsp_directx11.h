/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * The flat C API of the jpcsp Direct3D 11 wrapper.
 *
 * The Java side (jpcsp.graphics.RE.directx.DirectX11NativeWrapper) binds to
 * these functions with JNA, so the ABI has to stay simple and stable:
 *   - no COM interface and no structure ever crosses the boundary,
 *   - every resource is identified by a small positive int handle,
 *   - the handle 0 always means "no resource",
 *   - the descriptors are passed as separate scalar parameters.
 *
 * All the enumeration values (DXGI_FORMAT, D3D11_BLEND, ...) are the ones from
 * the Windows SDK, mirrored on the Java side by jpcsp.graphics.RE.directx.DirectX11.
 *
 * The functions must never throw and must tolerate unknown handles: a wrong
 * handle is reported through dx11GetLastError() and otherwise ignored, so that
 * a wrapper bug cannot take the emulator down.
 */
#ifndef JPCSP_DIRECTX11_H
#define JPCSP_DIRECTX11_H

#include <stdint.h>

#ifdef _WIN32
#define DX11_API __declspec(dllexport)
#else
#define DX11_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Shader stages, mirrored by DirectX11.DX11_STAGE_xxx */
#define DX11_STAGE_VERTEX   0
#define DX11_STAGE_PIXEL    1
#define DX11_STAGE_GEOMETRY 2
#define DX11_STAGE_HULL     3
#define DX11_STAGE_DOMAIN   4
#define DX11_STAGE_COMPUTE  5

/* Device creation flags, mirrored by DirectX11.DX11_DEVICE_FLAG_xxx */
#define DX11_DEVICE_FLAG_DEBUG           0x1
#define DX11_DEVICE_FLAG_SINGLE_THREADED 0x2

/* Clear flags: the two low bits are the D3D11_CLEAR_FLAG ones */
#define DX11_CLEAR_DEPTH   0x1
#define DX11_CLEAR_STENCIL 0x2
#define DX11_CLEAR_COLOR   0x4

/* Vertex attribute component types, mirrored by IRenderingEngine.RE_xxx */
#define DX11_TYPE_BYTE           0
#define DX11_TYPE_UNSIGNED_BYTE  1
#define DX11_TYPE_SHORT          2
#define DX11_TYPE_UNSIGNED_SHORT 3
#define DX11_TYPE_INT            4
#define DX11_TYPE_UNSIGNED_INT   5
#define DX11_TYPE_FLOAT          6
#define DX11_TYPE_DOUBLE         7

/*
 * Device life cycle
 */

/* Returns 1 when Direct3D 11 is usable on this system, 0 otherwise. */
DX11_API int32_t dx11Init(void);
DX11_API void    dx11Shutdown(void);

/* Creates the device, the immediate context and the swap chain of the window. */
DX11_API int32_t dx11CreateDevice(int64_t hwnd, int32_t width, int32_t height, int32_t flags);
DX11_API void    dx11DestroyDevice(void);
DX11_API void    dx11Resize(int32_t width, int32_t height);
DX11_API void    dx11Present(int32_t swapInterval);
DX11_API int32_t dx11GetFeatureLevel(void);
DX11_API const char *dx11GetAdapterDescription(void);
/* Returns the last error and clears it, or an empty string when there is none. */
DX11_API const char *dx11GetLastError(void);

/*
 * Buffers
 */

DX11_API int32_t dx11CreateBuffer(int32_t bindFlags, int32_t usage, int32_t size, const void *data);
DX11_API void    dx11DeleteBuffer(int32_t buffer);
DX11_API void    dx11UpdateBuffer(int32_t buffer, int32_t offset, int32_t size, const void *data);
DX11_API void    dx11BindVertexBuffer(int32_t slot, int32_t buffer, int32_t stride, int32_t offset);
DX11_API void    dx11BindIndexBuffer(int32_t buffer, int32_t format, int32_t offset);
DX11_API void    dx11BindConstantBuffer(int32_t stage, int32_t slot, int32_t buffer);

/*
 * Textures
 */

DX11_API int32_t dx11CreateTexture(int32_t width, int32_t height, int32_t format, int32_t levels, int32_t bindFlags);
DX11_API void    dx11DeleteTexture(int32_t texture);
DX11_API void    dx11UpdateTexture(int32_t texture, int32_t level, int32_t x, int32_t y, int32_t width, int32_t height, int32_t rowPitch, int32_t size, const void *data);
DX11_API void    dx11UpdateCompressedTexture(int32_t texture, int32_t level, int32_t width, int32_t height, int32_t size, const void *data);
DX11_API void    dx11ReadTexture(int32_t texture, int32_t level, int32_t size, void *data);
DX11_API void    dx11BindTexture(int32_t stage, int32_t slot, int32_t texture);
DX11_API void    dx11GenerateMipmaps(int32_t texture);
DX11_API int32_t dx11GetTextureLevelParameter(int32_t texture, int32_t level, int32_t parameter);
DX11_API void    dx11CopyRenderTargetToTexture(int32_t texture, int32_t level, int32_t xOffset, int32_t yOffset, int32_t x, int32_t y, int32_t width, int32_t height);

/*
 * Render targets
 */

DX11_API void dx11BindRenderTargets(int32_t colorTexture, int32_t depthStencilTexture);
DX11_API void dx11ReadPixels(int32_t x, int32_t y, int32_t width, int32_t height, int32_t format, int32_t size, void *data);
DX11_API void dx11Blit(int32_t srcX0, int32_t srcY0, int32_t srcX1, int32_t srcY1, int32_t dstX0, int32_t dstY0, int32_t dstX1, int32_t dstY1, int32_t mask, int32_t linearFilter);

/*
 * Pipeline states
 *
 * The state objects created from these descriptors are cached by the wrapper:
 * calling a setter with an already seen descriptor does not allocate anything.
 */

DX11_API void dx11SetBlendState(int32_t enabled, int32_t srcRGB, int32_t dstRGB, int32_t opRGB, int32_t srcAlpha, int32_t dstAlpha, int32_t opAlpha, int32_t writeMask, float red, float green, float blue, float alpha);
DX11_API void dx11SetDepthStencilState(int32_t depthEnabled, int32_t depthWrite, int32_t depthFunc, int32_t stencilEnabled, int32_t stencilReadMask, int32_t stencilWriteMask, int32_t stencilFunc, int32_t stencilRef, int32_t failOp, int32_t depthFailOp, int32_t passOp);
DX11_API void dx11SetRasterizerState(int32_t cullMode, int32_t frontCounterClockwise, int32_t fillMode, int32_t scissorEnabled, int32_t depthClipEnabled, int32_t antialiasedLine);
DX11_API void dx11SetSamplerState(int32_t slot, int32_t filter, int32_t addressU, int32_t addressV, float minLod, float maxLod, float mipLodBias, int32_t maxAnisotropy);
DX11_API void dx11SetViewport(float x, float y, float width, float height, float minDepth, float maxDepth);
DX11_API void dx11SetScissor(int32_t left, int32_t top, int32_t right, int32_t bottom);

/*
 * Shaders and programs
 *
 * A "program" groups the shaders of the different stages, like an OpenGL
 * program object. It also owns the shadow copy of the uniform constant buffer.
 */

DX11_API int32_t dx11CompileShader(int32_t stage, const char *hlslSource, const char *entryPoint);
DX11_API void    dx11DeleteShader(int32_t shader);
DX11_API const char *dx11GetShaderLog(int32_t shader);
DX11_API int32_t dx11CreateProgram(void);
DX11_API void    dx11DeleteProgram(int32_t program);
DX11_API void    dx11AttachShader(int32_t program, int32_t shader);
DX11_API int32_t dx11LinkProgram(int32_t program);
DX11_API void    dx11UseProgram(int32_t program);
DX11_API const char *dx11GetProgramLog(int32_t program);
/* Returns an index into the reflected uniform table, or -1 when not active. */
DX11_API int32_t dx11GetUniformLocation(int32_t program, const char *name);
DX11_API void    dx11SetUniformInt(int32_t program, int32_t location, int32_t count, const int32_t *values);
DX11_API void    dx11SetUniformFloat(int32_t program, int32_t location, int32_t count, const float *values);
/* order is 3 for a mat3 and 4 for a mat4, count is the number of matrices. */
DX11_API void    dx11SetUniformMatrix(int32_t program, int32_t location, int32_t order, int32_t count, const float *values);

/*
 * Input layout
 *
 * The elements are accumulated between dx11BeginInputLayout() and
 * dx11EndInputLayout(), which creates (or reuses) the matching input layout for
 * the vertex shader of the given program.
 */

DX11_API void dx11BeginInputLayout(void);
DX11_API void dx11AddInputElement(int32_t location, int32_t size, int32_t type, int32_t normalized, int32_t stride, int32_t offset);
DX11_API void dx11EndInputLayout(int32_t program);

/*
 * Drawing
 */

DX11_API void dx11Clear(int32_t mask, float red, float green, float blue, float alpha, float depth, int32_t stencil);
DX11_API void dx11Draw(int32_t topology, int32_t first, int32_t count);
DX11_API void dx11DrawIndexed(int32_t topology, int32_t count, int32_t indexFormat, int32_t offset);
DX11_API void dx11Flush(void);
DX11_API void dx11Finish(void);

/*
 * Occlusion queries
 */

DX11_API int32_t dx11CreateQuery(void);
DX11_API void    dx11DeleteQuery(int32_t query);
DX11_API void    dx11BeginQuery(int32_t query);
DX11_API void    dx11EndQuery(int32_t query);
DX11_API int32_t dx11IsQueryResultAvailable(int32_t query);
DX11_API int32_t dx11GetQueryResult(int32_t query);

#ifdef __cplusplus
}
#endif

#endif /* JPCSP_DIRECTX11_H */
