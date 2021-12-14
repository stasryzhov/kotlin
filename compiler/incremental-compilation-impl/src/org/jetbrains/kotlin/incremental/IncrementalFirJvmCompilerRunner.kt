/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.incremental

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiJavaModule
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.jvm.JvmGeneratorExtensionsImpl
import org.jetbrains.kotlin.backend.jvm.serialization.JvmIdSignatureDescriptor
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.build.report.metrics.BuildTime
import org.jetbrains.kotlin.build.report.metrics.measure
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.computeKotlinPaths
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.config.kotlinSourceRoots
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.fir.FirDiagnosticsCompilerResultsReporter
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.IrMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.setupCommonArguments
import org.jetbrains.kotlin.cli.jvm.*
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.findMainClass
import org.jetbrains.kotlin.cli.jvm.compiler.forAllFiles
import org.jetbrains.kotlin.cli.jvm.compiler.pipeline.*
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmModulePathRoot
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.compilerRunner.MessageCollectorToOutputItemsCollectorAdapter
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.compilerRunner.SimpleOutputItem
import org.jetbrains.kotlin.compilerRunner.toGeneratedFile
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.diagnostics.DiagnosticReporterFactory
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrConverter
import org.jetbrains.kotlin.fir.backend.jvm.Fir2IrJvmSpecialAnnotationSymbolProvider
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmKotlinMangler
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmVisibilityConverter
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirProviderImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.multiproject.ModulesApiHistory
import org.jetbrains.kotlin.incremental.util.BufferingMessageCollector
import org.jetbrains.kotlin.ir.backend.jvm.serialization.JvmDescriptorMangler
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.progress.CompilationCanceledException
import java.io.File

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
        args: K2JVMCompilerArguments,
        caches: IncrementalJvmCachesManager,
        allKotlinSources: List<File>,
        compilationMode: CompilationMode,
        originalMessageCollector: MessageCollector,
        withSnapshot: Boolean,
        abiSnapshot: AbiSnapshot,
        classpathAbiSnapshot: Map<String, AbiSnapshot>
    ): ExitCode {

        // outputs collector
        args.reportOutputFiles = true
        val outputItemsCollector = OutputItemsCollectorImpl()
        val bufferingMessageCollector = BufferingMessageCollector()
        val messageCollectorAdapter = MessageCollectorToOutputItemsCollectorAdapter(bufferingMessageCollector, outputItemsCollector)
        val collector = GroupingMessageCollector(messageCollectorAdapter, args.allWarningsAsErrors)

        var exitCode = ExitCode.OK
        val rootDisposable = Disposer.newDisposable()

        try {
            preBuildHook(args, compilationMode)

            // TODO: probably shoudl be passed along with allKotlinSources
            // TODO: file path normalization
            val allCommonSources = args.commonSources?.mapTo(mutableSetOf(), ::File).orEmpty()

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
            val moduleName = args.moduleName ?: JvmProtoBufUtil.DEFAULT_MODULE_NAME
            val targetId = TargetId(moduleName, "java-production") // TODO: get rid of magic constant

            // - configuration
            val configuration = CompilerConfiguration()

            configuration.put(CLIConfigurationKeys.ORIGINAL_MESSAGE_COLLECTOR_KEY, messageCollectorAdapter)
            configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
            configuration.put(IrMessageLogger.IR_MESSAGE_LOGGER, IrMessageCollector(collector))

            configuration.setupCommonArguments(args) { JvmMetadataVersion(*it) }

            configuration.setupJvmSpecificArguments(args)

            val paths = computeKotlinPaths(collector, args)
            if (collector.hasErrors()) return ExitCode.COMPILATION_ERROR

            // -- plugins
            val pluginClasspaths: Iterable<String> = args.pluginClasspaths?.asIterable() ?: emptyList()
            val pluginOptions = args.pluginOptions?.toMutableList() ?: ArrayList()
            // TODO: add scripting support when ready in FIR
            val pluginLoadResult = PluginCliParser.loadPluginsSafe(pluginClasspaths, pluginOptions, configuration)
            if (pluginLoadResult != ExitCode.OK) return pluginLoadResult
            // -- /plugins

            configuration.configureExplicitContentRoots(args)
            configuration.configureStandardLibs(paths, args)
            configuration.configureAdvancedJvmOptions(args)
            configuration.configureKlibPaths(args)

            args.destination?.let {
                val destination = File(it)
                if (destination.path.endsWith(".jar")) {
                    configuration.put(JVMConfigurationKeys.OUTPUT_JAR, destination)
                } else {
                    configuration.put(JVMConfigurationKeys.OUTPUT_DIRECTORY, destination)
                }
            }
            configuration.configureBaseRoots(args)
            configuration.configureSourceRootsFromSources(allKotlinSources, allCommonSources, args.javaPackagePrefix)
            // - /configuration

            setIdeaIoUseFallback()

            // -AbstractProjectEnvironment-
            val projectEnvironment =
                createProjectEnvironment(configuration, rootDisposable, EnvironmentConfigFiles.JVM_CONFIG_FILES, messageCollectorAdapter)

            // -sources
            val allPlatformSourceFiles = linkedSetOf<File>()
            val allCommonSourceFiles = linkedSetOf<File>()

            configuration.kotlinSourceRoots.forAllFiles(configuration, projectEnvironment.project) { virtualFile, isCommon ->
                val file = File(virtualFile.canonicalPath ?: virtualFile.path)
                if (!file.isFile) error("TODO: better error: file not found $virtualFile")
                if (isCommon) allCommonSourceFiles.add(file)
                else allPlatformSourceFiles.add(file)
            }

            // !! main class - maybe from cache?
            var mainClassFqName: FqName? = null

            fun incrementalCompilationStep(
                allCommonSourceFiles: LinkedHashSet<File>,
                allPlatformSourceFiles: LinkedHashSet<File>,
                projectEnvironment: VfsBasedProjectEnvironment,
                body: (Collection<File>) -> Boolean
            ): Boolean {
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
                //TODO(valtman) sourceToCompile calculate based on abiSnapshot
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

                val isSuccessful = body(dirtySources)

                if (!isSuccessful) {
                    exitCode = ExitCode.COMPILATION_ERROR
                }
                reporter.reportCompileIteration(compilationMode is CompilationMode.Incremental, sourcesToCompile, exitCode)

                if (!isSuccessful) return false

                // update dirty after analysis

                dirtySourcesSinceLastTimeFile.delete()

                val changesCollector = ChangesCollector()
                reporter.measure(BuildTime.IC_UPDATE_CACHES) {
                    caches.platformCache.updateComplementaryFiles(dirtySources, expectActualTracker)
                    caches.lookupCache.update(lookupTracker, sourcesToCompile, removedKotlinSources)
                    updateIncrementalCache(emptyList(), caches.platformCache, changesCollector, null)
                }
                if (compilationMode is CompilationMode.Rebuild) {
                    if (withSnapshot) {
                        abiSnapshot.protos.putAll(changesCollector.protoDataChanges())
                    }
                    return false
                }

                val (dirtyLookupSymbols, dirtyClassFqNames, forceRecompile) = changesCollector.getDirtyData(
                    listOf(caches.platformCache),
                    reporter
                )
                val compiledInThisIterationSet = sourcesToCompile.toHashSet()

                val forceToRecompileFiles = mapClassesFqNamesToFiles(listOf(caches.platformCache), forceRecompile, reporter)
                with(dirtySources) {
                    clear()
                    addAll(mapLookupSymbolsToFiles(caches.lookupCache, dirtyLookupSymbols, reporter, excludes = compiledInThisIterationSet))
                    addAll(
                        mapClassesFqNamesToFiles(
                            listOf(caches.platformCache),
                            dirtyClassFqNames,
                            reporter,
                            excludes = compiledInThisIterationSet
                        )
                    )
                    if (!compiledInThisIterationSet.containsAll(forceToRecompileFiles)) {
                        addAll(forceToRecompileFiles)
                    }
                }

                buildDirtyLookupSymbols.addAll(dirtyLookupSymbols)
                buildDirtyFqNames.addAll(dirtyClassFqNames)
                return true
            }

            val diagnosticsReporter = DiagnosticReporterFactory.createReporter()
            val compilerEnvironment = ModuleCompilerEnvironment(projectEnvironment, diagnosticsReporter)

            val firModules = mutableListOf<ModuleCompilerAnalyzedOutput>()

            while (dirtySources.any() || runWithNoDirtyKotlinSources(caches)) {
                val isStepSuccessful =
                    incrementalCompilationStep(allCommonSourceFiles, allPlatformSourceFiles, projectEnvironment) { sourcesToCompile ->
                        val compilerInput = ModuleCompilerInput(
                            targetId,
                            CommonPlatforms.defaultCommonPlatform, sourcesToCompile.filter { it in allCommonSourceFiles },
                            JvmPlatforms.unspecifiedJvmPlatform, sourcesToCompile.filter { it in allPlatformSourceFiles },
                            configuration,
                            firModules.map { it.session.moduleData }
                        )

                        val analysisResults = compileModuleToAnalyzedFir(compilerInput, compilerEnvironment)
                        firModules.add(analysisResults)

                        // TODO: consider what to do if many compilations find a main class
                        if (mainClassFqName == null && configuration.get(JVMConfigurationKeys.OUTPUT_JAR) != null) {
                            mainClassFqName = findMainClass(analysisResults.fir)
                        }

                        !diagnosticsReporter.hasErrors
                    }
                if (!isStepSuccessful) break
            }

            val extensions = JvmGeneratorExtensionsImpl(configuration)
            val irGenerationExtensions =
                (projectEnvironment as? VfsBasedProjectEnvironment)?.project?.let { IrGenerationExtension.getInstances(it) }.orEmpty()
            val signaturer = JvmIdSignatureDescriptor(JvmDescriptorMangler(null))
            val allCommonFirFiles = firModules.flatMap { it.session.moduleData.dependsOnDependencies }
                .map { it.session }
                .filter { it.kind == FirSession.Kind.Source }
                .flatMap { (it.firProvider as FirProviderImpl).getAllFirFiles() }

            val firstFirModule = firModules.first()

            val (irModuleFragment, symbolTable, components) = Fir2IrConverter.createModuleFragment(
                firstFirModule.session, firstFirModule.scopeSession, firModules.flatMap { it.fir } + allCommonFirFiles,
                firstFirModule.session.languageVersionSettings, signaturer,
                extensions, FirJvmKotlinMangler(firstFirModule.session), IrFactoryImpl,
                FirJvmVisibilityConverter,
                Fir2IrJvmSpecialAnnotationSymbolProvider(),
                irGenerationExtensions
            )

            val irInput = ModuleCompilerIrBackendInput(
                targetId,
                configuration,
                extensions,
                irModuleFragment,
                symbolTable,
                components,
                firstFirModule.session
            )

            val codegenOutput = generateCodeFromIr(irInput, compilerEnvironment)

            FirDiagnosticsCompilerResultsReporter.reportToMessageCollector(diagnosticsReporter, messageCollectorAdapter)

            writeOutputs(
                projectEnvironment,
                configuration,
                listOf(codegenOutput.generationState),
                mainClassFqName
            )

            val generatedFiles = outputItemsCollector.outputs.map(SimpleOutputItem::toGeneratedFile)
            reporter.measure(BuildTime.IC_UPDATE_CACHES) {
                caches.inputsCache.registerOutputForSourceFiles(generatedFiles)
                updateIncrementalCache(generatedFiles, caches.platformCache, ChangesCollector(), null)
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
            bufferingMessageCollector.flush(originalMessageCollector)
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