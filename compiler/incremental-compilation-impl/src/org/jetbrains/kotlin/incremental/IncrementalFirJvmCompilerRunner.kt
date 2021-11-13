/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.incremental

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiJavaModule
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.build.report.metrics.BuildTime
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.computeKotlinPaths
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.config.kotlinSourceRoots
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.IrMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.setupCommonArguments
import org.jetbrains.kotlin.cli.jvm.*
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.forAllFiles
import org.jetbrains.kotlin.cli.jvm.compiler.pipeline.createProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmModulePathRoot
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.multiproject.ModulesApiHistory
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.progress.CompilationCanceledException
import java.io.File
import java.util.HashSet

class IncrementalFirJvmCompilerRunner(
    workingDir: File,
    reporter: BuildReporter,
    buildHistoryFile: File,
    outputFiles: Collection<File>,
    modulesApiHistory: ModulesApiHistory,
    kotlinSourceFilesExtensions: List<String> = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS,
    classpathChanges: ClasspathChanges
) : IncrementalJvmCompilerRunner(
    workingDir,
    reporter,
    false,
    buildHistoryFile,
    outputFiles,
    modulesApiHistory,
    kotlinSourceFilesExtensions,
    classpathChanges
) {

    override fun compileIncrementally(
        arguments: K2JVMCompilerArguments,
        caches: IncrementalJvmCachesManager,
        allKotlinSources: List<File>,
        compilationMode: CompilationMode,
        messageCollector: MessageCollector,
        withSnapshot: Boolean,
        abiSnapshot: AbiSnapshot,
        classpathAbiSnapshot: Map<String, AbiSnapshot>
    ): ExitCode {

        preBuildHook(arguments, compilationMode)

        // TODO: probably shoudl be passed along with allKotlinSources
        // TODO: file path normalization
        val allCommonSources = arguments.commonSources?.mapTo(mutableSetOf(), ::File).orEmpty()

        val buildTimeMode: BuildTime
        val dirtySources = when (compilationMode) {
            is CompilationMode.Incremental -> {
                buildTimeMode = BuildTime.INCREMENTAL_ITERATION
                compilationMode.dirtyFiles.toMutableList()
            }
            is CompilationMode.Rebuild -> {
                buildTimeMode = BuildTime.NON_INCREMENTAL_ITERATION
                reporter.addAttribute(compilationMode.reason)
                allKotlinSources.toMutableList()
            }
        }
        // TODO: check possible important modifications of the dirty sources after this point
        if (dirtySources.isEmpty()) return ExitCode.OK

        val currentBuildInfo = BuildInfo(startTS = System.currentTimeMillis(), classpathAbiSnapshot)
        val buildDirtyLookupSymbols = HashSet<LookupSymbol>()
        val buildDirtyFqNames = HashSet<FqName>()
        val allDirtySources = HashSet<File>()

        // from K2JVMCompiler (~)
        val moduleName = arguments.moduleName ?: JvmProtoBufUtil.DEFAULT_MODULE_NAME
        val targetId = TargetId(moduleName, "java-production") // TODO: get rid of magic constant

        // - configuration
        val configuration = CompilerConfiguration()

        configuration.put(CLIConfigurationKeys.ORIGINAL_MESSAGE_COLLECTOR_KEY, messageCollector)

        val collector = GroupingMessageCollector(messageCollector, arguments.allWarningsAsErrors).also {
            configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, it)
        }

        configuration.put(IrMessageLogger.IR_MESSAGE_LOGGER, IrMessageCollector(collector))

        configuration.setupCommonArguments(arguments) { JvmMetadataVersion(*it) }

        configuration.setupJvmSpecificArguments(arguments)

        val paths = computeKotlinPaths(collector, arguments)
        if (collector.hasErrors()) return ExitCode.COMPILATION_ERROR

        // -- plugins
        val pluginClasspaths: Iterable<String> = arguments.pluginClasspaths?.asIterable() ?: emptyList()
        val pluginOptions = arguments.pluginOptions?.toMutableList() ?: ArrayList()
        // TODO: add scripting support when ready in FIR
        val pluginLoadResult = PluginCliParser.loadPluginsSafe(pluginClasspaths, pluginOptions, configuration)
        if (pluginLoadResult != ExitCode.OK) return pluginLoadResult
        // -- /plugins

        configuration.configureExplicitContentRoots(arguments)
        configuration.configureStandardLibs(paths, arguments)
        configuration.configureAdvancedJvmOptions(arguments)
        configuration.configureKlibPaths(arguments)

        arguments.destination?.let {
            val destination = File(it)
            if (destination.path.endsWith(".jar")) {
                configuration.put(JVMConfigurationKeys.OUTPUT_JAR, destination)
            } else {
                configuration.put(JVMConfigurationKeys.OUTPUT_DIRECTORY, destination)
            }
        }
        configuration.configureBaseRoots(arguments)
        configuration.configureSourceRootsFromSources(allKotlinSources, allCommonSources, arguments.javaPackagePrefix)
        // - /configuration

        var exitCode = ExitCode.OK
        val rootDisposable = Disposer.newDisposable()
        try {
            setIdeaIoUseFallback()

            val projectEnvironment =
                createProjectEnvironment(configuration, rootDisposable, EnvironmentConfigFiles.JVM_CONFIG_FILES, messageCollector)

            val allPlatformSourceFiles = linkedSetOf<File>()
            val allCommonSourceFiles = linkedSetOf<File>()

            // !!
            configuration.kotlinSourceRoots.forAllFiles(configuration, projectEnvironment.project) { virtualFile, isCommon ->
                val file = File(virtualFile.canonicalPath ?: virtualFile.path)
                if (!file.isFile) error("TODO: better error: file not found $virtualFile")
                if (isCommon) allCommonSourceFiles.add(file)
                else allPlatformSourceFiles.add(file)
            }


            // --

            // !! main class - maybe from cache?

            while (dirtySources.any() || runWithNoDirtyKotlinSources(caches)) {
                val complementaryFiles = caches.platformCache.getComplementaryFilesRecursive(dirtySources)
                dirtySources.addAll(complementaryFiles)
                caches.platformCache.markDirty(dirtySources)
                caches.inputsCache.removeOutputForSourceFiles(dirtySources)

                val lookupTracker = LookupTrackerImpl(LookupTracker.DO_NOTHING)
                val expectActualTracker = ExpectActualTrackerImpl()
                val targetToCache = mapOf(targetId to caches.platformCache)
                val incrementalComponents = IncrementalCompilationComponentsImpl(targetToCache)
                //TODO(valtman) sourceToCompile calculate based on abiSnapshot

                // TODO: should be a callback to some external manager
                val (sourcesToCompile, removedKotlinSources) = dirtySources.partition(File::exists)

                // configuration
                with(configuration) {
                    put(CommonConfigurationKeys.LOOKUP_TRACKER, lookupTracker)
                    put(CommonConfigurationKeys.EXPECT_ACTUAL_TRACKER, expectActualTracker)
                    //putIfNotNull(CommonConfigurationKeys.INLINE_CONST_TRACKER, TODO("Find out where to get it from"))
                    put(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS, incrementalComponents)
                    //putIfNotNull(ClassicFrontendSpecificJvmConfigurationKeys.JAVA_CLASSES_TRACKER, TODO("Not yet implemented in fir"))
                }

                // /configuration

                allDirtySources.addAll(dirtySources)
                val text = allDirtySources.joinToString(separator = System.getProperty("line.separator")) { it.canonicalPath }
                dirtySourcesSinceLastTimeFile.writeText(text)

                // run compiler:


            }
        } catch (e: CompilationCanceledException) {
            collector.report(CompilerMessageSeverity.INFO, "Compilation was canceled", null)
            return ExitCode.OK
        } catch (e: RuntimeException) {
            val cause = e.cause
            if (cause is CompilationCanceledException) {
                collector.report(CompilerMessageSeverity.INFO, "Compilation was canceled", null)
                return ExitCode.OK
            } else {
                throw e
            }
        } finally {
            Disposer.dispose(rootDisposable)
        }
        return exitCode
    }
}

fun CompilerConfiguration.configureBaseRoots(args: K2JVMCompilerArguments) {

    var isJava9Module = false
    args.javaSourceRoots?.forEach {
        val file = File(it)
        val packagePrefix = args.javaPackagePrefix
        addJavaSourceRoot(file, packagePrefix)
        if (!isJava9Module && packagePrefix == null && (file.name == PsiJavaModule.MODULE_INFO_FILE ||
                    (file.isDirectory && file.listFiles().any { it.name == PsiJavaModule.MODULE_INFO_FILE }))
        ) {
            isJava9Module = true
        }
    }

    args.classpath?.split(File.pathSeparator)?.forEach { classpathRoot ->
        add(
            CLIConfigurationKeys.CONTENT_ROOTS,
            if (isJava9Module) JvmModulePathRoot(File(classpathRoot)) else JvmClasspathRoot(File(classpathRoot))
        )
    }

    // TODO: modularJdkRoot (now seems only processed from the build file
}

fun CompilerConfiguration.configureSourceRootsFromSources(
    allSources: Collection<File>, commonSources: Set<File>, javaPackagePrefix: String?
) {
    for (sourceFile in allSources) {
        if (sourceFile.name.endsWith(JavaFileType.DOT_DEFAULT_EXTENSION)) {
            addJavaSourceRoot(sourceFile, javaPackagePrefix)
        } else {
            addKotlinSourceRoot(sourceFile.path, isCommon = sourceFile in commonSources)

            if (sourceFile.isDirectory) {
                addJavaSourceRoot(sourceFile, javaPackagePrefix)
            }
        }
    }
}