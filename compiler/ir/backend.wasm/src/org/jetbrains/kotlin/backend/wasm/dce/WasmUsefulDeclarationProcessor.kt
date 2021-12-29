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
    private val fieldsInitializers: Map<IrField, IrSetField>,
    printReachabilityInfo: Boolean
) : UsefulDeclarationProcessor(printReachabilityInfo, removeUnusedAssociatedObjects = false) {

    private val unitGetInstance: IrSimpleFunction = context.findUnitGetInstanceFunction()

    override val bodyVisitor: BodyVisitorBase = object : BodyVisitorBase() {
        override fun <T> visitConst(expression: IrConst<T>, data: IrDeclaration) = when (expression.kind) {
            is IrConstKind.Null -> expression.type.enqueueType(data, "expression type")
            is IrConstKind.String -> context.wasmSymbols.stringGetLiteral.owner
                .enqueue(data, "String literal intrinsic getter stringGetLiteral")
            else -> Unit
        }

        private fun tryToProcessIntrinsicCall(call: IrCall, from: IrDeclaration): Boolean = when (call.symbol) {
            context.wasmSymbols.unboxIntrinsic -> {
                val fromType = call.getTypeArgument(0)
                if (fromType != null && !fromType.isNothing() && !fromType.isNullableNothing()) {
                    val backingField = call.getTypeArgument(1)
                        ?.let { context.inlineClassesUtils.getInlinedClass(it) }
                        ?.let { getInlineClassBackingField(it) }
                    if (backingField != null) {
                        backingField.parentAsClass.enqueue(from, "type for unboxIntrinsic")
                        backingField.enqueue(from, "backing inline class field for unboxIntrinsic")
                    }
                }
                true
            }
            context.wasmSymbols.wasmClassId,
            context.wasmSymbols.wasmInterfaceId,
            context.wasmSymbols.wasmRefCast -> {
                call.getTypeArgument(0)?.getClass()?.symbol?.owner?.enqueue(from, "generic intrinsic ${call.symbol.owner.name}")
                true
            }
            else -> false
        }

        private fun tryToProcessWasmOpIntrinsicCall(call: IrCall, function: IrFunction, from: IrDeclaration): Boolean {
            if (function.hasWasmNoOpCastAnnotation()) {
                return true
            }

            val opString = function.getWasmOpAnnotation()
            if (opString != null) {
                val op = WasmOp.valueOf(opString)
                when (op.immediates.size) {
                    0 -> {
                        if (op == WasmOp.REF_TEST) {
                            call.getTypeArgument(0)?.getRuntimeClass?.enqueue(from, "REF_TEST")
                        }
                    }
                    1 -> {
                        if (op.immediates.firstOrNull() == WasmImmediateKind.STRUCT_TYPE_IDX) {
                            function.dispatchReceiverParameter?.type?.classOrNull?.owner?.enqueue(from, "STRUCT_TYPE_IDX")
                        }
                    }
                }
                return true
            }
            return false
        }

        override fun visitCall(expression: IrCall, data: IrDeclaration) {
            super.visitCall(expression, data)

            if (expression.symbol == context.wasmSymbols.boxIntrinsic) {
                expression.getTypeArgument(0)?.getRuntimeClass?.enqueue(data, "boxIntrinsic")
                return
            }

            if (expression.symbol == unitGetInstance.symbol) {
                unitGetInstance.enqueue(data, "unitGetInstance")
                return
            }

            val function: IrFunction = expression.symbol.owner.realOverrideTarget
            if (function.returnType == context.irBuiltIns.unitType) {
                unitGetInstance.enqueue(data, "unitGetInstance")
                return
            }

            if (tryToProcessIntrinsicCall(expression, from = data)) return
            if (tryToProcessWasmOpIntrinsicCall(expression, function, from = data)) return

            val isSuperCall = expression.superQualifierSymbol != null
            if (function is IrSimpleFunction && function.isOverridable && !isSuperCall) {
                val klass = function.parentAsClass
                if (!klass.isInterface) {
                    context.wasmSymbols.getVirtualMethodId.owner.enqueue(data, "getVirtualMethodId")
                    function.symbol.owner.enqueue(data, "referenceFunctionType")
                } else {
                    klass.symbol.owner.enqueue(data, "referenceInterfaceId")
                    context.wasmSymbols.getInterfaceImplId.owner.enqueue(data, "getInterfaceImplId")
                    function.symbol.owner.enqueue(data, "referenceInterfaceTable and referenceFunctionType")
                }
            }
        }
    }

    private fun IrType.needToEnqueueType(): Boolean = when (this) {
        context.builtIns.booleanType,
        context.builtIns.byteType,
        context.builtIns.shortType,
        context.builtIns.charType,
        context.builtIns.booleanType,
        context.builtIns.byteType,
        context.builtIns.shortType,
        context.builtIns.intType,
        context.builtIns.charType,
        context.builtIns.longType,
        context.builtIns.floatType,
        context.builtIns.doubleType,
        context.builtIns.nothingType,
        context.wasmSymbols.voidType -> false
        else -> this.erasedUpperBound?.isExternal != true &&
                context.inlineClassesUtils.getInlinedClass(this) == null &&
                !isBuiltInWasmRefType(this)
    }

    private fun IrType.enqueueRuntimeClassOrAny(from: IrDeclaration, info: String): Unit =
        (this.getRuntimeClass ?: context.wasmSymbols.any.owner).enqueue(from, info, isContagious = false)

    private fun IrType.enqueueType(from: IrDeclaration, info: String) {
        if (needToEnqueueType()) {
            enqueueRuntimeClassOrAny(from, info)
        }
    }

    private fun IrDeclaration.enqueueParentClass() =
        parentClassOrNull?.enqueue(this, "parent class", isContagious = false)

    override fun processField(irField: IrField) {
        super.processField(irField)
        irField.enqueueParentClass()
        irField.type.enqueueType(irField, "field types")
        fieldsInitializers[irField]?.value?.accept(bodyVisitor, irField)
    }

    override fun processClass(irClass: IrClass) {
        super.processClass(irClass)

        irClass.getWasmArrayAnnotation()?.type
            ?.enqueueType(irClass, "array type for wasm array annotated")

        if (context.inlineClassesUtils.isClassInlineLike(irClass)) {
            irClass.declarations
                .firstIsInstanceOrNull<IrConstructor>()
                ?.takeIf { it.isPrimary }
                ?.enqueue(irClass, "inline class primary ctor")
        }
    }

    private fun IrValueParameter.enqueueValueParameterType(from: IrDeclaration) {
        if (context.inlineClassesUtils.shouldValueParameterBeBoxed(this)) {
            type.enqueueRuntimeClassOrAny(from, "function ValueParameterType")
        } else {
            type.enqueueType(from, "function ValueParameterType")
        }
    }

    private fun processIrFunction(irFunction: IrFunction) {
        if (irFunction.isFakeOverride) return

        val isIntrinsic = irFunction.hasWasmNoOpCastAnnotation() || irFunction.getWasmOpAnnotation() != null
        if (isIntrinsic) return

        irFunction.getEffectiveValueParameters().forEach { it.enqueueValueParameterType(irFunction) }
        irFunction.returnType.enqueueType(irFunction, "function return type")
    }

    override fun processSimpleFunction(irFunction: IrSimpleFunction) {
        super.processSimpleFunction(irFunction)
        irFunction.enqueueParentClass()
        if (irFunction.isFakeOverride) {
            irFunction.overriddenSymbols.forEach { overridden ->
                overridden.owner.enqueue(irFunction, "original for fake-override")
            }
        }
        processIrFunction(irFunction)
    }

    override fun processConstructor(irConstructor: IrConstructor) {
        super.processConstructor(irConstructor)
        if (context.inlineClassesUtils.isClassInlineLike(irConstructor.parentAsClass)) return
        processIrFunction(irConstructor)
    }

    override fun isExported(declaration: IrDeclaration): Boolean =
        declaration.isJsExport() || declaration.isEffectivelyExternal()
}