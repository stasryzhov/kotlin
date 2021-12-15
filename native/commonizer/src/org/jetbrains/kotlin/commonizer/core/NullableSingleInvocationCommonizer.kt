/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.core

import org.jetbrains.kotlin.commonizer.CommonizerSettings

interface NullableSingleInvocationCommonizer<T> : SettingsAware {
    operator fun invoke(values: List<T>): T?
}

abstract class AbstractNullableSingleInvocationCommonizer<T>(
    override val settings: CommonizerSettings,
) : NullableSingleInvocationCommonizer<T>

fun <T : Any> NullableSingleInvocationCommonizer<T>.asCommonizer(): Commonizer<T, T?> = object : Commonizer<T, T?> {
    private val collectedValues = mutableListOf<T>()

    override val result: T?
        get() = this@asCommonizer.invoke(collectedValues)

    override val settings: CommonizerSettings
        get() = this@asCommonizer.settings

    override fun commonizeWith(next: T): Boolean {
        collectedValues.add(next)
        return true
    }
}


