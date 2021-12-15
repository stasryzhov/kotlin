/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.core

import org.jetbrains.kotlin.commonizer.CommonizerSettings

private typealias IsMarkedNullable = Boolean

internal interface TypeNullabilityCommonizer : AssociativeCommonizer<IsMarkedNullable>

internal fun TypeNullabilityCommonizer(
    options: TypeCommonizer.Options,
    settings: CommonizerSettings,
): TypeNullabilityCommonizer {
    return if (options.enableCovariantNullabilityCommonization) CovariantTypeNullabilityCommonizer(settings)
    else EqualTypeNullabilityCommonizer(settings)
}

private class CovariantTypeNullabilityCommonizer(
    override val settings: CommonizerSettings,
) : TypeNullabilityCommonizer {
    override fun commonize(first: IsMarkedNullable, second: IsMarkedNullable): IsMarkedNullable {
        return first || second
    }
}

private class EqualTypeNullabilityCommonizer(
    override val settings: CommonizerSettings,
) : TypeNullabilityCommonizer {
    override fun commonize(first: IsMarkedNullable, second: IsMarkedNullable): IsMarkedNullable? {
        if (first != second) return null
        return first
    }
}