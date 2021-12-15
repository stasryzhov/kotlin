/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.core

import org.jetbrains.kotlin.commonizer.DefaultCommonizerSettings
import org.jetbrains.kotlin.commonizer.cir.CirPropertySetter
import org.jetbrains.kotlin.descriptors.Visibilities
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PropertySetterCommonizerTest {

    @Test
    fun `missing only`() {
        assertNull(
            createCommonizer().commonize(null, null),
            "Expected no property setting being commonized from nulls"
        )
    }

    @Test
    fun `missing and public`() {
        assertSame(
            createCommonizer().privateFallbackSetter,
            createCommonizer().commonize(null, CirPropertySetter.createDefaultNoAnnotations(Visibilities.Public))
        )
    }

    @Test
    fun `public public`() {
        assertEquals(
            CirPropertySetter.createDefaultNoAnnotations(Visibilities.Public),
            createCommonizer().commonize(
                CirPropertySetter.createDefaultNoAnnotations(Visibilities.Public),
                CirPropertySetter.createDefaultNoAnnotations(Visibilities.Public)
            )
        )
    }

    @Test
    fun `internal internal`() {
        assertEquals(
            CirPropertySetter.createDefaultNoAnnotations(Visibilities.Internal),
            createCommonizer().commonize(
                CirPropertySetter.createDefaultNoAnnotations(Visibilities.Internal),
                CirPropertySetter.createDefaultNoAnnotations(Visibilities.Internal)
            )
        )
    }

    @Test
    fun `internal public`() {
        assertEquals(
            createCommonizer().privateFallbackSetter,
            createCommonizer().commonize(
                CirPropertySetter.createDefaultNoAnnotations(Visibilities.Internal),
                CirPropertySetter.createDefaultNoAnnotations(Visibilities.Public)
            )
        )
    }

    fun createCommonizer(): PropertySetterCommonizer = PropertySetterCommonizer(DefaultCommonizerSettings)
}
