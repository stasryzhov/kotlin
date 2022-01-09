/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.ir.backend.js.ast.JsCapturedName
import org.jetbrains.kotlin.ir.backend.js.utils.JsGenerationContext
import org.jetbrains.kotlin.ir.backend.js.utils.emptyScope
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.js.backend.ast.*

class JsCallTransformer(
    private val jsCode: IrCall,
    private val context: JsGenerationContext
) {
    private val statements = getStatementsList()

    fun generateStatement(): JsStatement {
        return when (statements.size) {
            0 -> JsEmpty
            1 -> statements.single().withSource(jsCode, context)
            // TODO: use transparent block (e.g. JsCompositeBlock)
            else -> JsBlock(statements)
        }
    }

    fun generateExpression(): JsExpression {
        if (statements.isEmpty()) return JsPrefixOperation(JsUnaryOperator.VOID, JsIntLiteral(3)) // TODO: report warning or even error

        val lastStatement = statements.last()
        if (statements.size == 1) {
            if (lastStatement is JsExpressionStatement) return lastStatement.expression.withSource(jsCode, context)
        }

        val newStatements = statements.toMutableList()

        when (lastStatement) {
            is JsReturn -> {
            }
            is JsExpressionStatement -> {
                newStatements[statements.lastIndex] = JsReturn(lastStatement.expression)
            }
            // TODO: report warning or even error
            else -> newStatements += JsReturn(JsPrefixOperation(JsUnaryOperator.VOID, JsIntLiteral(3)))
        }

        val syntheticFunction = JsFunction(emptyScope, JsBlock(newStatements), "")
        return JsInvocation(syntheticFunction).withSource(jsCode, context)
    }

    private fun getStatementsList(): List<JsStatement> {
        val codeArgument = jsCode.getValueArgument(0) ?: compilationException("JsCode is expected", jsCode)
        val statements = translateJsCodeIntoStatementList(codeArgument, context.staticContext.backendContext)
        return statements?.withResolvedCapturedVariables() ?: compilationException("Cannot compute js code", jsCode)
    }

    private fun List<JsStatement>.withResolvedCapturedVariables(): List<JsStatement> {
        return apply { JsCodeTransformer(context).acceptList(this) }
    }
}

private class JsCodeTransformer(private val context: JsGenerationContext) : RecursiveJsVisitor() {
    override fun visitNameRef(nameRef: JsNameRef) {
        val name = nameRef.name as? JsCapturedName ?: return
        nameRef.name = when (val captured = name.captured) {
            is IrGetValue -> context.getNameForValueDeclaration(captured.symbol.owner)
            is IrFunctionReference -> context.getNameForStaticFunction(captured.symbol.owner as IrSimpleFunction)
            else -> compilationException("Wrong IR node was provided into captured list", captured)
        }
        super.visitNameRef(nameRef)
    }
}