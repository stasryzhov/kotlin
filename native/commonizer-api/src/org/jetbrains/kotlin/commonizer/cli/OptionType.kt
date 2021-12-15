/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.cli

public abstract class OptionType<T>(
    public val alias: String,
    public val description: String,
    public val mandatory: Boolean = true
) {
    public val argumentString: String
        get() = "-$alias"

    public abstract fun parse(rawValue: String, onError: (reason: String) -> Nothing): Option<T>
}
