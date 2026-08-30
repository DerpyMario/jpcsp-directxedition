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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.log4j.Logger;

import com.sun.jna.Library;
import com.sun.jna.Native;

import jpcsp.graphics.VideoEngine;

/**
 * @author gid15
 *
 * The {@link IDirectX11Wrapper} implementation talking to the native
 * "jpcsp-directx11" library through JNA.
 *
 * The native library exposes a flat C API (see jni/directx11/jpcsp_directx11.h),
 * so no COM interface ever crosses the JNI boundary: the Java side only
 * manipulates integer handles.
 *
 * All the buffers passed to the native library must be direct buffers, JNA maps
 * those to a plain pointer without any copy. Non direct buffers are copied into
 * a scratch direct buffer first.
 */
public class DirectX11NativeWrapper implements IDirectX11Wrapper {
	private static Logger log = VideoEngine.log;
	public static final String libraryName = "jpcsp-directx11";
	private static Api api;
	private static boolean libraryLoaded;
	private static boolean libraryLoadFailed;
	private ByteBuffer scratchBuffer;

	/**
	 * The flat C API implemented by the native wrapper.
	 *
	 * The methods are intentionally primitive: all the resources are identified
	 * by an int handle and all the descriptors are passed as separate scalar
	 * parameters so that the ABI stays stable.
	 */
	public interface Api extends Library {
		int dx11Init();
		void dx11Shutdown();
		int dx11CreateDevice(long hwnd, int width, int height, int flags);
		void dx11DestroyDevice();
		void dx11Resize(int width, int height);
		void dx11Present(int swapInterval);
		int dx11GetFeatureLevel();
		String dx11GetAdapterDescription();
		String dx11GetLastError();

		int dx11CreateBuffer(int bindFlags, int usage, int size, Buffer data);
		void dx11DeleteBuffer(int buffer);
		void dx11UpdateBuffer(int buffer, int offset, int size, Buffer data);
		void dx11BindVertexBuffer(int slot, int buffer, int stride, int offset);
		void dx11BindIndexBuffer(int buffer, int format, int offset);
		void dx11BindConstantBuffer(int stage, int slot, int buffer);

		int dx11CreateTexture(int width, int height, int format, int levels, int bindFlags);
		void dx11DeleteTexture(int texture);
		void dx11UpdateTexture(int texture, int level, int x, int y, int width, int height, int rowPitch, int size, Buffer data);
		void dx11UpdateCompressedTexture(int texture, int level, int width, int height, int size, Buffer data);
		void dx11ReadTexture(int texture, int level, int size, Buffer data);
		void dx11BindTexture(int stage, int slot, int texture);
		void dx11GenerateMipmaps(int texture);
		int dx11GetTextureLevelParameter(int texture, int level, int parameter);
		void dx11CopyRenderTargetToTexture(int texture, int level, int xOffset, int yOffset, int x, int y, int width, int height);

		void dx11BindRenderTargets(int colorTexture, int depthStencilTexture);
		void dx11ReadPixels(int x, int y, int width, int height, int format, int size, Buffer data);
		void dx11Blit(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int linearFilter);

		void dx11SetBlendState(int enabled, int srcRGB, int dstRGB, int opRGB, int srcAlpha, int dstAlpha, int opAlpha, int writeMask, float red, float green, float blue, float alpha);
		void dx11SetDepthStencilState(int depthEnabled, int depthWrite, int depthFunc, int stencilEnabled, int stencilReadMask, int stencilWriteMask, int stencilFunc, int stencilRef, int failOp, int depthFailOp, int passOp);
		void dx11SetRasterizerState(int cullMode, int frontCounterClockwise, int fillMode, int scissorEnabled, int depthClipEnabled, int antialiasedLine);
		void dx11SetSamplerState(int slot, int filter, int addressU, int addressV, float minLod, float maxLod, float mipLodBias, int maxAnisotropy);
		void dx11SetViewport(float x, float y, float width, float height, float minDepth, float maxDepth);
		void dx11SetScissor(int left, int top, int right, int bottom);

		int dx11CompileShader(int stage, String hlslSource, String entryPoint);
		void dx11DeleteShader(int shader);
		String dx11GetShaderLog(int shader);
		int dx11CreateProgram();
		void dx11DeleteProgram(int program);
		void dx11AttachShader(int program, int shader);
		int dx11LinkProgram(int program);
		void dx11UseProgram(int program);
		String dx11GetProgramLog(int program);
		int dx11GetUniformLocation(int program, String name);
		void dx11SetUniformInt(int program, int location, int count, int[] values);
		void dx11SetUniformFloat(int program, int location, int count, float[] values);
		void dx11SetUniformMatrix(int program, int location, int order, int count, float[] values);

		void dx11BeginInputLayout();
		void dx11AddInputElement(int location, int size, int type, int normalized, int stride, int offset);
		void dx11EndInputLayout(int program);

		void dx11Clear(int mask, float red, float green, float blue, float alpha, float depth, int stencil);
		void dx11Draw(int topology, int first, int count);
		void dx11DrawIndexed(int topology, int count, int indexFormat, int offset);
		void dx11Flush();
		void dx11Finish();

		int dx11CreateQuery();
		void dx11DeleteQuery(int query);
		void dx11BeginQuery(int query);
		void dx11EndQuery(int query);
		int dx11IsQueryResultAvailable(int query);
		int dx11GetQueryResult(int query);
	}

	/**
	 * Try to load the native wrapper library. The result is cached: a failed
	 * attempt is never retried, the emulator has to keep starting quickly when
	 * the library is not installed.
	 *
	 * @return true if the native wrapper is usable
	 */
	public static synchronized boolean isAvailable() {
		if (libraryLoaded) {
			return true;
		}
		if (libraryLoadFailed) {
			return false;
		}

		libraryLoadFailed = true;
		try {
			api = Native.load(libraryName, Api.class);
		} catch (UnsatisfiedLinkError e) {
			log.info(String.format("The DirectX 11 wrapper library '%s' is not available: %s", libraryName, e.getMessage()));
			return false;
		} catch (NoClassDefFoundError e) {
			log.info(String.format("The DirectX 11 wrapper library '%s' cannot be loaded: %s", libraryName, e.getMessage()));
			return false;
		}

		int result;
		try {
			result = api.dx11Init();
		} catch (UnsatisfiedLinkError e) {
			log.warn(String.format("The DirectX 11 wrapper library '%s' is incomplete: %s", libraryName, e.getMessage()));
			api = null;
			return false;
		}

		if (result == 0) {
			log.info(String.format("Direct3D 11 is not available on this system: %s", api.dx11GetLastError()));
			api = null;
			return false;
		}

		libraryLoaded = true;
		libraryLoadFailed = false;

		return true;
	}

	public DirectX11NativeWrapper() {
		if (!isAvailable()) {
			throw new IllegalStateException(String.format("The native library '%s' is not available", libraryName));
		}
	}

	private Buffer direct(Buffer buffer, int size) {
		if (buffer == null) {
			return null;
		}
		if (buffer.isDirect()) {
			return buffer;
		}

		// JNA can only pass direct buffers as a plain pointer.
		// Copy the content of a heap buffer into a scratch direct buffer.
		if (scratchBuffer == null || scratchBuffer.capacity() < size) {
			scratchBuffer = ByteBuffer.allocateDirect(Math.max(size, 64 * 1024)).order(ByteOrder.LITTLE_ENDIAN);
		}
		scratchBuffer.clear();

		ByteBuffer dst = scratchBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		dst.limit(size);
		if (buffer instanceof ByteBuffer) {
			ByteBuffer src = ((ByteBuffer) buffer).duplicate();
			src.limit(src.position() + Math.min(size, src.remaining()));
			dst.put(src);
		} else if (buffer.hasArray()) {
			Object array = buffer.array();
			int offset = buffer.arrayOffset() + buffer.position();
			if (array instanceof int[]) {
				dst.asIntBuffer().put((int[]) array, offset, size >> 2);
			} else if (array instanceof short[]) {
				dst.asShortBuffer().put((short[]) array, offset, size >> 1);
			} else if (array instanceof float[]) {
				dst.asFloatBuffer().put((float[]) array, offset, size >> 2);
			} else if (array instanceof byte[]) {
				dst.put((byte[]) array, offset, size);
			} else {
				log.error(String.format("DirectX11NativeWrapper: unsupported buffer type %s", buffer.getClass().getName()));
				return null;
			}
		} else {
			log.error(String.format("DirectX11NativeWrapper: cannot access buffer %s", buffer));
			return null;
		}

		return scratchBuffer;
	}

	@Override
	public String getName() {
		return "JNA/jpcsp-directx11";
	}

	@Override
	public boolean createDevice(long hwnd, int width, int height, int flags) {
		return api.dx11CreateDevice(hwnd, width, height, flags) != 0;
	}

	@Override
	public void destroyDevice() {
		api.dx11DestroyDevice();
	}

	@Override
	public void resize(int width, int height) {
		api.dx11Resize(width, height);
	}

	@Override
	public void present(int swapInterval) {
		api.dx11Present(swapInterval);
	}

	@Override
	public int getFeatureLevel() {
		return api.dx11GetFeatureLevel();
	}

	@Override
	public String getAdapterDescription() {
		return api.dx11GetAdapterDescription();
	}

	@Override
	public String getLastError() {
		return api.dx11GetLastError();
	}

	@Override
	public int createBuffer(int bindFlags, int usage, int size, Buffer data) {
		return api.dx11CreateBuffer(bindFlags, usage, size, direct(data, size));
	}

	@Override
	public void deleteBuffer(int buffer) {
		api.dx11DeleteBuffer(buffer);
	}

	@Override
	public void updateBuffer(int buffer, int offset, int size, Buffer data) {
		api.dx11UpdateBuffer(buffer, offset, size, direct(data, size));
	}

	@Override
	public void bindVertexBuffer(int slot, int buffer, int stride, int offset) {
		api.dx11BindVertexBuffer(slot, buffer, stride, offset);
	}

	@Override
	public void bindIndexBuffer(int buffer, int format, int offset) {
		api.dx11BindIndexBuffer(buffer, format, offset);
	}

	@Override
	public void bindConstantBuffer(int stage, int slot, int buffer) {
		api.dx11BindConstantBuffer(stage, slot, buffer);
	}

	@Override
	public int createTexture(int width, int height, int format, int levels, int bindFlags) {
		return api.dx11CreateTexture(width, height, format, levels, bindFlags);
	}

	@Override
	public void deleteTexture(int texture) {
		api.dx11DeleteTexture(texture);
	}

	@Override
	public void updateTexture(int texture, int level, int x, int y, int width, int height, int rowPitch, int size, Buffer data) {
		api.dx11UpdateTexture(texture, level, x, y, width, height, rowPitch, size, direct(data, size));
	}

	@Override
	public void updateCompressedTexture(int texture, int level, int width, int height, int size, Buffer data) {
		api.dx11UpdateCompressedTexture(texture, level, width, height, size, direct(data, size));
	}

	@Override
	public void readTexture(int texture, int level, int size, Buffer data) {
		if (data != null && !data.isDirect()) {
			log.warn("DirectX11NativeWrapper.readTexture requires a direct buffer");
			return;
		}
		api.dx11ReadTexture(texture, level, size, data);
	}

	@Override
	public void bindTexture(int stage, int slot, int texture) {
		api.dx11BindTexture(stage, slot, texture);
	}

	@Override
	public void generateMipmaps(int texture) {
		api.dx11GenerateMipmaps(texture);
	}

	@Override
	public int getTextureLevelParameter(int texture, int level, int parameter) {
		return api.dx11GetTextureLevelParameter(texture, level, parameter);
	}

	@Override
	public void copyRenderTargetToTexture(int texture, int level, int xOffset, int yOffset, int x, int y, int width, int height) {
		api.dx11CopyRenderTargetToTexture(texture, level, xOffset, yOffset, x, y, width, height);
	}

	@Override
	public void bindRenderTargets(int colorTexture, int depthStencilTexture) {
		api.dx11BindRenderTargets(colorTexture, depthStencilTexture);
	}

	@Override
	public void readPixels(int x, int y, int width, int height, int format, int size, Buffer data) {
		if (data != null && !data.isDirect()) {
			log.warn("DirectX11NativeWrapper.readPixels requires a direct buffer");
			return;
		}
		api.dx11ReadPixels(x, y, width, height, format, size, data);
	}

	@Override
	public void blit(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, boolean linearFilter) {
		api.dx11Blit(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, linearFilter ? 1 : 0);
	}

	@Override
	public void setBlendState(boolean enabled, int srcRGB, int dstRGB, int opRGB, int srcAlpha, int dstAlpha, int opAlpha, int writeMask, float[] blendFactor) {
		api.dx11SetBlendState(enabled ? 1 : 0, srcRGB, dstRGB, opRGB, srcAlpha, dstAlpha, opAlpha, writeMask, blendFactor[0], blendFactor[1], blendFactor[2], blendFactor[3]);
	}

	@Override
	public void setDepthStencilState(boolean depthEnabled, boolean depthWrite, int depthFunc, boolean stencilEnabled, int stencilReadMask, int stencilWriteMask, int stencilFunc, int stencilRef, int failOp, int depthFailOp, int passOp) {
		api.dx11SetDepthStencilState(depthEnabled ? 1 : 0, depthWrite ? 1 : 0, depthFunc, stencilEnabled ? 1 : 0, stencilReadMask, stencilWriteMask, stencilFunc, stencilRef, failOp, depthFailOp, passOp);
	}

	@Override
	public void setRasterizerState(int cullMode, boolean frontCounterClockwise, int fillMode, boolean scissorEnabled, boolean depthClipEnabled, boolean antialiasedLine) {
		api.dx11SetRasterizerState(cullMode, frontCounterClockwise ? 1 : 0, fillMode, scissorEnabled ? 1 : 0, depthClipEnabled ? 1 : 0, antialiasedLine ? 1 : 0);
	}

	@Override
	public void setSamplerState(int slot, int filter, int addressU, int addressV, float minLod, float maxLod, float mipLodBias, int maxAnisotropy) {
		api.dx11SetSamplerState(slot, filter, addressU, addressV, minLod, maxLod, mipLodBias, maxAnisotropy);
	}

	@Override
	public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
		api.dx11SetViewport(x, y, width, height, minDepth, maxDepth);
	}

	@Override
	public void setScissor(int left, int top, int right, int bottom) {
		api.dx11SetScissor(left, top, right, bottom);
	}

	@Override
	public int compileShader(int stage, String hlslSource, String entryPoint) {
		return api.dx11CompileShader(stage, hlslSource, entryPoint);
	}

	@Override
	public void deleteShader(int shader) {
		api.dx11DeleteShader(shader);
	}

	@Override
	public String getShaderLog(int shader) {
		return api.dx11GetShaderLog(shader);
	}

	@Override
	public int createProgram() {
		return api.dx11CreateProgram();
	}

	@Override
	public void deleteProgram(int program) {
		api.dx11DeleteProgram(program);
	}

	@Override
	public void attachShader(int program, int shader) {
		api.dx11AttachShader(program, shader);
	}

	@Override
	public boolean linkProgram(int program) {
		return api.dx11LinkProgram(program) != 0;
	}

	@Override
	public void useProgram(int program) {
		api.dx11UseProgram(program);
	}

	@Override
	public String getProgramLog(int program) {
		return api.dx11GetProgramLog(program);
	}

	@Override
	public int getUniformLocation(int program, String name) {
		return api.dx11GetUniformLocation(program, name);
	}

	@Override
	public void setUniformInt(int program, int location, int count, int[] values) {
		api.dx11SetUniformInt(program, location, count, values);
	}

	@Override
	public void setUniformFloat(int program, int location, int count, float[] values) {
		api.dx11SetUniformFloat(program, location, count, values);
	}

	@Override
	public void setUniformMatrix(int program, int location, int order, int count, float[] values) {
		api.dx11SetUniformMatrix(program, location, order, count, values);
	}

	@Override
	public void beginInputLayout() {
		api.dx11BeginInputLayout();
	}

	@Override
	public void addInputElement(int location, int size, int type, boolean normalized, int stride, int offset) {
		api.dx11AddInputElement(location, size, type, normalized ? 1 : 0, stride, offset);
	}

	@Override
	public void endInputLayout(int program) {
		api.dx11EndInputLayout(program);
	}

	@Override
	public void clear(int mask, float red, float green, float blue, float alpha, float depth, int stencil) {
		api.dx11Clear(mask, red, green, blue, alpha, depth, stencil);
	}

	@Override
	public void draw(int topology, int first, int count) {
		api.dx11Draw(topology, first, count);
	}

	@Override
	public void drawIndexed(int topology, int count, int indexFormat, int offset) {
		api.dx11DrawIndexed(topology, count, indexFormat, offset);
	}

	@Override
	public void flush() {
		api.dx11Flush();
	}

	@Override
	public void finish() {
		api.dx11Finish();
	}

	@Override
	public int createQuery() {
		return api.dx11CreateQuery();
	}

	@Override
	public void deleteQuery(int query) {
		api.dx11DeleteQuery(query);
	}

	@Override
	public void beginQuery(int query) {
		api.dx11BeginQuery(query);
	}

	@Override
	public void endQuery(int query) {
		api.dx11EndQuery(query);
	}

	@Override
	public boolean isQueryResultAvailable(int query) {
		return api.dx11IsQueryResultAvailable(query) != 0;
	}

	@Override
	public int getQueryResult(int query) {
		return api.dx11GetQueryResult(query);
	}
}
