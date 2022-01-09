/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ast

import org.jetbrains.kotlin.ir.expressions.IrVarargElement
import org.jetbrains.kotlin.js.backend.ast.JsName

class JsCapturedName(ident: String, val captured: IrVarargElement) : JsName(ident, false)