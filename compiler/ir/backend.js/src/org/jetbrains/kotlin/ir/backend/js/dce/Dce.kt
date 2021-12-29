/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.dce

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.export.isExported
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.config.RuntimeDiagnostic

fun eliminateDeadDeclarations(
    modules: Iterable<IrModuleFragment>,
    context: JsIrBackendContext,
    removeUnusedAssociatedObjects: Boolean = true,
) {
    val allRoots = buildRoots(modules, context)

    val printReachabilityInfo =
        context.configuration.getBoolean(JSConfigurationKeys.PRINT_REACHABILITY_INFO) ||
                java.lang.Boolean.getBoolean("kotlin.js.ir.dce.print.reachability.info")

    val usefulDeclarations = JsUsefulDeclarationProcessor(context, printReachabilityInfo, removeUnusedAssociatedObjects)
        .collectDeclarations(allRoots, context.additionalExportedDeclarations)

    val uselessDeclarationsProcessor = UselessDeclarationsRemover(removeUnusedAssociatedObjects, usefulDeclarations, context, context.dceRuntimeDiagnostic)
    modules.forEach { module ->
        module.files.forEach {
            it.acceptVoid(uselessDeclarationsProcessor)
        }
    }
}

private fun IrField.isConstant(): Boolean {
    return correspondingPropertySymbol?.owner?.isConst ?: false
}

private fun IrDeclaration.addRootsTo(to: MutableCollection<IrDeclaration>, context: JsIrBackendContext) {
    when {
        this is IrProperty -> {
            backingField?.addRootsTo(to, context)
            getter?.addRootsTo(to, context)
            setter?.addRootsTo(to, context)
        }
        isEffectivelyExternal() -> {
            to += this
        }
        isExported(context) -> {
            to += this
        }
        this is IrField -> {
            // TODO: simplify
            if ((initializer != null && !isKotlinPackage() || correspondingPropertySymbol?.owner?.isExported(context) == true) && !isConstant()) {
                to += this
            }
        }
        this is IrSimpleFunction -> {
            if (correspondingPropertySymbol?.owner?.isExported(context) == true) {
                to += this
            }
        }
    }
}

private fun buildRoots(modules: Iterable<IrModuleFragment>, context: JsIrBackendContext): List<IrDeclaration> {
    val rootDeclarations = mutableListOf<IrDeclaration>()
    val allFiles = (modules.flatMap { it.files } + context.packageLevelJsModules + context.externalPackageFragment.values)
    allFiles.forEach { file ->
        file.declarations.forEach { declaration ->
            declaration.addRootsTo(rootDeclarations, context)
        }
    }

    rootDeclarations += context.testFunsPerFile.values

    val dceRuntimeDiagnostic = context.dceRuntimeDiagnostic
    if (dceRuntimeDiagnostic != null) {
        rootDeclarations += dceRuntimeDiagnostic.unreachableDeclarationMethod(context).owner
    }

    // TODO: Generate calls to main as IR->IR lowering and reference coroutineEmptyContinuation directly
    JsMainFunctionDetector(context).getMainFunctionOrNull(modules.last())?.let { mainFunction ->
        rootDeclarations += mainFunction
        if (mainFunction.isLoweredSuspendFunction(context)) {
            rootDeclarations += context.coroutineEmptyContinuation.owner
        }
    }

    return rootDeclarations
}

internal fun RuntimeDiagnostic.unreachableDeclarationMethod(context: JsIrBackendContext) =
    when (this) {
        RuntimeDiagnostic.LOG -> context.intrinsics.jsUnreachableDeclarationLog
        RuntimeDiagnostic.EXCEPTION -> context.intrinsics.jsUnreachableDeclarationException
    }

internal fun IrField.isKotlinPackage() =
    fqNameWhenAvailable?.asString()?.startsWith("kotlin") == true

