/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.generator.model

import org.jetbrains.kotlin.ir.generator.config.*
import org.jetbrains.kotlin.ir.generator.util.*
import org.jetbrains.kotlin.utils.addToStdlib.castAll
import org.jetbrains.kotlin.utils.addToStdlib.partitionIsInstance

private object InferredOverriddenType : TypeRef

data class Model(val elements: List<Element>, val baseElement: Element, val abstractElement: Element)

fun config2model(config: Config): Model {
    val ec2el = mutableMapOf<ElementConfig, Element>()

    val elements = config.elements.map { ec ->
        val fields = ec.fields.mapTo(mutableListOf()) { fc ->
            val field = when (fc) {
                is SimpleFieldConfig -> SingleField(
                    fc,
                    fc.name,
                    fc.type ?: InferredOverriddenType,
                    fc.nullable,
                    fc.mutable,
                    fc.isChild,
                    fc.baseDefaultValue,
                    fc.baseGetter
                )
                is ListFieldConfig -> {
                    val listType = if (fc.mutability == ListFieldConfig.Mutability.List) type(
                        "kotlin.collections",
                        "MutableList"
                    ) else type("kotlin.collections", "List")
                    ListField(
                        fc,
                        fc.name,
                        fc.elementType ?: InferredOverriddenType,
                        listType,
                        fc.nullable,
                        fc.mutability == ListFieldConfig.Mutability.Var,
                        fc.isChild,
                        fc.mutability != ListFieldConfig.Mutability.Immutable,
                        fc.baseDefaultValue,
                        fc.baseGetter
                    )
                }
            }
            field
        }

        val element = Element(
            ec,
            ec.name,
            ec.category.packageName,
            ec.params,
            fields
        )
        ec2el[ec.element] = element
        element
    }

    val (baseElement, abstractElement) = replaceElementRefs(config, ec2el)
    setTypeKinds(elements)
    addAbstractElement(elements, abstractElement)
    markLeaves(elements)
    configureDescriptorApiAnnotation(elements)
    processFieldOverrides(elements)
    addWalkableChildren(elements)

    return Model(elements, baseElement, abstractElement)
}

private data class SpecialElements(val baseElement: Element, val abstractElement: Element)

private fun replaceElementRefs(config: Config, mapping: Map<ElementConfig, Element>): SpecialElements {
    val visited = mutableMapOf<TypeRef, TypeRef>()

    fun transform(type: TypeRef): TypeRef {
        visited[type]?.let {
            return it
        }

        return when (type) {
            is ElementConfigOrRef -> {
                val args = type.args.mapValues { transform(it.value) }
                val el = mapping.getValue(type.element)
                ElementRef(el, args, type.nullable)
            }
            is ClassRef<*> -> {
                @Suppress("UNCHECKED_CAST") // this is the upper bound, compiler could know that, right?
                type as ClassRef<TypeParameterRef>

                val args = type.args.mapValues { transform(it.value) }
                type.copy(args = args)
            }
            else -> type
        }.also { visited[type] = it }
    }

    val baseEl = transform(config.baseElement) as ElementRef
    val absEl = transform(config.abstractElement) as ElementRef

    for (ec in config.elements) {
        val el = mapping[ec.element]!!
        val (elParents, otherParents) = ec.parents
            .map { transform(it) }
            .partitionIsInstance<TypeRef, ElementRef>()
        el.elementParents = elParents.takeIf { it.isNotEmpty() || el == baseEl.element } ?: listOf(baseEl)
        el.otherParents = otherParents.castAll<ClassRef<*>>().toList()
        el.visitorParent = ec.visitorParent?.let(::transform) as ElementRef?
        el.transformerReturnType = (ec.transformerReturnType?.let(::transform) as ElementRef?)?.element

        for (field in el.fields) {
            when (field) {
                is SingleField -> {
                    field.type = transform(field.type)
                }
                is ListField -> {
                    field.elementType = transform(field.elementType)
                }
            }
        }
    }

    return SpecialElements(baseEl.element, absEl.element)
}

private fun markLeaves(elements: List<Element>) {
    val leaves = elements.toMutableSet()

    for (el in elements) {
        for (parent in el.elementParents) {
            leaves.remove(parent.element)
        }
    }

    for (el in leaves) {
        el.isLeaf = true
    }
}


private fun setTypeKinds(elements: List<Element>) {
    val nodeMap = elements.associateWith {
        object : Node {
            val element = it
            override var kind = it.targetKind
            override var parentNodes = emptyList<Node>()
        }
    }
    val nodes = nodeMap.values.toList()
    for (node in nodes) {
        node.parentNodes = node.element.elementParents.map { nodeMap.getValue(it.element) }
    }

    solveGraphForClassVsInterface(nodes)
    for (node in nodes) {
        node.element.targetKind?.let { requested ->
            val actual = node.kind!!
            check(actual == requested) { "Could not meet type kind requirement for element ${node.element} - requested $requested, was $actual" }
        }
        node.element.kind = when (node.kind!!) {
            TypeKind.Interface -> Element.Kind.Interface
            TypeKind.Class -> Element.Kind.AbstractClass
        }
    }
}

private fun addAbstractElement(elements: List<Element>, abstractElement: Element) {
    for (el in elements) {
        if (el == abstractElement) {
            continue
        }

        if (el.kind!!.typeKind == TypeKind.Class && el.elementParents.none { it.element.kind!!.typeKind == TypeKind.Class }) {
            el.elementParents += ElementRef(abstractElement)
        }
    }
}

private fun configureDescriptorApiAnnotation(elements: List<Element>) {
    for (el in elements) {
        for (field in el.fields) {
            val type = field.type
            if (type is ClassRef<*> && type.packageName.startsWith("org.jetbrains.kotlin.descriptors") &&
                type.simpleName.endsWith("Descriptor") && type.simpleName != "ModuleDescriptor"
            ) {
                field.needsDescriptorApiAnnotation = true
            }
        }
    }
}

private fun processFieldOverrides(elements: List<Element>) {
    for (element in iterateElementsParentFirst(elements)) {
        for (field in element.fields) {
            fun visitParents(visited: Element) {
                for (parent in visited.elementParents) {
                    val overriddenField = parent.element.fields.singleOrNull { it.name == field.name }
                    if (overriddenField != null) {
                        field.isOverride = true
                        field.needsDescriptorApiAnnotation =
                            field.needsDescriptorApiAnnotation || overriddenField.needsDescriptorApiAnnotation

                        fun transformInferredType(type: TypeRef, overriddenType: TypeRef) =
                            type.takeUnless { it is InferredOverriddenType } ?: overriddenType
                        when (field) {
                            is SingleField -> {
                                field.type = transformInferredType(field.type, (overriddenField as SingleField).type)
                            }
                            is ListField -> {
                                field.elementType = transformInferredType(field.elementType, (overriddenField as ListField).elementType)
                            }
                        }

                        break
                    }

                    visitParents(parent.element)
                }
            }

            visitParents(element)
        }
    }
}

private fun addWalkableChildren(elements: List<Element>) {
    for (element in elements) {
        val walkableChildren = element.fields.filter { it.isChild }.associateBy { it.name }.toMutableMap()

        fun visitParents(visited: Element) {
            for (parent in visited.elementParents) {
                for (field in parent.element.fields) {
                    if (field.isChild) {
                        walkableChildren.remove(field.name)
                    }
                }

                visitParents(parent.element)
            }
        }

        visitParents(element)
        element.walkableChildren = walkableChildren.values.toList()
    }

    for (element in iterateElementsParentFirst(elements)) {
        fun visitParentsForAccept(of: Element) {
            for (parent in of.elementParents) {
                if (parent.element.walkableChildren.isNotEmpty()) {
                    element.acceptChildrenSupers.add(parent.element)
                } else {
                    visitParentsForAccept(parent.element)
                }
            }
        }

        fun visitParentsForTransform(of: Element) {
            for (parent in of.elementParents) {
                if (parent.element.transformableChildren.isNotEmpty()) {
                    element.transformChildrenSupers.add(parent.element)
                } else {
                    visitParentsForTransform(parent.element)
                }
            }
        }

        visitParentsForAccept(element)
        visitParentsForTransform(element)
    }
}

private fun iterateElementsParentFirst(elements: List<Element>) = sequence {
    val pending = elements.sortedBy { it.elementParents.size }.toMutableSet()
    pendingLoop@ while (pending.isNotEmpty()) {
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val element = iter.next()
            if (element.elementParents.none { it.element in pending }) {
                yield(element)
                iter.remove()
                continue@pendingLoop
            }
        }

        error("Cannot find next element to process")
    }
}
