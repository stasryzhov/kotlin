/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.lightTree

import org.jetbrains.kotlin.fir.FirRenderer
import org.jetbrains.kotlin.fir.builder.AbstractRawFirBuilderTestCase
import org.jetbrains.kotlin.fir.builder.StubFirScopeProvider
import org.jetbrains.kotlin.fir.pipeline.LightTreeToFirConverter
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.session.FirSessionFactory
import org.jetbrains.kotlin.test.KotlinTestUtils
import java.io.File
import java.nio.file.Paths


abstract class AbstractLightTree2FirConverterTestCase : AbstractRawFirBuilderTestCase() {

    fun doTest(filePath: String) {
        val lightTree = LightTreeAstBuilder().buildFileAST(Paths.get(filePath).toUri())
        val firFile = LightTreeToFirConverter(
            FirSessionFactory.createEmptySession(),
            StubFirScopeProvider,
            stubMode = false
        ).convert(lightTree)
        val firDump = firFile.render(mode = FirRenderer.RenderMode.WithDeclarationAttributes)

        val expectedFile = File(filePath.replace(".kt", ".txt"))
        KotlinTestUtils.assertEqualsToFile(expectedFile, firDump)
    }
}
