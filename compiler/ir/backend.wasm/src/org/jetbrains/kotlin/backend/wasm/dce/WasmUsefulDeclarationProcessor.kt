/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.dce

import org.jetbrains.kotlin.backend.common.ir.isOverridable
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.ir2wasm.*
import org.jetbrains.kotlin.backend.wasm.utils.*
import org.jetbrains.kotlin.ir.backend.js.dce.UsefulDeclarationProcessor
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.kotlin.wasm.ir.*

internal class WasmUsefulDeclarationProcessor(
    override val context: WasmBackendContext,
    printReachabilityInfo: Boolean
) : UsefulDeclarationProcessor(printReachabilityInfo, removeUnusedAssociatedObjects = false) {

    private val unitGetInstance: IrSimpleFunction = context.findUnitGetInstanceFunction()

    override val bodyVisitor: BodyVisitorBase = object : BodyVisitorBase() {
        override fun <T> visitConst(expression: IrConst<T>) = when (expression.kind) {
            is IrConstKind.Null -> expression.type.enqueueType("expression type")
            is IrConstKind.String -> context.wasmSymbols.stringGetLiteral.owner
                .enqueue("String literal intrinsic getter stringGetLiteral")
            else -> Unit
        }

        private fun tryToProcessIntrinsicCall(call: IrCall): Boolean = when (call.symbol) {
            context.wasmSymbols.unboxIntrinsic -> {
                val fromType = call.getTypeArgument(0)
                if (fromType != null && !fromType.isNothing() && !fromType.isNullableNothing()) {
                    val backingField = call.getTypeArgument(1)
                        ?.let { context.inlineClassesUtils.getInlinedClass(it) }
                        ?.let { getInlineClassBackingField(it) }
                    if (backingField != null) {
                        backingField.parentAsClass.enqueue("type for unboxIntrinsic")
                        backingField.enqueue("backing inline class field for unboxIntrinsic")
                    }
                }
                true
            }
            context.wasmSymbols.wasmClassId,
            context.wasmSymbols.wasmInterfaceId,
            context.wasmSymbols.wasmRefCast -> {
                call.getTypeArgument(0)?.getClass()?.symbol?.owner?.enqueue("generic intrinsic ${call.symbol.owner.name}")
                true
            }
            else -> false
        }

        private fun tryToProcessWasmOpIntrinsicCall(call: IrCall, function: IrFunction): Boolean {
            if (function.hasWasmNoOpCastAnnotation()) {
                return true
            }

            val opString = function.getWasmOpAnnotation()
            if (opString != null) {
                val op = WasmOp.valueOf(opString)
                when (op.immediates.size) {
                    0 -> {
                        if (op == WasmOp.REF_TEST) {
                            call.getTypeArgument(0)?.getRuntimeClass?.enqueue("REF_TEST")
                        }
                    }
                    1 -> {
                        if (op.immediates.firstOrNull() == WasmImmediateKind.STRUCT_TYPE_IDX) {
                            function.dispatchReceiverParameter?.type?.classOrNull?.owner?.enqueue("STRUCT_TYPE_IDX")
                        }
                    }
                }
                return true
            }
            return false
        }

        override fun visitCall(expression: IrCall) {
            super.visitCall(expression)

            if (expression.symbol == context.wasmSymbols.boxIntrinsic) {
                expression.getTypeArgument(0)?.getRuntimeClass?.enqueue("boxIntrinsic")
                return
            }

            if (expression.symbol == unitGetInstance.symbol) {
                unitGetInstance.enqueue("unitGetInstance")
                return
            }

            val function: IrFunction = expression.symbol.owner.realOverrideTarget
            if (function.returnType == context.irBuiltIns.unitType) {
                unitGetInstance.enqueue("unitGetInstance")
            }

            if (tryToProcessIntrinsicCall(expression)) return
            if (tryToProcessWasmOpIntrinsicCall(expression, function)) return

            val isSuperCall = expression.superQualifierSymbol != null
            if (function is IrSimpleFunction && function.isOverridable && !isSuperCall) {
                val klass = function.parentAsClass
                if (!klass.isInterface) {
                    context.wasmSymbols.getVirtualMethodId.owner.enqueue("getVirtualMethodId")
                    function.symbol.owner.enqueue("referenceFunctionType")
                } else {
                    klass.symbol.owner.enqueue("referenceInterfaceId")
                    context.wasmSymbols.getInterfaceImplId.owner.enqueue("getInterfaceImplId")
                    function.symbol.owner.enqueue("referenceInterfaceTable and referenceFunctionType")
                }
            }
        }
    }

    private fun IrType.getInlinedValueTypeIfAny(): IrType? = when (this) {
        context.irBuiltIns.booleanType,
        context.irBuiltIns.byteType,
        context.irBuiltIns.shortType,
        context.irBuiltIns.charType,
        context.irBuiltIns.booleanType,
        context.irBuiltIns.byteType,
        context.irBuiltIns.shortType,
        context.irBuiltIns.intType,
        context.irBuiltIns.charType,
        context.irBuiltIns.longType,
        context.irBuiltIns.floatType,
        context.irBuiltIns.doubleType,
        context.irBuiltIns.nothingType,
        context.wasmSymbols.voidType -> null
        else -> when {
            isBuiltInWasmRefType(this) -> null
            erasedUpperBound?.isExternal == true -> null
            else -> when (val ic = context.inlineClassesUtils.getInlinedClass(this)) {
                null -> this
                else -> context.inlineClassesUtils.getInlineClassUnderlyingType(ic).getInlinedValueTypeIfAny()
            }
        }
    }

    private fun IrType.enqueueRuntimeClassOrAny(info: String): Unit =
        (this.getRuntimeClass ?: context.wasmSymbols.any.owner).enqueue(info, isContagious = false)

    private fun IrType.enqueueType(info: String) {
        getInlinedValueTypeIfAny()
            ?.enqueueRuntimeClassOrAny(info)
    }

    private fun IrDeclaration.enqueueParentClass(): Unit = inEnclosingDeclaration {
        parentClassOrNull?.enqueue("parent class", isContagious = false)
    }

    override fun processField(irField: IrField) {
        super.processField(irField)
        irField.enqueueParentClass()
        irField.inEnclosingDeclaration {
            irField.type.enqueueType("field types")
        }
    }

    override fun processClass(irClass: IrClass) {
        super.processClass(irClass)

        irClass.inEnclosingDeclaration {
            irClass.getWasmArrayAnnotation()?.type
                ?.enqueueType("array type for wasm array annotated")

            if (context.inlineClassesUtils.isClassInlineLike(irClass)) {
                irClass.declarations
                    .firstIsInstanceOrNull<IrConstructor>()
                    ?.takeIf { it.isPrimary }
                    ?.enqueue("inline class primary ctor")
            }
        }
    }

    private fun IrValueParameter.enqueueValueParameterType() {
        if (context.inlineClassesUtils.shouldValueParameterBeBoxed(this)) {
            type.enqueueRuntimeClassOrAny("function ValueParameterType")
        } else {
            type.enqueueType("function ValueParameterType")
        }
    }

    private fun processIrFunction(irFunction: IrFunction) {
        if (irFunction.isFakeOverride) return

        val isIntrinsic = irFunction.hasWasmNoOpCastAnnotation() || irFunction.getWasmOpAnnotation() != null
        if (isIntrinsic) return

        irFunction.inEnclosingDeclaration {
            irFunction.getEffectiveValueParameters().forEach { it.enqueueValueParameterType() }
            irFunction.returnType.enqueueType("function return type")
        }
    }

    override fun processSimpleFunction(irFunction: IrSimpleFunction) {
        super.processSimpleFunction(irFunction)
        irFunction.enqueueParentClass()
        if (irFunction.isFakeOverride) {
            irFunction.inEnclosingDeclaration {
                irFunction.overriddenSymbols.forEach { overridden ->
                    overridden.owner.enqueue("original for fake-override")
                }
            }
        }
        processIrFunction(irFunction)
    }

    override fun processConstructor(irConstructor: IrConstructor) {
        super.processConstructor(irConstructor)
        if (context.inlineClassesUtils.isClassInlineLike(irConstructor.parentAsClass)) return
        processIrFunction(irConstructor)
    }

    override fun isExported(declaration: IrDeclaration): Boolean = declaration.isJsExport()
}