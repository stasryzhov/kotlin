/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.cli

public sealed class CommonizerSettingOptionType<T : Any>(
    alias: String,
    description: String,
    public val default: T
) : OptionType<T>(
    alias,
    description,
    mandatory = false,
)

public val AdditionalCommonizerSettings: List<CommonizerSettingOptionType<*>> = listOf(
    PlatformIntegers,
)
