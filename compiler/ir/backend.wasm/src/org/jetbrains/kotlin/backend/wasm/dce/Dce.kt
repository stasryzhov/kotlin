/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.dce

import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.builtins.StandardNames.BUILT_INS_PACKAGE_FQ_NAME
import org.jetbrains.kotlin.ir.backend.js.dce.UselessDeclarationsRemover
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.name.isSubpackageOf

private val WasmBackendContext.initializersList
    get() = (fieldInitFunction.body as IrBlockBody).statements

internal fun eliminateDeadDeclarations(modules: List<IrModuleFragment>, context: WasmBackendContext) {
    val printReachabilityInfo =
        context.configuration.getBoolean(JSConfigurationKeys.PRINT_REACHABILITY_INFO) ||
                java.lang.Boolean.getBoolean("kotlin.wasm.dce.print.reachability.info")

    val fieldsToInitializers = context.initializersList.associate { (it as IrSetField).symbol.owner to it }

    val usefulDeclarations = WasmUsefulDeclarationProcessor(
        context = context,
        fieldsInitializers = fieldsToInitializers,
        printReachabilityInfo = printReachabilityInfo
    ).collectDeclarations(
        rootDeclarations = buildRoots(modules),
        additionalDeclarations = buildAdditionalDeclarations(context, fieldsToInitializers)
    )

    removeUnusedDeclarations(
        context = context,
        modules = modules,
        usefulDeclarations = usefulDeclarations + context.fieldInitFunction
    )
}

private fun removeUnusedDeclarations(
    context: WasmBackendContext,
    modules: List<IrModuleFragment>,
    usefulDeclarations: Set<IrDeclaration>
) {
    context.initializersList.removeIf {
        (it as IrSetField).symbol.owner !in usefulDeclarations
    }

    val remover = UselessDeclarationsRemover(
        removeUnusedAssociatedObjects = false,
        usefulDeclarations = usefulDeclarations,
        context = context,
        dceRuntimeDiagnostic = null,
    )

    modules.onAllFiles {
        acceptVoid(remover)
    }
}

private fun buildAdditionalDeclarations(context: WasmBackendContext, fieldsToInitializers: Map<IrField, IrSetField>): List<IrDeclaration> {
    fun Map.Entry<IrField, IrSetField>.initializerMustBeIncluded(): Boolean =
        (value.value !is IrConst<*> && !key.kotlinFqName.isSubpackageOf(BUILT_INS_PACKAGE_FQ_NAME)) ||
                key.correspondingPropertySymbol?.owner?.hasAnnotation(context.wasmSymbols.eagerInitialization) == true

    return buildList {
        add(context.irBuiltIns.throwableClass.owner)
        add(context.mainCallsWrapperFunction)
        for (initializedField in fieldsToInitializers) {
            if (initializedField.initializerMustBeIncluded()) {
                add(initializedField.key)
            }
        }
    }
}

private fun buildRoots(modules: List<IrModuleFragment>): List<IrDeclaration> = buildList {
    modules.onAllFiles {
        declarations.forEach { declaration ->
            if (declaration.isJsExport() || declaration.isEffectivelyExternal()) {
                add(declaration)
            }
        }
    }
}

private inline fun List<IrModuleFragment>.onAllFiles(body: IrFile.() -> Unit) {
    forEach { module ->
        module.files.forEach { file ->
            file.body()
        }
    }
}