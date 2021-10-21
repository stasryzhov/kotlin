/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.generator.model

import org.jetbrains.kotlin.ir.generator.util.TypeKind

/*
 * Assume that we have element `E` with parents `P1, P2`
 *
 * Introduce variables E, P1, P2. If variable `X` is true then `X` is class, otherwise it is interface
 *
 * Build 2SAT function for it: (E || !P1) && (E || !P2) && (!P1 || !P2)
 * Simple explanation:
 *   if `P1` is a class then `E` also should be a class
 *   if `P1` is a class then `P2` can not be a class (because both of them a parents of E`
 */

interface Node {
    val parentNodes: List<Node>
    var kind: TypeKind?
}

fun solveGraphForClassVsInterface(elements: List<Node>) {
    val elementMapping = ElementMapping(elements)

    val solution = solve2sat(elements, elementMapping)
    processRequirementsFromConfig(solution, elementMapping)
    updateKinds(solution, elementMapping)
}

private class ElementMapping(val elements: Collection<Node>) {
    private val varToElements: Map<Int, Node> = elements.mapIndexed { index, element -> 2 * index to element }.toMap() +
            elements.mapIndexed { index, element -> 2 * index + 1 to element }.toMap()
    private val elementsToVar: Map<Node, Int> = elements.mapIndexed { index, element -> element to index }.toMap()
    private val hasInheritors = elements.map { it to false }.toMap(mutableMapOf()).also {
        for (element in elements) {
            for (parent in element.parentNodes) {
                it[parent] = true
            }
        }
    }

    operator fun get(element: Node): Int = elementsToVar.getValue(element)
    operator fun get(index: Int): Node = varToElements.getValue(index)

    fun hasInheritors(element: Node): Boolean {
        return hasInheritors[element]!!
    }

    val size: Int = elements.size
}

private fun updateKinds(solution: List<Boolean>, elementMapping: ElementMapping) {
    for (index in solution.indices) {
        val isClass = solution[index]
        val element = elementMapping[index * 2]
        val existingKind = element.kind
        if (isClass) {
            if (existingKind == TypeKind.Interface)
                throw IllegalStateException(element.toString())

            if (existingKind == null) {
                element.kind = TypeKind.Class
            }
        } else {
            element.kind = TypeKind.Interface
        }
    }
}

private fun processRequirementsFromConfig(solution: MutableList<Boolean>, elementMapping: ElementMapping) {
    fun forceParentsToBeInterfaces(element: Node) {
        val origin = element
        val index = elementMapping[origin]
        if (!solution[index]) return
        solution[index] = false
        origin.parentNodes.forEach { forceParentsToBeInterfaces(it) }
    }

    fun forceInheritorsToBeClasses(element: Node) {
        val queue = ArrayDeque<Node>()
        queue.add(element)
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            val index = elementMapping[e]
            if (solution[index]) continue
            solution[index] = true
            for (inheritor in elementMapping.elements) {
                if (e in inheritor.parentNodes) {
                    queue.add(inheritor)
                }
            }
        }
    }

    for (index in solution.indices) {
        val element = elementMapping[index * 2]
        val kind = element.kind ?: continue
        if (kind == TypeKind.Interface) {
            forceParentsToBeInterfaces(element)
        } else {
            forceInheritorsToBeClasses(element)
        }
    }
}

private fun solve2sat(elements: Collection<Node>, elementsToVar: ElementMapping): MutableList<Boolean> {
    val (g, gt) = buildGraphs(elements, elementsToVar)

    val used = g.indices.mapTo(mutableListOf()) { false }
    val order = mutableListOf<Int>()
    val comp = g.indices.mapTo(mutableListOf()) { -1 }
    val n = g.size

    fun dfs1(v: Int) {
        used[v] = true
        for (to in g[v]) {
            if (!used[to]) {
                dfs1(to)
            }
        }
        order += v
    }

    fun dfs2(v: Int, cl: Int) {
        comp[v] = cl
        for (to in gt[v]) {
            if (comp[to] == -1) {
                dfs2(to, cl)
            }
        }
    }

    for (i in g.indices) {
        if (!used[i]) {
            dfs1(i)
        }
    }

    var j = 0
    for (i in g.indices) {
        val v = order[n - i - 1]
        if (comp[v] == -1) {
            dfs2(v, j++)
        }
    }

    val res = (1..elements.size).mapTo(mutableListOf()) { false }

    for (i in 0 until n step 2) {
        if (comp[i] == comp[i + 1]) {
            throw IllegalStateException("Somehow there is no solution. Please contact with @dmitriy.novozhilov")
        }
        res[i / 2] = comp[i] > comp[i + 1]
    }
    return res
}


private fun buildGraphs(elements: Collection<Node>, elementMapping: ElementMapping): Pair<List<List<Int>>, List<List<Int>>> {
    val g = (1..elementMapping.size * 2).map { mutableListOf<Int>() }
    val gt = (1..elementMapping.size * 2).map { mutableListOf<Int>() }

    fun Int.direct(): Int = this
    fun Int.invert(): Int = this + 1

    fun extractIndex(element: Node) = elementMapping[element] * 2

    for (element in elements) {
        val elementVar = extractIndex(element)
        for (parent in element.parentNodes) {
            val parentVar = extractIndex(parent)
            // parent -> element
            g[parentVar.direct()] += elementVar.direct()
            g[elementVar.invert()] += parentVar.invert()
        }
        for (i in 0 until element.parentNodes.size) {
            for (j in i + 1 until element.parentNodes.size) {
                val firstParentVar = extractIndex(element.parentNodes[i])
                val secondParentVar = extractIndex(element.parentNodes[j])
                // firstParent -> !secondParent
                g[firstParentVar.direct()] += secondParentVar.invert()
                g[secondParentVar.direct()] += firstParentVar.invert()
            }
        }
    }

    for (from in g.indices) {
        for (to in g[from]) {
            gt[to] += from
        }
    }
    return g to gt
}
