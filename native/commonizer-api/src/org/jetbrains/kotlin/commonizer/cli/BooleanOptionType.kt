/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.cli

public class BooleanOptionType(
    alias: String,
    description: String,
    mandatory: Boolean
) : OptionType<Boolean>(alias, description, mandatory) {
    public override fun parse(rawValue: String, onError: (reason: String) -> Nothing): Option<Boolean> {
        val value = rawValue.lowercase().let {
            when (it) {
                in TRUE_TOKENS -> true
                in FALSE_TOKENS -> false
                else -> onError("Invalid boolean value: $it")
            }
        }

        return Option(this, value)
    }

    internal companion object {
        internal val TRUE_TOKENS = setOf("1", "on", "yes", "true")
        internal val FALSE_TOKENS = setOf("0", "off", "no", "false")
    }
}

internal fun OptionType<Boolean>.parseBoolean(rawValue: String, onError: (reason: String) -> Nothing): Option<Boolean> {
    val value = rawValue.lowercase().let {
        when (it) {
            in BooleanOptionType.TRUE_TOKENS -> true
            in BooleanOptionType.FALSE_TOKENS -> false
            else -> onError("Invalid boolean value: $it")
        }
    }

    return Option(this, value)
}
