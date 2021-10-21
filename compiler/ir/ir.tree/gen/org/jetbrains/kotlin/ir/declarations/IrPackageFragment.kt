/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

//This file was generated automatically
//DO NOT MODIFY IT MANUALLY

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.ir.IrAbstractElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.symbols.IrPackageFragmentSymbol
import org.jetbrains.kotlin.name.FqName

/**
 * A non-leaf IR tree element.
 * @sample org.jetbrains.kotlin.ir.generator.IrTree.packageFragment
 */
abstract class IrPackageFragment : IrAbstractElement(), IrDeclarationContainer, IrSymbolOwner
        {
    abstract override val symbol: IrPackageFragmentSymbol

    @ObsoleteDescriptorBasedAPI
    abstract val packageFragmentDescriptor: PackageFragmentDescriptor

    abstract val fqName: FqName
}
