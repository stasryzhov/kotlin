/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer

import org.jetbrains.kotlin.commonizer.cli.*
import java.io.File
import java.net.URLClassLoader

public fun CliCommonizer(classpath: Iterable<File>): CliCommonizer {
    return CliCommonizer(URLClassLoader(classpath.map { it.absoluteFile.toURI().toURL() }.toTypedArray()))
}

public fun CliCommonizer(classLoader: ClassLoader): CliCommonizer {
    return CliCommonizer(CommonizerClassLoaderExecutor(classLoader))
}

public class CliCommonizer(private val executor: Executor) : NativeDistributionCommonizer, CInteropCommonizer {
    public fun interface Executor {
        public operator fun invoke(arguments: List<String>)
    }

    override fun commonizeLibraries(
        konanHome: File,
        inputLibraries: Set<File>,
        dependencyLibraries: Set<CommonizerDependency>,
        outputTargets: Set<SharedCommonizerTarget>,
        outputDirectory: File,
        logLevel: CommonizerLogLevel,
        additionalSettings: List<CommonizerSetting<*>>,
    ) {
        if (inputLibraries.isEmpty()) return
        val arguments = mutableListOf<String>().apply {
            add("native-klib-commonize")
            add(NativeDistributionOptionType.argumentString); add(konanHome.absolutePath)
            add(InputLibrariesOptionType.argumentString); add(inputLibraries.joinToString(";") { it.absolutePath })
            add(OutputCommonizerTargetsOptionType.argumentString); add(outputTargets.joinToString(";") { it.identityString })
            add(OutputOptionType.argumentString); add(outputDirectory.absolutePath)
            if (dependencyLibraries.isNotEmpty()) {
                add(DependencyLibrariesOptionType.argumentString); add(dependencyLibraries.joinToString(";"))
            }
            add(LogLevelOptionType.argumentString); add(logLevel.name.lowercase())
            for ((setting, settingValue) in additionalSettings) {
                add(setting.argumentString); add(settingValue.toString())
            }
        }
        executor(arguments)
    }

    override fun commonizeNativeDistribution(
        konanHome: File,
        outputDirectory: File,
        outputTargets: Set<SharedCommonizerTarget>,
        logLevel: CommonizerLogLevel,
        additionalSettings: List<CommonizerSetting<*>>,
    ) {
        val arguments = mutableListOf<String>().apply {
            add("native-dist-commonize")
            add(NativeDistributionOptionType.argumentString); add(konanHome.absolutePath)
            add(OutputOptionType.argumentString); add(outputDirectory.absolutePath)
            add(OutputCommonizerTargetsOptionType.argumentString); add(outputTargets.joinToString(";") { it.identityString })
            add(LogLevelOptionType.argumentString); add(logLevel.name.lowercase());
            for ((setting, settingValue) in additionalSettings) {
                add(setting.argumentString); add(settingValue.toString())
            }
        }

        executor(arguments)
    }
}

private class CommonizerClassLoaderExecutor(private val commonizerClassLoader: ClassLoader) : CliCommonizer.Executor {
    companion object {
        private const val commonizerMainClass = "org.jetbrains.kotlin.commonizer.cli.CommonizerCLI"
        private const val commonizerMainFunction = "main"
    }

    @Throws(Throwable::class)
    override fun invoke(arguments: List<String>) {
        val commonizerMainClass = commonizerClassLoader.loadClass(commonizerMainClass)
        val commonizerMainMethod = commonizerMainClass.methods.singleOrNull { it.name == commonizerMainFunction }
            ?: throw IllegalArgumentException(
                "Missing or conflicting $commonizerMainFunction function in " +
                        "Class ${commonizerMainClass.name} from ClassLoader $commonizerClassLoader"
            )
        commonizerMainMethod.invoke(null, arguments.toTypedArray())
    }
}
