/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.dce

import org.jetbrains.kotlin.backend.common.ir.isMemberOfOpenClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.JsCommonBackendContext
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import java.util.*

abstract class UsefulDeclarationProcessor(
    private val printReachabilityInfo: Boolean,
    protected val removeUnusedAssociatedObjects: Boolean
) {
    abstract val context: JsCommonBackendContext

    protected fun getMethodOfAny(name: String): IrDeclaration =
        context.irBuiltIns.anyClass.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == name }

    protected val toStringMethod: IrDeclaration by lazy { getMethodOfAny("toString") }
    protected abstract fun isExported(declaration: IrDeclaration): Boolean
    protected abstract val bodyVisitor: BodyVisitorBase

    protected abstract inner class BodyVisitorBase : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildren(this, null)
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            super.visitFunctionAccess(expression)
            expression.symbol.owner.enqueue("function access")
        }

        override fun visitRawFunctionReference(expression: IrRawFunctionReference) {
            super.visitRawFunctionReference(expression)
            expression.symbol.owner.enqueue("raw function access")
        }

        override fun visitVariableAccess(expression: IrValueAccessExpression) {
            visitDeclarationReference(expression)
            expression.symbol.owner.enqueue("variable access")
        }

        override fun visitFieldAccess(expression: IrFieldAccessExpression) {
            super.visitFieldAccess(expression)
            expression.symbol.owner.enqueue("field access")
        }

        override fun visitStringConcatenation(expression: IrStringConcatenation) {
            super.visitStringConcatenation(expression)
            toStringMethod.enqueue("string concatenation")
        }
    }

    private fun addReachabilityInfoIfNeeded(
        to: IrDeclaration,
        description: String?,
        isContagiousOverridableDeclaration: Boolean,
        altFromFqn: String?
    ) {
        if (!printReachabilityInfo) return
        val fromFqn = (enclosingDeclaration as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: altFromFqn ?: "<unknown>"
        val toFqn = (to as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: "<unknown>"
        val comment = (description ?: "") + (if (isContagiousOverridableDeclaration) "[CONTAGIOUS!]" else "")
        val info = "\"$fromFqn\" -> \"$toFqn\"" + (if (comment.isBlank()) "" else " // $comment")
        reachabilityInfo.add(info)
    }

    protected var enclosingDeclaration: IrDeclaration? = null
    protected inline fun IrDeclaration?.inEnclosingDeclaration(body: () -> Unit) {
        val oldInDeclaration = enclosingDeclaration
        try {
            enclosingDeclaration = this
            body()
        } finally {
            enclosingDeclaration = oldInDeclaration
        }
    }

    protected fun IrDeclaration.enqueue(
        description: String?,
        isContagious: Boolean = true,
        altFromFqn: String? = null
    ) {
        // Ignore non-external IrProperty because we don't want to generate code for them and codegen doesn't support it.
        if (this is IrProperty && !this.isExternal) return

        // TODO check that this is overridable
        // it requires fixing how functions with default arguments is handled
        val isContagiousOverridableDeclaration = isContagious && this is IrOverridableDeclaration<*> && this.isMemberOfOpenClass

        addReachabilityInfoIfNeeded(this, description, isContagiousOverridableDeclaration, altFromFqn)

        if (isContagiousOverridableDeclaration) {
            contagiousReachableDeclarations.add(this as IrOverridableDeclaration<*>)
        }

        if (this !in result) {
            result.add(this)
            queue.addLast(this)
        }
    }

    // This collection contains declarations whose reachability should be propagated to overrides.
    // Overriding uncontagious declaration will not lead to becoming a declaration reachable.
    // By default, all declarations treated as contagious, it's not the most efficient, but it's safest.
    // In case when we access a declaration through a fake-override declaration, the original (real) one will not be marked as contagious,
    // so, later, other overrides will not be processed unconditionally only because it overrides a reachable declaration.
    //
    // The collection must be a subset of [result] set.
    private val contagiousReachableDeclarations = hashSetOf<IrOverridableDeclaration<*>>()
    protected val constructedClasses = hashSetOf<IrClass>()
    private val reachabilityInfo: MutableSet<String> = if (printReachabilityInfo) linkedSetOf() else Collections.emptySet()
    private val queue = ArrayDeque<IrDeclaration>()
    protected val result = hashSetOf<IrDeclaration>()
    protected val classesWithObjectAssociations = hashSetOf<IrClass>()

    protected open fun processField(irField: IrField): Unit = Unit

    protected open fun processClass(irClass: IrClass) {
        irClass.inEnclosingDeclaration {
            irClass.superTypes.forEach {
                (it.classifierOrNull as? IrClassSymbol)?.owner?.enqueue( "superTypes")
            }

            if (irClass.isObject && isExported(irClass)) {
                irClass.inEnclosingDeclaration {
                    context.mapping.objectToGetInstanceFunction[irClass]
                        ?.enqueue("Exported object getInstance function")
                }
            }

            irClass.annotations.forEach {
                val annotationClass = it.symbol.owner.constructedClass
                if (annotationClass.isAssociatedObjectAnnotatedAnnotation) {
                    classesWithObjectAssociations += irClass
                    annotationClass.enqueue("@AssociatedObject annotated annotation class")
                }
            }
        }
    }

    protected open fun processSimpleFunction(irFunction: IrSimpleFunction) {
        if (irFunction.isFakeOverride) {
            irFunction.inEnclosingDeclaration {
                irFunction.resolveFakeOverride()?.enqueue("real overridden fun", isContagious = false)
            }
        }
    }

    protected open fun processConstructor(irConstructor: IrConstructor) {
        // Collect instantiated classes.
        irConstructor.constructedClass.let {
            irConstructor.inEnclosingDeclaration {
                it.enqueue("constructed class")
            }
            constructedClasses += it
        }
    }

    protected open fun processConstructedClassDeclaration(declaration: IrDeclaration) {
        if (declaration in result) return

        fun IrOverridableDeclaration<*>.findOverriddenContagiousDeclaration(): IrOverridableDeclaration<*>? {
            for (overriddenSymbol in this.overriddenSymbols) {
                val overriddenDeclaration = overriddenSymbol.owner as? IrOverridableDeclaration<*> ?: continue

                if (overriddenDeclaration in contagiousReachableDeclarations) return overriddenDeclaration

                overriddenDeclaration.findOverriddenContagiousDeclaration()?.let {
                    return it
                }
            }
            return null
        }

        if (declaration is IrOverridableDeclaration<*>) {
            declaration.findOverriddenContagiousDeclaration()?.let {
                it.inEnclosingDeclaration {
                    declaration.enqueue("overrides useful declaration")
                }
            }
        }

        // A hack to enforce property lowering.
        // Until a getter is accessed it doesn't get moved to the declaration list.
        if (declaration is IrProperty) {
            declaration.getter?.run {
                inEnclosingDeclaration {
                    findOverriddenContagiousDeclaration()?.let { enqueue("(getter) overrides useful declaration") }
                }
            }
            declaration.setter?.run {
                inEnclosingDeclaration {
                    findOverriddenContagiousDeclaration()?.let { enqueue("(setter) overrides useful declaration") }
                }
            }
        }
    }

    protected open fun handleAssociatedObjects(): Unit = Unit

    fun collectDeclarations(rootDeclarations: Iterable<IrDeclaration>): Set<IrDeclaration> {

        // Add roots
        rootDeclarations.forEach {
            it.enqueue(null, altFromFqn = "<ROOT>")
        }

        while (queue.isNotEmpty()) {
            while (queue.isNotEmpty()) {
                val declaration = queue.pollFirst()

                when (declaration) {
                    is IrClass -> processClass(declaration)
                    is IrSimpleFunction -> processSimpleFunction(declaration)
                    is IrConstructor -> processConstructor(declaration)
                    is IrField -> processField(declaration)
                }

                val body = when (declaration) {
                    is IrFunction -> declaration.body
                    is IrField -> declaration.initializer
                    is IrVariable -> declaration.initializer
                    else -> null
                }

                declaration.inEnclosingDeclaration {
                    body?.accept(bodyVisitor, null)
                }
            }

            handleAssociatedObjects()

            for (klass in constructedClasses) {
                // TODO a better way to support inverse overrides.
                for (declaration in klass.declarations) {
                    processConstructedClassDeclaration(declaration)
                }
            }
        }

        if (printReachabilityInfo) {
            reachabilityInfo.forEach(::println)
        }

        return result
    }
}