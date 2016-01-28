/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

@file:Suppress("unused")

package org.jetbrains.kotlin.incremental

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.build.GeneratedFile
import org.jetbrains.kotlin.build.GeneratedJvmClass
import org.jetbrains.kotlin.build.JvmSourceRoot
import org.jetbrains.kotlin.build.isModuleMappingFile
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCache
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.modules.KotlinModuleXmlBuilder
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.progress.CompilationCanceledStatus
import org.jetbrains.kotlin.utils.keysToMap
import java.io.File
import java.util.*


fun Iterable<File>.javaSourceRoots(roots: Iterable<File>): Iterable<File> =
        filter { it.isJavaFile() }
                .map { findSrcDirRoot(it, roots) }
                .filterNotNull()

fun makeModuleFile(name: String, isTest: Boolean, outputDir: File, sourcesToCompile: List<File>, javaSourceRoots: Iterable<File>, classpath: Iterable<File>, friendDirs: Iterable<File>): File {
    val builder = KotlinModuleXmlBuilder()
    builder.addModule(
            name,
            outputDir.absolutePath,
            sourcesToCompile,
            javaSourceRoots.map { JvmSourceRoot(it) },
            classpath,
            "java-production",
            isTest,
            // this excludes the output directories from the class path, to be removed for true incremental compilation
            setOf(outputDir),
            friendDirs
    )

    val scriptFile = File.createTempFile("kjps", StringUtil.sanitizeJavaIdentifier(name) + ".script.xml")

    FileUtil.writeToFile(scriptFile, builder.asText().toString())

    return scriptFile
}

fun makeCompileServices(
        incrementalCaches: Map<TargetId, IncrementalCache>,
        lookupTracker: LookupTracker,
        compilationCanceledStatus: CompilationCanceledStatus?
): Services =
    with(Services.Builder()) {
        register(IncrementalCompilationComponents::class.java, IncrementalCompilationComponentsImpl(incrementalCaches, lookupTracker))
        compilationCanceledStatus?.let {
            register(CompilationCanceledStatus::class.java, it)
        }
        build()
    }


fun makeLookupTracker(parentLookupTracker: LookupTracker = LookupTracker.DO_NOTHING): LookupTracker =
        if (IncrementalCompilation.isExperimental()) LookupTrackerImpl(parentLookupTracker)
        else parentLookupTracker

fun<Target> makeIncrementalCachesMap(
        targets: Iterable<Target>,
        getDependencies: (Target) -> Iterable<Target>,
        getCache: (Target) -> IncrementalCacheImpl<Target>,
        getTargetId: Target.() -> TargetId
): Map<TargetId, IncrementalCacheImpl<Target>>
{
    val dependents = targets.keysToMap { hashSetOf<Target>() }
    val targetsWithDependents = targets.toHashSet()

    for (target in targets) {
        for (dependency in getDependencies(target)) {
            if (dependency !in targets) continue

            dependents[dependency]!!.add(target)
            targetsWithDependents.add(target)
        }
    }

    val caches = targetsWithDependents.keysToMap { getCache(it) }

    for ((target, cache) in caches) {
        dependents[target]?.forEach {
            cache.addDependentCache(caches[it]!!)
        }
    }

    return caches.mapKeys { it.key.getTargetId() }
}

fun<Target> updateIncrementalCaches(
        targets: Iterable<Target>,
        generatedFiles: List<GeneratedFile<Target>>,
        compiledWithErrors: Boolean,
        getIncrementalCache: (Target) -> IncrementalCacheImpl<Target>
): CompilationResult {

    var changesInfo = CompilationResult.NO_CHANGES
    for (generatedFile in generatedFiles) {
        val ic = getIncrementalCache(generatedFile.target)
        when {
            generatedFile is GeneratedJvmClass<Target> -> changesInfo += ic.saveFileToCache(generatedFile)
            generatedFile.outputFile.isModuleMappingFile() -> changesInfo += ic.saveModuleMappingToCache(generatedFile.sourceFiles, generatedFile.outputFile)
        }
    }

    if (!compiledWithErrors) {
        targets.forEach {
            val newChangesInfo = getIncrementalCache(it).clearCacheForRemovedClasses()
            changesInfo += newChangesInfo
        }
    }

    return changesInfo
}

fun LookupStorage.update(
        lookupTracker: LookupTracker,
        filesToCompile: Iterable<File>,
        removedFiles: Iterable<File>
) {
    if (lookupTracker !is LookupTrackerImpl) throw AssertionError("Lookup tracker is expected to be LookupTrackerImpl, got ${lookupTracker.javaClass}")

    removeLookupsFrom(filesToCompile.asSequence() + removedFiles.asSequence())

    addAll(lookupTracker.lookups.entrySet(), lookupTracker.pathInterner.values)
}

fun<Target> OutputItemsCollectorImpl.generatedFiles(
        targets: Collection<Target>,
        representativeTarget: Target,
        getSources: (Target) -> Iterable<File>,
        getOutputDir: (Target) -> File?
): List<GeneratedFile<Target>> {
    // If there's only one target, this map is empty: get() always returns null, and the representativeTarget will be used below
    val sourceToTarget =
            if (targets.size >1) targets.flatMap { target -> getSources(target).map { Pair(it, target) } }.toMap()
            else mapOf<File, Target>()

    return outputs.map { outputItem ->
        val target =
                outputItem.sourceFiles.firstOrNull()?.let { sourceToTarget[it] } ?:
                targets.filter { getOutputDir(it)?.let { outputItem.outputFile.startsWith(it) } ?: false }.singleOrNull() ?:
                representativeTarget
        if (outputItem.outputFile.name.endsWith(".class"))
            GeneratedJvmClass(target, outputItem.sourceFiles, outputItem.outputFile)
        else
            GeneratedFile(target, outputItem.sourceFiles, outputItem.outputFile)
    }
}

fun<Target> CompilationResult.dirtyLookups(
        caches: Sequence<IncrementalCacheImpl<TargetId>>
): Iterable<LookupSymbol> =
        changes.asIterable().flatMap { change ->
            when (change) {
                is ChangeInfo.SignatureChanged -> {
                    val fqNames = if (!change.areSubclassesAffected) listOf(change.fqName) else withSubtypes(change.fqName, caches)
                    fqNames.map {
                        val scope = it.parent().asString()
                        val name = it.shortName().identifier
                        LookupSymbol(name, scope)
                    }
                }
                is ChangeInfo.MembersChanged -> {
                    val scopes = withSubtypes(change.fqName, caches).map { it.asString() }
                    change.names.flatMap { name -> scopes.map { scope -> LookupSymbol(name, scope) } }
                }
                else -> listOf<LookupSymbol>()
            }
        }


private fun File.isJavaFile() = extension.equals(JavaFileType.INSTANCE.defaultExtension, ignoreCase = true)

private fun findSrcDirRoot(file: File, roots: Iterable<File>): File? =
        roots.firstOrNull { FileUtil.isAncestor(it, file, false) }

private fun<TargetId> withSubtypes(
        typeFqName: FqName,
        caches: Sequence<IncrementalCacheImpl<TargetId>>
): Set<FqName> {
    val types = LinkedList(listOf(typeFqName))
    val subtypes = hashSetOf<FqName>()

    while (types.isNotEmpty()) {
        val unprocessedType = types.pollFirst()

        caches.flatMap { it.getSubtypesOf(unprocessedType) }
                .filter { it !in subtypes }
                .forEach { types.addLast(it) }

        subtypes.add(unprocessedType)
    }

    return subtypes
}

