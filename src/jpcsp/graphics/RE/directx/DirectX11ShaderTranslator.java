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

import static jpcsp.graphics.RE.IRenderingEngine.RE_FRAGMENT_SHADER;
import static jpcsp.graphics.RE.IRenderingEngine.RE_GEOMETRY_SHADER;
import static jpcsp.graphics.RE.IRenderingEngine.RE_TESS_CONTROL_SHADER;
import static jpcsp.graphics.RE.IRenderingEngine.RE_TESS_EVALUATION_SHADER;
import static jpcsp.graphics.RE.IRenderingEngine.RE_VERTEX_SHADER;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author gid15
 *
 * Translate the GLSL shaders used by jpcsp into the HLSL accepted by the
 * Direct3D 11 shader compiler.
 *
 * The translation is a source to source rewrite, not a full GLSL compiler: it
 * covers the GLSL dialect actually used by the jpcsp shaders (shader.vert,
 * shader.frag, shader.geom and shader.tese) and reports everything it does not
 * understand instead of silently producing a broken shader.
 *
 * The overall shape of the generated HLSL is:
 * <pre>
 *   cbuffer JpcspUniforms : register(b0) { ...the GLSL uniforms... };
 *   Texture2D    tex_texture : register(t0);
 *   SamplerState tex_sampler : register(s0);
 *   struct ShaderInput  { ...the "in" variables, with TEXCOORDn semantics... };
 *   struct ShaderOutput { ...the "out" variables... };
 *   static float4 pspPosition;   // the GLSL variables kept as globals
 *   ...the translated GLSL code, with main() renamed to glslMain()...
 *   ShaderOutput main(ShaderInput input) {
 *       ...copy input into the globals...
 *       glslMain();
 *       ...copy the globals into the output...
 *   }
 * </pre>
 * Keeping the GLSL in/out variables as static globals means the body of the
 * shader does not have to be rewritten to reference structure members, which
 * removes a whole class of translation errors.
 */
public class DirectX11ShaderTranslator {
	public static final String entryPoint = "main";
	private static final String glslEntryPoint = "glslMain";

	// The GLSL types having a direct HLSL equivalent
	private static final String[][] typeReplacements = {
		{ "mat2x2", "float2x2" }, { "mat2x3", "float2x3" }, { "mat2x4", "float2x4" },
		{ "mat3x2", "float3x2" }, { "mat3x3", "float3x3" }, { "mat3x4", "float3x4" },
		{ "mat4x2", "float4x2" }, { "mat4x3", "float4x3" }, { "mat4x4", "float4x4" },
		{ "mat2", "float2x2" }, { "mat3", "float3x3" }, { "mat4", "float4x4" },
		{ "vec2", "float2" }, { "vec3", "float3" }, { "vec4", "float4" },
		{ "ivec2", "int2" }, { "ivec3", "int3" }, { "ivec4", "int4" },
		{ "uvec2", "uint2" }, { "uvec3", "uint3" }, { "uvec4", "uint4" },
		{ "bvec2", "bool2" }, { "bvec3", "bool3" }, { "bvec4", "bool4" }
	};

	// The GLSL intrinsics having a differently named HLSL equivalent
	private static final String[][] functionReplacements = {
		{ "mix", "lerp" },
		{ "fract", "frac" },
		{ "inversesqrt", "rsqrt" },
		{ "dFdx", "ddx" },
		{ "dFdy", "ddy" },
		{ "fwidth", "fwidth" },
		{ "atan", "atan2NotUnary" }, // handled below, atan(y,x) only
		{ "equal", "jpcspEqual" },
		{ "notEqual", "jpcspNotEqual" },
		{ "lessThan", "jpcspLessThan" },
		{ "lessThanEqual", "jpcspLessThanEqual" },
		{ "greaterThan", "jpcspGreaterThan" },
		{ "greaterThanEqual", "jpcspGreaterThanEqual" }
	};

	// The GLSL matrix types, used to decide whether '*' has to become mul()
	private static final Set<String> matrixTypes = new HashSet<String>();
	static {
		matrixTypes.add("mat2");
		matrixTypes.add("mat3");
		matrixTypes.add("mat4");
		matrixTypes.add("mat2x2");
		matrixTypes.add("mat2x3");
		matrixTypes.add("mat2x4");
		matrixTypes.add("mat3x2");
		matrixTypes.add("mat3x3");
		matrixTypes.add("mat3x4");
		matrixTypes.add("mat4x2");
		matrixTypes.add("mat4x3");
		matrixTypes.add("mat4x4");
	}

	private static final String macroPrefix = "^\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\s*\\([^)]*\\)\\s*)?";
	private static final Pattern uniformPattern = Pattern.compile(macroPrefix + "uniform\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^\\]]*\\])?\\s*;");
	private static final Pattern varyingPattern = Pattern.compile(macroPrefix + "(?:(flat|smooth|noperspective|centroid)\\s+)?(in|out|attribute|varying)\\s+(?:(flat|smooth|noperspective|centroid)\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^\\]]*\\])?\\s*;");
	private static final Pattern layoutPattern = Pattern.compile("layout\\s*\\([^)]*\\)\\s*");
	// Both "layout(location=3) out vec4 x;" and the LOCATION(3) macro used by the jpcsp shaders
	private static final Pattern locationPattern = Pattern.compile("\\b(?:LOCATION|location)\\s*[=(]\\s*(\\d+)");
	private static final Pattern versionPattern = Pattern.compile("^\\s*#\\s*version\\s+(\\d+).*$");
	private static final Pattern extensionPattern = Pattern.compile("^\\s*#\\s*extension\\b.*$");
	private static final Pattern precisionPattern = Pattern.compile("^\\s*precision\\s+\\w+\\s+\\w+\\s*;\\s*$");
	private static final Pattern localMatrixPattern = Pattern.compile("\\b(mat[234](?:x[234])?)\\s+([A-Za-z_][A-Za-z0-9_]*)");

	/**
	 * The outcome of a translation.
	 */
	public static class TranslatedShader {
		public final String source;
		public final List<String> warnings;
		/**
		 * The location of each shader input, i.e. the index of the TEXCOORD
		 * semantic it has been given. It is the value returned by
		 * getAttribLocation() for a vertex shader.
		 */
		public final Map<String, Integer> attributeLocations;

		public TranslatedShader(String source, List<String> warnings, Map<String, Integer> attributeLocations) {
			this.source = source;
			this.warnings = warnings;
			this.attributeLocations = attributeLocations;
		}

		public boolean hasWarnings() {
			return !warnings.isEmpty();
		}

		public String getWarningsLog() {
			StringBuilder s = new StringBuilder();
			for (String warning : warnings) {
				s.append(warning).append('\n');
			}

			return s.toString();
		}
	}

	private static class Variable {
		public final String type;
		public final String name;
		public final String arraySuffix;
		public final String interpolation;
		/**
		 * The preprocessor directives enclosing the declaration, e.g.
		 * { "#if USE_DYNAMIC_DEFINES", "#else" }. They are re-emitted around
		 * everything generated for this variable so that a declaration living
		 * in a conditional branch stays in that branch.
		 */
		public final List<String> guards;
		/** The GLSL layout location, or -1 when the declaration has none */
		public final int location;

		public Variable(String type, String name, String arraySuffix, String interpolation, List<String> guards, int location) {
			this.type = type;
			this.name = name;
			this.arraySuffix = arraySuffix == null ? "" : arraySuffix;
			this.interpolation = interpolation;
			this.guards = guards;
			this.location = location;
		}
	}

	private final int stage;
	private final List<String> warnings = new LinkedList<String>();
	private final List<Variable> uniforms = new ArrayList<Variable>();
	private final List<Variable> inputs = new ArrayList<Variable>();
	private final List<Variable> outputs = new ArrayList<Variable>();
	private final Map<String, Integer> samplers = new HashMap<String, Integer>();
	private final Map<String, String> samplerTypes = new HashMap<String, String>();
	private final Set<String> matrixVariables = new HashSet<String>();
	private final Map<String, Integer> attributeLocations = new HashMap<String, Integer>();
	private List<String> openGuards;
	private int glslVersion = 110;
	private boolean usesFragCoord;
	private boolean usesFrontFacing;
	private boolean usesPointCoord;
	private boolean usesVertexID;
	private boolean usesInstanceID;
	private boolean usesFragDepth;
	private final Set<String> usedFragData = new HashSet<String>();

	public DirectX11ShaderTranslator(int stage) {
		this.stage = stage;
	}

	/**
	 * Translate a complete GLSL shader into HLSL.
	 *
	 * @param glslSource the GLSL source, as handed over by REShader
	 * @return the translated shader, together with the constructs that could not
	 *         be translated
	 */
	public TranslatedShader translate(String glslSource) {
		String source = removeComments(glslSource.replace("\r\n", "\n").replace('\r', '\n'));
		source = extractDeclarations(source);
		source = translateTypes(source);
		source = translateConstructors(source);
		source = translateBuiltins(source);
		source = translateFunctions(source);
		source = rewriteMatrixMultiplications(source);
		source = renameEntryPoint(source);

		StringBuilder hlsl = new StringBuilder();
		appendPrologue(hlsl);
		appendUniformBuffer(hlsl);
		appendSamplers(hlsl);
		appendStructures(hlsl);
		appendGlobals(hlsl);
		hlsl.append(source);
		appendEntryPoint(hlsl);

		return new TranslatedShader(hlsl.toString(), warnings, attributeLocations);
	}

	private void warn(String format, Object... arguments) {
		warnings.add(String.format(format, arguments));
	}

	/**
	 * Remove the GLSL comments, so that the later rewriting steps cannot be
	 * confused by code appearing inside a comment.
	 */
	private static String removeComments(String source) {
		StringBuilder s = new StringBuilder(source.length());
		int length = source.length();
		for (int i = 0; i < length; i++) {
			char c = source.charAt(i);
			if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
				while (i < length && source.charAt(i) != '\n') {
					i++;
				}
				s.append('\n');
			} else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < length && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
					if (source.charAt(i) == '\n') {
						s.append('\n');
					}
					i++;
				}
				i++;
			} else {
				s.append(c);
			}
		}

		return s.toString();
	}

	/**
	 * Move the global uniform, in and out declarations out of the shader body:
	 * they are re-emitted as a constant buffer and as input/output structures.
	 */
	private String extractDeclarations(String source) {
		StringBuilder body = new StringBuilder(source.length());
		int braceLevel = 0;
		// The preprocessor conditions currently open, one entry per nesting level
		List<List<String>> conditions = new ArrayList<List<String>>();

		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();

			Matcher versionMatcher = versionPattern.matcher(line);
			if (versionMatcher.matches()) {
				glslVersion = Integer.parseInt(versionMatcher.group(1));
				body.append('\n');
				continue;
			}

			if (extensionPattern.matcher(line).matches() || precisionPattern.matcher(line).matches()) {
				// Neither #extension nor precision qualifiers exist in HLSL
				body.append('\n');
				continue;
			}

			if (trimmed.startsWith("#")) {
				updateConditions(conditions, trimmed);
				// Keep the preprocessor directives, HLSL understands the same ones
				body.append(line).append('\n');
				continue;
			}

			String withoutLayout = layoutPattern.matcher(line).replaceAll("");

			if (braceLevel == 0) {
				Matcher uniformMatcher = uniformPattern.matcher(withoutLayout);
				if (uniformMatcher.find()) {
					addUniform(uniformMatcher.group(1), uniformMatcher.group(2), uniformMatcher.group(3), flatten(conditions));
					body.append('\n');
					continue;
				}

				Matcher varyingMatcher = varyingPattern.matcher(withoutLayout);
				if (varyingMatcher.find()) {
					String interpolation = varyingMatcher.group(1) != null ? varyingMatcher.group(1) : varyingMatcher.group(3);
					addVarying(varyingMatcher.group(2), varyingMatcher.group(4), varyingMatcher.group(5), varyingMatcher.group(6), interpolation, flatten(conditions), parseLocation(line));
					body.append('\n');
					continue;
				}
			}

			braceLevel += count(withoutLayout, '{') - count(withoutLayout, '}');
			body.append(withoutLayout).append('\n');
		}

		collectMatrixVariables(body.toString());

		return body.toString();
	}

	/**
	 * Follow the #if/#else/#endif nesting so that a declaration extracted from a
	 * conditional branch can be re-emitted inside the very same branch.
	 */
	private static void updateConditions(List<List<String>> conditions, String directive) {
		if (directive.startsWith("#if")) {
			List<String> level = new ArrayList<String>();
			level.add(directive);
			conditions.add(level);
		} else if (directive.startsWith("#elif") || directive.startsWith("#else")) {
			if (!conditions.isEmpty()) {
				conditions.get(conditions.size() - 1).add(directive);
			}
		} else if (directive.startsWith("#endif")) {
			if (!conditions.isEmpty()) {
				conditions.remove(conditions.size() - 1);
			}
		}
	}

	private static int parseLocation(String line) {
		Matcher matcher = locationPattern.matcher(line);
		if (matcher.find()) {
			return Integer.parseInt(matcher.group(1));
		}

		return -1;
	}

	private static List<String> flatten(List<List<String>> conditions) {
		if (conditions.isEmpty()) {
			return null;
		}

		List<String> guards = new ArrayList<String>();
		for (List<String> level : conditions) {
			guards.addAll(level);
		}

		return guards;
	}

	/**
	 * Open the preprocessor guards of a declaration, closing the previously
	 * opened ones first. Consecutive declarations sharing the same guards are
	 * emitted inside a single #if block.
	 */
	private void setGuards(StringBuilder hlsl, Variable variable) {
		List<String> guards = variable.guards;
		if (openGuards == null ? guards == null : openGuards.equals(guards)) {
			return;
		}

		closeGuards(hlsl);

		if (guards != null) {
			for (String guard : guards) {
				hlsl.append(guard).append('\n');
			}
			openGuards = guards;
		}
	}

	/**
	 * Close the currently opened preprocessor guards, if any.
	 */
	private void closeGuards(StringBuilder hlsl) {
		if (openGuards != null) {
			for (String guard : openGuards) {
				if (guard.startsWith("#if")) {
					hlsl.append("#endif\n");
				}
			}
			openGuards = null;
		}
	}

	private static int count(String s, char c) {
		int n = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) {
				n++;
			}
		}

		return n;
	}

	private void addUniform(String type, String name, String arraySuffix, List<String> guards) {
		if (type.endsWith("sampler2D") || type.endsWith("sampler2DRect") || type.endsWith("samplerBuffer")) {
			int slot = samplers.size();
			samplers.put(name, Integer.valueOf(slot));
			samplerTypes.put(name, type);
			return;
		}

		if (type.endsWith("sampler3D") || type.endsWith("samplerCube") || type.endsWith("sampler2DArray")) {
			warn("Unsupported sampler type '%s' for uniform '%s'", type, name);
			return;
		}

		if (matrixTypes.contains(type)) {
			matrixVariables.add(name);
		}

		uniforms.add(new Variable(type, name, arraySuffix, null, guards, -1));
	}

	private void addVarying(String direction, String type, String name, String arraySuffix, String interpolation, List<String> guards, int location) {
		boolean isInput = "in".equals(direction) || "attribute".equals(direction)
				|| ("varying".equals(direction) && stage != RE_VERTEX_SHADER);

		if (matrixTypes.contains(type)) {
			matrixVariables.add(name);
		}

		Variable variable = new Variable(type, name, arraySuffix, interpolation, guards, location);
		if (isInput) {
			inputs.add(variable);
		} else {
			outputs.add(variable);
		}
	}

	/**
	 * Remember every matrix typed local variable and function parameter: the
	 * '*' operator has to become mul() for those.
	 */
	private void collectMatrixVariables(String source) {
		Matcher matcher = localMatrixPattern.matcher(source);
		while (matcher.find()) {
			matrixVariables.add(matcher.group(2));
		}
	}

	private String translateTypes(String source) {
		for (String[] replacement : typeReplacements) {
			source = replaceIdentifier(source, replacement[0], replacement[1]);
		}

		return source;
	}

	/**
	 * GLSL allows a vector or matrix constructor to truncate its single
	 * argument, e.g. vec3(aVec4) or mat3(aMat4). HLSL rejects those
	 * constructors but accepts the equivalent cast, so rewrite
	 * "float3(expression)" into "(float3)(expression)".
	 */
	private static String translateConstructors(String source) {
		String[] castableTypes = {
			"float2", "float3", "float4",
			"int2", "int3", "int4",
			"uint2", "uint3", "uint4",
			"bool2", "bool3", "bool4",
			"float2x2", "float2x3", "float2x4",
			"float3x2", "float3x3", "float3x4",
			"float4x2", "float4x3", "float4x4",
			"float", "int", "uint", "bool"
		};

		for (String type : castableTypes) {
			StringBuilder s = new StringBuilder(source.length());
			int index = 0;
			while (true) {
				int found = indexOfIdentifier(source, type, index);
				if (found < 0) {
					s.append(source, index, source.length());
					break;
				}

				int openParen = skipSpaces(source, found + type.length());
				int closeParen = openParen < source.length() && source.charAt(openParen) == '(' ? findMatchingParen(source, openParen) : -1;
				if (closeParen < 0 || hasTopLevelComma(source, openParen + 1, closeParen)) {
					// A declaration or a real multi-argument constructor
					s.append(source, index, found + type.length());
					index = found + type.length();
					continue;
				}

				String argument = source.substring(openParen + 1, closeParen).trim();
				if (argument.isEmpty()) {
					s.append(source, index, found + type.length());
					index = found + type.length();
					continue;
				}

				s.append(source, index, found);
				s.append('(').append(type).append(")(").append(argument).append(')');
				index = closeParen + 1;
			}
			source = s.toString();
		}

		return source;
	}

	private String translateBuiltins(String source) {
		usesFragCoord = containsIdentifier(source, "gl_FragCoord");
		usesFrontFacing = containsIdentifier(source, "gl_FrontFacing");
		usesPointCoord = containsIdentifier(source, "gl_PointCoord");
		usesVertexID = containsIdentifier(source, "gl_VertexID");
		usesInstanceID = containsIdentifier(source, "gl_InstanceID");
		usesFragDepth = containsIdentifier(source, "gl_FragDepth");

		Matcher fragDataMatcher = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]").matcher(source);
		while (fragDataMatcher.find()) {
			usedFragData.add(fragDataMatcher.group(1));
		}
		source = fragDataMatcher.replaceAll("jpcsp_FragData$1");

		if (containsIdentifier(source, "gl_Vertex") || containsIdentifier(source, "gl_Normal")
				|| containsIdentifier(source, "gl_Color") || containsIdentifier(source, "gl_MultiTexCoord0")) {
			warn("The fixed-function GLSL attributes (gl_Vertex, gl_Normal, gl_Color...) have no Direct3D 11 equivalent");
		}

		if (containsIdentifier(source, "gl_ModelViewProjectionMatrix") || containsIdentifier(source, "gl_ModelViewMatrix")) {
			warn("The fixed-function GLSL matrices have no Direct3D 11 equivalent");
		}

		// The remaining built-ins keep their name and are declared as globals
		return source;
	}

	private String translateFunctions(String source) {
		// GLSL mod() and HLSL fmod() differ for negative operands,
		// use the GLSL definition explicitly
		source = replaceIdentifier(source, "mod", "jpcspMod");

		// texture sampling
		source = translateTextureCalls(source);

		for (String[] replacement : functionReplacements) {
			if ("atan".equals(replacement[0])) {
				source = translateAtan(source);
			} else {
				source = replaceIdentifier(source, replacement[0], replacement[1]);
			}
		}

		return source;
	}

	/**
	 * atan(y, x) becomes atan2(y, x) while the single argument atan(x) stays.
	 */
	private String translateAtan(String source) {
		StringBuilder s = new StringBuilder(source.length());
		int index = 0;
		while (true) {
			int found = indexOfIdentifier(source, "atan", index);
			if (found < 0) {
				s.append(source, index, source.length());
				break;
			}

			int openParen = skipSpaces(source, found + "atan".length());
			if (openParen >= source.length() || source.charAt(openParen) != '(') {
				s.append(source, index, found + "atan".length());
				index = found + "atan".length();
				continue;
			}

			int closeParen = findMatchingParen(source, openParen);
			if (closeParen < 0) {
				s.append(source, index, found + "atan".length());
				index = found + "atan".length();
				continue;
			}

			boolean twoArguments = hasTopLevelComma(source, openParen + 1, closeParen);
			s.append(source, index, found);
			s.append(twoArguments ? "atan2" : "atan");
			index = found + "atan".length();
		}

		return s.toString();
	}

	/**
	 * Turn the GLSL sampling calls into the Direct3D 11 method calls on the
	 * Texture2D object matching the sampler.
	 */
	private String translateTextureCalls(String source) {
		source = replaceTextureCall(source, "texture2DLod", "SampleLevel", 3);
		source = replaceTextureCall(source, "textureLod", "SampleLevel", 3);
		source = replaceTextureCall(source, "texture2DProj", "Sample", 2);
		source = replaceTextureCall(source, "texture2D", "Sample", 2);
		source = replaceTextureCall(source, "texture", "Sample", 2);
		source = replaceTexelFetch(source);

		return source;
	}

	private String replaceTextureCall(String source, String glslName, String hlslMethod, int argumentCount) {
		StringBuilder s = new StringBuilder(source.length());
		int index = 0;
		while (true) {
			int found = indexOfIdentifier(source, glslName, index);
			if (found < 0) {
				s.append(source, index, source.length());
				break;
			}

			int openParen = skipSpaces(source, found + glslName.length());
			if (openParen >= source.length() || source.charAt(openParen) != '(') {
				s.append(source, index, found + glslName.length());
				index = found + glslName.length();
				continue;
			}

			int closeParen = findMatchingParen(source, openParen);
			if (closeParen < 0) {
				s.append(source, index, found + glslName.length());
				index = found + glslName.length();
				continue;
			}

			List<String> arguments = splitArguments(source, openParen + 1, closeParen);
			if (arguments.size() != argumentCount) {
				s.append(source, index, found + glslName.length());
				index = found + glslName.length();
				continue;
			}

			String samplerName = arguments.get(0).trim();
			if (!samplers.containsKey(samplerName)) {
				warn("Sampling from the unknown sampler '%s'", samplerName);
				s.append(source, index, closeParen + 1);
				index = closeParen + 1;
				continue;
			}

			s.append(source, index, found);
			s.append(getTextureName(samplerName)).append('.').append(hlslMethod).append('(');
			s.append(getSamplerName(samplerName));
			for (int i = 1; i < arguments.size(); i++) {
				s.append(", ").append(arguments.get(i).trim());
			}
			s.append(')');
			index = closeParen + 1;
		}

		return s.toString();
	}

	/**
	 * texelFetch(sampler, ivec2(x, y), lod) becomes tex.Load(int3(x, y, lod)).
	 */
	private String replaceTexelFetch(String source) {
		StringBuilder s = new StringBuilder(source.length());
		int index = 0;
		while (true) {
			int found = indexOfIdentifier(source, "texelFetch", index);
			if (found < 0) {
				s.append(source, index, source.length());
				break;
			}

			int openParen = skipSpaces(source, found + "texelFetch".length());
			int closeParen = openParen < source.length() && source.charAt(openParen) == '(' ? findMatchingParen(source, openParen) : -1;
			if (closeParen < 0) {
				s.append(source, index, found + "texelFetch".length());
				index = found + "texelFetch".length();
				continue;
			}

			List<String> arguments = splitArguments(source, openParen + 1, closeParen);
			if (arguments.size() < 2) {
				s.append(source, index, closeParen + 1);
				index = closeParen + 1;
				continue;
			}

			String samplerName = arguments.get(0).trim();
			if (!samplers.containsKey(samplerName)) {
				warn("texelFetch from the unknown sampler '%s'", samplerName);
				s.append(source, index, closeParen + 1);
				index = closeParen + 1;
				continue;
			}

			String level = arguments.size() >= 3 ? arguments.get(2).trim() : "0";
			s.append(source, index, found);
			s.append(getTextureName(samplerName)).append(".Load(int3(").append(arguments.get(1).trim()).append(", ").append(level).append("))");
			index = closeParen + 1;
		}

		return s.toString();
	}

	/**
	 * In GLSL '*' between a matrix and a vector is a matrix product, in HLSL it
	 * is a component wise product: rewrite those into mul().
	 */
	private String rewriteMatrixMultiplications(String source) {
		StringBuilder s = new StringBuilder(source);

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '*') {
				continue;
			}
			// Skip *= and the '*' of a /* comment (comments are already removed)
			if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
				continue;
			}
			if (i > 0 && s.charAt(i - 1) == '*') {
				continue;
			}

			int leftEnd = skipSpacesBackward(s, i - 1) + 1;
			int leftStart = findOperandStart(s, leftEnd - 1);
			int rightStart = skipSpaces(s, i + 1);
			int rightEnd = findOperandEnd(s, rightStart);
			if (leftStart < 0 || rightEnd < 0 || leftStart >= leftEnd || rightStart >= rightEnd) {
				continue;
			}

			String left = s.substring(leftStart, leftEnd);
			String right = s.substring(rightStart, rightEnd);
			if (!isMatrixExpression(left) && !isMatrixExpression(right)) {
				continue;
			}

			String replacement = String.format("mul(%s, %s)", left, right);
			s.replace(leftStart, rightEnd, replacement);
			i = leftStart + replacement.length() - 1;
		}

		return s.toString();
	}

	private boolean isMatrixExpression(String expression) {
		String s = expression.trim();
		if (s.isEmpty()) {
			return false;
		}

		// A swizzle or a member access always yields a vector or a scalar
		int lastDot = s.lastIndexOf('.');
		if (lastDot >= 0 && lastDot + 1 < s.length() && Character.isLetter(s.charAt(lastDot + 1))) {
			return false;
		}

		if (s.startsWith("(") || s.startsWith("mul(")) {
			// Cannot tell without a real type analysis, mul() is already applied
			return false;
		}

		Matcher matcher = Pattern.compile("^(?:float([234])x([234])|[A-Za-z_][A-Za-z0-9_]*)").matcher(s);
		if (!matcher.find()) {
			return false;
		}

		if (matcher.group(1) != null) {
			// A float4x4(...) style constructor
			return true;
		}

		return matrixVariables.contains(matcher.group());
	}

	private String renameEntryPoint(String source) {
		StringBuilder s = new StringBuilder(source.length());
		int index = 0;
		boolean renamed = false;
		while (true) {
			int found = indexOfIdentifier(source, "main", index);
			if (found < 0) {
				s.append(source, index, source.length());
				break;
			}

			int openParen = skipSpaces(source, found + "main".length());
			boolean isDefinition = openParen < source.length() && source.charAt(openParen) == '(';
			s.append(source, index, found);
			s.append(isDefinition ? glslEntryPoint : "main");
			renamed |= isDefinition;
			index = found + "main".length();
		}

		if (!renamed) {
			warn("The shader has no main() function");
		}

		return s.toString();
	}

	//
	// HLSL generation
	//

	private void appendPrologue(StringBuilder hlsl) {
		hlsl.append("// Generated by the jpcsp DirectX 11 renderer from a GLSL shader.\n");
		hlsl.append("#define __VERSION__ ").append(glslVersion).append('\n');
		hlsl.append("#define JPCSP_DIRECTX11 1\n");
		hlsl.append("#pragma pack_matrix(column_major)\n");
		// GLSL mod() is defined with floor(), HLSL fmod() truncates instead
		hlsl.append("float  jpcspMod(float x, float y)   { return x - y * floor(x / y); }\n");
		hlsl.append("float2 jpcspMod(float2 x, float2 y) { return x - y * floor(x / y); }\n");
		hlsl.append("float3 jpcspMod(float3 x, float3 y) { return x - y * floor(x / y); }\n");
		hlsl.append("float4 jpcspMod(float4 x, float4 y) { return x - y * floor(x / y); }\n");
		hlsl.append("float2 jpcspMod(float2 x, float y)  { return x - y * floor(x / y); }\n");
		hlsl.append("float3 jpcspMod(float3 x, float y)  { return x - y * floor(x / y); }\n");
		hlsl.append("float4 jpcspMod(float4 x, float y)  { return x - y * floor(x / y); }\n");
		// The GLSL relational functions return a bvec, HLSL uses the operators
		hlsl.append("#define jpcspEqual(a, b)            ((a) == (b))\n");
		hlsl.append("#define jpcspNotEqual(a, b)         ((a) != (b))\n");
		hlsl.append("#define jpcspLessThan(a, b)         ((a) < (b))\n");
		hlsl.append("#define jpcspLessThanEqual(a, b)    ((a) <= (b))\n");
		hlsl.append("#define jpcspGreaterThan(a, b)      ((a) > (b))\n");
		hlsl.append("#define jpcspGreaterThanEqual(a, b) ((a) >= (b))\n");
		hlsl.append('\n');
	}

	private void appendUniformBuffer(StringBuilder hlsl) {
		if (uniforms.isEmpty()) {
			return;
		}

		hlsl.append("cbuffer JpcspUniforms : register(b0) {\n");
		for (Variable uniform : uniforms) {
			setGuards(hlsl, uniform);
			hlsl.append('\t').append(getHlslType(uniform.type)).append(' ').append(uniform.name).append(uniform.arraySuffix).append(";\n");
		}
		closeGuards(hlsl);
		hlsl.append("};\n\n");
	}

	private void appendSamplers(StringBuilder hlsl) {
		if (samplers.isEmpty()) {
			return;
		}

		for (Map.Entry<String, Integer> sampler : samplers.entrySet()) {
			String name = sampler.getKey();
			int slot = sampler.getValue().intValue();
			String type = samplerTypes.get(name);
			String textureType = "Texture2D";
			if (type.startsWith("i")) {
				textureType = "Texture2D<int4>";
			} else if (type.startsWith("u")) {
				textureType = "Texture2D<uint4>";
			}
			hlsl.append(textureType).append(' ').append(getTextureName(name)).append(" : register(t").append(slot).append(");\n");
			hlsl.append("SamplerState ").append(getSamplerName(name)).append(" : register(s").append(slot).append(");\n");
		}
		hlsl.append('\n');
	}

	private void appendStructures(StringBuilder hlsl) {
		hlsl.append("struct JpcspShaderInput {\n");
		int semantic = 0;
		for (Variable input : inputs) {
			semantic = appendStructureMember(hlsl, input, semantic);
		}
		closeGuards(hlsl);
		if (stage == RE_FRAGMENT_SHADER) {
			hlsl.append("\tfloat4 jpcsp_FragCoord : SV_Position;\n");
			if (usesFrontFacing) {
				hlsl.append("\tbool jpcsp_FrontFacing : SV_IsFrontFace;\n");
			}
		} else if (stage == RE_VERTEX_SHADER) {
			if (usesVertexID) {
				hlsl.append("\tuint jpcsp_VertexID : SV_VertexID;\n");
			}
			if (usesInstanceID) {
				hlsl.append("\tuint jpcsp_InstanceID : SV_InstanceID;\n");
			}
		}
		if (inputs.isEmpty() && stage != RE_FRAGMENT_SHADER && !usesVertexID && !usesInstanceID) {
			// Direct3D 11 rejects an empty input signature
			hlsl.append("\tfloat4 jpcsp_Unused : TEXCOORD0;\n");
		}
		hlsl.append("};\n\n");

		hlsl.append("struct JpcspShaderOutput {\n");
		semantic = 0;
		for (Variable output : outputs) {
			semantic = appendStructureMember(hlsl, output, semantic);
		}
		closeGuards(hlsl);
		if (stage == RE_FRAGMENT_SHADER) {
			if (usedFragData.isEmpty()) {
				hlsl.append("\tfloat4 jpcsp_FragColor : SV_Target0;\n");
			} else {
				for (String index : usedFragData) {
					hlsl.append("\tfloat4 jpcsp_FragData").append(index).append(" : SV_Target").append(index).append(";\n");
				}
			}
			if (usesFragDepth) {
				hlsl.append("\tfloat jpcsp_FragDepth : SV_Depth;\n");
			}
		} else {
			hlsl.append("\tfloat4 jpcsp_Position : SV_Position;\n");
		}
		hlsl.append("};\n\n");
	}

	/**
	 * Emit one member of the input or output structure. The GLSL layout
	 * location becomes the TEXCOORD index, so that a vertex output and the
	 * matching fragment input keep being connected the same way as in GLSL.
	 *
	 * @return the next free semantic index
	 */
	private int appendStructureMember(StringBuilder hlsl, Variable variable, int semantic) {
		int index = variable.location >= 0 ? variable.location : semantic;

		setGuards(hlsl, variable);
		hlsl.append('\t');
		if (variable.interpolation != null) {
			if ("flat".equals(variable.interpolation)) {
				hlsl.append("nointerpolation ");
			} else if ("noperspective".equals(variable.interpolation)) {
				hlsl.append("noperspective ");
			} else if ("smooth".equals(variable.interpolation)) {
				hlsl.append("linear ");
			} else if ("centroid".equals(variable.interpolation)) {
				hlsl.append("centroid ");
			}
		}
		hlsl.append(getHlslType(variable.type)).append(' ').append(variable.name).append(variable.arraySuffix);
		hlsl.append(" : TEXCOORD").append(index).append(";\n");

		if (inputs.contains(variable)) {
			attributeLocations.put(variable.name, Integer.valueOf(index));
		}

		return Math.max(semantic, index) + 1;
	}

	private void appendGlobals(StringBuilder hlsl) {
		// The shader body keeps using the GLSL variable names, they are declared
		// as static globals and copied from/to the input and output structures.
		for (Variable input : inputs) {
			setGuards(hlsl, input);
			hlsl.append("static ").append(getHlslType(input.type)).append(' ').append(input.name).append(input.arraySuffix).append(";\n");
		}
		for (Variable output : outputs) {
			setGuards(hlsl, output);
			hlsl.append("static ").append(getHlslType(output.type)).append(' ').append(output.name).append(output.arraySuffix).append(";\n");
		}
		closeGuards(hlsl);

		if (stage == RE_FRAGMENT_SHADER) {
			if (usedFragData.isEmpty()) {
				hlsl.append("static float4 gl_FragColor;\n");
			} else {
				for (String index : usedFragData) {
					hlsl.append("static float4 jpcsp_FragData").append(index).append(";\n");
				}
			}
			if (usesFragCoord) {
				hlsl.append("static float4 gl_FragCoord;\n");
			}
			if (usesFrontFacing) {
				hlsl.append("static bool gl_FrontFacing;\n");
			}
			if (usesPointCoord) {
				hlsl.append("static float2 gl_PointCoord;\n");
			}
			if (usesFragDepth) {
				hlsl.append("static float gl_FragDepth;\n");
			}
		} else {
			hlsl.append("static float4 gl_Position;\n");
			if (usesVertexID) {
				hlsl.append("static int gl_VertexID;\n");
			}
			if (usesInstanceID) {
				hlsl.append("static int gl_InstanceID;\n");
			}
		}
		hlsl.append('\n');
	}

	private void appendEntryPoint(StringBuilder hlsl) {
		hlsl.append("\nJpcspShaderOutput ").append(entryPoint).append("(JpcspShaderInput jpcspInput) {\n");
		for (Variable input : inputs) {
			setGuards(hlsl, input);
			hlsl.append("\t").append(input.name).append(" = jpcspInput.").append(input.name).append(";\n");
		}
		closeGuards(hlsl);
		if (stage == RE_FRAGMENT_SHADER) {
			if (usesFragCoord) {
				hlsl.append("\tgl_FragCoord = jpcspInput.jpcsp_FragCoord;\n");
			}
			if (usesFrontFacing) {
				hlsl.append("\tgl_FrontFacing = jpcspInput.jpcsp_FrontFacing;\n");
			}
		} else {
			if (usesVertexID) {
				hlsl.append("\tgl_VertexID = (int) jpcspInput.jpcsp_VertexID;\n");
			}
			if (usesInstanceID) {
				hlsl.append("\tgl_InstanceID = (int) jpcspInput.jpcsp_InstanceID;\n");
			}
		}

		hlsl.append("\n\t").append(glslEntryPoint).append("();\n\n");

		hlsl.append("\tJpcspShaderOutput jpcspOutput;\n");
		for (Variable output : outputs) {
			setGuards(hlsl, output);
			hlsl.append("\tjpcspOutput.").append(output.name).append(" = ").append(output.name).append(";\n");
		}
		closeGuards(hlsl);
		if (stage == RE_FRAGMENT_SHADER) {
			if (usedFragData.isEmpty()) {
				hlsl.append("\tjpcspOutput.jpcsp_FragColor = gl_FragColor;\n");
			} else {
				for (String index : usedFragData) {
					hlsl.append("\tjpcspOutput.jpcsp_FragData").append(index).append(" = jpcsp_FragData").append(index).append(";\n");
				}
			}
			if (usesFragDepth) {
				hlsl.append("\tjpcspOutput.jpcsp_FragDepth = gl_FragDepth;\n");
			}
		} else {
			hlsl.append("\tjpcspOutput.jpcsp_Position = gl_Position;\n");
		}
		hlsl.append("\treturn jpcspOutput;\n");
		hlsl.append("}\n");
	}

	private static String getHlslType(String glslType) {
		for (String[] replacement : typeReplacements) {
			if (replacement[0].equals(glslType)) {
				return replacement[1];
			}
		}

		return glslType;
	}

	private static String getTextureName(String samplerName) {
		return samplerName + "_texture";
	}

	private static String getSamplerName(String samplerName) {
		return samplerName + "_sampler";
	}

	/**
	 * @return the HLSL shader model target matching a jpcsp shader type
	 */
	public static String getShaderTarget(int stage) {
		switch (stage) {
			case RE_VERTEX_SHADER:          return "vs_5_0";
			case RE_FRAGMENT_SHADER:        return "ps_5_0";
			case RE_GEOMETRY_SHADER:        return "gs_5_0";
			case RE_TESS_CONTROL_SHADER:    return "hs_5_0";
			case RE_TESS_EVALUATION_SHADER: return "ds_5_0";
		}

		return "cs_5_0";
	}

	//
	// Small lexical helpers
	//

	private static boolean isIdentifierChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static boolean containsIdentifier(String source, String identifier) {
		return indexOfIdentifier(source, identifier, 0) >= 0;
	}

	private static int indexOfIdentifier(String source, String identifier, int fromIndex) {
		for (int index = source.indexOf(identifier, fromIndex); index >= 0; index = source.indexOf(identifier, index + 1)) {
			if (index > 0 && isIdentifierChar(source.charAt(index - 1))) {
				continue;
			}
			int end = index + identifier.length();
			if (end < source.length() && isIdentifierChar(source.charAt(end))) {
				continue;
			}
			return index;
		}

		return -1;
	}

	private static String replaceIdentifier(String source, String identifier, String replacement) {
		StringBuilder s = new StringBuilder(source.length());
		int index = 0;
		while (true) {
			int found = indexOfIdentifier(source, identifier, index);
			if (found < 0) {
				s.append(source, index, source.length());
				break;
			}
			s.append(source, index, found).append(replacement);
			index = found + identifier.length();
		}

		return s.toString();
	}

	private static int skipSpaces(CharSequence source, int index) {
		while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
			index++;
		}

		return index;
	}

	private static int skipSpacesBackward(CharSequence source, int index) {
		while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
			index--;
		}

		return index;
	}

	private static int findMatchingParen(String source, int openParen) {
		int level = 0;
		for (int i = openParen; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				level++;
			} else if (c == ')') {
				level--;
				if (level == 0) {
					return i;
				}
			}
		}

		return -1;
	}

	private static boolean hasTopLevelComma(String source, int start, int end) {
		int level = 0;
		for (int i = start; i < end; i++) {
			char c = source.charAt(i);
			if (c == '(' || c == '[') {
				level++;
			} else if (c == ')' || c == ']') {
				level--;
			} else if (c == ',' && level == 0) {
				return true;
			}
		}

		return false;
	}

	private static List<String> splitArguments(String source, int start, int end) {
		List<String> arguments = new ArrayList<String>();
		int level = 0;
		int argumentStart = start;
		for (int i = start; i < end; i++) {
			char c = source.charAt(i);
			if (c == '(' || c == '[') {
				level++;
			} else if (c == ')' || c == ']') {
				level--;
			} else if (c == ',' && level == 0) {
				arguments.add(source.substring(argumentStart, i));
				argumentStart = i + 1;
			}
		}
		if (argumentStart < end || !arguments.isEmpty()) {
			arguments.add(source.substring(argumentStart, end));
		}

		return arguments;
	}

	/**
	 * Walk backwards over a complete operand: an identifier, possibly followed
	 * by array subscripts, member accesses and a function call.
	 *
	 * @return the index of the first character of the operand, or -1
	 */
	private static int findOperandStart(CharSequence source, int end) {
		int i = end;
		if (i < 0) {
			return -1;
		}

		boolean any = false;
		while (i >= 0) {
			char c = source.charAt(i);
			if (c == ')' || c == ']') {
				char open = c == ')' ? '(' : '[';
				int level = 0;
				while (i >= 0) {
					char c2 = source.charAt(i);
					if (c2 == c) {
						level++;
					} else if (c2 == open) {
						level--;
						if (level == 0) {
							break;
						}
					}
					i--;
				}
				if (i < 0) {
					return -1;
				}
				i--;
				any = true;
			} else if (isIdentifierChar(c) || c == '.') {
				while (i >= 0 && (isIdentifierChar(source.charAt(i)) || source.charAt(i) == '.')) {
					i--;
				}
				any = true;
			} else {
				break;
			}
		}

		return any ? i + 1 : -1;
	}

	/**
	 * Walk forwards over a complete operand, the mirror of findOperandStart().
	 *
	 * @return the index just after the last character of the operand, or -1
	 */
	private static int findOperandEnd(CharSequence source, int start) {
		int i = start;
		int length = source.length();
		if (i >= length) {
			return -1;
		}

		// A unary operator in front of the operand
		while (i < length && (source.charAt(i) == '-' || source.charAt(i) == '+' || source.charAt(i) == '!')) {
			i++;
		}

		boolean any = false;
		while (i < length) {
			char c = source.charAt(i);
			if (isIdentifierChar(c) || c == '.') {
				while (i < length && (isIdentifierChar(source.charAt(i)) || source.charAt(i) == '.')) {
					i++;
				}
				any = true;
			} else if (c == '(' || c == '[') {
				char close = c == '(' ? ')' : ']';
				int level = 0;
				while (i < length) {
					char c2 = source.charAt(i);
					if (c2 == c) {
						level++;
					} else if (c2 == close) {
						level--;
						if (level == 0) {
							break;
						}
					}
					i++;
				}
				if (i >= length) {
					return -1;
				}
				i++;
				any = true;
			} else {
				break;
			}
		}

		return any ? i : -1;
	}
}
