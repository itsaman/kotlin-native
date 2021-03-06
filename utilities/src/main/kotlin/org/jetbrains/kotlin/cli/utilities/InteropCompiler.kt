/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.utilities

import org.jetbrains.kotlin.backend.konan.library.KLIB_CURRENT_ABI_VERSION
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.defaultResolver
import org.jetbrains.kotlin.konan.library.includedHeaders
import org.jetbrains.kotlin.konan.library.libraryResolver
import org.jetbrains.kotlin.konan.library.packageFqName
import org.jetbrains.kotlin.konan.target.PlatformManager
import org.jetbrains.kotlin.native.interop.gen.jvm.interop
import org.jetbrains.kotlin.utils.addIfNotNull

private const val NODEFAULTLIBS = "-nodefaultlibs"
private const val PURGE_USER_LIBS = "--purge_user_libs"

// TODO: this function should eventually be eliminated from 'utilities'. 
// The interaction of interop and the compler should be streamlined. 

fun invokeInterop(flavor: String, args: Array<String>): Array<String> {
    val cinteropArgFilter = listOf(NODEFAULTLIBS, PURGE_USER_LIBS)

    var outputFileName = "nativelib"
    var targetRequest = "host"
    val libraries = mutableListOf<String>()
    val repos = mutableListOf<String>()
    var noDefaultLibs = false
    var purgeUserLibs = false
    var temporaryFilesDir = ""
    for (i in args.indices) {
        val arg = args[i]
        val nextArg = args.getOrNull(i + 1)
        if (arg.startsWith("-o"))
            outputFileName = nextArg ?: outputFileName
        if (arg == "-target")
            targetRequest = nextArg ?: targetRequest
        if (arg == "-library")
            libraries.addIfNotNull(nextArg)
        if (arg == "-r" || arg == "-repo")
            repos.addIfNotNull(nextArg)
        if (arg == NODEFAULTLIBS)
            noDefaultLibs = true
        if (arg == PURGE_USER_LIBS)
            purgeUserLibs = true
        if (arg == "--temporary_files_dir")
            temporaryFilesDir = nextArg ?: ""
    }


    val buildDir = File("$outputFileName-build")
    val generatedDir = File(buildDir, "kotlin")
    val nativesDir = File(buildDir, "natives")
    val cstubsName ="cstubs"
    val manifest = File(buildDir, "manifest.properties")

    val target = PlatformManager().targetManager(targetRequest).target
    val resolver = defaultResolver(repos, target).libraryResolver(KLIB_CURRENT_ABI_VERSION)
    val allLibraries = resolver.resolveWithDependencies(
            libraries, noStdLib = true, noDefaultLibs = noDefaultLibs
    ).getFullList()

    val importArgs = allLibraries.flatMap { library ->
        // TODO: handle missing properties?
        library.packageFqName?.asString()?.let { packageFqName ->
            val headerIds = library.includedHeaders
            val arg = "$packageFqName:${headerIds.joinToString(";")}"
            listOf("-import", arg)
        } ?: emptyList()
    }

    val additionalArgs = listOf(
        "-generated", generatedDir.path, 
        "-natives", nativesDir.path, 
        "-cstubsname", cstubsName,
        "-manifest", manifest.path,
        "-flavor", flavor,
        "-temporaryFilesDir", temporaryFilesDir
    ) + importArgs

    val cinteropArgs = (additionalArgs + args.filter { it !in cinteropArgFilter }).toTypedArray()

    val cinteropArgsToCompiler = interop(flavor, cinteropArgs) ?: emptyArray()

    val nativeStubs = 
        if (flavor == "wasm") 
            arrayOf("-includeBinary", File(nativesDir, "js_stubs.js").path)
        else 
            arrayOf("-nativelibrary",File(nativesDir, "$cstubsName.bc").path)

    val konancArgs = arrayOf(
        generatedDir.path, 
        "-produce", "library", 
        "-o", outputFileName,
        "-target", target.visibleName,
        "-manifest", manifest.path,
        "--temporary_files_dir", temporaryFilesDir) +
        nativeStubs +
        cinteropArgsToCompiler + 
        libraries.flatMap { listOf("-library", it) } + 
        repos.flatMap { listOf("-repo", it) } +
        (if (noDefaultLibs) arrayOf(NODEFAULTLIBS) else emptyArray()) +
        (if (purgeUserLibs) arrayOf(PURGE_USER_LIBS) else emptyArray())

    return konancArgs
}


