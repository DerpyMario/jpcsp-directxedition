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

/**
 * @author gid15
 *
 * The impedance matching between the OpenGL like state of the jpcsp rendering
 * pipeline and the immutable state objects of Direct3D 11.
 *
 * OpenGL lets each state bit be changed independently (glEnable(GL_BLEND),
 * glBlendFunc, glColorMask...) while Direct3D 11 groups them into four
 * immutable objects: blend state, depth/stencil state, rasterizer state and
 * sampler state. This class accumulates the individual changes and only pushes
 * a complete descriptor to the wrapper when a draw call actually needs it, so a
 * long sequence of state changes still results in a single state object switch.
 *
 * The wrapper is responsible for caching the created state objects, this class
 * only avoids the redundant calls.
 */
public class DirectX11StateCache {
	public static final int MAX_SAMPLERS = 4;

	private final IDirectX11Wrapper wrapper;

	// Blend state
	private boolean blendEnabled;
	private int blendSrcRGB = D3D11_BLEND_ONE;
	private int blendDstRGB = D3D11_BLEND_ZERO;
	private int blendOpRGB = D3D11_BLEND_OP_ADD;
	private int blendSrcAlpha = D3D11_BLEND_ONE;
	private int blendDstAlpha = D3D11_BLEND_ZERO;
	private int blendOpAlpha = D3D11_BLEND_OP_ADD;
	private int colorWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
	private final float[] blendFactor = new float[] { 1f, 1f, 1f, 1f };
	private boolean blendDirty = true;

	// Depth/stencil state
	private boolean depthTestEnabled;
	private boolean depthWriteEnabled = true;
	private int depthFunc = D3D11_COMPARISON_LESS;
	private boolean stencilTestEnabled;
	private int stencilReadMask = 0xFF;
	private int stencilWriteMask = 0xFF;
	private int stencilFunc = D3D11_COMPARISON_ALWAYS;
	private int stencilRef;
	private int stencilFailOp = D3D11_STENCIL_OP_KEEP;
	private int stencilDepthFailOp = D3D11_STENCIL_OP_KEEP;
	private int stencilPassOp = D3D11_STENCIL_OP_KEEP;
	private boolean depthStencilDirty = true;

	// Rasterizer state
	private boolean cullFaceEnabled;
	private int cullMode = D3D11_CULL_NONE;
	private boolean frontCounterClockwise = true;
	private int fillMode = D3D11_FILL_SOLID;
	private boolean scissorTestEnabled;
	private boolean depthClipEnabled = true;
	private boolean antialiasedLine;
	private boolean rasterizerDirty = true;

	// Sampler states, one per texture unit
	private final int[] samplerFilter = new int[MAX_SAMPLERS];
	private final int[] samplerAddressU = new int[MAX_SAMPLERS];
	private final int[] samplerAddressV = new int[MAX_SAMPLERS];
	private final float[] samplerMinLod = new float[MAX_SAMPLERS];
	private final float[] samplerMaxLod = new float[MAX_SAMPLERS];
	private final float[] samplerLodBias = new float[MAX_SAMPLERS];
	private final int[] samplerAnisotropy = new int[MAX_SAMPLERS];
	private final boolean[] samplerDirty = new boolean[MAX_SAMPLERS];

	// Viewport and scissor
	private float viewportX, viewportY, viewportWidth, viewportHeight;
	private float viewportMinDepth = 0f;
	private float viewportMaxDepth = 1f;
	private boolean viewportDirty = true;
	private int scissorLeft, scissorTop, scissorRight, scissorBottom;
	private boolean scissorDirty = true;

	public DirectX11StateCache(IDirectX11Wrapper wrapper) {
		this.wrapper = wrapper;

		for (int i = 0; i < MAX_SAMPLERS; i++) {
			samplerFilter[i] = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
			samplerAddressU[i] = D3D11_TEXTURE_ADDRESS_WRAP;
			samplerAddressV[i] = D3D11_TEXTURE_ADDRESS_WRAP;
			samplerMinLod[i] = 0f;
			samplerMaxLod[i] = Float.MAX_VALUE;
			samplerLodBias[i] = 0f;
			samplerAnisotropy[i] = 1;
			samplerDirty[i] = true;
		}
	}

	/**
	 * Mark every state as dirty, e.g. after the device has been recreated.
	 */
	public void reset() {
		blendDirty = true;
		depthStencilDirty = true;
		rasterizerDirty = true;
		viewportDirty = true;
		scissorDirty = true;
		for (int i = 0; i < MAX_SAMPLERS; i++) {
			samplerDirty[i] = true;
		}
	}

	//
	// Blend state
	//

	public void setBlendEnabled(boolean enabled) {
		if (blendEnabled != enabled) {
			blendEnabled = enabled;
			blendDirty = true;
		}
	}

	public void setBlendFunc(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
		if (blendSrcRGB != srcRGB || blendDstRGB != dstRGB || blendSrcAlpha != srcAlpha || blendDstAlpha != dstAlpha) {
			blendSrcRGB = srcRGB;
			blendDstRGB = dstRGB;
			blendSrcAlpha = srcAlpha;
			blendDstAlpha = dstAlpha;
			blendDirty = true;
		}
	}

	public void setBlendEquation(int opRGB, int opAlpha) {
		if (blendOpRGB != opRGB || blendOpAlpha != opAlpha) {
			blendOpRGB = opRGB;
			blendOpAlpha = opAlpha;
			blendDirty = true;
		}
	}

	public void setBlendFactor(float red, float green, float blue, float alpha) {
		if (blendFactor[0] != red || blendFactor[1] != green || blendFactor[2] != blue || blendFactor[3] != alpha) {
			blendFactor[0] = red;
			blendFactor[1] = green;
			blendFactor[2] = blue;
			blendFactor[3] = alpha;
			blendDirty = true;
		}
	}

	public void setColorWriteMask(int mask) {
		if (colorWriteMask != mask) {
			colorWriteMask = mask;
			blendDirty = true;
		}
	}

	//
	// Depth and stencil state
	//

	public void setDepthTestEnabled(boolean enabled) {
		if (depthTestEnabled != enabled) {
			depthTestEnabled = enabled;
			depthStencilDirty = true;
		}
	}

	public void setDepthWriteEnabled(boolean enabled) {
		if (depthWriteEnabled != enabled) {
			depthWriteEnabled = enabled;
			depthStencilDirty = true;
		}
	}

	public void setDepthFunc(int func) {
		if (depthFunc != func) {
			depthFunc = func;
			depthStencilDirty = true;
		}
	}

	public void setStencilTestEnabled(boolean enabled) {
		if (stencilTestEnabled != enabled) {
			stencilTestEnabled = enabled;
			depthStencilDirty = true;
		}
	}

	public void setStencilFunc(int func, int ref, int readMask) {
		if (stencilFunc != func || stencilRef != ref || stencilReadMask != readMask) {
			stencilFunc = func;
			stencilRef = ref;
			stencilReadMask = readMask;
			depthStencilDirty = true;
		}
	}

	public void setStencilWriteMask(int mask) {
		if (stencilWriteMask != mask) {
			stencilWriteMask = mask;
			depthStencilDirty = true;
		}
	}

	public void setStencilOp(int failOp, int depthFailOp, int passOp) {
		if (stencilFailOp != failOp || stencilDepthFailOp != depthFailOp || stencilPassOp != passOp) {
			stencilFailOp = failOp;
			stencilDepthFailOp = depthFailOp;
			stencilPassOp = passOp;
			depthStencilDirty = true;
		}
	}

	//
	// Rasterizer state
	//

	public void setCullFaceEnabled(boolean enabled) {
		if (cullFaceEnabled != enabled) {
			cullFaceEnabled = enabled;
			rasterizerDirty = true;
		}
	}

	public void setCullMode(int mode) {
		if (cullMode != mode) {
			cullMode = mode;
			rasterizerDirty = true;
		}
	}

	public void setFrontCounterClockwise(boolean counterClockwise) {
		if (frontCounterClockwise != counterClockwise) {
			frontCounterClockwise = counterClockwise;
			rasterizerDirty = true;
		}
	}

	public void setFillMode(int mode) {
		if (fillMode != mode) {
			fillMode = mode;
			rasterizerDirty = true;
		}
	}

	public void setScissorTestEnabled(boolean enabled) {
		if (scissorTestEnabled != enabled) {
			scissorTestEnabled = enabled;
			rasterizerDirty = true;
		}
	}

	public void setDepthClipEnabled(boolean enabled) {
		if (depthClipEnabled != enabled) {
			depthClipEnabled = enabled;
			rasterizerDirty = true;
		}
	}

	public void setAntialiasedLine(boolean enabled) {
		if (antialiasedLine != enabled) {
			antialiasedLine = enabled;
			rasterizerDirty = true;
		}
	}

	//
	// Sampler state
	//

	public void setSamplerFilter(int slot, int filter) {
		if (slot >= 0 && slot < MAX_SAMPLERS && samplerFilter[slot] != filter) {
			samplerFilter[slot] = filter;
			samplerDirty[slot] = true;
		}
	}

	public int getSamplerFilter(int slot) {
		return slot >= 0 && slot < MAX_SAMPLERS ? samplerFilter[slot] : D3D11_FILTER_MIN_MAG_MIP_LINEAR;
	}

	public void setSamplerAddress(int slot, int addressU, int addressV) {
		if (slot >= 0 && slot < MAX_SAMPLERS && (samplerAddressU[slot] != addressU || samplerAddressV[slot] != addressV)) {
			samplerAddressU[slot] = addressU;
			samplerAddressV[slot] = addressV;
			samplerDirty[slot] = true;
		}
	}

	public void setSamplerLodRange(int slot, float minLod, float maxLod) {
		if (slot >= 0 && slot < MAX_SAMPLERS && (samplerMinLod[slot] != minLod || samplerMaxLod[slot] != maxLod)) {
			samplerMinLod[slot] = minLod;
			samplerMaxLod[slot] = maxLod;
			samplerDirty[slot] = true;
		}
	}

	public void setSamplerLodBias(int slot, float bias) {
		if (slot >= 0 && slot < MAX_SAMPLERS && samplerLodBias[slot] != bias) {
			samplerLodBias[slot] = bias;
			samplerDirty[slot] = true;
		}
	}

	public void setSamplerAnisotropy(int slot, int maxAnisotropy) {
		if (slot >= 0 && slot < MAX_SAMPLERS && samplerAnisotropy[slot] != maxAnisotropy) {
			samplerAnisotropy[slot] = maxAnisotropy;
			samplerDirty[slot] = true;
		}
	}

	//
	// Viewport and scissor
	//

	public void setViewport(float x, float y, float width, float height) {
		if (viewportX != x || viewportY != y || viewportWidth != width || viewportHeight != height) {
			viewportX = x;
			viewportY = y;
			viewportWidth = width;
			viewportHeight = height;
			viewportDirty = true;
		}
	}

	public void setDepthRange(float minDepth, float maxDepth) {
		if (viewportMinDepth != minDepth || viewportMaxDepth != maxDepth) {
			viewportMinDepth = minDepth;
			viewportMaxDepth = maxDepth;
			viewportDirty = true;
		}
	}

	/**
	 * Direct3D 11 scissor rectangles are given as two corners, not as an origin
	 * and a size like the OpenGL ones.
	 */
	public void setScissor(int left, int top, int right, int bottom) {
		if (scissorLeft != left || scissorTop != top || scissorRight != right || scissorBottom != bottom) {
			scissorLeft = left;
			scissorTop = top;
			scissorRight = right;
			scissorBottom = bottom;
			scissorDirty = true;
		}
	}

	/**
	 * Push all the pending state changes to the wrapper. Called right before
	 * each draw call and before each clear.
	 */
	public void apply() {
		if (blendDirty) {
			wrapper.setBlendState(blendEnabled, blendSrcRGB, blendDstRGB, blendOpRGB, blendSrcAlpha, blendDstAlpha, blendOpAlpha, colorWriteMask, blendFactor);
			blendDirty = false;
		}

		if (depthStencilDirty) {
			wrapper.setDepthStencilState(depthTestEnabled, depthWriteEnabled, depthFunc, stencilTestEnabled, stencilReadMask, stencilWriteMask, stencilFunc, stencilRef, stencilFailOp, stencilDepthFailOp, stencilPassOp);
			depthStencilDirty = false;
		}

		if (rasterizerDirty) {
			wrapper.setRasterizerState(cullFaceEnabled ? cullMode : D3D11_CULL_NONE, frontCounterClockwise, fillMode, scissorTestEnabled, depthClipEnabled, antialiasedLine);
			rasterizerDirty = false;
		}

		for (int i = 0; i < MAX_SAMPLERS; i++) {
			if (samplerDirty[i]) {
				wrapper.setSamplerState(i, samplerFilter[i], samplerAddressU[i], samplerAddressV[i], samplerMinLod[i], samplerMaxLod[i], samplerLodBias[i], samplerAnisotropy[i]);
				samplerDirty[i] = false;
			}
		}

		if (viewportDirty) {
			wrapper.setViewport(viewportX, viewportY, viewportWidth, viewportHeight, viewportMinDepth, viewportMaxDepth);
			viewportDirty = false;
		}

		if (scissorDirty) {
			wrapper.setScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);
			scissorDirty = false;
		}
	}
}
