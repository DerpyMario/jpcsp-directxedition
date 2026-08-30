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
 * The Direct3D 11 implementation of the jpcsp wrapper API.
 *
 * See jpcsp_directx11.h for the contract. The Java side never sees a COM
 * interface: everything is addressed by an int handle allocated here.
 */

#include "jpcsp_directx11.h"

#include <d3d11.h>
#include <d3dcompiler.h>
#include <dxgi.h>

#include <map>
#include <string>
#include <vector>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "d3dcompiler.lib")
#pragma comment(lib, "dxgi.lib")

namespace {

template<class T> void release(T *&object) {
	if (object != NULL) {
		object->Release();
		object = NULL;
	}
}

struct BufferResource {
	ID3D11Buffer *buffer;
	UINT size;
	UINT bindFlags;
	D3D11_USAGE usage;
};

struct TextureResource {
	ID3D11Texture2D *texture;
	ID3D11ShaderResourceView *shaderResourceView;
	ID3D11RenderTargetView *renderTargetView;
	ID3D11DepthStencilView *depthStencilView;
	DXGI_FORMAT format;
	UINT width;
	UINT height;
	UINT levels;
	UINT bindFlags;
};

struct ShaderResource {
	int stage;
	ID3DBlob *bytecode;
	ID3D11DeviceChild *shader;
	std::string log;
};

/*
 * Where one uniform lives inside the constant buffer of one shader stage.
 *
 * The shader translator emits a "cbuffer JpcspUniforms : register(b0)" per
 * shader, so the very same uniform can sit at a different offset in the vertex
 * and in the pixel shader. A uniform is therefore described by one entry per
 * stage declaring it, and writing it updates all of them.
 */
struct UniformStageInfo {
	int stage;
	UINT offset;        /* byte offset inside the constant buffer of that stage */
	UINT elementCount;  /* 1 for a scalar, the array length otherwise */
	UINT elementStride; /* bytes between two array elements */
	UINT componentsPerElement;
};

struct UniformInfo {
	std::vector<UniformStageInfo> stages;
};

struct ProgramResource {
	ShaderResource *stages[6];
	std::vector<unsigned char> constantData[6];
	ID3D11Buffer *constantBuffer[6];
	bool constantDataDirty[6];
	std::map<std::string, int> uniformIndices;
	std::vector<UniformInfo> uniforms;
	ID3D11InputLayout *inputLayout;
	std::string inputLayoutKey;
	std::string log;
};

struct QueryResource {
	ID3D11Query *query;
	UINT64 result;
	bool resultRead;
};

/*
 * The private pipeline used to rescale a blit. Direct3D 11 has no equivalent of
 * glBlitFramebuffer(), a scaling copy has to be drawn.
 */
struct BlitPipeline {
	ID3D11VertexShader *vertexShader;
	ID3D11PixelShader *pixelShader;
	ID3D11Buffer *constantBuffer;
	ID3D11SamplerState *pointSampler;
	ID3D11SamplerState *linearSampler;
	ID3D11BlendState *blendState;
	ID3D11DepthStencilState *depthStencilState;
	ID3D11RasterizerState *rasterizerState;
	bool initialized;
	bool failed;
};

struct InputElement {
	int location;
	int size;
	int type;
	int normalized;
	int stride;
	int offset;
};

struct Context {
	ID3D11Device *device;
	ID3D11DeviceContext *deviceContext;
	IDXGISwapChain *swapChain;
	ID3D11Texture2D *backBufferDepth;
	ID3D11RenderTargetView *backBufferRenderTargetView;
	ID3D11DepthStencilView *backBufferDepthStencilView;
	D3D_FEATURE_LEVEL featureLevel;
	std::string adapterDescription;
	std::string lastError;
	UINT backBufferWidth;
	UINT backBufferHeight;

	std::map<int, BufferResource *> buffers;
	std::map<int, TextureResource *> textures;
	std::map<int, ShaderResource *> shaders;
	std::map<int, ProgramResource *> programs;
	std::map<int, QueryResource *> queries;
	int nextHandle;

	/* The immutable state objects, cached by their descriptor */
	std::map<std::string, ID3D11BlendState *> blendStates;
	std::map<std::string, ID3D11DepthStencilState *> depthStencilStates;
	std::map<std::string, ID3D11RasterizerState *> rasterizerStates;
	std::map<std::string, ID3D11SamplerState *> samplerStates;
	std::map<std::string, ID3D11InputLayout *> inputLayouts;

	ProgramResource *currentProgram;
	BlitPipeline blit;
	std::vector<InputElement> pendingInputElements;
	D3D11_PRIMITIVE_TOPOLOGY currentTopology;
	ID3D11RenderTargetView *currentRenderTargetView;
	ID3D11DepthStencilView *currentDepthStencilView;
	int currentQuery;
};

Context *ctx = NULL;

void setError(const char *message) {
	if (ctx != NULL) {
		ctx->lastError = message;
	}
}

void setError(const char *message, HRESULT hr) {
	if (ctx != NULL) {
		char text[512];
		_snprintf_s(text, sizeof(text), _TRUNCATE, "%s (HRESULT 0x%08lX)", message, (unsigned long) hr);
		ctx->lastError = text;
	}
}

bool hasDevice() {
	return ctx != NULL && ctx->device != NULL;
}

int allocateHandle() {
	return ctx->nextHandle++;
}

template<class T> T *find(std::map<int, T *> &resources, int handle) {
	if (handle == 0) {
		return NULL;
	}
	typename std::map<int, T *>::iterator it = resources.find(handle);

	return it == resources.end() ? NULL : it->second;
}

/*
 * The vertex attribute formats. Direct3D 11 has no equivalent of the OpenGL
 * "size + type + normalized" triple, each combination maps to one DXGI format.
 */
DXGI_FORMAT getAttributeFormat(int size, int type, bool normalized) {
	switch (type) {
		case DX11_TYPE_FLOAT:
			switch (size) {
				case 1: return DXGI_FORMAT_R32_FLOAT;
				case 2: return DXGI_FORMAT_R32G32_FLOAT;
				case 3: return DXGI_FORMAT_R32G32B32_FLOAT;
				default: return DXGI_FORMAT_R32G32B32A32_FLOAT;
			}
		case DX11_TYPE_BYTE:
			return normalized ? DXGI_FORMAT_R8G8B8A8_SNORM : DXGI_FORMAT_R8G8B8A8_SINT;
		case DX11_TYPE_UNSIGNED_BYTE:
			return normalized ? DXGI_FORMAT_R8G8B8A8_UNORM : DXGI_FORMAT_R8G8B8A8_UINT;
		case DX11_TYPE_SHORT:
			if (size <= 2) {
				return normalized ? DXGI_FORMAT_R16G16_SNORM : DXGI_FORMAT_R16G16_SINT;
			}
			return normalized ? DXGI_FORMAT_R16G16B16A16_SNORM : DXGI_FORMAT_R16G16B16A16_SINT;
		case DX11_TYPE_UNSIGNED_SHORT:
			if (size <= 2) {
				return normalized ? DXGI_FORMAT_R16G16_UNORM : DXGI_FORMAT_R16G16_UINT;
			}
			return normalized ? DXGI_FORMAT_R16G16B16A16_UNORM : DXGI_FORMAT_R16G16B16A16_UINT;
		case DX11_TYPE_INT:
			switch (size) {
				case 1: return DXGI_FORMAT_R32_SINT;
				case 2: return DXGI_FORMAT_R32G32_SINT;
				case 3: return DXGI_FORMAT_R32G32B32_SINT;
				default: return DXGI_FORMAT_R32G32B32A32_SINT;
			}
		case DX11_TYPE_UNSIGNED_INT:
			switch (size) {
				case 1: return DXGI_FORMAT_R32_UINT;
				case 2: return DXGI_FORMAT_R32G32_UINT;
				case 3: return DXGI_FORMAT_R32G32B32_UINT;
				default: return DXGI_FORMAT_R32G32B32A32_UINT;
			}
	}

	return DXGI_FORMAT_R32G32B32A32_FLOAT;
}

/* The number of bytes of one block row of a compressed format */
UINT getBlockSize(DXGI_FORMAT format) {
	switch (format) {
		case DXGI_FORMAT_BC1_UNORM: return 8;
		case DXGI_FORMAT_BC2_UNORM:
		case DXGI_FORMAT_BC3_UNORM: return 16;
		default: return 0;
	}
}

UINT getBytesPerPixel(DXGI_FORMAT format) {
	switch (format) {
		case DXGI_FORMAT_R8_UINT:
		case DXGI_FORMAT_R8_UNORM:
			return 1;
		case DXGI_FORMAT_B5G6R5_UNORM:
		case DXGI_FORMAT_B5G5R5A1_UNORM:
		case DXGI_FORMAT_B4G4R4A4_UNORM:
		case DXGI_FORMAT_R16_UINT:
		case DXGI_FORMAT_D16_UNORM:
			return 2;
		default:
			return 4;
	}
}

/*
 * A texture used as a depth/stencil buffer cannot be sampled with the same
 * format: the typeless format has to be used for the resource itself.
 */
DXGI_FORMAT getResourceFormat(DXGI_FORMAT format, UINT bindFlags) {
	if ((bindFlags & D3D11_BIND_DEPTH_STENCIL) != 0) {
		switch (format) {
			case DXGI_FORMAT_D16_UNORM:         return DXGI_FORMAT_R16_TYPELESS;
			case DXGI_FORMAT_D24_UNORM_S8_UINT: return DXGI_FORMAT_R24G8_TYPELESS;
			case DXGI_FORMAT_D32_FLOAT:         return DXGI_FORMAT_R32_TYPELESS;
			default: break;
		}
	}

	return format;
}

DXGI_FORMAT getShaderResourceFormat(DXGI_FORMAT format, UINT bindFlags) {
	if ((bindFlags & D3D11_BIND_DEPTH_STENCIL) != 0) {
		switch (format) {
			case DXGI_FORMAT_D16_UNORM:         return DXGI_FORMAT_R16_UNORM;
			case DXGI_FORMAT_D24_UNORM_S8_UINT: return DXGI_FORMAT_R24_UNORM_X8_TYPELESS;
			case DXGI_FORMAT_D32_FLOAT:         return DXGI_FORMAT_R32_FLOAT;
			default: break;
		}
	}

	return format;
}

std::string makeKey(const int *values, int count) {
	char text[256];
	std::string key;
	for (int i = 0; i < count; i++) {
		_snprintf_s(text, sizeof(text), _TRUNCATE, "%d,", values[i]);
		key += text;
	}

	return key;
}

void releaseBackBufferViews() {
	release(ctx->backBufferRenderTargetView);
	release(ctx->backBufferDepthStencilView);
	release(ctx->backBufferDepth);
}

bool createBackBufferViews(UINT width, UINT height) {
	ID3D11Texture2D *backBuffer = NULL;
	HRESULT hr = ctx->swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), (void **) &backBuffer);
	if (FAILED(hr)) {
		setError("Cannot get the swap chain back buffer", hr);
		return false;
	}

	hr = ctx->device->CreateRenderTargetView(backBuffer, NULL, &ctx->backBufferRenderTargetView);
	release(backBuffer);
	if (FAILED(hr)) {
		setError("Cannot create the back buffer render target view", hr);
		return false;
	}

	D3D11_TEXTURE2D_DESC depthDesc;
	ZeroMemory(&depthDesc, sizeof(depthDesc));
	depthDesc.Width = width;
	depthDesc.Height = height;
	depthDesc.MipLevels = 1;
	depthDesc.ArraySize = 1;
	depthDesc.Format = DXGI_FORMAT_D24_UNORM_S8_UINT;
	depthDesc.SampleDesc.Count = 1;
	depthDesc.Usage = D3D11_USAGE_DEFAULT;
	depthDesc.BindFlags = D3D11_BIND_DEPTH_STENCIL;

	hr = ctx->device->CreateTexture2D(&depthDesc, NULL, &ctx->backBufferDepth);
	if (FAILED(hr)) {
		setError("Cannot create the back buffer depth/stencil texture", hr);
		return false;
	}

	hr = ctx->device->CreateDepthStencilView(ctx->backBufferDepth, NULL, &ctx->backBufferDepthStencilView);
	if (FAILED(hr)) {
		setError("Cannot create the back buffer depth/stencil view", hr);
		return false;
	}

	ctx->backBufferWidth = width;
	ctx->backBufferHeight = height;
	ctx->currentRenderTargetView = ctx->backBufferRenderTargetView;
	ctx->currentDepthStencilView = ctx->backBufferDepthStencilView;
	ctx->deviceContext->OMSetRenderTargets(1, &ctx->currentRenderTargetView, ctx->currentDepthStencilView);

	return true;
}

/*
 * Walk the constant buffers of a compiled shader and remember the offset, the
 * array length and the stride of every variable. The Java side addresses the
 * uniforms by the index inside this table.
 */
void reflectUniforms(ProgramResource *program, ShaderResource *shader) {
	if (shader == NULL || shader->bytecode == NULL) {
		return;
	}

	int stage = shader->stage;
	if (stage < 0 || stage >= 6) {
		return;
	}

	ID3D11ShaderReflection *reflection = NULL;
	HRESULT hr = D3DReflect(shader->bytecode->GetBufferPointer(), shader->bytecode->GetBufferSize(), IID_ID3D11ShaderReflection, (void **) &reflection);
	if (FAILED(hr) || reflection == NULL) {
		return;
	}

	D3D11_SHADER_DESC shaderDesc;
	if (FAILED(reflection->GetDesc(&shaderDesc))) {
		release(reflection);
		return;
	}

	for (UINT i = 0; i < shaderDesc.ConstantBuffers; i++) {
		ID3D11ShaderReflectionConstantBuffer *constantBuffer = reflection->GetConstantBufferByIndex(i);
		D3D11_SHADER_BUFFER_DESC bufferDesc;
		if (constantBuffer == NULL || FAILED(constantBuffer->GetDesc(&bufferDesc))) {
			continue;
		}
		if (bufferDesc.Type != D3D_CT_CBUFFER) {
			continue;
		}

		if (program->constantData[stage].size() < bufferDesc.Size) {
			program->constantData[stage].resize(bufferDesc.Size, 0);
		}

		for (UINT v = 0; v < bufferDesc.Variables; v++) {
			ID3D11ShaderReflectionVariable *variable = constantBuffer->GetVariableByIndex(v);
			D3D11_SHADER_VARIABLE_DESC variableDesc;
			if (variable == NULL || FAILED(variable->GetDesc(&variableDesc))) {
				continue;
			}

			ID3D11ShaderReflectionType *type = variable->GetType();
			D3D11_SHADER_TYPE_DESC typeDesc;
			ZeroMemory(&typeDesc, sizeof(typeDesc));
			if (type != NULL) {
				type->GetDesc(&typeDesc);
			}

			UINT rows = typeDesc.Rows > 0 ? typeDesc.Rows : 1;
			UINT columns = typeDesc.Columns > 0 ? typeDesc.Columns : 1;

			UniformStageInfo info;
			info.stage = stage;
			info.offset = variableDesc.StartOffset;
			info.elementCount = typeDesc.Elements > 0 ? typeDesc.Elements : 1;
			info.componentsPerElement = rows * columns;
			/*
			 * The HLSL packing rules: an array element and a matrix column each
			 * start on a 16 byte boundary. The size reported by the reflection
			 * cannot be used, it excludes the padding of the last element.
			 */
			if (typeDesc.Class == D3D_SVC_MATRIX_COLUMNS) {
				info.elementStride = columns * 16;
			} else if (typeDesc.Class == D3D_SVC_MATRIX_ROWS) {
				info.elementStride = rows * 16;
			} else {
				info.elementStride = 16;
			}

			int index;
			std::map<std::string, int>::iterator it = program->uniformIndices.find(variableDesc.Name);
			if (it == program->uniformIndices.end()) {
				index = (int) program->uniforms.size();
				program->uniformIndices[variableDesc.Name] = index;
				program->uniforms.push_back(UniformInfo());
			} else {
				index = it->second;
			}
			program->uniforms[index].stages.push_back(info);
		}
	}

	release(reflection);
}

/* Upload the shadow copy of the constant buffer of every stage of the program */
void uploadConstantBuffer(ProgramResource *program) {
	if (program == NULL) {
		return;
	}

	for (int stage = 0; stage < 6; stage++) {
		if (program->constantData[stage].empty()) {
			continue;
		}

		if (program->constantBuffer[stage] == NULL) {
			D3D11_BUFFER_DESC desc;
			ZeroMemory(&desc, sizeof(desc));
			/* A constant buffer size has to be a multiple of 16 bytes */
			desc.ByteWidth = (UINT) ((program->constantData[stage].size() + 15) & ~(size_t) 15);
			desc.Usage = D3D11_USAGE_DYNAMIC;
			desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
			desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

			HRESULT hr = ctx->device->CreateBuffer(&desc, NULL, &program->constantBuffer[stage]);
			if (FAILED(hr)) {
				setError("Cannot create the uniform constant buffer", hr);
				continue;
			}
			program->constantDataDirty[stage] = true;
		}

		if (program->constantDataDirty[stage]) {
			D3D11_MAPPED_SUBRESOURCE mapped;
			if (SUCCEEDED(ctx->deviceContext->Map(program->constantBuffer[stage], 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				memcpy(mapped.pData, &program->constantData[stage][0], program->constantData[stage].size());
				ctx->deviceContext->Unmap(program->constantBuffer[stage], 0);
			}
			program->constantDataDirty[stage] = false;
		}

		ID3D11Buffer *buffer = program->constantBuffer[stage];
		switch (stage) {
			case DX11_STAGE_VERTEX:   ctx->deviceContext->VSSetConstantBuffers(0, 1, &buffer); break;
			case DX11_STAGE_PIXEL:    ctx->deviceContext->PSSetConstantBuffers(0, 1, &buffer); break;
			case DX11_STAGE_GEOMETRY: ctx->deviceContext->GSSetConstantBuffers(0, 1, &buffer); break;
			case DX11_STAGE_HULL:     ctx->deviceContext->HSSetConstantBuffers(0, 1, &buffer); break;
			case DX11_STAGE_DOMAIN:   ctx->deviceContext->DSSetConstantBuffers(0, 1, &buffer); break;
			default: break;
		}
	}
}

void bindProgramShaders(ProgramResource *program) {
	if (program == NULL) {
		return;
	}

	ctx->deviceContext->VSSetShader(program->stages[DX11_STAGE_VERTEX] == NULL ? NULL : (ID3D11VertexShader *) program->stages[DX11_STAGE_VERTEX]->shader, NULL, 0);
	ctx->deviceContext->PSSetShader(program->stages[DX11_STAGE_PIXEL] == NULL ? NULL : (ID3D11PixelShader *) program->stages[DX11_STAGE_PIXEL]->shader, NULL, 0);
	ctx->deviceContext->GSSetShader(program->stages[DX11_STAGE_GEOMETRY] == NULL ? NULL : (ID3D11GeometryShader *) program->stages[DX11_STAGE_GEOMETRY]->shader, NULL, 0);
	ctx->deviceContext->HSSetShader(program->stages[DX11_STAGE_HULL] == NULL ? NULL : (ID3D11HullShader *) program->stages[DX11_STAGE_HULL]->shader, NULL, 0);
	ctx->deviceContext->DSSetShader(program->stages[DX11_STAGE_DOMAIN] == NULL ? NULL : (ID3D11DomainShader *) program->stages[DX11_STAGE_DOMAIN]->shader, NULL, 0);
}

/*
 * The shaders drawing a rescaled copy. The full screen triangle is generated
 * from the vertex index, so the blit needs neither a vertex buffer nor an input
 * layout.
 */
static const char *blitShaderSource =
	"cbuffer BlitConstants : register(b0) { float4 uvScaleOffset; };\n"
	"Texture2D blitSource : register(t0);\n"
	"SamplerState blitSampler : register(s0);\n"
	"struct VSOut { float4 position : SV_Position; float2 uv : TEXCOORD0; };\n"
	"VSOut vsMain(uint id : SV_VertexID) {\n"
	"    VSOut output;\n"
	"    float2 corner = float2((id << 1) & 2, id & 2);\n"
	"    output.position = float4(corner * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);\n"
	"    output.uv = corner * uvScaleOffset.xy + uvScaleOffset.zw;\n"
	"    return output;\n"
	"}\n"
	"float4 psMain(VSOut input) : SV_Target {\n"
	"    return blitSource.Sample(blitSampler, input.uv);\n"
	"}\n";

ID3D11SamplerState *createBlitSampler(D3D11_FILTER filter) {
	D3D11_SAMPLER_DESC desc;
	ZeroMemory(&desc, sizeof(desc));
	desc.Filter = filter;
	desc.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
	desc.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
	desc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
	desc.MaxLOD = D3D11_FLOAT32_MAX;
	desc.ComparisonFunc = D3D11_COMPARISON_NEVER;

	ID3D11SamplerState *sampler = NULL;
	ctx->device->CreateSamplerState(&desc, &sampler);

	return sampler;
}

bool initBlitPipeline() {
	if (ctx->blit.initialized) {
		return true;
	}
	if (ctx->blit.failed) {
		return false;
	}

	ctx->blit.failed = true;

	ID3DBlob *vertexBytecode = NULL;
	ID3DBlob *pixelBytecode = NULL;
	ID3DBlob *errors = NULL;
	size_t length = strlen(blitShaderSource);

	HRESULT hr = D3DCompile(blitShaderSource, length, NULL, NULL, NULL, "vsMain", "vs_4_0", 0, 0, &vertexBytecode, &errors);
	if (FAILED(hr)) {
		setError("Cannot compile the blit vertex shader", hr);
		release(errors);
		return false;
	}
	release(errors);

	hr = D3DCompile(blitShaderSource, length, NULL, NULL, NULL, "psMain", "ps_4_0", 0, 0, &pixelBytecode, &errors);
	if (FAILED(hr)) {
		setError("Cannot compile the blit pixel shader", hr);
		release(errors);
		release(vertexBytecode);
		return false;
	}
	release(errors);

	hr = ctx->device->CreateVertexShader(vertexBytecode->GetBufferPointer(), vertexBytecode->GetBufferSize(), NULL, &ctx->blit.vertexShader);
	if (SUCCEEDED(hr)) {
		hr = ctx->device->CreatePixelShader(pixelBytecode->GetBufferPointer(), pixelBytecode->GetBufferSize(), NULL, &ctx->blit.pixelShader);
	}
	release(vertexBytecode);
	release(pixelBytecode);
	if (FAILED(hr)) {
		setError("Cannot create the blit shaders", hr);
		return false;
	}

	D3D11_BUFFER_DESC bufferDesc;
	ZeroMemory(&bufferDesc, sizeof(bufferDesc));
	bufferDesc.ByteWidth = 16;
	bufferDesc.Usage = D3D11_USAGE_DYNAMIC;
	bufferDesc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
	bufferDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
	hr = ctx->device->CreateBuffer(&bufferDesc, NULL, &ctx->blit.constantBuffer);
	if (FAILED(hr)) {
		setError("Cannot create the blit constant buffer", hr);
		return false;
	}

	ctx->blit.pointSampler = createBlitSampler(D3D11_FILTER_MIN_MAG_MIP_POINT);
	ctx->blit.linearSampler = createBlitSampler(D3D11_FILTER_MIN_MAG_MIP_LINEAR);

	D3D11_BLEND_DESC blendDesc;
	ZeroMemory(&blendDesc, sizeof(blendDesc));
	blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
	ctx->device->CreateBlendState(&blendDesc, &ctx->blit.blendState);

	D3D11_DEPTH_STENCIL_DESC depthStencilDesc;
	ZeroMemory(&depthStencilDesc, sizeof(depthStencilDesc));
	ctx->device->CreateDepthStencilState(&depthStencilDesc, &ctx->blit.depthStencilState);

	D3D11_RASTERIZER_DESC rasterizerDesc;
	ZeroMemory(&rasterizerDesc, sizeof(rasterizerDesc));
	rasterizerDesc.CullMode = D3D11_CULL_NONE;
	rasterizerDesc.FillMode = D3D11_FILL_SOLID;
	rasterizerDesc.DepthClipEnable = TRUE;
	ctx->device->CreateRasterizerState(&rasterizerDesc, &ctx->blit.rasterizerState);

	if (ctx->blit.pointSampler == NULL || ctx->blit.linearSampler == NULL || ctx->blit.blendState == NULL
			|| ctx->blit.depthStencilState == NULL || ctx->blit.rasterizerState == NULL) {
		setError("Cannot create the blit pipeline states");
		return false;
	}

	ctx->blit.initialized = true;
	ctx->blit.failed = false;

	return true;
}

void releaseBlitPipeline() {
	release(ctx->blit.vertexShader);
	release(ctx->blit.pixelShader);
	release(ctx->blit.constantBuffer);
	release(ctx->blit.pointSampler);
	release(ctx->blit.linearSampler);
	release(ctx->blit.blendState);
	release(ctx->blit.depthStencilState);
	release(ctx->blit.rasterizerState);
	ctx->blit.initialized = false;
	ctx->blit.failed = false;
}

/* The resource, the render target view and the shader resource view of one side
 * of a blit. A texture handle of 0 means the back buffer. */
struct BlitSurface {
	ID3D11Resource *resource;
	ID3D11RenderTargetView *renderTargetView;
	ID3D11ShaderResourceView *shaderResourceView;
	bool ownsResource;
};

bool getBlitSurface(int32_t texture, bool depthStencil, BlitSurface &surface) {
	surface.resource = NULL;
	surface.renderTargetView = NULL;
	surface.shaderResourceView = NULL;
	surface.ownsResource = false;

	if (texture == 0) {
		ID3D11View *view = depthStencil ? (ID3D11View *) ctx->backBufferDepthStencilView : (ID3D11View *) ctx->backBufferRenderTargetView;
		if (view == NULL) {
			return false;
		}
		view->GetResource(&surface.resource);
		surface.ownsResource = true;
		surface.renderTargetView = depthStencil ? NULL : ctx->backBufferRenderTargetView;
		return surface.resource != NULL;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource == NULL || resource->texture == NULL) {
		return false;
	}

	surface.resource = resource->texture;
	surface.renderTargetView = resource->renderTargetView;
	surface.shaderResourceView = resource->shaderResourceView;

	return true;
}

void releaseBlitSurface(BlitSurface &surface) {
	if (surface.ownsResource) {
		release(surface.resource);
	}
	surface.resource = NULL;
}

void prepareDraw(D3D11_PRIMITIVE_TOPOLOGY topology) {
	if (ctx->currentTopology != topology) {
		ctx->currentTopology = topology;
		ctx->deviceContext->IASetPrimitiveTopology(topology);
	}

	uploadConstantBuffer(ctx->currentProgram);
}

} /* anonymous namespace */

extern "C" {

int32_t dx11Init(void) {
	if (ctx == NULL) {
		/* The parentheses value-initialize the struct: the pointers and the
		 * counters are zeroed and the maps are default constructed. */
		ctx = new Context();
		ctx->currentTopology = D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED;
		ctx->nextHandle = 1;
	}

	/* Check that the Direct3D 11 runtime is present without creating a window */
	ID3D11Device *device = NULL;
	ID3D11DeviceContext *deviceContext = NULL;
	D3D_FEATURE_LEVEL featureLevel;
	HRESULT hr = D3D11CreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, NULL, 0, D3D11_SDK_VERSION, &device, &featureLevel, &deviceContext);
	if (FAILED(hr)) {
		setError("Direct3D 11 is not available", hr);
		return 0;
	}

	release(deviceContext);
	release(device);

	return 1;
}

void dx11Shutdown(void) {
	dx11DestroyDevice();
	if (ctx != NULL) {
		delete ctx;
		ctx = NULL;
	}
}

int32_t dx11CreateDevice(int64_t hwnd, int32_t width, int32_t height, int32_t flags) {
	if (ctx == NULL && dx11Init() == 0) {
		return 0;
	}
	if (ctx->device != NULL) {
		dx11DestroyDevice();
	}

	DXGI_SWAP_CHAIN_DESC swapChainDesc;
	ZeroMemory(&swapChainDesc, sizeof(swapChainDesc));
	swapChainDesc.BufferCount = 1;
	swapChainDesc.BufferDesc.Width = width;
	swapChainDesc.BufferDesc.Height = height;
	swapChainDesc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
	swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
	swapChainDesc.OutputWindow = (HWND) (intptr_t) hwnd;
	swapChainDesc.SampleDesc.Count = 1;
	swapChainDesc.Windowed = TRUE;
	/* The BitBlt model coexists with the OpenGL pixel format already set on the
	 * window by the AWT canvas: jpcsp only uses that window handle here, it
	 * never swaps the OpenGL buffers while Direct3D 11 is active. */
	swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

	UINT deviceFlags = 0;
	if ((flags & DX11_DEVICE_FLAG_DEBUG) != 0) {
		deviceFlags |= D3D11_CREATE_DEVICE_DEBUG;
	}
	if ((flags & DX11_DEVICE_FLAG_SINGLE_THREADED) != 0) {
		deviceFlags |= D3D11_CREATE_DEVICE_SINGLETHREADED;
	}

	const D3D_FEATURE_LEVEL featureLevels[] = {
		D3D_FEATURE_LEVEL_11_0,
		D3D_FEATURE_LEVEL_10_1,
		D3D_FEATURE_LEVEL_10_0
	};

	HRESULT hr = D3D11CreateDeviceAndSwapChain(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, deviceFlags,
			featureLevels, ARRAYSIZE(featureLevels), D3D11_SDK_VERSION,
			&swapChainDesc, &ctx->swapChain, &ctx->device, &ctx->featureLevel, &ctx->deviceContext);
	if (FAILED(hr)) {
		setError("Cannot create the Direct3D 11 device and swap chain", hr);
		dx11DestroyDevice();
		return 0;
	}

	IDXGIDevice *dxgiDevice = NULL;
	if (SUCCEEDED(ctx->device->QueryInterface(__uuidof(IDXGIDevice), (void **) &dxgiDevice))) {
		IDXGIAdapter *adapter = NULL;
		if (SUCCEEDED(dxgiDevice->GetAdapter(&adapter))) {
			DXGI_ADAPTER_DESC adapterDesc;
			if (SUCCEEDED(adapter->GetDesc(&adapterDesc))) {
				char description[256];
				size_t converted = 0;
				wcstombs_s(&converted, description, sizeof(description), adapterDesc.Description, _TRUNCATE);
				ctx->adapterDescription = description;
			}
			release(adapter);
		}
		release(dxgiDevice);
	}

	if (!createBackBufferViews(width, height)) {
		dx11DestroyDevice();
		return 0;
	}

	D3D11_VIEWPORT viewport;
	viewport.TopLeftX = 0.0f;
	viewport.TopLeftY = 0.0f;
	viewport.Width = (float) width;
	viewport.Height = (float) height;
	viewport.MinDepth = 0.0f;
	viewport.MaxDepth = 1.0f;
	ctx->deviceContext->RSSetViewports(1, &viewport);

	return 1;
}

void dx11DestroyDevice(void) {
	if (ctx == NULL) {
		return;
	}

	for (std::map<int, BufferResource *>::iterator it = ctx->buffers.begin(); it != ctx->buffers.end(); ++it) {
		release(it->second->buffer);
		delete it->second;
	}
	ctx->buffers.clear();

	for (std::map<int, TextureResource *>::iterator it = ctx->textures.begin(); it != ctx->textures.end(); ++it) {
		release(it->second->shaderResourceView);
		release(it->second->renderTargetView);
		release(it->second->depthStencilView);
		release(it->second->texture);
		delete it->second;
	}
	ctx->textures.clear();

	for (std::map<int, ShaderResource *>::iterator it = ctx->shaders.begin(); it != ctx->shaders.end(); ++it) {
		release(it->second->bytecode);
		release(it->second->shader);
		delete it->second;
	}
	ctx->shaders.clear();

	for (std::map<int, ProgramResource *>::iterator it = ctx->programs.begin(); it != ctx->programs.end(); ++it) {
		for (int stage = 0; stage < 6; stage++) {
			release(it->second->constantBuffer[stage]);
		}
		delete it->second;
	}
	ctx->programs.clear();

	for (std::map<int, QueryResource *>::iterator it = ctx->queries.begin(); it != ctx->queries.end(); ++it) {
		release(it->second->query);
		delete it->second;
	}
	ctx->queries.clear();

	for (std::map<std::string, ID3D11BlendState *>::iterator it = ctx->blendStates.begin(); it != ctx->blendStates.end(); ++it) {
		release(it->second);
	}
	ctx->blendStates.clear();
	for (std::map<std::string, ID3D11DepthStencilState *>::iterator it = ctx->depthStencilStates.begin(); it != ctx->depthStencilStates.end(); ++it) {
		release(it->second);
	}
	ctx->depthStencilStates.clear();
	for (std::map<std::string, ID3D11RasterizerState *>::iterator it = ctx->rasterizerStates.begin(); it != ctx->rasterizerStates.end(); ++it) {
		release(it->second);
	}
	ctx->rasterizerStates.clear();
	for (std::map<std::string, ID3D11SamplerState *>::iterator it = ctx->samplerStates.begin(); it != ctx->samplerStates.end(); ++it) {
		release(it->second);
	}
	ctx->samplerStates.clear();
	for (std::map<std::string, ID3D11InputLayout *>::iterator it = ctx->inputLayouts.begin(); it != ctx->inputLayouts.end(); ++it) {
		release(it->second);
	}
	ctx->inputLayouts.clear();

	releaseBlitPipeline();
	releaseBackBufferViews();

	if (ctx->deviceContext != NULL) {
		ctx->deviceContext->ClearState();
		ctx->deviceContext->Flush();
	}
	release(ctx->swapChain);
	release(ctx->deviceContext);
	release(ctx->device);

	ctx->currentProgram = NULL;
	ctx->currentRenderTargetView = NULL;
	ctx->currentDepthStencilView = NULL;
	ctx->currentTopology = D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED;
}

void dx11Resize(int32_t width, int32_t height) {
	if (!hasDevice() || width <= 0 || height <= 0) {
		return;
	}

	ctx->deviceContext->OMSetRenderTargets(0, NULL, NULL);
	releaseBackBufferViews();

	HRESULT hr = ctx->swapChain->ResizeBuffers(1, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, 0);
	if (FAILED(hr)) {
		setError("Cannot resize the swap chain buffers", hr);
		return;
	}

	createBackBufferViews(width, height);
}

void dx11Present(int32_t swapInterval) {
	if (!hasDevice()) {
		return;
	}

	HRESULT hr = ctx->swapChain->Present(swapInterval, 0);
	if (FAILED(hr)) {
		setError("Cannot present the swap chain", hr);
	}
}

int32_t dx11GetFeatureLevel(void) {
	return hasDevice() ? (int32_t) ctx->featureLevel : 0;
}

const char *dx11GetAdapterDescription(void) {
	return ctx == NULL ? "" : ctx->adapterDescription.c_str();
}

const char *dx11GetLastError(void) {
	if (ctx == NULL) {
		return "";
	}

	static std::string lastError;
	lastError = ctx->lastError;
	ctx->lastError.clear();

	return lastError.c_str();
}

/*
 * Buffers
 */

int32_t dx11CreateBuffer(int32_t bindFlags, int32_t usage, int32_t size, const void *data) {
	if (!hasDevice() || size <= 0) {
		return 0;
	}

	D3D11_BUFFER_DESC desc;
	ZeroMemory(&desc, sizeof(desc));
	desc.ByteWidth = (UINT) ((size + 15) & ~15);
	desc.Usage = (D3D11_USAGE) usage;
	desc.BindFlags = bindFlags;
	if (desc.Usage == D3D11_USAGE_DYNAMIC) {
		desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
	}

	/* Direct3D reads ByteWidth bytes from the initial data. The width has been
	 * rounded up, so the initial data can only be used when nothing was added. */
	bool useInitialData = data != NULL && desc.ByteWidth == (UINT) size;

	D3D11_SUBRESOURCE_DATA initialData;
	ZeroMemory(&initialData, sizeof(initialData));
	initialData.pSysMem = data;

	ID3D11Buffer *buffer = NULL;
	HRESULT hr = ctx->device->CreateBuffer(&desc, useInitialData ? &initialData : NULL, &buffer);
	if (FAILED(hr)) {
		setError("Cannot create a buffer", hr);
		return 0;
	}

	BufferResource *resource = new BufferResource();
	resource->buffer = buffer;
	resource->size = desc.ByteWidth;
	resource->bindFlags = bindFlags;
	resource->usage = desc.Usage;

	int handle = allocateHandle();
	ctx->buffers[handle] = resource;

	if (data != NULL && !useInitialData) {
		dx11UpdateBuffer(handle, 0, size, data);
	}

	return handle;
}

void dx11DeleteBuffer(int32_t buffer) {
	if (ctx == NULL) {
		return;
	}

	BufferResource *resource = find(ctx->buffers, buffer);
	if (resource == NULL) {
		return;
	}

	release(resource->buffer);
	delete resource;
	ctx->buffers.erase(buffer);
}

void dx11UpdateBuffer(int32_t buffer, int32_t offset, int32_t size, const void *data) {
	if (!hasDevice() || data == NULL || size <= 0) {
		return;
	}

	BufferResource *resource = find(ctx->buffers, buffer);
	if (resource == NULL) {
		return;
	}

	if (resource->usage == D3D11_USAGE_DYNAMIC && offset == 0) {
		D3D11_MAPPED_SUBRESOURCE mapped;
		HRESULT hr = ctx->deviceContext->Map(resource->buffer, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped);
		if (FAILED(hr)) {
			setError("Cannot map a buffer", hr);
			return;
		}
		memcpy(mapped.pData, data, size < (int32_t) resource->size ? size : (int32_t) resource->size);
		ctx->deviceContext->Unmap(resource->buffer, 0);
		return;
	}

	D3D11_BOX box;
	box.left = offset;
	box.right = offset + size;
	box.top = 0;
	box.bottom = 1;
	box.front = 0;
	box.back = 1;
	ctx->deviceContext->UpdateSubresource(resource->buffer, 0, &box, data, 0, 0);
}

void dx11BindVertexBuffer(int32_t slot, int32_t buffer, int32_t stride, int32_t offset) {
	if (!hasDevice()) {
		return;
	}

	BufferResource *resource = find(ctx->buffers, buffer);
	ID3D11Buffer *vertexBuffer = resource == NULL ? NULL : resource->buffer;
	UINT strides = (UINT) stride;
	UINT offsets = (UINT) offset;
	ctx->deviceContext->IASetVertexBuffers(slot, 1, &vertexBuffer, &strides, &offsets);
}

void dx11BindIndexBuffer(int32_t buffer, int32_t format, int32_t offset) {
	if (!hasDevice()) {
		return;
	}

	BufferResource *resource = find(ctx->buffers, buffer);
	ctx->deviceContext->IASetIndexBuffer(resource == NULL ? NULL : resource->buffer, (DXGI_FORMAT) format, (UINT) offset);
}

void dx11BindConstantBuffer(int32_t stage, int32_t slot, int32_t buffer) {
	if (!hasDevice()) {
		return;
	}

	BufferResource *resource = find(ctx->buffers, buffer);
	ID3D11Buffer *constantBuffer = resource == NULL ? NULL : resource->buffer;
	switch (stage) {
		case DX11_STAGE_VERTEX:   ctx->deviceContext->VSSetConstantBuffers(slot, 1, &constantBuffer); break;
		case DX11_STAGE_PIXEL:    ctx->deviceContext->PSSetConstantBuffers(slot, 1, &constantBuffer); break;
		case DX11_STAGE_GEOMETRY: ctx->deviceContext->GSSetConstantBuffers(slot, 1, &constantBuffer); break;
		case DX11_STAGE_HULL:     ctx->deviceContext->HSSetConstantBuffers(slot, 1, &constantBuffer); break;
		case DX11_STAGE_DOMAIN:   ctx->deviceContext->DSSetConstantBuffers(slot, 1, &constantBuffer); break;
		default: break;
	}
}

/*
 * Textures
 */

int32_t dx11CreateTexture(int32_t width, int32_t height, int32_t format, int32_t levels, int32_t bindFlags) {
	if (!hasDevice() || width <= 0 || height <= 0) {
		return 0;
	}

	D3D11_TEXTURE2D_DESC desc;
	ZeroMemory(&desc, sizeof(desc));
	desc.Width = width;
	desc.Height = height;
	desc.MipLevels = levels < 1 ? 1 : levels;
	desc.ArraySize = 1;
	desc.Format = getResourceFormat((DXGI_FORMAT) format, bindFlags);
	desc.SampleDesc.Count = 1;
	desc.Usage = D3D11_USAGE_DEFAULT;
	desc.BindFlags = bindFlags;
	if ((bindFlags & D3D11_BIND_DEPTH_STENCIL) != 0) {
		/* A depth/stencil texture is also sampled by the stencil emulation */
		desc.BindFlags |= D3D11_BIND_SHADER_RESOURCE;
	}

	ID3D11Texture2D *texture = NULL;
	HRESULT hr = ctx->device->CreateTexture2D(&desc, NULL, &texture);
	if (FAILED(hr)) {
		setError("Cannot create a texture", hr);
		return 0;
	}

	TextureResource *resource = new TextureResource();
	resource->texture = texture;
	resource->format = (DXGI_FORMAT) format;
	resource->width = width;
	resource->height = height;
	resource->levels = desc.MipLevels;
	resource->bindFlags = desc.BindFlags;

	if ((desc.BindFlags & D3D11_BIND_SHADER_RESOURCE) != 0) {
		D3D11_SHADER_RESOURCE_VIEW_DESC viewDesc;
		ZeroMemory(&viewDesc, sizeof(viewDesc));
		viewDesc.Format = getShaderResourceFormat((DXGI_FORMAT) format, bindFlags);
		viewDesc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
		viewDesc.Texture2D.MipLevels = desc.MipLevels;
		ctx->device->CreateShaderResourceView(texture, &viewDesc, &resource->shaderResourceView);
	}

	if ((desc.BindFlags & D3D11_BIND_RENDER_TARGET) != 0) {
		ctx->device->CreateRenderTargetView(texture, NULL, &resource->renderTargetView);
	}

	if ((desc.BindFlags & D3D11_BIND_DEPTH_STENCIL) != 0) {
		D3D11_DEPTH_STENCIL_VIEW_DESC viewDesc;
		ZeroMemory(&viewDesc, sizeof(viewDesc));
		viewDesc.Format = (DXGI_FORMAT) format;
		viewDesc.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
		ctx->device->CreateDepthStencilView(texture, &viewDesc, &resource->depthStencilView);
	}

	int handle = allocateHandle();
	ctx->textures[handle] = resource;

	return handle;
}

void dx11DeleteTexture(int32_t texture) {
	if (ctx == NULL) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource == NULL) {
		return;
	}

	release(resource->shaderResourceView);
	release(resource->renderTargetView);
	release(resource->depthStencilView);
	release(resource->texture);
	delete resource;
	ctx->textures.erase(texture);
}

void dx11UpdateTexture(int32_t texture, int32_t level, int32_t x, int32_t y, int32_t width, int32_t height, int32_t rowPitch, int32_t size, const void *data) {
	if (!hasDevice() || data == NULL) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource == NULL || resource->texture == NULL) {
		return;
	}

	UINT pitch = rowPitch > 0 ? (UINT) rowPitch : width * getBytesPerPixel(resource->format);

	D3D11_BOX box;
	box.left = x;
	box.right = x + width;
	box.top = y;
	box.bottom = y + height;
	box.front = 0;
	box.back = 1;

	ctx->deviceContext->UpdateSubresource(resource->texture, level, &box, data, pitch, pitch * height);
}

void dx11UpdateCompressedTexture(int32_t texture, int32_t level, int32_t width, int32_t height, int32_t size, const void *data) {
	if (!hasDevice() || data == NULL) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource == NULL || resource->texture == NULL) {
		return;
	}

	UINT blockSize = getBlockSize(resource->format);
	if (blockSize == 0) {
		setError("Not a compressed texture format");
		return;
	}

	UINT blocksPerRow = (width + 3) / 4;
	UINT pitch = blocksPerRow * blockSize;

	ctx->deviceContext->UpdateSubresource(resource->texture, level, NULL, data, pitch, pitch * ((height + 3) / 4));
}

void dx11ReadTexture(int32_t texture, int32_t level, int32_t size, void *data) {
	if (!hasDevice() || data == NULL) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource == NULL || resource->texture == NULL) {
		return;
	}

	UINT width = resource->width >> level;
	UINT height = resource->height >> level;
	if (width == 0) {
		width = 1;
	}
	if (height == 0) {
		height = 1;
	}

	D3D11_TEXTURE2D_DESC desc;
	ZeroMemory(&desc, sizeof(desc));
	desc.Width = width;
	desc.Height = height;
	desc.MipLevels = 1;
	desc.ArraySize = 1;
	desc.Format = getResourceFormat(resource->format, resource->bindFlags);
	desc.SampleDesc.Count = 1;
	desc.Usage = D3D11_USAGE_STAGING;
	desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;

	ID3D11Texture2D *staging = NULL;
	HRESULT hr = ctx->device->CreateTexture2D(&desc, NULL, &staging);
	if (FAILED(hr)) {
		setError("Cannot create the staging texture for a read back", hr);
		return;
	}

	ctx->deviceContext->CopySubresourceRegion(staging, 0, 0, 0, 0, resource->texture, level, NULL);

	D3D11_MAPPED_SUBRESOURCE mapped;
	hr = ctx->deviceContext->Map(staging, 0, D3D11_MAP_READ, 0, &mapped);
	if (SUCCEEDED(hr)) {
		UINT bytesPerRow = width * getBytesPerPixel(resource->format);
		unsigned char *destination = (unsigned char *) data;
		const unsigned char *source = (const unsigned char *) mapped.pData;
		UINT copied = 0;
		for (UINT row = 0; row < height && copied + bytesPerRow <= (UINT) size; row++) {
			memcpy(destination + copied, source + row * mapped.RowPitch, bytesPerRow);
			copied += bytesPerRow;
		}
		ctx->deviceContext->Unmap(staging, 0);
	} else {
		setError("Cannot map the staging texture", hr);
	}

	release(staging);
}

void dx11BindTexture(int32_t stage, int32_t slot, int32_t texture) {
	if (!hasDevice()) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	ID3D11ShaderResourceView *view = resource == NULL ? NULL : resource->shaderResourceView;
	switch (stage) {
		case DX11_STAGE_VERTEX:   ctx->deviceContext->VSSetShaderResources(slot, 1, &view); break;
		case DX11_STAGE_GEOMETRY: ctx->deviceContext->GSSetShaderResources(slot, 1, &view); break;
		default:                  ctx->deviceContext->PSSetShaderResources(slot, 1, &view); break;
	}
}

void dx11GenerateMipmaps(int32_t texture) {
	if (!hasDevice()) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource != NULL && resource->shaderResourceView != NULL) {
		ctx->deviceContext->GenerateMips(resource->shaderResourceView);
	}
}

int32_t dx11GetTextureLevelParameter(int32_t texture, int32_t level, int32_t parameter) {
	TextureResource *resource = ctx == NULL ? NULL : find(ctx->textures, texture);
	if (resource == NULL) {
		return 0;
	}

	/* Only the parameters which cannot be answered by the Java side */
	switch (parameter) {
		case 3:  return (int32_t) resource->format; /* RE_TEXTURE_INTERNAL_FORMAT */
		case 14: return getBlockSize(resource->format) != 0 ? 1 : 0; /* RE_TEXTURE_COMPRESSED */
		default: return 0;
	}
}

void dx11CopyRenderTargetToTexture(int32_t texture, int32_t level, int32_t xOffset, int32_t yOffset, int32_t x, int32_t y, int32_t width, int32_t height) {
	if (!hasDevice() || ctx->currentRenderTargetView == NULL) {
		return;
	}

	TextureResource *resource = find(ctx->textures, texture);
	if (resource == NULL || resource->texture == NULL) {
		return;
	}

	ID3D11Resource *source = NULL;
	ctx->currentRenderTargetView->GetResource(&source);
	if (source == NULL) {
		return;
	}

	D3D11_BOX box;
	box.left = x;
	box.right = x + width;
	box.top = y;
	box.bottom = y + height;
	box.front = 0;
	box.back = 1;

	ctx->deviceContext->CopySubresourceRegion(resource->texture, level, xOffset, yOffset, 0, source, 0, &box);
	release(source);
}

/*
 * Render targets
 */

void dx11BindRenderTargets(int32_t colorTexture, int32_t depthStencilTexture) {
	if (!hasDevice()) {
		return;
	}

	TextureResource *color = find(ctx->textures, colorTexture);
	TextureResource *depth = find(ctx->textures, depthStencilTexture);

	ctx->currentRenderTargetView = color != NULL && color->renderTargetView != NULL ? color->renderTargetView : ctx->backBufferRenderTargetView;
	ctx->currentDepthStencilView = depth != NULL && depth->depthStencilView != NULL ? depth->depthStencilView : ctx->backBufferDepthStencilView;

	if (colorTexture != 0 && (color == NULL || color->renderTargetView == NULL)) {
		setError("The texture bound as a render target has no render target view");
	}

	ctx->deviceContext->OMSetRenderTargets(1, &ctx->currentRenderTargetView, ctx->currentDepthStencilView);
}

void dx11ReadPixels(int32_t x, int32_t y, int32_t width, int32_t height, int32_t format, int32_t size, void *data) {
	if (!hasDevice() || data == NULL) {
		return;
	}

	ID3D11Resource *source = NULL;
	bool depthRead = format == DXGI_FORMAT_D16_UNORM || format == DXGI_FORMAT_D24_UNORM_S8_UINT || format == DXGI_FORMAT_D32_FLOAT;
	if (depthRead) {
		if (ctx->currentDepthStencilView == NULL) {
			return;
		}
		ctx->currentDepthStencilView->GetResource(&source);
	} else {
		if (ctx->currentRenderTargetView == NULL) {
			return;
		}
		ctx->currentRenderTargetView->GetResource(&source);
	}

	if (source == NULL) {
		return;
	}

	ID3D11Texture2D *sourceTexture = NULL;
	if (FAILED(source->QueryInterface(__uuidof(ID3D11Texture2D), (void **) &sourceTexture))) {
		release(source);
		return;
	}

	D3D11_TEXTURE2D_DESC sourceDesc;
	sourceTexture->GetDesc(&sourceDesc);

	D3D11_TEXTURE2D_DESC desc = sourceDesc;
	desc.Width = width;
	desc.Height = height;
	desc.MipLevels = 1;
	desc.ArraySize = 1;
	desc.Usage = D3D11_USAGE_STAGING;
	desc.BindFlags = 0;
	desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
	desc.MiscFlags = 0;

	ID3D11Texture2D *staging = NULL;
	HRESULT hr = ctx->device->CreateTexture2D(&desc, NULL, &staging);
	if (FAILED(hr)) {
		setError("Cannot create the staging texture for readPixels", hr);
		release(sourceTexture);
		release(source);
		return;
	}

	D3D11_BOX box;
	box.left = x;
	box.right = x + width;
	box.top = y;
	box.bottom = y + height;
	box.front = 0;
	box.back = 1;
	ctx->deviceContext->CopySubresourceRegion(staging, 0, 0, 0, 0, sourceTexture, 0, &box);

	D3D11_MAPPED_SUBRESOURCE mapped;
	hr = ctx->deviceContext->Map(staging, 0, D3D11_MAP_READ, 0, &mapped);
	if (SUCCEEDED(hr)) {
		UINT bytesPerRow = width * getBytesPerPixel((DXGI_FORMAT) format);
		unsigned char *destination = (unsigned char *) data;
		const unsigned char *sourceData = (const unsigned char *) mapped.pData;
		UINT copied = 0;
		for (int row = 0; row < height && copied + bytesPerRow <= (UINT) size; row++) {
			memcpy(destination + copied, sourceData + row * mapped.RowPitch, bytesPerRow);
			copied += bytesPerRow;
		}
		ctx->deviceContext->Unmap(staging, 0);
	} else {
		setError("Cannot map the staging texture for readPixels", hr);
	}

	release(staging);
	release(sourceTexture);
	release(source);
}

void dx11Blit(int32_t sourceTexture, int32_t destinationTexture, int32_t srcX0, int32_t srcY0, int32_t srcX1, int32_t srcY1, int32_t dstX0, int32_t dstY0, int32_t dstX1, int32_t dstY1, int32_t depthStencil, int32_t linearFilter) {
	if (!hasDevice()) {
		return;
	}

	if (sourceTexture == destinationTexture) {
		setError("A blit cannot read and write the same texture");
		return;
	}

	int32_t sourceWidth = srcX1 - srcX0;
	int32_t sourceHeight = srcY1 - srcY0;
	int32_t destinationWidth = dstX1 - dstX0;
	int32_t destinationHeight = dstY1 - dstY0;
	if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) {
		return;
	}

	BlitSurface source;
	BlitSurface destination;
	if (!getBlitSurface(sourceTexture, depthStencil != 0, source)) {
		setError("The source of the blit is not a valid surface");
		return;
	}
	if (!getBlitSurface(destinationTexture, depthStencil != 0, destination)) {
		setError("The destination of the blit is not a valid surface");
		releaseBlitSurface(source);
		return;
	}

	bool sameSize = sourceWidth == destinationWidth && sourceHeight == destinationHeight;

	if (depthStencil != 0 || sameSize || source.shaderResourceView == NULL || destination.renderTargetView == NULL) {
		/*
		 * A plain copy. It is the only option for a depth/stencil buffer, which
		 * a pixel shader cannot write, and it is also the fastest path when
		 * nothing has to be rescaled.
		 */
		if (!sameSize) {
			setError("A rescaling blit needs a sampleable source and a render target destination");
			releaseBlitSurface(source);
			releaseBlitSurface(destination);
			return;
		}

		/* The destination is very likely the current render target, and
		 * Direct3D refuses to copy into a bound resource. */
		ctx->deviceContext->OMSetRenderTargets(0, NULL, NULL);

		D3D11_BOX box;
		box.left = srcX0;
		box.right = srcX1;
		box.top = srcY0;
		box.bottom = srcY1;
		box.front = 0;
		box.back = 1;
		ctx->deviceContext->CopySubresourceRegion(destination.resource, 0, dstX0, dstY0, 0, source.resource, 0, &box);

		ctx->deviceContext->OMSetRenderTargets(1, &ctx->currentRenderTargetView, ctx->currentDepthStencilView);

		releaseBlitSurface(source);
		releaseBlitSurface(destination);
		return;
	}

	/* A rescaling copy: draw the source as a full screen triangle */
	if (!initBlitPipeline()) {
		releaseBlitSurface(source);
		releaseBlitSurface(destination);
		return;
	}

	D3D11_TEXTURE2D_DESC sourceDesc;
	ZeroMemory(&sourceDesc, sizeof(sourceDesc));
	{
		ID3D11Texture2D *sourceTexture2D = NULL;
		if (SUCCEEDED(source.resource->QueryInterface(__uuidof(ID3D11Texture2D), (void **) &sourceTexture2D))) {
			sourceTexture2D->GetDesc(&sourceDesc);
			release(sourceTexture2D);
		}
	}
	if (sourceDesc.Width == 0 || sourceDesc.Height == 0) {
		setError("Cannot determine the size of the blit source");
		releaseBlitSurface(source);
		releaseBlitSurface(destination);
		return;
	}

	float constants[4];
	constants[0] = (float) sourceWidth / (float) sourceDesc.Width;
	constants[1] = (float) sourceHeight / (float) sourceDesc.Height;
	constants[2] = (float) srcX0 / (float) sourceDesc.Width;
	constants[3] = (float) srcY0 / (float) sourceDesc.Height;

	D3D11_MAPPED_SUBRESOURCE mapped;
	if (SUCCEEDED(ctx->deviceContext->Map(ctx->blit.constantBuffer, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
		memcpy(mapped.pData, constants, sizeof(constants));
		ctx->deviceContext->Unmap(ctx->blit.constantBuffer, 0);
	}

	ID3D11ShaderResourceView *nullShaderResourceView = NULL;
	ID3D11SamplerState *sampler = linearFilter != 0 ? ctx->blit.linearSampler : ctx->blit.pointSampler;
	const float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };

	ctx->deviceContext->OMSetRenderTargets(1, &destination.renderTargetView, NULL);

	D3D11_VIEWPORT viewport;
	viewport.TopLeftX = (float) dstX0;
	viewport.TopLeftY = (float) dstY0;
	viewport.Width = (float) destinationWidth;
	viewport.Height = (float) destinationHeight;
	viewport.MinDepth = 0.0f;
	viewport.MaxDepth = 1.0f;
	ctx->deviceContext->RSSetViewports(1, &viewport);

	ctx->deviceContext->OMSetBlendState(ctx->blit.blendState, blendFactor, 0xFFFFFFFF);
	ctx->deviceContext->OMSetDepthStencilState(ctx->blit.depthStencilState, 0);
	ctx->deviceContext->RSSetState(ctx->blit.rasterizerState);
	ctx->deviceContext->IASetInputLayout(NULL);
	ctx->deviceContext->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
	ctx->deviceContext->VSSetShader(ctx->blit.vertexShader, NULL, 0);
	ctx->deviceContext->PSSetShader(ctx->blit.pixelShader, NULL, 0);
	ctx->deviceContext->GSSetShader(NULL, NULL, 0);
	ctx->deviceContext->HSSetShader(NULL, NULL, 0);
	ctx->deviceContext->DSSetShader(NULL, NULL, 0);
	ctx->deviceContext->VSSetConstantBuffers(0, 1, &ctx->blit.constantBuffer);
	ctx->deviceContext->PSSetShaderResources(0, 1, &source.shaderResourceView);
	ctx->deviceContext->PSSetSamplers(0, 1, &sampler);

	ctx->deviceContext->Draw(3, 0);

	/* Unbind the source, it may be bound as a render target again right after */
	ctx->deviceContext->PSSetShaderResources(0, 1, &nullShaderResourceView);
	ctx->deviceContext->OMSetRenderTargets(1, &ctx->currentRenderTargetView, ctx->currentDepthStencilView);

	/* The caller re-pushes the whole pipeline state, but the topology is cached
	 * on this side and has just been changed behind its back. */
	ctx->currentTopology = D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED;

	releaseBlitSurface(source);
	releaseBlitSurface(destination);
}

/*
 * Pipeline states
 */

void dx11SetBlendState(int32_t enabled, int32_t srcRGB, int32_t dstRGB, int32_t opRGB, int32_t srcAlpha, int32_t dstAlpha, int32_t opAlpha, int32_t writeMask, float red, float green, float blue, float alpha) {
	if (!hasDevice()) {
		return;
	}

	const int values[] = { enabled, srcRGB, dstRGB, opRGB, srcAlpha, dstAlpha, opAlpha, writeMask };
	std::string key = makeKey(values, ARRAYSIZE(values));

	ID3D11BlendState *state = NULL;
	std::map<std::string, ID3D11BlendState *>::iterator it = ctx->blendStates.find(key);
	if (it != ctx->blendStates.end()) {
		state = it->second;
	} else {
		D3D11_BLEND_DESC desc;
		ZeroMemory(&desc, sizeof(desc));
		desc.RenderTarget[0].BlendEnable = enabled != 0;
		desc.RenderTarget[0].SrcBlend = (D3D11_BLEND) srcRGB;
		desc.RenderTarget[0].DestBlend = (D3D11_BLEND) dstRGB;
		desc.RenderTarget[0].BlendOp = (D3D11_BLEND_OP) opRGB;
		desc.RenderTarget[0].SrcBlendAlpha = (D3D11_BLEND) srcAlpha;
		desc.RenderTarget[0].DestBlendAlpha = (D3D11_BLEND) dstAlpha;
		desc.RenderTarget[0].BlendOpAlpha = (D3D11_BLEND_OP) opAlpha;
		desc.RenderTarget[0].RenderTargetWriteMask = (UINT8) writeMask;

		HRESULT hr = ctx->device->CreateBlendState(&desc, &state);
		if (FAILED(hr)) {
			setError("Cannot create a blend state", hr);
			return;
		}
		ctx->blendStates[key] = state;
	}

	const float blendFactor[4] = { red, green, blue, alpha };
	ctx->deviceContext->OMSetBlendState(state, blendFactor, 0xFFFFFFFF);
}

void dx11SetDepthStencilState(int32_t depthEnabled, int32_t depthWrite, int32_t depthFunc, int32_t stencilEnabled, int32_t stencilReadMask, int32_t stencilWriteMask, int32_t stencilFunc, int32_t stencilRef, int32_t failOp, int32_t depthFailOp, int32_t passOp) {
	if (!hasDevice()) {
		return;
	}

	const int values[] = { depthEnabled, depthWrite, depthFunc, stencilEnabled, stencilReadMask, stencilWriteMask, stencilFunc, failOp, depthFailOp, passOp };
	std::string key = makeKey(values, ARRAYSIZE(values));

	ID3D11DepthStencilState *state = NULL;
	std::map<std::string, ID3D11DepthStencilState *>::iterator it = ctx->depthStencilStates.find(key);
	if (it != ctx->depthStencilStates.end()) {
		state = it->second;
	} else {
		D3D11_DEPTH_STENCIL_DESC desc;
		ZeroMemory(&desc, sizeof(desc));
		desc.DepthEnable = depthEnabled != 0;
		desc.DepthWriteMask = depthWrite != 0 ? D3D11_DEPTH_WRITE_MASK_ALL : D3D11_DEPTH_WRITE_MASK_ZERO;
		desc.DepthFunc = (D3D11_COMPARISON_FUNC) depthFunc;
		desc.StencilEnable = stencilEnabled != 0;
		desc.StencilReadMask = (UINT8) stencilReadMask;
		desc.StencilWriteMask = (UINT8) stencilWriteMask;
		desc.FrontFace.StencilFunc = (D3D11_COMPARISON_FUNC) stencilFunc;
		desc.FrontFace.StencilFailOp = (D3D11_STENCIL_OP) failOp;
		desc.FrontFace.StencilDepthFailOp = (D3D11_STENCIL_OP) depthFailOp;
		desc.FrontFace.StencilPassOp = (D3D11_STENCIL_OP) passOp;
		desc.BackFace = desc.FrontFace;

		HRESULT hr = ctx->device->CreateDepthStencilState(&desc, &state);
		if (FAILED(hr)) {
			setError("Cannot create a depth/stencil state", hr);
			return;
		}
		ctx->depthStencilStates[key] = state;
	}

	ctx->deviceContext->OMSetDepthStencilState(state, (UINT) stencilRef);
}

void dx11SetRasterizerState(int32_t cullMode, int32_t frontCounterClockwise, int32_t fillMode, int32_t scissorEnabled, int32_t depthClipEnabled, int32_t antialiasedLine) {
	if (!hasDevice()) {
		return;
	}

	const int values[] = { cullMode, frontCounterClockwise, fillMode, scissorEnabled, depthClipEnabled, antialiasedLine };
	std::string key = makeKey(values, ARRAYSIZE(values));

	ID3D11RasterizerState *state = NULL;
	std::map<std::string, ID3D11RasterizerState *>::iterator it = ctx->rasterizerStates.find(key);
	if (it != ctx->rasterizerStates.end()) {
		state = it->second;
	} else {
		D3D11_RASTERIZER_DESC desc;
		ZeroMemory(&desc, sizeof(desc));
		desc.CullMode = (D3D11_CULL_MODE) cullMode;
		desc.FrontCounterClockwise = frontCounterClockwise != 0;
		desc.FillMode = (D3D11_FILL_MODE) fillMode;
		desc.ScissorEnable = scissorEnabled != 0;
		desc.DepthClipEnable = depthClipEnabled != 0;
		desc.AntialiasedLineEnable = antialiasedLine != 0;

		HRESULT hr = ctx->device->CreateRasterizerState(&desc, &state);
		if (FAILED(hr)) {
			setError("Cannot create a rasterizer state", hr);
			return;
		}
		ctx->rasterizerStates[key] = state;
	}

	ctx->deviceContext->RSSetState(state);
}

void dx11SetSamplerState(int32_t slot, int32_t filter, int32_t addressU, int32_t addressV, float minLod, float maxLod, float mipLodBias, int32_t maxAnisotropy) {
	if (!hasDevice()) {
		return;
	}

	char text[128];
	_snprintf_s(text, sizeof(text), _TRUNCATE, "%d,%d,%d,%f,%f,%f,%d", filter, addressU, addressV, minLod, maxLod, mipLodBias, maxAnisotropy);
	std::string key = text;

	ID3D11SamplerState *state = NULL;
	std::map<std::string, ID3D11SamplerState *>::iterator it = ctx->samplerStates.find(key);
	if (it != ctx->samplerStates.end()) {
		state = it->second;
	} else {
		D3D11_SAMPLER_DESC desc;
		ZeroMemory(&desc, sizeof(desc));
		desc.Filter = (D3D11_FILTER) filter;
		desc.AddressU = (D3D11_TEXTURE_ADDRESS_MODE) addressU;
		desc.AddressV = (D3D11_TEXTURE_ADDRESS_MODE) addressV;
		desc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
		desc.MinLOD = minLod;
		desc.MaxLOD = maxLod;
		desc.MipLODBias = mipLodBias;
		desc.MaxAnisotropy = maxAnisotropy < 1 ? 1 : maxAnisotropy;
		desc.ComparisonFunc = D3D11_COMPARISON_NEVER;

		HRESULT hr = ctx->device->CreateSamplerState(&desc, &state);
		if (FAILED(hr)) {
			setError("Cannot create a sampler state", hr);
			return;
		}
		ctx->samplerStates[key] = state;
	}

	ctx->deviceContext->PSSetSamplers(slot, 1, &state);
	ctx->deviceContext->VSSetSamplers(slot, 1, &state);
}

void dx11SetViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
	if (!hasDevice()) {
		return;
	}

	D3D11_VIEWPORT viewport;
	viewport.TopLeftX = x;
	viewport.TopLeftY = y;
	viewport.Width = width;
	viewport.Height = height;
	viewport.MinDepth = minDepth;
	viewport.MaxDepth = maxDepth;
	ctx->deviceContext->RSSetViewports(1, &viewport);
}

void dx11SetScissor(int32_t left, int32_t top, int32_t right, int32_t bottom) {
	if (!hasDevice()) {
		return;
	}

	D3D11_RECT rect;
	rect.left = left;
	rect.top = top;
	rect.right = right;
	rect.bottom = bottom;
	ctx->deviceContext->RSSetScissorRects(1, &rect);
}

/*
 * Shaders and programs
 */

int32_t dx11CompileShader(int32_t stage, const char *hlslSource, const char *entryPoint) {
	if (!hasDevice() || hlslSource == NULL) {
		return 0;
	}

	static const char *targets[] = { "vs_5_0", "ps_5_0", "gs_5_0", "hs_5_0", "ds_5_0", "cs_5_0" };
	if (stage < 0 || stage >= (int32_t) ARRAYSIZE(targets)) {
		setError("Unknown shader stage");
		return 0;
	}

	ID3DBlob *bytecode = NULL;
	ID3DBlob *errors = NULL;
	UINT compileFlags = D3DCOMPILE_OPTIMIZATION_LEVEL3;
	HRESULT hr = D3DCompile(hlslSource, strlen(hlslSource), NULL, NULL, NULL,
			entryPoint == NULL ? "main" : entryPoint, targets[stage], compileFlags, 0, &bytecode, &errors);

	std::string log;
	if (errors != NULL) {
		log.assign((const char *) errors->GetBufferPointer(), errors->GetBufferSize());
		release(errors);
	}

	if (FAILED(hr)) {
		setError(log.empty() ? "Cannot compile a shader" : log.c_str(), hr);
		release(bytecode);
		return 0;
	}

	ID3D11DeviceChild *shader = NULL;
	const void *code = bytecode->GetBufferPointer();
	SIZE_T codeSize = bytecode->GetBufferSize();
	switch (stage) {
		case DX11_STAGE_VERTEX:   hr = ctx->device->CreateVertexShader(code, codeSize, NULL, (ID3D11VertexShader **) &shader); break;
		case DX11_STAGE_PIXEL:    hr = ctx->device->CreatePixelShader(code, codeSize, NULL, (ID3D11PixelShader **) &shader); break;
		case DX11_STAGE_GEOMETRY: hr = ctx->device->CreateGeometryShader(code, codeSize, NULL, (ID3D11GeometryShader **) &shader); break;
		case DX11_STAGE_HULL:     hr = ctx->device->CreateHullShader(code, codeSize, NULL, (ID3D11HullShader **) &shader); break;
		case DX11_STAGE_DOMAIN:   hr = ctx->device->CreateDomainShader(code, codeSize, NULL, (ID3D11DomainShader **) &shader); break;
		default:                  hr = ctx->device->CreateComputeShader(code, codeSize, NULL, (ID3D11ComputeShader **) &shader); break;
	}

	if (FAILED(hr)) {
		setError("Cannot create a shader object", hr);
		release(bytecode);
		return 0;
	}

	ShaderResource *resource = new ShaderResource();
	resource->stage = stage;
	resource->bytecode = bytecode;
	resource->shader = shader;
	resource->log = log;

	int handle = allocateHandle();
	ctx->shaders[handle] = resource;

	return handle;
}

void dx11DeleteShader(int32_t shader) {
	if (ctx == NULL) {
		return;
	}

	ShaderResource *resource = find(ctx->shaders, shader);
	if (resource == NULL) {
		return;
	}

	release(resource->bytecode);
	release(resource->shader);
	delete resource;
	ctx->shaders.erase(shader);
}

const char *dx11GetShaderLog(int32_t shader) {
	ShaderResource *resource = ctx == NULL ? NULL : find(ctx->shaders, shader);

	return resource == NULL ? "" : resource->log.c_str();
}

int32_t dx11CreateProgram(void) {
	if (ctx == NULL) {
		return 0;
	}

	ProgramResource *resource = new ProgramResource();
	for (int i = 0; i < 6; i++) {
		resource->stages[i] = NULL;
		resource->constantBuffer[i] = NULL;
		resource->constantDataDirty[i] = false;
	}
	resource->inputLayout = NULL;

	int handle = allocateHandle();
	ctx->programs[handle] = resource;

	return handle;
}

void dx11DeleteProgram(int32_t program) {
	if (ctx == NULL) {
		return;
	}

	ProgramResource *resource = find(ctx->programs, program);
	if (resource == NULL) {
		return;
	}

	if (ctx->currentProgram == resource) {
		ctx->currentProgram = NULL;
	}

	for (int stage = 0; stage < 6; stage++) {
		release(resource->constantBuffer[stage]);
	}
	delete resource;
	ctx->programs.erase(program);
}

void dx11AttachShader(int32_t program, int32_t shader) {
	if (ctx == NULL) {
		return;
	}

	ProgramResource *programResource = find(ctx->programs, program);
	ShaderResource *shaderResource = find(ctx->shaders, shader);
	if (programResource == NULL || shaderResource == NULL) {
		return;
	}

	if (shaderResource->stage >= 0 && shaderResource->stage < 6) {
		programResource->stages[shaderResource->stage] = shaderResource;
	}
}

int32_t dx11LinkProgram(int32_t program) {
	if (!hasDevice()) {
		return 0;
	}

	ProgramResource *resource = find(ctx->programs, program);
	if (resource == NULL) {
		return 0;
	}

	if (resource->stages[DX11_STAGE_VERTEX] == NULL) {
		resource->log = "The program has no vertex shader";
		return 0;
	}

	/* Direct3D 11 has no link step: the "linking" reflects the uniforms of all
	 * the attached shaders into one shared constant buffer. */
	resource->uniformIndices.clear();
	resource->uniforms.clear();
	for (int stage = 0; stage < 6; stage++) {
		resource->constantData[stage].clear();
		release(resource->constantBuffer[stage]);
	}

	for (int stage = 0; stage < 6; stage++) {
		reflectUniforms(resource, resource->stages[stage]);
		resource->constantDataDirty[stage] = true;
	}

	resource->log.clear();

	return 1;
}

void dx11UseProgram(int32_t program) {
	if (!hasDevice()) {
		return;
	}

	ProgramResource *resource = find(ctx->programs, program);
	ctx->currentProgram = resource;

	if (resource == NULL) {
		ctx->deviceContext->VSSetShader(NULL, NULL, 0);
		ctx->deviceContext->PSSetShader(NULL, NULL, 0);
		ctx->deviceContext->GSSetShader(NULL, NULL, 0);
		ctx->deviceContext->HSSetShader(NULL, NULL, 0);
		ctx->deviceContext->DSSetShader(NULL, NULL, 0);
		return;
	}

	bindProgramShaders(resource);
	if (resource->inputLayout != NULL) {
		ctx->deviceContext->IASetInputLayout(resource->inputLayout);
	}
}

const char *dx11GetProgramLog(int32_t program) {
	ProgramResource *resource = ctx == NULL ? NULL : find(ctx->programs, program);

	return resource == NULL ? "" : resource->log.c_str();
}

int32_t dx11GetUniformLocation(int32_t program, const char *name) {
	if (ctx == NULL || name == NULL) {
		return -1;
	}

	ProgramResource *resource = find(ctx->programs, program);
	if (resource == NULL) {
		return -1;
	}

	std::map<std::string, int>::iterator it = resource->uniformIndices.find(name);

	return it == resource->uniformIndices.end() ? -1 : it->second;
}

namespace {

/*
 * Write "count" components into the shadow copy of the constant buffer.
 *
 * The HLSL packing rules apply: every array element and every matrix column
 * starts on a 16 byte boundary, so the values cannot simply be memcpy'ed.
 */
void writeUniform(ProgramResource *program, int32_t location, int32_t count, const void *values, size_t componentSize) {
	if (program == NULL || location < 0 || location >= (int32_t) program->uniforms.size() || values == NULL || count <= 0) {
		return;
	}

	const UniformInfo &uniform = program->uniforms[location];
	const unsigned char *source = (const unsigned char *) values;

	for (size_t s = 0; s < uniform.stages.size(); s++) {
		const UniformStageInfo &info = uniform.stages[s];
		std::vector<unsigned char> &data = program->constantData[info.stage];
		UINT components = info.componentsPerElement > 0 ? info.componentsPerElement : 1;

		UINT written = 0;
		for (UINT element = 0; element < info.elementCount && written < (UINT) count; element++) {
			size_t offset = info.offset + element * info.elementStride;
			UINT elementComponents = components;
			if (written + elementComponents > (UINT) count) {
				elementComponents = (UINT) count - written;
			}
			if (offset + elementComponents * componentSize > data.size()) {
				break;
			}
			memcpy(&data[offset], source + written * componentSize, elementComponents * componentSize);
			written += elementComponents;
		}

		program->constantDataDirty[info.stage] = true;
	}
}

} /* anonymous namespace */

void dx11SetUniformInt(int32_t program, int32_t location, int32_t count, const int32_t *values) {
	writeUniform(ctx == NULL ? NULL : find(ctx->programs, program), location, count, values, sizeof(int32_t));
}

void dx11SetUniformFloat(int32_t program, int32_t location, int32_t count, const float *values) {
	writeUniform(ctx == NULL ? NULL : find(ctx->programs, program), location, count, values, sizeof(float));
}

void dx11SetUniformMatrix(int32_t program, int32_t location, int32_t order, int32_t count, const float *values) {
	ProgramResource *resource = ctx == NULL ? NULL : find(ctx->programs, program);
	if (resource == NULL || location < 0 || location >= (int32_t) resource->uniforms.size() || values == NULL) {
		return;
	}

	const UniformInfo &uniform = resource->uniforms[location];

	for (size_t s = 0; s < uniform.stages.size(); s++) {
		const UniformStageInfo &info = uniform.stages[s];
		std::vector<unsigned char> &data = resource->constantData[info.stage];

		/* A GLSL matrix is column major and tightly packed, an HLSL column_major
		 * matrix pads every column to 16 bytes: copy the columns one by one. */
		for (int32_t matrix = 0; matrix < count && (UINT) matrix < info.elementCount; matrix++) {
			size_t base = info.offset + matrix * info.elementStride;
			for (int32_t column = 0; column < order; column++) {
				size_t offset = base + column * 16;
				if (offset + order * sizeof(float) > data.size()) {
					break;
				}
				memcpy(&data[offset], values + (matrix * order + column) * order, order * sizeof(float));
			}
		}

		resource->constantDataDirty[info.stage] = true;
	}
}

/*
 * Input layout
 */

void dx11BeginInputLayout(void) {
	if (ctx != NULL) {
		ctx->pendingInputElements.clear();
	}
}

void dx11AddInputElement(int32_t location, int32_t size, int32_t type, int32_t normalized, int32_t stride, int32_t offset) {
	if (ctx == NULL) {
		return;
	}

	InputElement element;
	element.location = location;
	element.size = size;
	element.type = type;
	element.normalized = normalized;
	element.stride = stride;
	element.offset = offset;
	ctx->pendingInputElements.push_back(element);
}

void dx11EndInputLayout(int32_t program) {
	if (!hasDevice()) {
		return;
	}

	ProgramResource *resource = find(ctx->programs, program);
	if (resource == NULL || resource->stages[DX11_STAGE_VERTEX] == NULL) {
		return;
	}

	char text[128];
	std::string key;
	_snprintf_s(text, sizeof(text), _TRUNCATE, "p%d:", program);
	key = text;
	for (size_t i = 0; i < ctx->pendingInputElements.size(); i++) {
		const InputElement &element = ctx->pendingInputElements[i];
		_snprintf_s(text, sizeof(text), _TRUNCATE, "%d/%d/%d/%d/%d;", element.location, element.size, element.type, element.normalized, element.offset);
		key += text;
	}

	if (resource->inputLayout != NULL && resource->inputLayoutKey == key) {
		ctx->deviceContext->IASetInputLayout(resource->inputLayout);
		return;
	}

	ID3D11InputLayout *inputLayout = NULL;
	std::map<std::string, ID3D11InputLayout *>::iterator it = ctx->inputLayouts.find(key);
	if (it != ctx->inputLayouts.end()) {
		inputLayout = it->second;
	} else {
		std::vector<D3D11_INPUT_ELEMENT_DESC> elements;
		for (size_t i = 0; i < ctx->pendingInputElements.size(); i++) {
			const InputElement &element = ctx->pendingInputElements[i];
			D3D11_INPUT_ELEMENT_DESC desc;
			ZeroMemory(&desc, sizeof(desc));
			/* The shader translator gives every vertex shader input a
			 * TEXCOORD semantic whose index is its GLSL location. */
			desc.SemanticName = "TEXCOORD";
			desc.SemanticIndex = element.location;
			desc.Format = getAttributeFormat(element.size, element.type, element.normalized != 0);
			desc.InputSlot = 0;
			desc.AlignedByteOffset = element.offset;
			desc.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
			elements.push_back(desc);
		}

		if (elements.empty()) {
			return;
		}

		ID3DBlob *bytecode = resource->stages[DX11_STAGE_VERTEX]->bytecode;
		HRESULT hr = ctx->device->CreateInputLayout(&elements[0], (UINT) elements.size(), bytecode->GetBufferPointer(), bytecode->GetBufferSize(), &inputLayout);
		if (FAILED(hr)) {
			setError("Cannot create the input layout", hr);
			return;
		}
		ctx->inputLayouts[key] = inputLayout;
	}

	resource->inputLayout = inputLayout;
	resource->inputLayoutKey = key;
	ctx->deviceContext->IASetInputLayout(inputLayout);
}

/*
 * Drawing
 */

void dx11Clear(int32_t mask, float red, float green, float blue, float alpha, float depth, int32_t stencil) {
	if (!hasDevice()) {
		return;
	}

	if ((mask & DX11_CLEAR_COLOR) != 0 && ctx->currentRenderTargetView != NULL) {
		const float color[4] = { red, green, blue, alpha };
		ctx->deviceContext->ClearRenderTargetView(ctx->currentRenderTargetView, color);
	}

	UINT depthStencilFlags = 0;
	if ((mask & DX11_CLEAR_DEPTH) != 0) {
		depthStencilFlags |= D3D11_CLEAR_DEPTH;
	}
	if ((mask & DX11_CLEAR_STENCIL) != 0) {
		depthStencilFlags |= D3D11_CLEAR_STENCIL;
	}
	if (depthStencilFlags != 0 && ctx->currentDepthStencilView != NULL) {
		ctx->deviceContext->ClearDepthStencilView(ctx->currentDepthStencilView, depthStencilFlags, depth, (UINT8) stencil);
	}
}

void dx11Draw(int32_t topology, int32_t first, int32_t count) {
	if (!hasDevice() || count <= 0) {
		return;
	}

	prepareDraw((D3D11_PRIMITIVE_TOPOLOGY) topology);
	ctx->deviceContext->Draw(count, first);
}

void dx11DrawIndexed(int32_t topology, int32_t count, int32_t indexFormat, int32_t offset) {
	if (!hasDevice() || count <= 0) {
		return;
	}

	prepareDraw((D3D11_PRIMITIVE_TOPOLOGY) topology);
	UINT indexSize = indexFormat == DXGI_FORMAT_R16_UINT ? 2 : 4;
	ctx->deviceContext->DrawIndexed(count, offset / indexSize, 0);
}

void dx11Flush(void) {
	if (hasDevice()) {
		ctx->deviceContext->Flush();
	}
}

void dx11Finish(void) {
	if (!hasDevice()) {
		return;
	}

	/* Direct3D 11 has no glFinish(): an event query signalled at the end of the
	 * command list is the documented equivalent. */
	D3D11_QUERY_DESC desc;
	ZeroMemory(&desc, sizeof(desc));
	desc.Query = D3D11_QUERY_EVENT;

	ID3D11Query *query = NULL;
	if (FAILED(ctx->device->CreateQuery(&desc, &query))) {
		ctx->deviceContext->Flush();
		return;
	}

	ctx->deviceContext->End(query);
	ctx->deviceContext->Flush();
	while (ctx->deviceContext->GetData(query, NULL, 0, 0) == S_FALSE) {
		Sleep(0);
	}
	release(query);
}

/*
 * Occlusion queries
 */

int32_t dx11CreateQuery(void) {
	if (!hasDevice()) {
		return 0;
	}

	D3D11_QUERY_DESC desc;
	ZeroMemory(&desc, sizeof(desc));
	desc.Query = D3D11_QUERY_OCCLUSION;

	ID3D11Query *query = NULL;
	HRESULT hr = ctx->device->CreateQuery(&desc, &query);
	if (FAILED(hr)) {
		setError("Cannot create an occlusion query", hr);
		return 0;
	}

	QueryResource *resource = new QueryResource();
	resource->query = query;
	resource->result = 0;
	resource->resultRead = false;

	int handle = allocateHandle();
	ctx->queries[handle] = resource;

	return handle;
}

void dx11DeleteQuery(int32_t query) {
	if (ctx == NULL) {
		return;
	}

	QueryResource *resource = find(ctx->queries, query);
	if (resource == NULL) {
		return;
	}

	release(resource->query);
	delete resource;
	ctx->queries.erase(query);
}

void dx11BeginQuery(int32_t query) {
	if (!hasDevice()) {
		return;
	}

	QueryResource *resource = find(ctx->queries, query);
	if (resource == NULL) {
		return;
	}

	resource->resultRead = false;
	ctx->currentQuery = query;
	ctx->deviceContext->Begin(resource->query);
}

void dx11EndQuery(int32_t query) {
	if (!hasDevice()) {
		return;
	}

	QueryResource *resource = find(ctx->queries, query == 0 ? ctx->currentQuery : query);
	if (resource == NULL) {
		return;
	}

	ctx->deviceContext->End(resource->query);
	ctx->currentQuery = 0;
}

int32_t dx11IsQueryResultAvailable(int32_t query) {
	if (!hasDevice()) {
		return 0;
	}

	QueryResource *resource = find(ctx->queries, query);
	if (resource == NULL) {
		return 0;
	}

	if (resource->resultRead) {
		return 1;
	}

	UINT64 result = 0;
	if (ctx->deviceContext->GetData(resource->query, &result, sizeof(result), 0) == S_OK) {
		resource->result = result;
		resource->resultRead = true;
		return 1;
	}

	return 0;
}

int32_t dx11GetQueryResult(int32_t query) {
	if (!hasDevice()) {
		return 0;
	}

	QueryResource *resource = find(ctx->queries, query);
	if (resource == NULL) {
		return 0;
	}

	if (!resource->resultRead) {
		UINT64 result = 0;
		while (ctx->deviceContext->GetData(resource->query, &result, sizeof(result), 0) == S_FALSE) {
			Sleep(0);
		}
		resource->result = result;
		resource->resultRead = true;
	}

	return (int32_t) resource->result;
}

} /* extern "C" */
