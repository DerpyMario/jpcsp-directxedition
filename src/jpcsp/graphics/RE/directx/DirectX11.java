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

/**
 * @author gid15
 *
 * The Direct3D 11 and DXGI enumeration values used by the DirectX 11 wrapper.
 *
 * The values are the ones defined by the Windows SDK headers (d3d11.h, dxgiformat.h,
 * d3dcommon.h). They are duplicated here so that the Java side can build the
 * descriptors passed to the native wrapper without having to call back into it.
 */
public class DirectX11 {
	// D3D_FEATURE_LEVEL
	public static final int D3D_FEATURE_LEVEL_9_1  = 0x9100;
	public static final int D3D_FEATURE_LEVEL_9_2  = 0x9200;
	public static final int D3D_FEATURE_LEVEL_9_3  = 0x9300;
	public static final int D3D_FEATURE_LEVEL_10_0 = 0xA000;
	public static final int D3D_FEATURE_LEVEL_10_1 = 0xA100;
	public static final int D3D_FEATURE_LEVEL_11_0 = 0xB000;
	public static final int D3D_FEATURE_LEVEL_11_1 = 0xB100;

	// D3D11_PRIMITIVE_TOPOLOGY
	public static final int D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED     = 0;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_POINTLIST     = 1;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_LINELIST      = 2;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP     = 3;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST  = 4;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP = 5;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_LINELIST_ADJ      = 10;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP_ADJ     = 11;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST_ADJ  = 12;
	public static final int D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP_ADJ = 13;
	// Patch topologies are D3D11_PRIMITIVE_TOPOLOGY_1_CONTROL_POINT_PATCHLIST + (n - 1)
	public static final int D3D11_PRIMITIVE_TOPOLOGY_1_CONTROL_POINT_PATCHLIST = 33;

	// D3D11_COMPARISON_FUNC
	public static final int D3D11_COMPARISON_NEVER         = 1;
	public static final int D3D11_COMPARISON_LESS          = 2;
	public static final int D3D11_COMPARISON_EQUAL         = 3;
	public static final int D3D11_COMPARISON_LESS_EQUAL    = 4;
	public static final int D3D11_COMPARISON_GREATER       = 5;
	public static final int D3D11_COMPARISON_NOT_EQUAL     = 6;
	public static final int D3D11_COMPARISON_GREATER_EQUAL = 7;
	public static final int D3D11_COMPARISON_ALWAYS        = 8;

	// D3D11_STENCIL_OP
	public static final int D3D11_STENCIL_OP_KEEP     = 1;
	public static final int D3D11_STENCIL_OP_ZERO     = 2;
	public static final int D3D11_STENCIL_OP_REPLACE  = 3;
	public static final int D3D11_STENCIL_OP_INCR_SAT = 4;
	public static final int D3D11_STENCIL_OP_DECR_SAT = 5;
	public static final int D3D11_STENCIL_OP_INVERT   = 6;
	public static final int D3D11_STENCIL_OP_INCR     = 7;
	public static final int D3D11_STENCIL_OP_DECR     = 8;

	// D3D11_BLEND
	public static final int D3D11_BLEND_ZERO             = 1;
	public static final int D3D11_BLEND_ONE              = 2;
	public static final int D3D11_BLEND_SRC_COLOR        = 3;
	public static final int D3D11_BLEND_INV_SRC_COLOR    = 4;
	public static final int D3D11_BLEND_SRC_ALPHA        = 5;
	public static final int D3D11_BLEND_INV_SRC_ALPHA    = 6;
	public static final int D3D11_BLEND_DEST_ALPHA       = 7;
	public static final int D3D11_BLEND_INV_DEST_ALPHA   = 8;
	public static final int D3D11_BLEND_DEST_COLOR       = 9;
	public static final int D3D11_BLEND_INV_DEST_COLOR   = 10;
	public static final int D3D11_BLEND_SRC_ALPHA_SAT    = 11;
	public static final int D3D11_BLEND_BLEND_FACTOR     = 14;
	public static final int D3D11_BLEND_INV_BLEND_FACTOR = 15;

	// D3D11_BLEND_OP
	public static final int D3D11_BLEND_OP_ADD          = 1;
	public static final int D3D11_BLEND_OP_SUBTRACT     = 2;
	public static final int D3D11_BLEND_OP_REV_SUBTRACT = 3;
	public static final int D3D11_BLEND_OP_MIN          = 4;
	public static final int D3D11_BLEND_OP_MAX          = 5;

	// D3D11_COLOR_WRITE_ENABLE
	public static final int D3D11_COLOR_WRITE_ENABLE_RED   = 1;
	public static final int D3D11_COLOR_WRITE_ENABLE_GREEN = 2;
	public static final int D3D11_COLOR_WRITE_ENABLE_BLUE  = 4;
	public static final int D3D11_COLOR_WRITE_ENABLE_ALPHA = 8;
	public static final int D3D11_COLOR_WRITE_ENABLE_ALL   = 15;

	// D3D11_CULL_MODE
	public static final int D3D11_CULL_NONE  = 1;
	public static final int D3D11_CULL_FRONT = 2;
	public static final int D3D11_CULL_BACK  = 3;

	// D3D11_FILL_MODE
	public static final int D3D11_FILL_WIREFRAME = 2;
	public static final int D3D11_FILL_SOLID     = 3;

	// D3D11_TEXTURE_ADDRESS_MODE
	public static final int D3D11_TEXTURE_ADDRESS_WRAP        = 1;
	public static final int D3D11_TEXTURE_ADDRESS_MIRROR      = 2;
	public static final int D3D11_TEXTURE_ADDRESS_CLAMP       = 3;
	public static final int D3D11_TEXTURE_ADDRESS_BORDER      = 4;
	public static final int D3D11_TEXTURE_ADDRESS_MIRROR_ONCE = 5;

	// D3D11_FILTER
	public static final int D3D11_FILTER_MIN_MAG_MIP_POINT               = 0x00;
	public static final int D3D11_FILTER_MIN_MAG_POINT_MIP_LINEAR        = 0x01;
	public static final int D3D11_FILTER_MIN_POINT_MAG_LINEAR_MIP_POINT  = 0x04;
	public static final int D3D11_FILTER_MIN_POINT_MAG_MIP_LINEAR        = 0x05;
	public static final int D3D11_FILTER_MIN_LINEAR_MAG_MIP_POINT        = 0x10;
	public static final int D3D11_FILTER_MIN_LINEAR_MAG_POINT_MIP_LINEAR = 0x11;
	public static final int D3D11_FILTER_MIN_MAG_LINEAR_MIP_POINT        = 0x14;
	public static final int D3D11_FILTER_MIN_MAG_MIP_LINEAR              = 0x15;
	public static final int D3D11_FILTER_ANISOTROPIC                     = 0x55;

	// D3D11_BIND_FLAG
	public static final int D3D11_BIND_VERTEX_BUFFER   = 0x0001;
	public static final int D3D11_BIND_INDEX_BUFFER    = 0x0002;
	public static final int D3D11_BIND_CONSTANT_BUFFER = 0x0004;
	public static final int D3D11_BIND_SHADER_RESOURCE = 0x0008;
	public static final int D3D11_BIND_RENDER_TARGET   = 0x0020;
	public static final int D3D11_BIND_DEPTH_STENCIL   = 0x0040;

	// D3D11_USAGE
	public static final int D3D11_USAGE_DEFAULT   = 0;
	public static final int D3D11_USAGE_IMMUTABLE = 1;
	public static final int D3D11_USAGE_DYNAMIC   = 2;
	public static final int D3D11_USAGE_STAGING   = 3;

	// D3D11_CLEAR_FLAG (extended with a color bit for the wrapper)
	public static final int DX11_CLEAR_COLOR   = 0x4;
	public static final int D3D11_CLEAR_DEPTH  = 0x1;
	public static final int D3D11_CLEAR_STENCIL = 0x2;

	// DXGI_FORMAT
	public static final int DXGI_FORMAT_UNKNOWN              = 0;
	public static final int DXGI_FORMAT_R32G32B32A32_FLOAT   = 2;
	public static final int DXGI_FORMAT_R32G32B32A32_UINT    = 3;
	public static final int DXGI_FORMAT_R32G32B32A32_SINT    = 4;
	public static final int DXGI_FORMAT_R32G32B32_FLOAT      = 6;
	public static final int DXGI_FORMAT_R32G32B32_UINT       = 7;
	public static final int DXGI_FORMAT_R32G32B32_SINT       = 8;
	public static final int DXGI_FORMAT_R16G16B16A16_FLOAT   = 10;
	public static final int DXGI_FORMAT_R16G16B16A16_UNORM   = 11;
	public static final int DXGI_FORMAT_R16G16B16A16_UINT    = 12;
	public static final int DXGI_FORMAT_R16G16B16A16_SNORM   = 13;
	public static final int DXGI_FORMAT_R16G16B16A16_SINT    = 14;
	public static final int DXGI_FORMAT_R32G32_FLOAT         = 16;
	public static final int DXGI_FORMAT_R32G32_UINT          = 17;
	public static final int DXGI_FORMAT_R32G32_SINT          = 18;
	public static final int DXGI_FORMAT_D32_FLOAT_S8X24_UINT = 20;
	public static final int DXGI_FORMAT_R10G10B10A2_UNORM    = 24;
	public static final int DXGI_FORMAT_R8G8B8A8_TYPELESS    = 27;
	public static final int DXGI_FORMAT_R8G8B8A8_UNORM       = 28;
	public static final int DXGI_FORMAT_R8G8B8A8_UNORM_SRGB  = 29;
	public static final int DXGI_FORMAT_R8G8B8A8_UINT        = 30;
	public static final int DXGI_FORMAT_R8G8B8A8_SNORM       = 31;
	public static final int DXGI_FORMAT_R8G8B8A8_SINT        = 32;
	public static final int DXGI_FORMAT_R16G16_FLOAT         = 34;
	public static final int DXGI_FORMAT_R16G16_UNORM         = 35;
	public static final int DXGI_FORMAT_R16G16_UINT          = 36;
	public static final int DXGI_FORMAT_R16G16_SNORM         = 37;
	public static final int DXGI_FORMAT_R16G16_SINT          = 38;
	public static final int DXGI_FORMAT_D32_FLOAT            = 40;
	public static final int DXGI_FORMAT_R32_FLOAT            = 41;
	public static final int DXGI_FORMAT_R32_UINT             = 42;
	public static final int DXGI_FORMAT_R32_SINT             = 43;
	public static final int DXGI_FORMAT_D24_UNORM_S8_UINT    = 45;
	public static final int DXGI_FORMAT_R8G8_UNORM           = 49;
	public static final int DXGI_FORMAT_R8G8_UINT            = 50;
	public static final int DXGI_FORMAT_R16_FLOAT            = 54;
	public static final int DXGI_FORMAT_D16_UNORM            = 55;
	public static final int DXGI_FORMAT_R16_UNORM            = 56;
	public static final int DXGI_FORMAT_R16_UINT             = 57;
	public static final int DXGI_FORMAT_R16_SNORM            = 58;
	public static final int DXGI_FORMAT_R16_SINT             = 59;
	public static final int DXGI_FORMAT_R8_UNORM             = 61;
	public static final int DXGI_FORMAT_R8_UINT              = 62;
	public static final int DXGI_FORMAT_R8_SNORM             = 63;
	public static final int DXGI_FORMAT_R8_SINT              = 64;
	public static final int DXGI_FORMAT_BC1_UNORM            = 71;
	public static final int DXGI_FORMAT_BC2_UNORM            = 74;
	public static final int DXGI_FORMAT_BC3_UNORM            = 77;
	public static final int DXGI_FORMAT_B5G6R5_UNORM         = 85;
	public static final int DXGI_FORMAT_B5G5R5A1_UNORM       = 86;
	public static final int DXGI_FORMAT_B8G8R8A8_UNORM       = 87;
	public static final int DXGI_FORMAT_B4G4R4A4_UNORM       = 115;

	// Shader stages, as used by the wrapper to select a pipeline stage
	public static final int DX11_STAGE_VERTEX   = 0;
	public static final int DX11_STAGE_PIXEL    = 1;
	public static final int DX11_STAGE_GEOMETRY = 2;
	public static final int DX11_STAGE_HULL     = 3;
	public static final int DX11_STAGE_DOMAIN   = 4;
	public static final int DX11_STAGE_COMPUTE  = 5;

	// Device creation flags understood by the wrapper
	public static final int DX11_DEVICE_FLAG_DEBUG      = 0x1;
	public static final int DX11_DEVICE_FLAG_SINGLE_THREADED = 0x2;

	// Invalid resource handle returned by the wrapper on error
	public static final int DX11_INVALID_HANDLE = 0;

	private DirectX11() {
	}

	public static String getFeatureLevelName(int featureLevel) {
		switch (featureLevel) {
			case D3D_FEATURE_LEVEL_9_1:  return "9_1";
			case D3D_FEATURE_LEVEL_9_2:  return "9_2";
			case D3D_FEATURE_LEVEL_9_3:  return "9_3";
			case D3D_FEATURE_LEVEL_10_0: return "10_0";
			case D3D_FEATURE_LEVEL_10_1: return "10_1";
			case D3D_FEATURE_LEVEL_11_0: return "11_0";
			case D3D_FEATURE_LEVEL_11_1: return "11_1";
		}

		return String.format("0x%04X", featureLevel);
	}
}
