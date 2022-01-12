/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.ir.backend.js.lower.JsScopesCollector
import org.jetbrains.kotlin.ir.backend.js.utils.JsGenerationContext
import org.jetbrains.kotlin.ir.backend.js.utils.emptyScope
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.js.backend.ast.*

class JsCallTransformer(
    private val jsCode: IrCall,
    private val context: JsGenerationContext
) {
    private val scopes = JsScopesCollector()
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
        return takeIf { context.checkIfJsCodeCaptured(jsCode.symbol) }
            ?.also { scopes.acceptList(this) }
            ?.apply {
                val arrayCall = jsCode.getValueArgument(1) as IrCall
                val capturedList = arrayCall.getValueArgument(0) as IrVararg
                JsCodeTransformer(capturedList.elements, context, scopes).acceptList(this)
            } ?: this
    }
}

private class JsCodeTransformer(
    captured: List<IrVarargElement>,
    private val context: JsGenerationContext,
    private val scopes: JsScopesCollector,
) : RecursiveJsVisitor() {
    private val functionStack = mutableListOf<JsFunction>()
    private val captured = captured.groupBy {
        when (it) {
            is IrGetValue -> it.symbol.owner.name.identifier
            is IrFunctionReference -> it.symbol.owner.name.identifier
            else -> compilationException("Wrong IR node was provided into captured list", it)
        }
    }

    override fun visitFunction(x: JsFunction) {
        functionStack.push(x)
        super.visitFunction(x)
        functionStack.pop()
    }

    override fun visitNameRef(nameRef: JsNameRef) {
        super.visitNameRef(nameRef)
        val ident = nameRef.ident.takeIf {
            nameRef.qualifier == null && !nameRef.isException()
        } ?: return

        val (capturedVariable, capturedFunction) = findCapturedNames(ident)

        if (capturedFunction == null && capturedVariable == null) return

        nameRef.name = when {
            capturedVariable != null -> context.getNameForValueDeclaration(capturedVariable.symbol.owner)
            capturedFunction != null -> context.getNameForStaticFunction(capturedFunction.symbol.owner)
            else -> return
        }
    }

    override fun visitInvocation(invocation: JsInvocation) {
        val nameRef = invocation.qualifier as? JsNameRef

        if (nameRef == null || nameRef.qualifier != null || nameRef.isException()) {
            return super.visitInvocation(invocation)
        }

        val (capturedVariable, capturedFunction) = findCapturedNames(nameRef.ident)

        nameRef.name = when {
            capturedFunction != null -> context.getNameForStaticFunction(capturedFunction.symbol.owner)
            capturedVariable != null -> context.getNameForValueDeclaration(capturedVariable.symbol.owner)
            else -> return
        }

        acceptList(invocation.arguments)
    }

    private fun JsNameRef.isException(): Boolean {
        return scopes.isTheVarWithNameExistsInScopeOf(functionStack.peek(), ident)
    }

    private fun findCapturedNames(name: String): Pair<IrValueDeclaration?, IrSimpleFunction?> {
        return findCapturedVariableWithName(name) to findCapturedFunctionWithName(name)
    }

    private fun findCapturedFunctionWithName(name: String): IrSimpleFunction? {
        val capturedCallableReference = captured[name]?.findIsInstance<IrFunctionReference>()
        return capturedCallableReference?.symbol?.owner as IrSimpleFunction?
    }

    private fun findCapturedVariableWithName(name: String): IrValueDeclaration? {
        val capturedValueReference = captured[name]?.findIsInstance<IrGetValue>()
        return capturedValueReference?.symbol?.owner
    }

    private inline fun <reified T> Iterable<*>.findIsInstance(): T? {
        return find { it is T } as? T
    }
}