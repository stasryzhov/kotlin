/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("SequencesKt")

package kotlin.sequences

//
// NOTE: THIS FILE IS AUTO-GENERATED by the GenerateStandardLib.kt
// See: https://github.com/JetBrains/kotlin/tree/master/libraries/stdlib
//


/**
 * Returns a sequence containing all elements that are instances of specified class.
 *
 * The operation is _intermediate_ and _stateless_.
 * 
 * @sample samples.collections.Collections.Filtering.filterIsInstanceJVM
 */
public fun <R> Sequence<*>.filterIsInstance(klass: Class<R>): Sequence<R> {
    @Suppress("UNCHECKED_CAST")
    return filter { klass.isInstance(it) } as Sequence<R>
}

/**
 * Appends all elements that are instances of specified class to the given [destination].
 *
 * The operation is _terminal_.
 * 
 * @sample samples.collections.Collections.Filtering.filterIsInstanceToJVM
 */
public fun <C : MutableCollection<in R>, R> Sequence<*>.filterIsInstanceTo(destination: C, klass: Class<R>): C {
    @Suppress("UNCHECKED_CAST")
    for (element in this) if (klass.isInstance(element)) destination.add(element as R)
    return destination
}

/**
 * Returns a new [SortedSet][java.util.SortedSet] of all elements.
 *
 * The operation is _terminal_.
 */
public fun <T : Comparable<T>> Sequence<T>.toSortedSet(): java.util.SortedSet<T> {
    return toCollection(java.util.TreeSet<T>())
}

/**
 * Returns a new [SortedSet][java.util.SortedSet] of all elements.
 * 
 * Elements in the set returned are sorted according to the given [comparator].
 *
 * The operation is _terminal_.
 */
public fun <T> Sequence<T>.toSortedSet(comparator: Comparator<in T>): java.util.SortedSet<T> {
    return toCollection(java.util.TreeSet<T>(comparator))
}

/**
 * Returns the sum of all values produced by [selector] function applied to each element in the sequence.
 *
 * The operation is _terminal_.
 */
@SinceKotlin("1.4")
@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@kotlin.jvm.JvmName("sumOfBigDecimal")
@kotlin.internal.InlineOnly
public inline fun <T> Sequence<T>.sumOf(selector: (T) -> java.math.BigDecimal): java.math.BigDecimal {
    var sum: java.math.BigDecimal = 0.toBigDecimal()
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

/**
 * Returns the sum of all values produced by [selector] function applied to each element in the sequence.
 *
 * The operation is _terminal_.
 */
@SinceKotlin("1.4")
@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@kotlin.jvm.JvmName("sumOfBigInteger")
@kotlin.internal.InlineOnly
public inline fun <T> Sequence<T>.sumOf(selector: (T) -> java.math.BigInteger): java.math.BigInteger {
    var sum: java.math.BigInteger = 0.toBigInteger()
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

