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
package jpcsp.graphics.RE.directx;

import java.nio.Buffer;

/**
 * @author gid15
 *
 * The contract of a Direct3D 11 wrapper.
 *
 * Java cannot talk to COM interfaces directly, so the Direct3D 11 device, its
 * immediate context and all its resources live inside a native wrapper which
 * exposes a flat, handle based API. This interface is that flat API seen from
 * the Java side: every resource is identified by a small positive integer
 * handle, exactly like an OpenGL object name.
 *
 * The interface is deliberately kept close to Direct3D 11 rather than to the
 * jpcsp {@link jpcsp.graphics.RE.IRenderingEngine}: the translation from the PSP
 * GE state to the Direct3D 11 state is the job of
 * {@link RenderingEngineDirectX11}, not of the wrapper.
 *
 * Implementations must be tolerant: a call referring to an unknown handle has to
 * be ignored instead of crashing the emulator.
 */
public interface IDirectX11Wrapper {
	//
	// Device life cycle
	//

	/**
	 * @return the name of this wrapper implementation, for logging purposes
	 */
	public String getName();

	/**
	 * Create the Direct3D 11 device, its immediate context and a swap chain
	 * presenting to the given native window.
	 *
	 * @param hwnd   the native window handle (HWND) receiving the swap chain
	 * @param width  the initial back buffer width
	 * @param height the initial back buffer height
	 * @param flags  a combination of DirectX11.DX11_DEVICE_FLAG_xxx
	 * @return true if the device could be created
	 */
	public boolean createDevice(long hwnd, int width, int height, int flags);

	/**
	 * Release the device and all the resources still allocated on it.
	 */
	public void destroyDevice();

	/**
	 * Resize the swap chain back buffer and its depth/stencil buffer.
	 */
	public void resize(int width, int height);

	/**
	 * Present the back buffer.
	 *
	 * @param swapInterval 0 for no vertical synchronization, 1 to synchronize
	 *                     with the vertical blank
	 */
	public void present(int swapInterval);

	/**
	 * @return the Direct3D feature level actually granted by the driver,
	 *         one of the DirectX11.D3D_FEATURE_LEVEL_xxx values
	 */
	public int getFeatureLevel();

	/**
	 * @return a human readable description of the adapter backing the device
	 */
	public String getAdapterDescription();

	/**
	 * @return the last error reported by the wrapper, or null if none
	 */
	public String getLastError();

	//
	// Buffers
	//

	public int createBuffer(int bindFlags, int usage, int size, Buffer data);
	public void deleteBuffer(int buffer);
	public void updateBuffer(int buffer, int offset, int size, Buffer data);
	public void bindVertexBuffer(int slot, int buffer, int stride, int offset);
	public void bindIndexBuffer(int buffer, int format, int offset);
	public void bindConstantBuffer(int stage, int slot, int buffer);

	//
	// Textures
	//

	public int createTexture(int width, int height, int format, int levels, int bindFlags);
	public void deleteTexture(int texture);
	public void updateTexture(int texture, int level, int x, int y, int width, int height, int rowPitch, int size, Buffer data);
	public void updateCompressedTexture(int texture, int level, int width, int height, int size, Buffer data);
	public void readTexture(int texture, int level, int size, Buffer data);
	public void bindTexture(int stage, int slot, int texture);
	public void generateMipmaps(int texture);
	public int getTextureLevelParameter(int texture, int level, int parameter);
	public void copyRenderTargetToTexture(int texture, int level, int xOffset, int yOffset, int x, int y, int width, int height);

	//
	// Render targets
	//

	public void bindRenderTargets(int colorTexture, int depthStencilTexture);
	public void readPixels(int x, int y, int width, int height, int format, int size, Buffer data);
	public void blit(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, boolean linearFilter);

	//
	// Pipeline states
	//

	public void setBlendState(boolean enabled, int srcRGB, int dstRGB, int opRGB, int srcAlpha, int dstAlpha, int opAlpha, int writeMask, float[] blendFactor);
	public void setDepthStencilState(boolean depthEnabled, boolean depthWrite, int depthFunc, boolean stencilEnabled, int stencilReadMask, int stencilWriteMask, int stencilFunc, int stencilRef, int failOp, int depthFailOp, int passOp);
	public void setRasterizerState(int cullMode, boolean frontCounterClockwise, int fillMode, boolean scissorEnabled, boolean depthClipEnabled, boolean antialiasedLine);
	public void setSamplerState(int slot, int filter, int addressU, int addressV, float minLod, float maxLod, float mipLodBias, int maxAnisotropy);
	public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth);
	public void setScissor(int left, int top, int right, int bottom);

	//
	// Shaders and programs
	//

	public int compileShader(int stage, String hlslSource, String entryPoint);
	public void deleteShader(int shader);
	public String getShaderLog(int shader);
	public int createProgram();
	public void deleteProgram(int program);
	public void attachShader(int program, int shader);
	public boolean linkProgram(int program);
	public void useProgram(int program);
	public String getProgramLog(int program);

	/**
	 * Look up a uniform inside the constant buffers reflected from the program.
	 *
	 * @return an opaque location, or -1 when the uniform is not active
	 */
	public int getUniformLocation(int program, String name);
	public void setUniformInt(int program, int location, int count, int[] values);
	public void setUniformFloat(int program, int location, int count, float[] values);
	public void setUniformMatrix(int program, int location, int order, int count, float[] values);

	//
	// Input layout
	//

	public void beginInputLayout();
	public void addInputElement(int location, int size, int type, boolean normalized, int stride, int offset);
	public void endInputLayout(int program);

	//
	// Drawing
	//

	public void clear(int mask, float red, float green, float blue, float alpha, float depth, int stencil);
	public void draw(int topology, int first, int count);
	public void drawIndexed(int topology, int count, int indexFormat, int offset);
	public void flush();
	public void finish();

	//
	// Occlusion queries
	//

	public int createQuery();
	public void deleteQuery(int query);
	public void beginQuery(int query);
	public void endQuery(int query);
	public boolean isQueryResultAvailable(int query);
	public int getQueryResult(int query);
}
