/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer

import org.jetbrains.kotlin.commonizer.cli.CommonizerSettingOptionType
import org.jetbrains.kotlin.commonizer.cli.Task

/**
 * Optional configuration settings for commonization task
 */
interface CommonizerSettings {
    fun <T : Any, S : CommonizerSettingOptionType<T>> getSetting(setting: S): T
}

internal object DefaultCommonizerSettings : CommonizerSettings {
    override fun <T : Any, S : CommonizerSettingOptionType<T>> getSetting(setting: S): T {
        return setting.default
    }
}

internal class TaskBasedCommonizerSettings(
    private val task: Task
) : CommonizerSettings {
    override fun <T : Any, S : CommonizerSettingOptionType<T>> getSetting(setting: S): T {
        return task.getCommonizerSetting(setting)
    }
}
