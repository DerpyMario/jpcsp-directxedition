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

import org.apache.log4j.Logger;
import org.lwjgl.system.Platform;

import jpcsp.graphics.VideoEngine;

/**
 * @author gid15
 *
 * Locate the Direct3D 11 wrapper to be used by {@link RenderingEngineDirectX11}.
 *
 * The lookup is intentionally defensive: Direct3D 11 only exists on Windows and
 * the native wrapper is an optional component, so every failure just means
 * "no DirectX 11 today" and the caller falls back to OpenGL.
 */
public class DirectX11WrapperFactory {
	private static Logger log = VideoEngine.log;
	private static Boolean available;

	private DirectX11WrapperFactory() {
	}

	/**
	 * @return true when a Direct3D 11 wrapper can be created on this system
	 */
	public static synchronized boolean isAvailable() {
		if (available == null) {
			available = Boolean.valueOf(checkAvailable());
		}

		return available.booleanValue();
	}

	private static boolean checkAvailable() {
		if (Platform.get() != Platform.WINDOWS) {
			log.info(String.format("The DirectX 11 rendering engine is only available on Windows, not on %s", Platform.get().getName()));
			return false;
		}

		return DirectX11NativeWrapper.isAvailable();
	}

	/**
	 * @return a new wrapper instance, or null when Direct3D 11 is not usable
	 */
	public static IDirectX11Wrapper createWrapper() {
		if (!isAvailable()) {
			return null;
		}

		try {
			return new DirectX11NativeWrapper();
		} catch (RuntimeException e) {
			log.error(String.format("Cannot create the DirectX 11 wrapper: %s", e));
			return null;
		}
	}
}
