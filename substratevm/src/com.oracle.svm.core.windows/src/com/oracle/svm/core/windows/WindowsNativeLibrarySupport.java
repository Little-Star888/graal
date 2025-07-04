/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.windows;

import java.io.FileDescriptor;

import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.PointerBase;

import com.oracle.svm.core.Isolates;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.AutomaticallyRegisteredImageSingleton;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.jdk.JNIPlatformNativeLibrarySupport;
import com.oracle.svm.core.jdk.Jvm;
import com.oracle.svm.core.jdk.NativeLibrarySupport;
import com.oracle.svm.core.jdk.PlatformNativeLibrarySupport;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.windows.headers.FileAPI;
import com.oracle.svm.core.windows.headers.LibLoaderAPI;
import com.oracle.svm.core.windows.headers.WinBase.HMODULE;
import com.oracle.svm.core.windows.headers.WinSock;

import jdk.graal.compiler.word.Word;

@AutomaticallyRegisteredFeature
class WindowsNativeLibraryFeature implements InternalFeature {
    @Override
    public void duringSetup(DuringSetupAccess access) {
        NativeLibrarySupport.singleton().preregisterUninitializedBuiltinLibrary("extnet");
    }
}

@AutomaticallyRegisteredImageSingleton(PlatformNativeLibrarySupport.class)
class WindowsNativeLibrarySupport extends JNIPlatformNativeLibrarySupport {

    @Override
    public boolean initializeBuiltinLibraries() {
        try {
            loadJavaLibrary();
            loadNetLibrary();
        } catch (UnsatisfiedLinkError e) {
            Log.log().string("System.loadLibrary failed, " + e).newline();
            return false;
        }
        return true;
    }

    @Override
    protected void loadJavaLibrary() {
        super.loadJavaLibrary();
        Target_java_io_WinNTFileSystem.initIDs();

        /* Initialize the handles of standard FileDescriptors. */
        WindowsUtils.setHandle(FileDescriptor.in, FileAPI.GetStdHandle(FileAPI.STD_INPUT_HANDLE()));
        WindowsUtils.setHandle(FileDescriptor.out, FileAPI.GetStdHandle(FileAPI.STD_OUTPUT_HANDLE()));
        WindowsUtils.setHandle(FileDescriptor.err, FileAPI.GetStdHandle(FileAPI.STD_ERROR_HANDLE()));
    }

    @SuppressWarnings("restricted")
    private static void loadNetLibrary() {
        if (Isolates.isCurrentFirst()) {
            WinSock.init();
            System.loadLibrary("net");
            /*
             * NOTE: because the native OnLoad code probes java.net.preferIPv4Stack and stores its
             * value in process-wide shared native state, the property's value in the first launched
             * isolate applies to all subsequently launched isolates.
             */
        } else {
            NativeLibrarySupport.singleton().registerInitializedBuiltinLibrary("net");
        }
        NativeLibrarySupport.singleton().registerInitializedBuiltinLibrary("extnet");
        System.loadLibrary("extnet");
    }

    @Override
    public WindowsNativeLibrary createLibrary(String canonical, boolean builtIn) {
        return new WindowsNativeLibrary(canonical, builtIn);
    }

    @Override
    public PointerBase findBuiltinSymbol(String name) {
        try (CCharPointerHolder symbol = CTypeConversion.toCString(name)) {
            HMODULE builtinHandle = LibLoaderAPI.GetModuleHandleA(Word.nullPointer());
            return LibLoaderAPI.GetProcAddress(builtinHandle, symbol.get());
        }
    }

    class WindowsNativeLibrary implements NativeLibrary {

        private final String canonicalIdentifier;
        private final boolean builtin;
        private HMODULE dlhandle;
        private boolean loaded = false;

        WindowsNativeLibrary(String canonicalIdentifier, boolean builtin) {
            this.canonicalIdentifier = canonicalIdentifier;
            this.builtin = builtin;
        }

        @Override
        public String getCanonicalIdentifier() {
            return canonicalIdentifier;
        }

        @Override
        public boolean isBuiltin() {
            return builtin;
        }

        @Override
        public boolean load() {
            assert !loaded;
            loaded = doLoad();
            return loaded;
        }

        @Override
        public boolean unload() {
            assert loaded;
            if (builtin) {
                return false;
            }
            assert dlhandle.isNonNull();
            if (LibLoaderAPI.FreeLibrary(dlhandle) != 0) {
                dlhandle = Word.nullPointer();
                return true;
            } else {
                return false;
            }
        }

        private boolean doLoad() {
            // Make sure the jvm.lib is available for linking
            // Need a better place to put this.
            Jvm.initialize();

            if (builtin) {
                return true;
            }
            assert dlhandle.isNull();
            try (CCharPointerHolder dllPathPin = CTypeConversion.toCString(canonicalIdentifier)) {
                CCharPointer dllPathPtr = dllPathPin.get();
                /*
                 * WinBase.SetDllDirectoryA(dllpathPtr); CCharPointerHolder pathPin =
                 * CTypeConversion.toCString(path); CCharPointer pathPtr = pathPin.get();
                 */
                dlhandle = LibLoaderAPI.LoadLibraryA(dllPathPtr);
            }
            return dlhandle.isNonNull();
        }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public PointerBase findSymbol(String name) {
            if (builtin) {
                return findBuiltinSymbol(name);
            }
            assert dlhandle.isNonNull();
            try (CCharPointerHolder symbol = CTypeConversion.toCString(name)) {
                return LibLoaderAPI.GetProcAddress(dlhandle, symbol.get());
            }
        }
    }
}

@TargetClass(className = "java.io.WinNTFileSystem")
final class Target_java_io_WinNTFileSystem {
    @Alias
    static native void initIDs();
}
