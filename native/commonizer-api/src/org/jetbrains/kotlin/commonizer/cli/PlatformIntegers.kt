/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.cli

public object PlatformIntegers : CommonizerSettingOptionType<Boolean>(
    "platform-integers",
    "Boolean (default false)\nEnable support of platform bit width integer commonization",
    default = false,
) {
    override fun parse(rawValue: String, onError: (reason: String) -> Nothing): Option<Boolean> =
        parseBoolean(rawValue, onError)
}
