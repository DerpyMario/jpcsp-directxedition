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

import static jpcsp.graphics.RE.directx.DirectX11.*;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

import jpcsp.graphics.VertexInfo;
import jpcsp.graphics.RE.NullRenderingEngine;

/**
 * @author gid15
 *
 * A RenderingEngine implementing the jpcsp rendering interface on top of
 * Direct3D 11, through the flat {@link IDirectX11Wrapper} native wrapper.
 *
 * This class holds all the translation logic between the OpenGL flavoured
 * rendering interface used by jpcsp and Direct3D 11:
 * <ul>
 * <li>the independent state bits are collected into the immutable Direct3D 11
 *     state objects by {@link DirectX11StateCache};</li>
 * <li>the GLSL shaders are translated to HLSL by
 *     {@link DirectX11ShaderTranslator};</li>
 * <li>the primitives which do not exist in Direct3D 11 (triangle fans and
 *     quads) are expanded into triangle lists;</li>
 * <li>the framebuffer objects are emulated with explicit render target
 *     bindings.</li>
 * </ul>
 *
 * Like {@link jpcsp.graphics.RE.RenderingEngineLwjgl}, this class contains no
 * rendering logic of its own: the PSP GE semantics are implemented by the proxies
 * sitting above it in the rendering pipeline.
 */
public class RenderingEngineDirectX11 extends NullRenderingEngine {
	public static final int MAX_ATTRIBUTES = 16;
	private static final int MAX_TEXTURE_UNITS = DirectX11StateCache.MAX_SAMPLERS;

	protected static final int[] depthFuncToD3D = {
		D3D11_COMPARISON_NEVER,         // ZTST_FUNCTION_NEVER_PASS_PIXEL
		D3D11_COMPARISON_ALWAYS,        // ZTST_FUNCTION_ALWAYS_PASS_PIXEL
		D3D11_COMPARISON_EQUAL,         // ZTST_FUNCTION_PASS_PX_WHEN_DEPTH_IS_EQUAL
		D3D11_COMPARISON_NOT_EQUAL,     // ZTST_FUNCTION_PASS_PX_WHEN_DEPTH_ISNOT_EQUAL
		D3D11_COMPARISON_LESS,          // ZTST_FUNCTION_PASS_PX_WHEN_DEPTH_IS_LESS
		D3D11_COMPARISON_LESS_EQUAL,    // ZTST_FUNCTION_PASS_PX_WHEN_DEPTH_IS_LESS_OR_EQUAL
		D3D11_COMPARISON_GREATER,       // ZTST_FUNCTION_PASS_PX_WHEN_DEPTH_IS_GREATER
		D3D11_COMPARISON_GREATER_EQUAL  // ZTST_FUNCTION_PASS_PX_WHEN_DEPTH_IS_GREATER_OR_EQUAL
	};
	protected static final int[] stencilFuncToD3D = {
		D3D11_COMPARISON_NEVER,         // STST_FUNCTION_NEVER_PASS_STENCIL_TEST
		D3D11_COMPARISON_ALWAYS,        // STST_FUNCTION_ALWAYS_PASS_STENCIL_TEST
		D3D11_COMPARISON_EQUAL,         // STST_FUNCTION_PASS_TEST_IF_MATCHES
		D3D11_COMPARISON_NOT_EQUAL,     // STST_FUNCTION_PASS_TEST_IF_DIFFERS
		D3D11_COMPARISON_LESS,          // STST_FUNCTION_PASS_TEST_IF_LESS
		D3D11_COMPARISON_LESS_EQUAL,    // STST_FUNCTION_PASS_TEST_IF_LESS_OR_EQUAL
		D3D11_COMPARISON_GREATER,       // STST_FUNCTION_PASS_TEST_IF_GREATER
		D3D11_COMPARISON_GREATER_EQUAL  // STST_FUNCTION_PASS_TEST_IF_GREATER_OR_EQUAL
	};
	protected static final int[] stencilOpToD3D = {
		D3D11_STENCIL_OP_KEEP,     // SOP_KEEP_STENCIL_VALUE
		D3D11_STENCIL_OP_ZERO,     // SOP_ZERO_STENCIL_VALUE
		D3D11_STENCIL_OP_REPLACE,  // SOP_REPLACE_STENCIL_VALUE
		D3D11_STENCIL_OP_INVERT,   // SOP_INVERT_STENCIL_VALUE
		D3D11_STENCIL_OP_INCR_SAT, // SOP_INCREMENT_STENCIL_VALUE
		D3D11_STENCIL_OP_DECR_SAT  // SOP_DECREMENT_STENCIL_VALUE
	};
	protected static final int[] blendSrcToD3D = {
		D3D11_BLEND_DEST_COLOR,       // GU_SRC_COLOR
		D3D11_BLEND_INV_DEST_COLOR,   // GU_ONE_MINUS_SRC_COLOR
		D3D11_BLEND_SRC_ALPHA,        // GU_SRC_ALPHA
		D3D11_BLEND_INV_SRC_ALPHA,    // GU_ONE_MINUS_SRC_ALPHA
		D3D11_BLEND_DEST_ALPHA,       // GU_DST_ALPHA
		D3D11_BLEND_INV_DEST_ALPHA,   // GU_ONE_MINUS_DST_ALPHA
		D3D11_BLEND_SRC_ALPHA,        // GU_DOUBLE_SRC_ALPHA
		D3D11_BLEND_INV_SRC_ALPHA,    // GU_ONE_MINUS_DOUBLE_SRC_ALPHA
		D3D11_BLEND_DEST_ALPHA,       // GU_DOUBLE_DST_ALPHA
		D3D11_BLEND_INV_DEST_ALPHA,   // GU_ONE_MINUS_DOUBLE_DST_ALPHA
		D3D11_BLEND_BLEND_FACTOR,     // GU_FIX_BLEND_COLOR
		D3D11_BLEND_INV_BLEND_FACTOR, // GU_FIX_BLEND_ONE_MINUS_COLOR
		D3D11_BLEND_ZERO,             // GU_FIX for 0x000000
		D3D11_BLEND_ONE               // GU_FIX for 0xFFFFFF
	};
	protected static final int[] blendDstToD3D = {
		D3D11_BLEND_SRC_COLOR,        // GU_SRC_COLOR
		D3D11_BLEND_INV_SRC_COLOR,    // GU_ONE_MINUS_SRC_COLOR
		D3D11_BLEND_SRC_ALPHA,        // GU_SRC_ALPHA
		D3D11_BLEND_INV_SRC_ALPHA,    // GU_ONE_MINUS_SRC_ALPHA
		D3D11_BLEND_DEST_ALPHA,       // GU_DST_ALPHA
		D3D11_BLEND_INV_DEST_ALPHA,   // GU_ONE_MINUS_DST_ALPHA
		D3D11_BLEND_SRC_ALPHA,        // GU_DOUBLE_SRC_ALPHA
		D3D11_BLEND_INV_SRC_ALPHA,    // GU_ONE_MINUS_DOUBLE_SRC_ALPHA
		D3D11_BLEND_DEST_ALPHA,       // GU_DOUBLE_DST_ALPHA
		D3D11_BLEND_INV_DEST_ALPHA,   // GU_ONE_MINUS_DOUBLE_DST_ALPHA
		D3D11_BLEND_BLEND_FACTOR,     // GU_FIX_BLEND_COLOR
		D3D11_BLEND_INV_BLEND_FACTOR, // GU_FIX_BLEND_ONE_MINUS_COLOR
		D3D11_BLEND_ZERO,             // GU_FIX_BLACK
		D3D11_BLEND_ONE               // GU_FIX_WHITE
	};
	protected static final int[] blendModeToD3D = {
		D3D11_BLEND_OP_ADD,          // ALPHA_SOURCE_BLEND_OPERATION_ADD
		D3D11_BLEND_OP_SUBTRACT,     // ALPHA_SOURCE_BLEND_OPERATION_SUBTRACT
		D3D11_BLEND_OP_REV_SUBTRACT, // ALPHA_SOURCE_BLEND_OPERATION_REVERSE_SUBTRACT
		D3D11_BLEND_OP_MIN,          // ALPHA_SOURCE_BLEND_OPERATION_MINIMUM_VALUE
		D3D11_BLEND_OP_MAX,          // ALPHA_SOURCE_BLEND_OPERATION_MAXIMUM_VALUE
		D3D11_BLEND_OP_ADD           // ALPHA_SOURCE_BLEND_OPERATION_ABSOLUTE_VALUE
	};
	protected static final int[] wrapModeToD3D = {
		D3D11_TEXTURE_ADDRESS_WRAP,  // TWRAP_WRAP_MODE_REPEAT
		D3D11_TEXTURE_ADDRESS_CLAMP  // TWRAP_WRAP_MODE_CLAMP
	};
	protected static final int[] textureFormatToD3D = {
		DXGI_FORMAT_B5G6R5_UNORM,   // TPSM_PIXEL_STORAGE_MODE_16BIT_BGR5650
		DXGI_FORMAT_B5G5R5A1_UNORM, // TPSM_PIXEL_STORAGE_MODE_16BIT_ABGR5551
		DXGI_FORMAT_B4G4R4A4_UNORM, // TPSM_PIXEL_STORAGE_MODE_16BIT_ABGR4444
		DXGI_FORMAT_R8G8B8A8_UNORM, // TPSM_PIXEL_STORAGE_MODE_32BIT_ABGR8888
		DXGI_FORMAT_R8_UINT,        // TPSM_PIXEL_STORAGE_MODE_4BIT_INDEXED
		DXGI_FORMAT_R8_UINT,        // TPSM_PIXEL_STORAGE_MODE_8BIT_INDEXED
		DXGI_FORMAT_R16_UINT,       // TPSM_PIXEL_STORAGE_MODE_16BIT_INDEXED
		DXGI_FORMAT_R32_UINT,       // TPSM_PIXEL_STORAGE_MODE_32BIT_INDEXED
		DXGI_FORMAT_BC1_UNORM,      // TPSM_PIXEL_STORAGE_MODE_DXT1
		DXGI_FORMAT_BC2_UNORM,      // TPSM_PIXEL_STORAGE_MODE_DXT3
		DXGI_FORMAT_BC3_UNORM,      // TPSM_PIXEL_STORAGE_MODE_DXT5
		DXGI_FORMAT_B5G6R5_UNORM,   // RE_PIXEL_STORAGE_16BIT_INDEXED_BGR5650
		DXGI_FORMAT_B5G5R5A1_UNORM, // RE_PIXEL_STORAGE_16BIT_INDEXED_ABGR5551
		DXGI_FORMAT_B4G4R4A4_UNORM, // RE_PIXEL_STORAGE_16BIT_INDEXED_ABGR4444
		DXGI_FORMAT_R8G8B8A8_UNORM, // RE_PIXEL_STORAGE_32BIT_INDEXED_ABGR8888
		DXGI_FORMAT_D16_UNORM,      // RE_DEPTH_COMPONENT
		DXGI_FORMAT_D24_UNORM_S8_UINT, // RE_STENCIL_INDEX
		DXGI_FORMAT_D24_UNORM_S8_UINT  // RE_DEPTH_STENCIL
	};
	protected static final int[] primitiveToD3D = {
		D3D11_PRIMITIVE_TOPOLOGY_POINTLIST,     // GU_POINTS / PRIM_POINT
		D3D11_PRIMITIVE_TOPOLOGY_LINELIST,      // GU_LINES / PRIM_LINE
		D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP,     // GU_LINE_STRIP / PRIM_LINES_STRIPS
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST,  // GU_TRIANGLES / PRIM_TRIANGLE
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP, // GU_TRIANGLE_STRIP / PRIM_TRIANGLE_STRIPS
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST,  // GU_TRIANGLE_FAN, expanded to a triangle list
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST,  // GU_SPRITES, expanded to a triangle list
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST,  // RE_QUADS, expanded to a triangle list
		D3D11_PRIMITIVE_TOPOLOGY_LINELIST_ADJ,      // RE_LINES_ADJACENCY
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST_ADJ,  // RE_TRIANGLES_ADJACENCY
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP_ADJ, // RE_TRIANGLE_STRIP_ADJACENCY
		D3D11_PRIMITIVE_TOPOLOGY_1_CONTROL_POINT_PATCHLIST, // RE_PATCHES
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP, // RE_SPLINE_TRIANGLES
		D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP,     // RE_SPLINE_LINES
		D3D11_PRIMITIVE_TOPOLOGY_POINTLIST,     // RE_SPLINE_POINTS
		D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP, // RE_BEZIER_TRIANGLES
		D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP,     // RE_BEZIER_LINES
		D3D11_PRIMITIVE_TOPOLOGY_POINTLIST      // RE_BEZIER_POINTS
	};
	protected static final int[] indexTypeToD3D = {
		DXGI_FORMAT_UNKNOWN,  // RE_BYTE
		DXGI_FORMAT_UNKNOWN,  // RE_UNSIGNED_BYTE, promoted to 16 bit
		DXGI_FORMAT_R16_UINT, // RE_SHORT
		DXGI_FORMAT_R16_UINT, // RE_UNSIGNED_SHORT
		DXGI_FORMAT_R32_UINT, // RE_INT
		DXGI_FORMAT_R32_UINT, // RE_UNSIGNED_INT
		DXGI_FORMAT_UNKNOWN,  // RE_FLOAT
		DXGI_FORMAT_UNKNOWN   // RE_DOUBLE
	};
	protected static final int[] shaderStageToD3D = {
		DX11_STAGE_VERTEX,   // RE_VERTEX_SHADER
		DX11_STAGE_PIXEL,    // RE_FRAGMENT_SHADER
		DX11_STAGE_GEOMETRY, // RE_GEOMETRY_SHADER
		DX11_STAGE_HULL,     // RE_TESS_CONTROL_SHADER
		DX11_STAGE_DOMAIN,   // RE_TESS_EVALUATION_SHADER
		DX11_STAGE_COMPUTE   // RE_COMPUTE_SHADER
	};
	protected static final int[] bufferBindFlagToD3D = {
		D3D11_BIND_VERTEX_BUFFER,   // RE_ARRAY_BUFFER
		D3D11_BIND_CONSTANT_BUFFER, // RE_UNIFORM_BUFFER
		D3D11_BIND_INDEX_BUFFER     // RE_ELEMENT_ARRAY_BUFFER
	};

	private static class TextureInfo {
		public int handle;
		public int width;
		public int height;
		public int format = -1;
		public int levels = 1;
		public int bindFlags = D3D11_BIND_SHADER_RESOURCE;
	}

	private static class BufferInfo {
		public int handle;
		public int size;
		public int bindFlags;
		/** A shadow copy of the index data, required to expand triangle fans and quads */
		public int[] indices;
	}

	private static class FramebufferInfo {
		public int colorTexture;
		public int depthStencilTexture;
	}

	private static class ShaderInfo {
		public int stage;
		public int handle;
		public String log = "";
		public Map<String, Integer> attributeLocations;
	}

	private static class VertexAttribute {
		public boolean enabled;
		public int size;
		public int type;
		public boolean normalized;
		public int stride;
		public int offset;
		public int buffer;
	}

	private final IDirectX11Wrapper wrapper;
	private final DirectX11StateCache states;

	private final Map<Integer, TextureInfo> textures = new HashMap<Integer, TextureInfo>();
	private final Map<Integer, BufferInfo> buffers = new HashMap<Integer, BufferInfo>();
	private final Map<Integer, FramebufferInfo> framebuffers = new HashMap<Integer, FramebufferInfo>();
	private final Map<Integer, ShaderInfo> shaders = new HashMap<Integer, ShaderInfo>();
	private final Map<Integer, String> programLogs = new HashMap<Integer, String>();
	private final Map<Integer, Map<String, Integer>> programAttributes = new HashMap<Integer, Map<String, Integer>>();
	private int nextObjectId = 1;

	private final VertexAttribute[] attributes = new VertexAttribute[MAX_ATTRIBUTES];
	private boolean inputLayoutDirty = true;

	private final int[] boundTextures = new int[MAX_TEXTURE_UNITS];
	private int activeTextureUnit;
	private int boundArrayBuffer;
	private int boundElementArrayBuffer;
	private int boundUniformBuffer;
	private int currentProgram;
	private int currentFramebuffer;

	private int textureMinFilter = 1;
	private int textureMagFilter = 1;
	private int textureMipmapMinLevel;
	private int textureMipmapMaxLevel = 1000;
	private float textureAnisotropy = 1f;

	private int dynamicVertexBuffer;
	private int dynamicVertexBufferSize;
	private int dynamicIndexBuffer;
	private int dynamicIndexBufferSize;
	private ByteBuffer expandedIndices;

	private int backBufferWidth;
	private int backBufferHeight;
	private int targetWidth;
	private int targetHeight;
	private int currentQuery;
	private long nextSync = 1L;
	private Buffer lastDynamicVertexBuffer;
	private int lastDynamicVertexBufferSize;

	/**
	 * Create the DirectX 11 rendering engine for the given native window.
	 *
	 * @param hwnd   the native window handle receiving the swap chain
	 * @param width  the initial back buffer width
	 * @param height the initial back buffer height
	 * @return the rendering engine, or null when Direct3D 11 cannot be used
	 */
	public static RenderingEngineDirectX11 newInstance(long hwnd, int width, int height) {
		IDirectX11Wrapper wrapper = DirectX11WrapperFactory.createWrapper();
		if (wrapper == null) {
			return null;
		}

		if (hwnd == 0L) {
			log.error("Cannot create the DirectX 11 rendering engine: no native window available");
			return null;
		}

		if (!wrapper.createDevice(hwnd, Math.max(width, 1), Math.max(height, 1), 0)) {
			log.error(String.format("Cannot create the Direct3D 11 device: %s", wrapper.getLastError()));
			return null;
		}

		log.info(String.format("Using RenderingEngineDirectX11 (%s), feature level %s, adapter '%s'", wrapper.getName(), DirectX11.getFeatureLevelName(wrapper.getFeatureLevel()), wrapper.getAdapterDescription()));

		return new RenderingEngineDirectX11(wrapper, width, height);
	}

	protected RenderingEngineDirectX11(IDirectX11Wrapper wrapper, int width, int height) {
		this.wrapper = wrapper;
		states = new DirectX11StateCache(wrapper);

		for (int i = 0; i < attributes.length; i++) {
			attributes[i] = new VertexAttribute();
		}

		backBufferWidth = width;
		backBufferHeight = height;
		targetWidth = width;
		targetHeight = height;
		states.setViewport(0f, 0f, width, height);
		states.setScissor(0, 0, width, height);
	}

	public IDirectX11Wrapper getWrapper() {
		return wrapper;
	}

	/**
	 * Present the back buffer. Called instead of the OpenGL buffer swapping when
	 * the DirectX 11 rendering engine is active.
	 */
	public void present(boolean verticalSync) {
		wrapper.present(verticalSync ? 1 : 0);
	}

	/**
	 * Resize the swap chain, e.g. when the display canvas has been resized.
	 */
	public void resize(int width, int height) {
		if (width > 0 && height > 0 && (width != backBufferWidth || height != backBufferHeight)) {
			backBufferWidth = width;
			backBufferHeight = height;
			if (currentFramebuffer == 0) {
				targetWidth = width;
				targetHeight = height;
			}
			wrapper.resize(width, height);
			states.reset();
		}
	}

	@Override
	public void exit() {
		wrapper.destroyDevice();
	}

	@Override
	public void reset() {
		states.reset();
		inputLayoutDirty = true;
	}

	//
	// Flags
	//

	@Override
	public void enableFlag(int flag) {
		setFlag(flag, true);
	}

	@Override
	public void disableFlag(int flag) {
		setFlag(flag, false);
	}

	private void setFlag(int flag, boolean enabled) {
		switch (flag) {
			case GU_DEPTH_TEST:
				states.setDepthTestEnabled(enabled);
				break;
			case GU_SCISSOR_TEST:
				states.setScissorTestEnabled(enabled);
				break;
			case GU_STENCIL_TEST:
				states.setStencilTestEnabled(enabled);
				break;
			case GU_BLEND:
				states.setBlendEnabled(enabled);
				break;
			case GU_CULL_FACE:
				states.setCullFaceEnabled(enabled);
				break;
			case GU_LINE_SMOOTH:
				states.setAntialiasedLine(enabled);
				break;
			case GU_CLIP_PLANES:
				// The PSP clipping planes are the near and far planes of the viewport
				states.setDepthClipEnabled(enabled);
				break;
			case GU_ALPHA_TEST:
			case GU_COLOR_TEST:
			case GU_FOG:
			case GU_LIGHTING:
			case GU_LIGHT0:
			case GU_LIGHT1:
			case GU_LIGHT2:
			case GU_LIGHT3:
			case GU_TEXTURE_2D:
			case GU_PATCH_CULL_FACE:
			case GU_FRAGMENT_2X:
			case RE_COLOR_MATERIAL:
			case RE_TEXTURE_GEN_S:
			case RE_TEXTURE_GEN_T:
			case GU_FACE_NORMAL_REVERSE:
			case GU_PATCH_FACE:
				// Implemented by the shaders, nothing to do at the Direct3D level
				break;
			case GU_DITHER:
				// Direct3D 11 has no dithering stage
				break;
			case GU_COLOR_LOGIC_OP:
				if (enabled && log.isDebugEnabled()) {
					log.debug("DirectX 11: the color logic operations are not supported");
				}
				break;
			default:
				if (log.isDebugEnabled()) {
					log.debug(String.format("DirectX 11: unsupported flag %d", flag));
				}
				break;
		}
	}

	//
	// Depth, stencil and blending
	//

	@Override
	public void setDepthFunc(int func) {
		states.setDepthFunc(depthFuncToD3D[func]);
	}

	@Override
	public void setDepthMask(boolean depthWriteEnabled) {
		states.setDepthWriteEnabled(depthWriteEnabled);
	}

	@Override
	public void setDepthRange(float zpos, float zscale, int near, int far) {
		states.setDepthRange((zpos - zscale) / 65535f, (zpos + zscale) / 65535f);
	}

	@Override
	public void setStencilFunc(int func, int ref, int mask) {
		states.setStencilFunc(stencilFuncToD3D[func], ref & 0xFF, mask & 0xFF);
	}

	@Override
	public void setStencilOp(int fail, int zfail, int zpass) {
		states.setStencilOp(stencilOpToD3D[fail], stencilOpToD3D[zfail], stencilOpToD3D[zpass]);
	}

	@Override
	public void setBlendFunc(int src, int dst) {
		int srcFactor = blendSrcToD3D[src];
		int dstFactor = blendDstToD3D[dst];
		states.setBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
	}

	@Override
	public void setBlendColor(float[] color) {
		states.setBlendFactor(color[0], color[1], color[2], color[3]);
	}

	@Override
	public void setBlendEquation(int mode) {
		states.setBlendEquation(blendModeToD3D[mode], blendModeToD3D[mode]);
	}

	@Override
	public void setBlendSFix(int sfix, float[] color) {
		states.setBlendFactor(color[0], color[1], color[2], color[3]);
	}

	@Override
	public void setBlendDFix(int dfix, float[] color) {
		states.setBlendFactor(color[0], color[1], color[2], color[3]);
	}

	@Override
	public void setColorMask(boolean redWriteEnabled, boolean greenWriteEnabled, boolean blueWriteEnabled, boolean alphaWriteEnabled) {
		int mask = 0;
		if (redWriteEnabled) {
			mask |= D3D11_COLOR_WRITE_ENABLE_RED;
		}
		if (greenWriteEnabled) {
			mask |= D3D11_COLOR_WRITE_ENABLE_GREEN;
		}
		if (blueWriteEnabled) {
			mask |= D3D11_COLOR_WRITE_ENABLE_BLUE;
		}
		if (alphaWriteEnabled) {
			mask |= D3D11_COLOR_WRITE_ENABLE_ALPHA;
		}
		states.setColorWriteMask(mask);
	}

	@Override
	public void setColorMask(int redMask, int greenMask, int blueMask, int alphaMask) {
		// A partial color mask can only be implemented by the fragment shader
		if (redMask != 0x00 || greenMask != 0x00 || blueMask != 0x00 || alphaMask != 0x00) {
			if (log.isDebugEnabled()) {
				log.debug(String.format("DirectX 11: partial color mask 0x%02X 0x%02X 0x%02X 0x%02X is implemented by the shader", redMask, greenMask, blueMask, alphaMask));
			}
		}
	}

	@Override
	public void setLogicOp(int logicOp) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("DirectX 11: the logic operation %d is not supported", logicOp));
		}
	}

	//
	// Rasterizer
	//

	@Override
	public void setViewport(int x, int y, int width, int height) {
		// OpenGL puts the viewport origin at the bottom left corner,
		// Direct3D at the top left corner.
		states.setViewport(x, getRenderTargetHeight() - y - height, width, height);
	}

	@Override
	public void setScissor(int x, int y, int width, int height) {
		if (width < 0 || height < 0) {
			states.setScissor(0, 0, getRenderTargetWidth(), getRenderTargetHeight());
		} else {
			int top = getRenderTargetHeight() - y - height;
			states.setScissor(x, top, x + width, top + height);
		}
	}

	@Override
	public void setFrontFace(boolean cw) {
		states.setFrontCounterClockwise(!cw);
	}

	@Override
	public void setPolygonMode(int mode) {
		states.setFillMode(mode == RE_POLYGON_MODE_FILL ? D3D11_FILL_SOLID : D3D11_FILL_WIREFRAME);
	}

	private int getRenderTargetWidth() {
		return targetWidth;
	}

	/**
	 * The height of the render target currently bound. It is needed to flip the
	 * vertical axis: OpenGL puts the origin at the bottom left corner of the
	 * render target, Direct3D at the top left one.
	 */
	private int getRenderTargetHeight() {
		return targetHeight;
	}

	//
	// Textures
	//

	@Override
	public int genTexture() {
		int id = nextObjectId++;
		textures.put(Integer.valueOf(id), new TextureInfo());

		return id;
	}

	@Override
	public void deleteTexture(int texture) {
		TextureInfo info = textures.remove(Integer.valueOf(texture));
		if (info != null && info.handle != DX11_INVALID_HANDLE) {
			wrapper.deleteTexture(info.handle);
		}
	}

	@Override
	public void bindTexture(int texture) {
		bindActiveTexture(activeTextureUnit, texture);
	}

	@Override
	public void setActiveTexture(int index) {
		activeTextureUnit = index;
	}

	@Override
	public void bindActiveTexture(int index, int texture) {
		if (index < 0 || index >= MAX_TEXTURE_UNITS) {
			return;
		}

		boundTextures[index] = texture;
		TextureInfo info = textures.get(Integer.valueOf(texture));
		int handle = info == null ? DX11_INVALID_HANDLE : info.handle;
		wrapper.bindTexture(DX11_STAGE_PIXEL, index, handle);
	}

	private TextureInfo getBoundTexture() {
		return textures.get(Integer.valueOf(boundTextures[activeTextureUnit]));
	}

	/**
	 * Create the native texture if it does not exist yet, or re-create it when
	 * its dimensions or its format have changed. Direct3D 11 textures are
	 * immutable, unlike the OpenGL ones which can be redefined by glTexImage2D.
	 */
	private TextureInfo prepareTexture(int internalFormat, int width, int height, int levels) {
		TextureInfo info = getBoundTexture();
		if (info == null) {
			return null;
		}

		int format = textureFormatToD3D[internalFormat];
		if (info.handle != DX11_INVALID_HANDLE && info.width == width && info.height == height && info.format == format && info.levels >= levels) {
			return info;
		}

		if (info.handle != DX11_INVALID_HANDLE) {
			wrapper.deleteTexture(info.handle);
		}

		int bindFlags = D3D11_BIND_SHADER_RESOURCE;
		if (internalFormat == RE_DEPTH_COMPONENT || internalFormat == RE_STENCIL_INDEX || internalFormat == RE_DEPTH_STENCIL) {
			bindFlags = D3D11_BIND_DEPTH_STENCIL;
		}

		info.width = width;
		info.height = height;
		info.format = format;
		// Direct3D 11 textures are immutable: allocate the complete mipmap chain
		// right away, otherwise uploading a mipmap level would have to recreate
		// the texture and lose the levels already uploaded.
		info.levels = Math.max(Math.max(levels, getMipmapLevelCount(width, height)), 1);
		info.bindFlags = bindFlags;
		info.handle = wrapper.createTexture(width, height, format, info.levels, bindFlags);

		if (info.handle == DX11_INVALID_HANDLE) {
			log.error(String.format("DirectX 11: cannot create a %dx%d texture: %s", width, height, wrapper.getLastError()));
		} else {
			// The shader resource view has changed, rebind it
			wrapper.bindTexture(DX11_STAGE_PIXEL, activeTextureUnit, info.handle);
		}

		return info;
	}

	private static int getMipmapLevelCount(int width, int height) {
		int levels = 1;
		for (int size = Math.max(width, height); size > 1; size >>= 1) {
			levels++;
		}

		return levels;
	}

	@Override
	public void setTexImage(int level, int internalFormat, int width, int height, int format, int type, int textureSize, Buffer buffer) {
		TextureInfo info;
		if (level == 0) {
			info = prepareTexture(internalFormat, width, height, 1);
		} else {
			// A mipmap level of an already allocated texture
			info = getBoundTexture();
		}
		if (info == null || info.handle == DX11_INVALID_HANDLE) {
			return;
		}

		if (buffer != null) {
			int size = sizeInBytes(buffer, textureSize);
			wrapper.updateTexture(info.handle, level, 0, 0, width, height, getRowPitch(internalFormat, width), size, buffer);
		}
	}

	@Override
	public void setTexImagexBRZ(int level, int internalFormat, int width, int height, int bufwidth, int format, int type, int textureSize, Buffer buffer) {
		// The xBRZ upscaling filter is implemented by a native OpenGL helper,
		// fall back to the unfiltered texture upload.
		setTexImage(level, internalFormat, width, height, format, type, textureSize, buffer);
	}

	@Override
	public void setTexSubImage(int level, int xOffset, int yOffset, int width, int height, int format, int type, int textureSize, Buffer buffer) {
		TextureInfo info = getBoundTexture();
		if (info == null || info.handle == DX11_INVALID_HANDLE || buffer == null) {
			return;
		}

		int size = sizeInBytes(buffer, textureSize);
		wrapper.updateTexture(info.handle, level, xOffset, yOffset, width, height, getRowPitch(format, width), size, buffer);
	}

	@Override
	public void setCompressedTexImage(int level, int internalFormat, int width, int height, int compressedSize, Buffer buffer) {
		TextureInfo info = level == 0 ? prepareTexture(internalFormat, width, height, 1) : getBoundTexture();
		if (info == null || info.handle == DX11_INVALID_HANDLE || buffer == null) {
			return;
		}

		wrapper.updateCompressedTexture(info.handle, level, width, height, sizeInBytes(buffer, compressedSize), buffer);
	}

	@Override
	public void getTexImage(int level, int format, int type, Buffer buffer) {
		TextureInfo info = getBoundTexture();
		if (info == null || info.handle == DX11_INVALID_HANDLE || buffer == null) {
			return;
		}

		wrapper.readTexture(info.handle, level, sizeInBytes(buffer, buffer.remaining()), buffer);
	}

	@Override
	public void copyTexSubImage(int level, int xOffset, int yOffset, int x, int y, int width, int height) {
		TextureInfo info = getBoundTexture();
		if (info == null || info.handle == DX11_INVALID_HANDLE) {
			return;
		}

		wrapper.copyRenderTargetToTexture(info.handle, level, xOffset, yOffset, x, getRenderTargetHeight() - y - height, width, height);
	}

	@Override
	public int getTextureLevelParameter(int texture, int level, int parameter) {
		TextureInfo info = textures.get(Integer.valueOf(texture));
		if (info == null || info.handle == DX11_INVALID_HANDLE) {
			return 0;
		}

		switch (parameter) {
			case RE_TEXTURE_WIDTH:  return Math.max(info.width >> level, 1);
			case RE_TEXTURE_HEIGHT: return Math.max(info.height >> level, 1);
			case RE_TEXTURE_DEPTH:  return 1;
		}

		return wrapper.getTextureLevelParameter(info.handle, level, parameter);
	}

	@Override
	public void setTextureWrapMode(int s, int t) {
		states.setSamplerAddress(activeTextureUnit, wrapModeToD3D[s], wrapModeToD3D[t]);
	}

	@Override
	public void setTextureMipmapMinFilter(int filter) {
		textureMinFilter = filter;
		updateSamplerFilter();
	}

	@Override
	public void setTextureMipmapMagFilter(int filter) {
		textureMagFilter = filter;
		updateSamplerFilter();
	}

	@Override
	public void setTextureMipmapMinLevel(int level) {
		textureMipmapMinLevel = level;
		states.setSamplerLodRange(activeTextureUnit, textureMipmapMinLevel, textureMipmapMaxLevel);
	}

	@Override
	public void setTextureMipmapMaxLevel(int level) {
		textureMipmapMaxLevel = level;
		states.setSamplerLodRange(activeTextureUnit, textureMipmapMinLevel, textureMipmapMaxLevel);
	}

	@Override
	public void setTextureAnisotropy(float value) {
		textureAnisotropy = value;
		states.setSamplerAnisotropy(activeTextureUnit, Math.max((int) value, 1));
		updateSamplerFilter();
	}

	@Override
	public float getMaxTextureAnisotropy() {
		// The maximum anisotropy is fixed by the Direct3D 11 specification
		return 16f;
	}

	/**
	 * Direct3D 11 encodes the minification, magnification and mipmap filters
	 * into a single D3D11_FILTER value: bits 4-5 for the minification, bits 2-3
	 * for the magnification and bits 0-1 for the mipmap filter.
	 */
	private void updateSamplerFilter() {
		if (textureAnisotropy > 1f) {
			states.setSamplerFilter(activeTextureUnit, D3D11_FILTER_ANISOTROPIC);
			return;
		}

		int minLinear;
		int mipLinear;
		switch (textureMinFilter) {
			case 0: minLinear = 0; mipLinear = 0; break; // TFLT_NEAREST
			case 1: minLinear = 1; mipLinear = 0; break; // TFLT_LINEAR
			case 4: minLinear = 0; mipLinear = 0; break; // TFLT_NEAREST_MIPMAP_NEAREST
			case 5: minLinear = 1; mipLinear = 0; break; // TFLT_LINEAR_MIPMAP_NEAREST
			case 6: minLinear = 0; mipLinear = 1; break; // TFLT_NEAREST_MIPMAP_LINEAR
			case 7: minLinear = 1; mipLinear = 1; break; // TFLT_LINEAR_MIPMAP_LINEAR
			default: minLinear = 0; mipLinear = 0; break;
		}
		int magLinear = textureMagFilter == 1 ? 1 : 0;

		states.setSamplerFilter(activeTextureUnit, (minLinear << 4) | (magLinear << 2) | mipLinear);
	}

	private static int getRowPitch(int pixelFormat, int width) {
		if (pixelFormat < 0 || pixelFormat >= sizeOfTextureType.length) {
			return width * 4;
		}

		int bytesPerPixel = sizeOfTextureType[pixelFormat];
		if (bytesPerPixel == 0) {
			// A 4 bit indexed or a compressed format, the wrapper computes the pitch
			return 0;
		}

		return width * bytesPerPixel;
	}

	//
	// Buffers
	//

	@Override
	public int genBuffer() {
		int id = nextObjectId++;
		buffers.put(Integer.valueOf(id), new BufferInfo());

		return id;
	}

	@Override
	public void deleteBuffer(int buffer) {
		BufferInfo info = buffers.remove(Integer.valueOf(buffer));
		if (info != null && info.handle != DX11_INVALID_HANDLE) {
			wrapper.deleteBuffer(info.handle);
		}
	}

	@Override
	public void bindBuffer(int target, int buffer) {
		switch (target) {
			case RE_ARRAY_BUFFER:
				boundArrayBuffer = buffer;
				inputLayoutDirty = true;
				break;
			case RE_ELEMENT_ARRAY_BUFFER:
				boundElementArrayBuffer = buffer;
				break;
			case RE_UNIFORM_BUFFER:
				boundUniformBuffer = buffer;
				break;
		}
	}

	private int getBoundBuffer(int target) {
		switch (target) {
			case RE_ARRAY_BUFFER:         return boundArrayBuffer;
			case RE_ELEMENT_ARRAY_BUFFER: return boundElementArrayBuffer;
			case RE_UNIFORM_BUFFER:       return boundUniformBuffer;
		}

		return 0;
	}

	@Override
	public void setBufferData(int target, int size, Buffer buffer, int usage) {
		BufferInfo info = buffers.get(Integer.valueOf(getBoundBuffer(target)));
		if (info == null) {
			return;
		}

		int bindFlags = bufferBindFlagToD3D[target];
		if (info.handle == DX11_INVALID_HANDLE || info.size < size || info.bindFlags != bindFlags) {
			if (info.handle != DX11_INVALID_HANDLE) {
				wrapper.deleteBuffer(info.handle);
			}
			info.size = size;
			info.bindFlags = bindFlags;
			info.handle = wrapper.createBuffer(bindFlags, D3D11_USAGE_DYNAMIC, size, buffer);
		} else if (buffer != null) {
			wrapper.updateBuffer(info.handle, 0, size, buffer);
		}

		if (target == RE_ELEMENT_ARRAY_BUFFER) {
			info.indices = readIndices(buffer, size);
		}
	}

	@Override
	public void setBufferSubData(int target, int offset, int size, Buffer buffer) {
		BufferInfo info = buffers.get(Integer.valueOf(getBoundBuffer(target)));
		if (info == null || info.handle == DX11_INVALID_HANDLE) {
			return;
		}

		wrapper.updateBuffer(info.handle, offset, size, buffer);

		if (target == RE_ELEMENT_ARRAY_BUFFER && offset == 0) {
			info.indices = readIndices(buffer, size);
		}
	}

	@Override
	public void bindBufferBase(int target, int bindingPoint, int buffer) {
		BufferInfo info = buffers.get(Integer.valueOf(buffer));
		int handle = info == null ? DX11_INVALID_HANDLE : info.handle;
		wrapper.bindConstantBuffer(DX11_STAGE_VERTEX, bindingPoint, handle);
		wrapper.bindConstantBuffer(DX11_STAGE_PIXEL, bindingPoint, handle);
	}

	/**
	 * Keep a copy of the index data on the Java side: expanding a triangle fan
	 * or a quad into a triangle list needs to read back the indices, which is
	 * not possible from a Direct3D 11 buffer without a staging copy.
	 */
	private static int[] readIndices(Buffer buffer, int size) {
		if (buffer == null) {
			return null;
		}

		if (buffer instanceof ShortBuffer) {
			ShortBuffer shortBuffer = (ShortBuffer) buffer;
			int count = Math.min(size >> 1, shortBuffer.remaining());
			int[] indices = new int[count];
			for (int i = 0; i < count; i++) {
				indices[i] = shortBuffer.get(shortBuffer.position() + i) & 0xFFFF;
			}
			return indices;
		}

		if (buffer instanceof IntBuffer) {
			IntBuffer intBuffer = (IntBuffer) buffer;
			int count = Math.min(size >> 2, intBuffer.remaining());
			int[] indices = new int[count];
			for (int i = 0; i < count; i++) {
				indices[i] = intBuffer.get(intBuffer.position() + i);
			}
			return indices;
		}

		if (buffer instanceof ByteBuffer) {
			ByteBuffer byteBuffer = (ByteBuffer) buffer;
			int count = Math.min(size, byteBuffer.remaining());
			int[] indices = new int[count];
			for (int i = 0; i < count; i++) {
				indices[i] = byteBuffer.get(byteBuffer.position() + i) & 0xFF;
			}
			return indices;
		}

		return null;
	}

	private static int sizeInBytes(Buffer buffer, int size) {
		if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) {
			return size << 2;
		}
		if (buffer instanceof ShortBuffer) {
			return size << 1;
		}
		if (buffer instanceof DoubleBuffer) {
			return size << 3;
		}

		return size;
	}

	//
	// Vertex attributes
	//

	@Override
	public void enableVertexAttribArray(int id) {
		if (id >= 0 && id < MAX_ATTRIBUTES && !attributes[id].enabled) {
			attributes[id].enabled = true;
			inputLayoutDirty = true;
		}
	}

	@Override
	public void disableVertexAttribArray(int id) {
		if (id >= 0 && id < MAX_ATTRIBUTES && attributes[id].enabled) {
			attributes[id].enabled = false;
			inputLayoutDirty = true;
		}
	}

	@Override
	public void setVertexAttribPointer(int id, int size, int type, boolean normalized, int stride, long offset) {
		if (id < 0 || id >= MAX_ATTRIBUTES) {
			return;
		}

		VertexAttribute attribute = attributes[id];
		attribute.size = size;
		attribute.type = type;
		attribute.normalized = normalized;
		attribute.stride = stride;
		attribute.offset = (int) offset;
		attribute.buffer = boundArrayBuffer;
		inputLayoutDirty = true;
	}

	@Override
	public void setVertexAttribPointer(int id, int size, int type, boolean normalized, int stride, int bufferSize, Buffer buffer) {
		// A client side vertex array: upload it into the scratch dynamic buffer
		int offset = uploadDynamicVertices(buffer, sizeInBytes(buffer, bufferSize));
		int previousBuffer = boundArrayBuffer;
		boundArrayBuffer = 0;
		setVertexAttribPointer(id, size, type, normalized, stride, offset);
		boundArrayBuffer = previousBuffer;
		attributes[id].buffer = -1;
	}

	private int uploadDynamicVertices(Buffer buffer, int size) {
		if (buffer == null || size <= 0) {
			return 0;
		}

		if (buffer == lastDynamicVertexBuffer && size == lastDynamicVertexBufferSize) {
			// REShader passes the same interleaved array once per vertex
			// attribute, upload it only once
			return 0;
		}
		lastDynamicVertexBuffer = buffer;
		lastDynamicVertexBufferSize = size;

		if (dynamicVertexBuffer == DX11_INVALID_HANDLE || dynamicVertexBufferSize < size) {
			if (dynamicVertexBuffer != DX11_INVALID_HANDLE) {
				wrapper.deleteBuffer(dynamicVertexBuffer);
			}
			dynamicVertexBufferSize = Math.max(size, 256 * 1024);
			dynamicVertexBuffer = wrapper.createBuffer(D3D11_BIND_VERTEX_BUFFER, D3D11_USAGE_DYNAMIC, dynamicVertexBufferSize, null);
		}

		wrapper.updateBuffer(dynamicVertexBuffer, 0, size, buffer);

		return 0;
	}

	/**
	 * Build the Direct3D 11 input layout from the enabled vertex attributes and
	 * bind the vertex buffer they read from.
	 */
	private void updateInputLayout() {
		if (!inputLayoutDirty) {
			return;
		}

		int stride = 0;
		int vertexBuffer = DX11_INVALID_HANDLE;
		boolean usingDynamicBuffer = false;

		wrapper.beginInputLayout();
		for (int id = 0; id < MAX_ATTRIBUTES; id++) {
			VertexAttribute attribute = attributes[id];
			if (!attribute.enabled) {
				continue;
			}

			wrapper.addInputElement(id, attribute.size, attribute.type, attribute.normalized, attribute.stride, attribute.offset);
			stride = Math.max(stride, attribute.stride);

			if (attribute.buffer == -1) {
				usingDynamicBuffer = true;
			} else {
				BufferInfo info = buffers.get(Integer.valueOf(attribute.buffer));
				if (info != null && info.handle != DX11_INVALID_HANDLE) {
					vertexBuffer = info.handle;
				}
			}
		}
		wrapper.endInputLayout(currentProgram);

		if (usingDynamicBuffer) {
			vertexBuffer = dynamicVertexBuffer;
		}
		wrapper.bindVertexBuffer(0, vertexBuffer, stride, 0);

		inputLayoutDirty = false;
	}

	@Override
	public void setVertexInfo(VertexInfo vinfo, boolean allNativeVertexInfo, boolean useVertexColor, boolean useTexture, boolean useNormal, int type) {
		// The vertex layout is entirely described by the vertex attributes
	}

	//
	// Shaders and programs
	//

	@Override
	public boolean isShaderAvailable() {
		return true;
	}

	@Override
	public String getShadingLanguageVersion() {
		return "HLSL Shader Model 5.0";
	}

	@Override
	public int createShader(int type) {
		int id = nextObjectId++;
		ShaderInfo info = new ShaderInfo();
		info.stage = type;
		shaders.put(Integer.valueOf(id), info);

		return id;
	}

	@Override
	public boolean compilerShader(int shader, String source) {
		ShaderInfo info = shaders.get(Integer.valueOf(shader));
		if (info == null) {
			return false;
		}

		DirectX11ShaderTranslator translator = new DirectX11ShaderTranslator(info.stage);
		DirectX11ShaderTranslator.TranslatedShader translated = translator.translate(source);
		if (translated.hasWarnings()) {
			log.warn(String.format("DirectX 11 shader translation:%n%s", translated.getWarningsLog()));
		}

		if (log.isTraceEnabled()) {
			log.trace(String.format("Translated HLSL shader:%n%s", translated.source));
		}

		info.attributeLocations = translated.attributeLocations;
		info.handle = wrapper.compileShader(shaderStageToD3D[info.stage], translated.source, DirectX11ShaderTranslator.entryPoint);
		info.log = translated.getWarningsLog() + nullToEmpty(wrapper.getShaderLog(info.handle));

		return info.handle != DX11_INVALID_HANDLE;
	}

	@Override
	public String getShaderInfoLog(int shader) {
		ShaderInfo info = shaders.get(Integer.valueOf(shader));

		return info == null ? "" : info.log;
	}

	@Override
	public int createProgram() {
		return wrapper.createProgram();
	}

	@Override
	public void attachShader(int program, int shader) {
		ShaderInfo info = shaders.get(Integer.valueOf(shader));
		if (info == null || info.handle == DX11_INVALID_HANDLE) {
			return;
		}

		wrapper.attachShader(program, info.handle);

		if (info.stage == RE_VERTEX_SHADER && info.attributeLocations != null) {
			// The vertex shader inputs define the attribute locations of the program
			programAttributes.put(Integer.valueOf(program), info.attributeLocations);
		}
	}

	@Override
	public boolean linkProgram(int program) {
		boolean linked = wrapper.linkProgram(program);
		programLogs.put(Integer.valueOf(program), nullToEmpty(wrapper.getProgramLog(program)));

		return linked;
	}

	@Override
	public boolean validateProgram(int program) {
		return true;
	}

	@Override
	public String getProgramInfoLog(int program) {
		String programLog = programLogs.get(Integer.valueOf(program));

		return programLog == null ? "" : programLog;
	}

	@Override
	public void useProgram(int program) {
		if (currentProgram != program) {
			currentProgram = program;
			inputLayoutDirty = true;
			wrapper.useProgram(program);
		}
	}

	@Override
	public int getUniformLocation(int program, String name) {
		return wrapper.getUniformLocation(program, name);
	}

	@Override
	public int getAttribLocation(int program, String name) {
		// The attribute locations are the TEXCOORD semantic indices assigned by
		// the shader translator, they are known without asking Direct3D
		Map<String, Integer> attributeLocations = programAttributes.get(Integer.valueOf(program));
		if (attributeLocations == null) {
			return -1;
		}

		Integer location = attributeLocations.get(name);

		return location == null ? -1 : location.intValue();
	}

	@Override
	public void bindAttribLocation(int program, int index, String name) {
		// The attribute locations are fixed by the TEXCOORD semantics generated
		// by the shader translator, they cannot be reassigned afterwards.
	}

	@Override
	public boolean isExtensionAvailable(String name) {
		// The OpenGL extensions have no Direct3D 11 equivalent. Returning false
		// keeps the shader generation on the plain uniform path.
		return false;
	}

	@Override
	public void setProgramParameter(int program, int parameter, int value) {
		// The geometry shader input/output types are part of the HLSL source
	}

	@Override
	public int getUniformBlockIndex(int program, String name) {
		return -1;
	}

	@Override
	public void setUniformBlockBinding(int program, int blockIndex, int bindingPoint) {
	}

	@Override
	public int getUniformIndex(int program, String name) {
		return wrapper.getUniformLocation(program, name);
	}

	@Override
	public int[] getUniformIndices(int program, String[] names) {
		int[] indices = new int[names.length];
		for (int i = 0; i < names.length; i++) {
			indices[i] = wrapper.getUniformLocation(program, names[i]);
		}

		return indices;
	}

	//
	// Uniforms
	//

	@Override
	public void setUniform(int id, int value) {
		wrapper.setUniformInt(currentProgram, id, 1, new int[] { value });
	}

	@Override
	public void setUniform(int id, int value1, int value2) {
		wrapper.setUniformInt(currentProgram, id, 2, new int[] { value1, value2 });
	}

	@Override
	public void setUniform(int id, float value) {
		wrapper.setUniformFloat(currentProgram, id, 1, new float[] { value });
	}

	@Override
	public void setUniform1v(int id, float[] values) {
		wrapper.setUniformFloat(currentProgram, id, values.length, values);
	}

	@Override
	public void setUniform2(int id, int[] values) {
		wrapper.setUniformInt(currentProgram, id, 2, values);
	}

	@Override
	public void setUniform3(int id, int[] values) {
		wrapper.setUniformInt(currentProgram, id, 3, values);
	}

	@Override
	public void setUniform3(int id, float[] values) {
		wrapper.setUniformFloat(currentProgram, id, 3, values);
	}

	@Override
	public void setUniform3v(int id, float[] values) {
		wrapper.setUniformFloat(currentProgram, id, values.length, values);
	}

	@Override
	public void setUniform4(int id, int[] values) {
		wrapper.setUniformInt(currentProgram, id, 4, values);
	}

	@Override
	public void setUniform4(int id, float[] values) {
		wrapper.setUniformFloat(currentProgram, id, 4, values);
	}

	@Override
	public void setUniformMatrix3(int id, int count, float[] values) {
		wrapper.setUniformMatrix(currentProgram, id, 3, count, values);
	}

	@Override
	public void setUniformMatrix4(int id, int count, float[] values) {
		wrapper.setUniformMatrix(currentProgram, id, 4, count, values);
	}

	//
	// Framebuffer objects
	//

	@Override
	public boolean isFramebufferObjectAvailable() {
		return true;
	}

	@Override
	public int genFramebuffer() {
		int id = nextObjectId++;
		framebuffers.put(Integer.valueOf(id), new FramebufferInfo());

		return id;
	}

	@Override
	public void deleteFramebuffer(int framebuffer) {
		framebuffers.remove(Integer.valueOf(framebuffer));
	}

	@Override
	public int genRenderbuffer() {
		// A render buffer is a texture which is never sampled
		return genTexture();
	}

	@Override
	public void deleteRenderbuffer(int renderbuffer) {
		deleteTexture(renderbuffer);
	}

	@Override
	public void bindRenderbuffer(int renderbuffer) {
		bindTexture(renderbuffer);
	}

	@Override
	public void setRenderbufferStorage(int internalFormat, int width, int height) {
		prepareTexture(internalFormat, width, height, 1);
	}

	@Override
	public void bindFramebuffer(int target, int framebuffer) {
		currentFramebuffer = framebuffer;

		FramebufferInfo info = framebuffers.get(Integer.valueOf(framebuffer));
		if (info == null) {
			// Bind the back buffer
			targetWidth = backBufferWidth;
			targetHeight = backBufferHeight;
			wrapper.bindRenderTargets(DX11_INVALID_HANDLE, DX11_INVALID_HANDLE);
			return;
		}

		TextureInfo colorTexture = textures.get(Integer.valueOf(info.colorTexture));
		if (colorTexture != null && colorTexture.width > 0) {
			targetWidth = colorTexture.width;
			targetHeight = colorTexture.height;
		}

		wrapper.bindRenderTargets(getTextureHandle(info.colorTexture), getTextureHandle(info.depthStencilTexture));
	}

	@Override
	public int getFramebufferBinding(int target) {
		return currentFramebuffer;
	}

	@Override
	public void setFramebufferTexture(int target, int attachment, int texture, int level) {
		FramebufferInfo info = framebuffers.get(Integer.valueOf(currentFramebuffer));
		if (info == null) {
			return;
		}

		if (attachment == RE_DEPTH_ATTACHMENT || attachment == RE_STENCIL_ATTACHMENT || attachment == RE_DEPTH_STENCIL_ATTACHMENT) {
			info.depthStencilTexture = texture;
		} else {
			info.colorTexture = texture;
		}

		bindFramebuffer(target, currentFramebuffer);
	}

	@Override
	public void setFramebufferRenderbuffer(int target, int attachment, int renderbuffer) {
		setFramebufferTexture(target, attachment, renderbuffer, 0);
	}

	private int getTextureHandle(int texture) {
		TextureInfo info = textures.get(Integer.valueOf(texture));

		return info == null ? DX11_INVALID_HANDLE : info.handle;
	}

	@Override
	public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
		wrapper.blit(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter != 0);
	}

	@Override
	public void readStencil(int x, int y, int width, int height, int bufferSize, Buffer buffer) {
		wrapper.readPixels(x, getRenderTargetHeight() - y - height, width, height, DXGI_FORMAT_D24_UNORM_S8_UINT, sizeInBytes(buffer, bufferSize), buffer);
	}

	@Override
	public void readDepth(int x, int y, int width, int height, int bufferSize, Buffer buffer) {
		wrapper.readPixels(x, getRenderTargetHeight() - y - height, width, height, DXGI_FORMAT_D16_UNORM, sizeInBytes(buffer, bufferSize), buffer);
	}

	//
	// Clearing
	//

	@Override
	public void clear(float red, float green, float blue, float alpha) {
		states.apply();
		wrapper.clear(DX11_CLEAR_COLOR, red, green, blue, alpha, 1f, 0);
	}

	//
	// Drawing
	//

	@Override
	public void startDisplay() {
		states.reset();
		inputLayoutDirty = true;
	}

	@Override
	public void endDisplay() {
		wrapper.flush();
	}

	@Override
	public void drawArrays(int primitive, int first, int count) {
		if (count <= 0) {
			return;
		}

		states.apply();
		updateInputLayout();

		int[] expanded = expandPrimitive(primitive, first, count);
		if (expanded == null) {
			wrapper.draw(primitiveToD3D[primitive], first, count);
		} else {
			drawExpandedIndices(expanded, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
		}
	}

	@Override
	public void drawArraysBurstMode(int primitive, int first, int count) {
		drawArrays(primitive, first, count);
	}

	@Override
	public void multiDrawArrays(int primitive, IntBuffer first, IntBuffer count) {
		if (first == null || count == null) {
			return;
		}

		int n = Math.min(first.remaining(), count.remaining());
		for (int i = 0; i < n; i++) {
			drawArrays(primitive, first.get(first.position() + i), count.get(count.position() + i));
		}
	}

	@Override
	public void drawElements(int primitive, int count, int indexType, Buffer indices, int indicesOffset) {
		if (count <= 0) {
			return;
		}

		int[] values = readIndices(indices, sizeInBytes(indices, count + indicesOffset));
		if (values == null) {
			return;
		}
		if (indicesOffset > 0) {
			if (indicesOffset >= values.length) {
				return;
			}
			int[] offsetValues = new int[values.length - indicesOffset];
			System.arraycopy(values, indicesOffset, offsetValues, 0, offsetValues.length);
			values = offsetValues;
		}

		states.apply();
		updateInputLayout();

		int[] expanded = expandIndices(primitive, values, count);
		if (expanded == null) {
			drawExpandedIndices(values, primitiveToD3D[primitive]);
		} else {
			drawExpandedIndices(expanded, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
		}
	}

	@Override
	public void drawElements(int primitive, int count, int indexType, long indicesOffset) {
		if (count <= 0) {
			return;
		}

		states.apply();
		updateInputLayout();

		if (needsExpansion(primitive)) {
			BufferInfo info = buffers.get(Integer.valueOf(boundElementArrayBuffer));
			if (info == null || info.indices == null) {
				log.warn(String.format("DirectX 11: cannot expand the primitive %d, the index data is not available", primitive));
				return;
			}

			int offset = (int) (indicesOffset / Math.max(sizeOfType[indexType], 1));
			int[] values = new int[count];
			System.arraycopy(info.indices, offset, values, 0, Math.min(count, info.indices.length - offset));
			drawExpandedIndices(expandIndices(primitive, values, count), D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
			return;
		}

		BufferInfo info = buffers.get(Integer.valueOf(boundElementArrayBuffer));
		if (info == null || info.handle == DX11_INVALID_HANDLE) {
			return;
		}

		wrapper.bindIndexBuffer(info.handle, indexTypeToD3D[indexType], 0);
		wrapper.drawIndexed(primitiveToD3D[primitive], count, indexTypeToD3D[indexType], (int) indicesOffset);
	}

	@Override
	public void drawElementsBurstMode(int primitive, int count, int indexType, long indicesOffset) {
		drawElements(primitive, count, indexType, indicesOffset);
	}

	@Override
	public void multiDrawElements(int primitive, IntBuffer first, IntBuffer count, int indexType, long indicesOffset) {
		if (first == null || count == null) {
			return;
		}

		int n = Math.min(first.remaining(), count.remaining());
		int indexSize = sizeOfType[indexType];
		for (int i = 0; i < n; i++) {
			int firstIndex = first.get(first.position() + i);
			drawElements(primitive, count.get(count.position() + i), indexType, indicesOffset + ((long) firstIndex) * indexSize);
		}
	}

	/**
	 * Direct3D 11 has neither triangle fans nor quads: those primitives are
	 * expanded into a triangle list.
	 */
	private static boolean needsExpansion(int primitive) {
		return primitive == GU_TRIANGLE_FAN || primitive == GU_SPRITES || primitive == RE_QUADS;
	}

	/**
	 * @return the triangle list indices replacing an unsupported primitive, or
	 *         null when the primitive is supported natively
	 */
	private static int[] expandPrimitive(int primitive, int first, int count) {
		if (primitive == GU_TRIANGLE_FAN) {
			if (count < 3) {
				return new int[0];
			}
			int[] indices = new int[(count - 2) * 3];
			int n = 0;
			for (int i = 1; i < count - 1; i++) {
				indices[n++] = first;
				indices[n++] = first + i;
				indices[n++] = first + i + 1;
			}
			return indices;
		}

		if (primitive == GU_SPRITES || primitive == RE_QUADS) {
			int quads = count / 4;
			int[] indices = new int[quads * 6];
			int n = 0;
			for (int i = 0; i < quads; i++) {
				int base = first + i * 4;
				indices[n++] = base;
				indices[n++] = base + 1;
				indices[n++] = base + 2;
				indices[n++] = base;
				indices[n++] = base + 2;
				indices[n++] = base + 3;
			}
			return indices;
		}

		return null;
	}

	/**
	 * The same expansion as {@link #expandPrimitive}, but starting from an
	 * existing index array instead of from consecutive vertices.
	 */
	private static int[] expandIndices(int primitive, int[] values, int count) {
		count = Math.min(count, values.length);

		if (primitive == GU_TRIANGLE_FAN) {
			if (count < 3) {
				return new int[0];
			}
			int[] indices = new int[(count - 2) * 3];
			int n = 0;
			for (int i = 1; i < count - 1; i++) {
				indices[n++] = values[0];
				indices[n++] = values[i];
				indices[n++] = values[i + 1];
			}
			return indices;
		}

		if (primitive == GU_SPRITES || primitive == RE_QUADS) {
			int quads = count / 4;
			int[] indices = new int[quads * 6];
			int n = 0;
			for (int i = 0; i < quads; i++) {
				int base = i * 4;
				indices[n++] = values[base];
				indices[n++] = values[base + 1];
				indices[n++] = values[base + 2];
				indices[n++] = values[base];
				indices[n++] = values[base + 2];
				indices[n++] = values[base + 3];
			}
			return indices;
		}

		return null;
	}

	private void drawExpandedIndices(int[] indices, int topology) {
		if (indices == null || indices.length == 0) {
			return;
		}

		int size = indices.length << 2;
		if (expandedIndices == null || expandedIndices.capacity() < size) {
			expandedIndices = ByteBuffer.allocateDirect(Math.max(size, 64 * 1024)).order(ByteOrder.nativeOrder());
		}
		expandedIndices.clear();
		IntBuffer intBuffer = expandedIndices.asIntBuffer();
		intBuffer.put(indices);
		expandedIndices.limit(size);

		if (dynamicIndexBuffer == DX11_INVALID_HANDLE || dynamicIndexBufferSize < size) {
			if (dynamicIndexBuffer != DX11_INVALID_HANDLE) {
				wrapper.deleteBuffer(dynamicIndexBuffer);
			}
			dynamicIndexBufferSize = Math.max(size, 64 * 1024);
			dynamicIndexBuffer = wrapper.createBuffer(D3D11_BIND_INDEX_BUFFER, D3D11_USAGE_DYNAMIC, dynamicIndexBufferSize, null);
		}

		wrapper.updateBuffer(dynamicIndexBuffer, 0, size, expandedIndices);
		wrapper.bindIndexBuffer(dynamicIndexBuffer, DXGI_FORMAT_R32_UINT, 0);
		wrapper.drawIndexed(topology, indices.length, DXGI_FORMAT_R32_UINT, 0);
	}

	//
	// Occlusion queries
	//

	@Override
	public boolean isQueryAvailable() {
		return true;
	}

	@Override
	public int genQuery() {
		return wrapper.createQuery();
	}

	@Override
	public void beginQuery(int id) {
		currentQuery = id;
		wrapper.beginQuery(id);
	}

	@Override
	public void endQuery() {
		wrapper.endQuery(currentQuery);
	}

	@Override
	public boolean getQueryResultAvailable(int id) {
		return wrapper.isQueryResultAvailable(id);
	}

	@Override
	public int getQueryResult(int id) {
		return wrapper.getQueryResult(id);
	}

	//
	// Synchronization
	//

	@Override
	public void waitForRenderingCompletion() {
		wrapper.finish();
	}

	@Override
	public long fenceSync() {
		wrapper.flush();

		return nextSync++;
	}

	@Override
	public void clientWaitSync(long sync, long timeout) {
		wrapper.finish();
	}

	@Override
	public void deleteSync(long sync) {
	}

	//
	// Capabilities
	//

	@Override
	public boolean isVertexArrayAvailable() {
		// Direct3D 11 has no vertex array objects, the input layout plays that role
		return false;
	}

	@Override
	public boolean isTextureBarrierAvailable() {
		return false;
	}

	@Override
	public boolean canNativeClut(int textureAddress, int pixelFormat, boolean textureSwizzle) {
		// The CLUT is read by the fragment shader from an integer texture
		return !textureSwizzle;
	}

	@Override
	public boolean checkAndLogErrors(String logComment) {
		String error = wrapper.getLastError();
		if (error == null || error.length() == 0) {
			return false;
		}

		if (logComment != null) {
			log.error(String.format("DirectX 11 error in %s: %s", logComment, error));
		} else {
			log.error(String.format("DirectX 11 error: %s", error));
		}

		return true;
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	@Override
	public String toString() {
		return String.format("RenderingEngineDirectX11[%s, %s]", wrapper.getName(), DirectX11.getFeatureLevelName(wrapper.getFeatureLevel()));
	}
}
