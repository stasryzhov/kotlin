/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.translateJsCodeIntoStatementList
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.util.isFunctionOrKFunction
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.js.backend.ast.*

// Outlines `kotlin.js.js(code: String)` calls where JS code references Kotlin locals.
// Makes locals usages explicit.
class JsCodeVariableCaptureLowering(val backendContext: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        // Fast path to avoid tracking locals scopes for bodies without js() calls
        if (!irBody.containsCallsTo(backendContext.intrinsics.jsCode))
            return

        val replacer = JsCodeVariableCapturingTransformer(backendContext, container)
        irBody.transformChildrenVoid(replacer)
    }
}

private fun IrElement.containsCallsTo(symbol: IrFunctionSymbol): Boolean {
    var result = false
    acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            if (expression.symbol == symbol) {
                result = true
            }
            super.visitCall(expression)
        }
    })

    return result
}

private class JsCodeVariableCapturingTransformer(
    val backendContext: JsIrBackendContext,
    val container: IrDeclaration,
) : IrElementTransformerVoidWithContext() {
    val localScopes: MutableList<MutableMap<String, MutableList<IrDeclarationWithName>>> =
        mutableListOf(mutableMapOf())

    init {
        if (container is IrFunction) {
            container.valueParameters.forEach {
                registerDeclaration(it)
            }
        }
    }

    inline fun <T> withLocalScope(body: () -> T): T {
        localScopes.push(mutableMapOf())
        val res = body()
        localScopes.pop()
        return res
    }

    fun registerDeclaration(irDeclaration: IrDeclarationWithName) {
        val name = irDeclaration.name
        if (!name.isSpecial) {
            val identifier = name.identifier
            val currentScope = localScopes.lastOrNull()
                ?: compilationException(
                    "Expecting a scope",
                    irDeclaration
                )
            val variablesList = currentScope[identifier] ?: mutableListOf()
            variablesList.add(irDeclaration)
            currentScope[identifier] = variablesList
        }
    }


    private fun findDeclarationWithName(name: String, isCalled: Boolean = false): IrDeclarationWithName? {
        for (i in (localScopes.size - 1) downTo 0) {
            val scope = localScopes[i]
            val variants = scope[name] ?: continue
            val possibleCapture = when {
                isCalled -> variants.find { it.isFunction() }
                else -> variants.last()
            }
            return possibleCapture ?: continue
        }
        return null
    }

    private fun IrDeclarationWithName.isFunction(): Boolean {
        return when (this) {
            is IrSimpleFunction -> true
            is IrValueParameter -> type.isFunctionOrKFunction() || type == backendContext.dynamicType
            is IrValueDeclaration -> type.isFunctionOrKFunction() || type == backendContext.dynamicType
            else -> false
        }
    }

    override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
        return withLocalScope { super.visitContainerExpression(expression) }
    }

    override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement {
        return withLocalScope { super.visitDeclaration(declaration) }
    }

    override fun visitValueParameterNew(declaration: IrValueParameter): IrStatement {
        return super.visitValueParameterNew(declaration).also { registerDeclaration(declaration) }
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        return super.visitVariable(declaration).also { registerDeclaration(declaration) }
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        return super.visitSimpleFunction(declaration).also { registerDeclaration(declaration) }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        return expression.withCapturedVarsByJsCodeIfNeeded() ?: super.visitCall(expression)
    }

    fun IrCall.withCapturedVarsByJsCodeIfNeeded(): IrExpression? {
        if (symbol != backendContext.intrinsics.jsCode) return null

        val jsCodeArg = getValueArgument(0) ?: compilationException("Expected js code string", this)
        val jsStatements = translateJsCodeIntoStatementList(jsCodeArg, backendContext) ?: return null
        val scopesCollector = JsScopesCollector().apply { acceptList(jsStatements) }
        // Collect used Kotlin local variables and parameters.
        val capturedNameSaver = JsCapturedNameSaver(scopesCollector).apply { acceptList(jsStatements) }

        if (!capturedNameSaver.haveCapturedVariablesInside()) return null

        return with(backendContext.createIrBuilder(container.symbol)) {
            irCall(backendContext.intrinsics.jsCodeCaptured.owner).apply {
                putValueArgument(0, jsCodeArg)
                putValueArgument(
                    1,
                    IrVarargImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        backendContext.dynamicType,
                        backendContext.dynamicType,
                        capturedNameSaver.getCapturedNames()
                    )
                )
            }
        }
    }

    private inner class JsCapturedNameSaver(val scopesInfo: JsScopesCollector) : RecursiveJsVisitor() {
        private val captured = mutableSetOf<IrVarargElement>()
        private val functionStack = mutableListOf<JsFunction>()

        fun haveCapturedVariablesInside(): Boolean {
            return captured.isNotEmpty()
        }

        fun getCapturedNames(): List<IrVarargElement> {
            return captured.toList()
        }

        override fun visitFunction(x: JsFunction) {
            functionStack.push(x)
            super.visitFunction(x)
            functionStack.pop()
        }

        override fun visitNameRef(nameRef: JsNameRef) {
            super.visitNameRef(nameRef)
            // With this approach we should be able to find all usages of Kotlin variables in JS code.
            // We will also collect shadowed usages, but it is OK since the same shadowing will be present in generated JS code.
            if (nameRef.qualifier != null || nameRef.isException()) return
            // Keeping track of processed names to avoid registering them multiple times
            val declaration = findDeclarationWithName(nameRef.ident, isCalled = false)
            capture(declaration?.toCapturableReference() ?: return)
        }

        override fun visitInvocation(invocation: JsInvocation) {
            val nameRef = invocation.qualifier as? JsNameRef
            val target = nameRef?.takeIf { it.qualifier == null && !it.isException() } ?: return super.visitInvocation(invocation)
            val declaration = findDeclarationWithName(target.ident, isCalled = true)
            val capturableReference = declaration?.toCapturableReference()

            if (capturableReference != null) {
                capture(capturableReference)
            }

            acceptList(invocation.arguments)
        }

        private fun JsNameRef.isException(): Boolean {
            return scopesInfo.isTheVarWithNameExistsInScopeOf(functionStack.peek(), ident)
        }

        private fun capture(element: IrVarargElement) {
            captured.add(element)
        }

        private fun IrDeclarationWithName.toCapturableReference(): IrVarargElement? =
            when (this) {
                is IrValueDeclaration -> IrGetValueImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    backendContext.dynamicType,
                    symbol
                )
                is IrSimpleFunction -> IrFunctionReferenceImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    backendContext.dynamicType,
                    symbol,
                    0,
                    0,
                )
                else -> null
            }
    }
}

class JsScopesCollector : RecursiveJsVisitor() {
    private val functionsStack = mutableListOf(Scope(null))
    private val functionalScopes = mutableMapOf<JsFunction?, Scope>(null to functionsStack.first())

    private class Scope(val parent: Scope?) {
        val variables = hashSetOf<String>()

        fun add(variableName: String) {
            variables.add(variableName)
        }

        fun isVariableWithNameExists(variableName: String): Boolean {
            return variables.contains(variableName) ||
                    parent?.isVariableWithNameExists(variableName) == true
        }
    }

    override fun visitVars(x: JsVars) {
        super.visitVars(x)
        val currentScope = functionsStack.last()
        x.vars.forEach { currentScope.add(it.name.ident) }
    }

    override fun visitFunction(x: JsFunction) {
        val parentScope = functionsStack.last()
        val newScope = Scope(parentScope).apply {
            val name = x.name?.ident
            if (name != null) add(name)
            x.parameters.forEach { add(it.name.ident) }
        }
        functionsStack.push(newScope)
        functionalScopes[x] = newScope
        super.visitFunction(x)
        functionsStack.pop()
    }

    fun isTheVarWithNameExistsInScopeOf(function: JsFunction?, variableName: String): Boolean {
        return functionalScopes[function]!!.isVariableWithNameExists(variableName)
    }
}
