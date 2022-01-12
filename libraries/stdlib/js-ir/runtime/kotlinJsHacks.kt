/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.js

@PublishedApi
internal fun <T : Enum<T>> enumValuesIntrinsic(): Array<T> =
    throw IllegalStateException("Should be replaced by compiler")

@PublishedApi
internal fun <T : Enum<T>> enumValueOfIntrinsic(@Suppress("UNUSED_PARAMETER") name: String): T =
    throw IllegalStateException("Should be replaced by compiler")

// These were necessary for legacy-property-access functionality in IR
// But this functionality is not required anymore, so these methods are redundant
// But they can't be removed because if old version of compiler will compile against new version of stdlib
// (when these methods are removed) compiler will fail with Internal error
@Deprecated("This is an intrinsic for removed functionality", level = DeprecationLevel.HIDDEN)
internal fun safePropertyGet(self: dynamic, getterName: String, propName: String): dynamic {
    val getter = self[getterName]
    return if (getter != null) getter.call(self) else self[propName]
}

@Deprecated("This is an intrinsic for removed functionality", level = DeprecationLevel.HIDDEN)
internal fun safePropertySet(self: dynamic, setterName: String, propName: String, value: dynamic) {
    val setter = self[setterName]
    if (setter != null) setter.call(self, value) else self[propName] = value
}

/**
WARNING: It is a special variant of `js` function
which gives an ability to capture outer variables and functions
 */
internal external fun jsCaptured(code: String, vararg captured: dynamic): dynamic
