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
package jpcsp.graphics.RE;

import static jpcsp.HLE.Modules.sceDisplayModule;
import static jpcsp.util.DurationStatistics.collectStatistics;

import jpcsp.HLE.modules.sceDisplay;
import jpcsp.graphics.VideoEngine;
import jpcsp.graphics.RE.directx.RenderingEngineDirectX11;
import jpcsp.graphics.RE.software.RESoftware;

/**
 * @author gid15
 *
 */
public class RenderingEngineFactory {
	private static final boolean enableDebugProxy = false;
	private static final boolean enableCheckErrorsProxy = false;
	private static final boolean enableStatisticsProxy = false;
	private static RenderingEngineDirectX11 directX11RenderingEngine;

	private static IRenderingEngine createRenderingEngine(boolean forDisplay) {
		final boolean isUsingSoftwareRenderer = sceDisplayModule.isUsingSoftwareRenderer();

		if (!forDisplay && directX11RenderingEngine != null) {
			// A complete rendering pipeline is being rebuilt, e.g. because the
			// display settings changed: release the previous Direct3D 11 device
			// before creating a new one on the same window.
			directX11RenderingEngine.exit();
			directX11RenderingEngine = null;
		}

		// Build the rendering pipeline, from the last entry to the first one.
		IRenderingEngine re;
		boolean isUsingDirectX11 = false;

		if (isUsingSoftwareRenderer) {
			// RenderingEngine using a complete software implementation, i.e. not using the GPU
			re = new RESoftware();
		} else {
			re = createDirectX11RenderingEngine();
			if (re != null) {
				isUsingDirectX11 = true;
			} else {
				// RenderingEngine performing the OpenGL calls by using the LWJGL library
				re = RenderingEngineLwjgl.newInstance();
			}
		}

		if (enableCheckErrorsProxy) {
			re = new CheckErrorsProxy(re);
		}

		if (enableStatisticsProxy && collectStatistics) {
			// Proxy collecting statistics for all the calls (number of calls and execution time)
			re = new StatisticsProxy(re);
		}

		if (enableDebugProxy) {
			// Proxy logging the calls at the DEBUG level
			re = new DebugProxy(re);
		}

		if (!isUsingSoftwareRenderer) {
			if (isUsingDirectX11) {
				// Direct3D 11 has no fixed-function pipeline, the shaders are mandatory
				re = new REShader(re);
			} else if (REShader.useShaders(re)) {
				// RenderingEngine using shaders
				re = new REShader(re);
			} else {
				// RenderingEngine using the OpenGL fixed-function pipeline (i.e. without shaders)
				re = new REFixedFunction(re);
			}
		}

		// Proxy removing redundant calls.
		// E.g. calls setting multiple times the same value,
		// or calls with an invalid parameter (e.g. for unused shader uniforms).
		// In the rendering pipeline, the State Proxy has to be called after
		// the Anisotropic/Viewport filters. These are modifying some parameters
		// and the State Proxy has to use the final parameter values.
		re = new StateProxy(re);

		// Proxy implementing a texture anisotropic filter
		re = new AnisotropicFilter(re);

        // Proxy implementing a viewport resizing filter
		re = new ViewportFilter(re);

		// Return the first entry in the pipeline
		return re;
	}

	/**
	 * Create the DirectX 11 rendering engine when it has been selected in the
	 * settings and Direct3D 11 is usable on this system.
	 *
	 * The Direct3D 11 swap chain is created on the same native window as the
	 * display canvas: the OpenGL context of the canvas stays unused, only its
	 * window handle is needed.
	 *
	 * @return the DirectX 11 rendering engine, or null to fall back to OpenGL
	 */
	private static IRenderingEngine createDirectX11RenderingEngine() {
		if (!sceDisplayModule.isUsingDirectX11Renderer()) {
			return null;
		}

		sceDisplay.AWTGLCanvas_sceDisplay canvas = sceDisplayModule.getCanvas();
		if (canvas == null) {
			return null;
		}

		RenderingEngineDirectX11 re = RenderingEngineDirectX11.newInstance(canvas.getDisplayWindow(), canvas.getWidth(), canvas.getHeight());
		if (re == null) {
			VideoEngine.log.warn("The DirectX 11 rendering engine is not available, falling back to OpenGL");
		}
		directX11RenderingEngine = re;

		return re;
	}

	/**
	 * @return the DirectX 11 rendering engine currently rendering the GE lists,
	 *         or null when the GE lists are not rendered with Direct3D 11
	 */
	public static RenderingEngineDirectX11 getDirectX11RenderingEngine() {
		return directX11RenderingEngine;
	}

	/**
	 * Create a rendering engine to be used for processing the GE lists.
	 * 
	 * @return the rendering engine to be used
	 */
	public static IRenderingEngine createRenderingEngine() {
		return createRenderingEngine(false);
	}

	/**
	 * Create a rendering engine to be used for display.
	 * This rendering engine forces the use of OpenGL and is not using the software rendering.
	 * 
	 * @return the rendering engine to be used for display
	 */
	public static IRenderingEngine createRenderingEngineForDisplay() {
		return createRenderingEngine(true);
	}

	/**
	 * Create a rendering engine to be used when the HLE modules have not yet
	 * been started.
	 * 
	 * @return the initial rendering engine
	 */
	public static IRenderingEngine createInitialRenderingEngine() {
		IRenderingEngine re = RenderingEngineLwjgl.newInstance();

		if (enableDebugProxy) {
			re = new DebugProxy(re);
		}

		return re;
	}
}
