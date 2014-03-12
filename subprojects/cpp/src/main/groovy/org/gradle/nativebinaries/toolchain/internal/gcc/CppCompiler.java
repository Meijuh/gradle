/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativebinaries.toolchain.internal.gcc;

import org.gradle.api.Action;
import org.gradle.nativebinaries.language.cpp.internal.CppCompileSpec;
import org.gradle.nativebinaries.toolchain.internal.CommandLineTool;

import java.util.List;

public class CppCompiler extends NativeCompiler<CppCompileSpec> {

    public CppCompiler(CommandLineTool<CppCompileSpec> commandLineTool, Action<List<String>> toolChainArgsAction, boolean useCommandFile) {
        super(commandLineTool, toolChainArgsAction, new CppCompileArgsTransformer(), useCommandFile);

    }

    private static class CppCompileArgsTransformer extends GccCompilerArgsTransformer<CppCompileSpec> {
        protected String getLanguage() {
            return "c++";
        }
    }
}
