/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.extractTypeParameters
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.multiplatform.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

// `doRemove` means should expect-declaration be removed from IR
@OptIn(ObsoleteDescriptorBasedAPI::class)
class ExpectDeclarationRemover(val symbolTable: ReferenceSymbolTable, private val doRemove: Boolean) : IrElementVisitorVoid {
    private val typeParameterSubstitutionMap = mutableMapOf<Pair<IrFunction, IrFunction>, Map<IrTypeParameter, IrTypeParameter>>()

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitFile(declaration: IrFile) {
        super.visitFile(declaration)
        declaration.declarations.removeAll {
            shouldRemoveTopLevelDeclaration(it)
        }
    }

    override fun visitValueParameter(declaration: IrValueParameter) {
        super.visitValueParameter(declaration)
        tryCopyDefaultArguments(declaration)
    }

    fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {

        if (declaration.isTopLevelDeclaration && shouldRemoveTopLevelDeclaration(declaration)) {
            return emptyList()
        }

        if (declaration is IrValueParameter) {
            tryCopyDefaultArguments(declaration)
        }

        return null
    }

    private fun shouldRemoveTopLevelDeclaration(declaration: IrDeclaration): Boolean {
        return doRemove && when (declaration) {
            is IrClass -> declaration.isExpect
            is IrProperty -> declaration.isExpect
            is IrFunction -> declaration.isExpect
            else -> false
        }
    }

    private fun isOptionalAnnotationClass(klass: IrClass): Boolean {
        return klass.kind == ClassKind.ANNOTATION_CLASS &&
                klass.isExpect &&
                klass.annotations.hasAnnotation(OptionalAnnotationUtil.OPTIONAL_EXPECTATION_FQ_NAME)
    }

    private fun tryCopyDefaultArguments(declaration: IrValueParameter) {
        // Keep actual default value if present. They are generally not allowed but can be suppressed with
        // @Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
        if (declaration.defaultValue != null) {
            return
        }

        val function = declaration.parent as? IrFunction ?: return

        if (function is IrConstructor) {
            if (isOptionalAnnotationClass(function.constructedClass)) {
                return
            }
        }

        if (!function.descriptor.isActual) return

        val index = declaration.index

        if (index < 0) return

        assert(function.valueParameters[index] == declaration)

        // If the containing declaration is an `expect class` that matches an `actual typealias`,
        // the `actual fun` or `actual constructor` for this may be in a different module.
        // Nothing we can do with those.
        // TODO they may not actually have the defaults though -- may be a frontend bug.
        val expectFunction =
            function.descriptor.findExpectForActual().safeAs<FunctionDescriptor>()?.let { symbolTable.referenceFunction(it).owner }
                ?: return

        val defaultValue = expectFunction.valueParameters[index].defaultValue ?: return

        val expectToActual = expectFunction to function
        if (expectToActual !in typeParameterSubstitutionMap) {
            val functionTypeParameters = extractTypeParameters(function)
            val expectFunctionTypeParameters = extractTypeParameters(expectFunction)

            expectFunctionTypeParameters.zip(functionTypeParameters).let { typeParametersMapping ->
                typeParameterSubstitutionMap[expectToActual] = typeParametersMapping.toMap()
            }
        }

        defaultValue.let { originalDefault ->
            declaration.defaultValue = declaration.factory.createExpressionBody(originalDefault.startOffset, originalDefault.endOffset) {
                expression = originalDefault.expression
                    .deepCopyWithSymbols(function) { symbolRemapper, _ ->
                        DeepCopyIrTreeWithSymbols(
                            symbolRemapper,
                            IrTypeParameterRemapper(typeParameterSubstitutionMap.getValue(expectToActual))
                        )
                    }
                    .remapExpectValueSymbols()
            }
        }
    }

    private fun MemberDescriptor.findActualForExpect(): MemberDescriptor? {
        if (!isExpect) error(this)
        return findCompatibleActualsForExpected(module).singleOrNull()

    }

    private fun MemberDescriptor.findExpectForActual(): MemberDescriptor? {
        if (!isActual) error(this)
        return findCompatibleExpectsForActual().singleOrNull()
    }

    private fun IrExpression.remapExpectValueSymbols(): IrExpression {
        return this.transform(object : IrElementTransformerVoid() {

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                expression.transformChildrenVoid()
                val newValue = remapExpectValue(expression.symbol)
                    ?: return expression

                return IrGetValueImpl(
                    expression.startOffset,
                    expression.endOffset,
                    newValue.type,
                    newValue.symbol,
                    expression.origin
                )
            }
        }, data = null)
    }

    private fun remapExpectValue(symbol: IrValueSymbol): IrValueParameter? {
        if (symbol !is IrValueParameterSymbol) {
            return null
        }

        val parameter = symbol.owner

        return when (val parent = parameter.parent) {
            is IrClass -> {
                assert(parameter == parent.thisReceiver)
                symbolTable.referenceClass(parent.descriptor.findActualForExpect() as ClassDescriptor).owner.thisReceiver!!
            }

            is IrFunction -> {
                val actualFunction =
                    symbolTable.referenceFunction(parent.descriptor.findActualForExpect() as FunctionDescriptor).owner
                when (parameter) {
                    parent.dispatchReceiverParameter ->
                        actualFunction.dispatchReceiverParameter!!
                    parent.extensionReceiverParameter ->
                        actualFunction.extensionReceiverParameter!!
                    else -> {
                        assert(parent.valueParameters[parameter.index] == parameter)
                        actualFunction.valueParameters[parameter.index]
                    }
                }
            }

            else -> error(parent)
        }
    }
}
