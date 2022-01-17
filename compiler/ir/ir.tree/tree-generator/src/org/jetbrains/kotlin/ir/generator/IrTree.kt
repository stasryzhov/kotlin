/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.generator

import com.squareup.kotlinpoet.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.InlineClassRepresentation
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.ir.generator.config.AbstractTreeBuilder
import org.jetbrains.kotlin.ir.generator.config.ElementConfig
import org.jetbrains.kotlin.ir.generator.config.ElementConfig.Category.*
import org.jetbrains.kotlin.ir.generator.config.ListFieldConfig.Mutability.List
import org.jetbrains.kotlin.ir.generator.config.ListFieldConfig.Mutability.Var
import org.jetbrains.kotlin.ir.generator.config.SimpleFieldConfig
import org.jetbrains.kotlin.ir.generator.print.toPoet
import org.jetbrains.kotlin.ir.generator.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.types.Variance

// Some declarations are marked with explicit type to avoid "compiler has fallen into recursive type problem" errors
object IrTree : AbstractTreeBuilder() {
    private fun symbol(type: TypeRef) = field("symbol", type)
    private fun descriptor(typeName: String) =
        field("descriptor", ClassRef<TypeParameterRef>(TypeKind.Interface, "org.jetbrains.kotlin.descriptors", typeName))

    private val factory: SimpleFieldConfig = field("factory", type(Packages.declarations, "IrFactory"))

    override val baseElement: ElementConfig by element(Other, name = "element") {
        transformByChildren = true

        parent(type(Packages.tree, "IrElementBase"))

        +field("startOffset", int)
        +field("endOffset", int)
    }
    override val abstractElement by element(Other) {
        typeKind = TypeKind.Class
    }
    val statement by element(Other)

    val declaration by element(Declaration) {
        parent(statement)
        parent(symbolOwner)
        parent(mutableAnnotationContainerType)

        +descriptor("DeclarationDescriptor")
        +field("origin", type(Packages.declarations, "IrDeclarationOrigin"), mutable = true)
        +field("parent", declarationParent, mutable = true)
        +factory
    }
    val declarationBase by element(Declaration) {
        typeKind = TypeKind.Class
        transformByChildren = true
        transformerReturnType = statement
        visitorParent = baseElement
        visitorName = "declaration"

        parent(declaration)
    }
    val declarationParent by element(Declaration)
    val declarationWithVisibility by element(Declaration) {
        parent(declaration)

        +field("visibility", type(Packages.descriptors, "DescriptorVisibility"), mutable = true)
    }
    val declarationWithName by element(Declaration) {
        parent(declaration)

        +field("name", type<Name>())
    }
    val possiblyExternalDeclaration by element(Declaration) {
        parent(declarationWithName)

        +field("isExternal", boolean)
    }
    val symbolOwner by element(Declaration) {
        +symbol(symbolType)
    }
    val metadataSourceOwner by element(Declaration) {
        +field("metadata", type(Packages.declarations, "MetadataSource"), nullable = true, mutable = true)
    }
    val overridableMember by element(Declaration) {
        parent(declaration)
        parent(declarationWithVisibility)
        parent(declarationWithName)
        parent(symbolOwner)

        +field("modality", type<Modality>())
    }
    val overridableDeclaration by element(Declaration) {
        val s = +param("S", symbolType)

        parent(overridableMember)

        +field("symbol", s)
        +field("isFakeOverride", boolean)
        +listField("overriddenSymbols", s, mutability = Var)
    }
    val memberWithContainerSource by element(Declaration) {
        parent(declarationWithName)

        +field("containerSource", type<DeserializedContainerSource>(), nullable = true)
    }
    val valueDeclaration by element(Declaration) {
        parent(declarationWithName)
        parent(symbolOwner)

        +descriptor("ValueDescriptor")
        +symbol(valueSymbolType)
        +field("type", irTypeType, mutable = true)
        +field("isAssignable", boolean)
    }
    val valueParameter by element(Declaration) {
        transform = true
        visitorParent = declarationBase

        parent(declarationBase)
        parent(valueDeclaration)

        +descriptor("ParameterDescriptor")
        +symbol(valueParameterSymbolType)
        +field("index", int)
        +field("varargElementType", irTypeType, nullable = true, mutable = true)
        +field("isCrossinline", boolean)
        +field("isNoinline", boolean)
        +field("isHidden", boolean)
        +field("defaultValue", expressionBody, nullable = true, mutable = true, isChild = true)
    }
    val `class` by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(possiblyExternalDeclaration)
        parent(declarationWithVisibility)
        parent(typeParametersContainer)
        parent(declarationContainer)
        parent(attributeContainer)
        parent(metadataSourceOwner)

        +descriptor("ClassDescriptor")
        +symbol(classSymbolType)
        +field("kind", type<ClassKind>())
        +field("modality", type<Modality>(), mutable = true)
        +field("isCompanion", boolean)
        +field("isInner", boolean)
        +field("isData", boolean)
        +field("isValue", boolean)
        +field("isExpect", boolean)
        +field("isFun", boolean)
        +field("source", type<SourceElement>())
        +listField("superTypes", irTypeType, mutability = Var)
        +field("thisReceiver", valueParameter, nullable = true, mutable = true, isChild = true)
        +field(
            "inlineClassRepresentation",
            type<InlineClassRepresentation<*>>().withArgs(type(Packages.types, "IrSimpleType")),
            nullable = true,
            mutable = true
        )
        +listField("sealedSubclasses", classSymbolType, mutability = Var)
    }
    val attributeContainer: ElementConfig by element(Declaration) {
        +field("attributeOwnerId", attributeContainer, mutable = true)
    }
    val anonymousInitializer by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)

        +descriptor("ClassDescriptor") // TODO special descriptor for anonymous initializer blocks
        +symbol(anonymousInitializerSymbolType)
        +field("isStatic", boolean)
        +field("body", blockBody, mutable = true, isChild = true)
    }
    val declarationContainer by element(Declaration) {
        parent(declarationParent)

        +listField("declarations", declaration, mutability = List, isChild = true)
    }
    val typeParametersContainer by element(Declaration) {
        parent(declaration)
        parent(declarationParent)

        +listField("typeParameters", typeParameter, mutability = Var, isChild = true)
    }
    val typeParameter by element(Declaration) {
        visitorParent = declarationBase
        transform = true

        parent(declarationBase)
        parent(declarationWithName)

        +descriptor("TypeParameterDescriptor")
        +symbol(typeParameterSymbolType)
        +field("variance", type<Variance>())
        +field("index", int)
        +field("isReified", boolean)
        +listField("superTypes", irTypeType, mutability = Var)
    }
    val returnTarget by element(Declaration) {
        parent(symbolOwner)

        +descriptor("FunctionDescriptor")
        +symbol(returnTargetSymbolType)
    }
    val function by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(possiblyExternalDeclaration)
        parent(declarationWithVisibility)
        parent(typeParametersContainer)
        parent(symbolOwner)
        parent(declarationParent)
        parent(returnTarget)
        parent(memberWithContainerSource)
        parent(metadataSourceOwner)

        +descriptor("FunctionDescriptor")
        +symbol(functionSymbolType)
        +field("isInline", boolean)
        +field("isExpect", boolean)
        +field("returnType", irTypeType, mutable = true)
        +field("dispatchReceiverParameter", valueParameter, mutable = true, nullable = true, isChild = true)
        +field("extensionReceiverParameter", valueParameter, mutable = true, nullable = true, isChild = true)
        +listField("valueParameters", valueParameter, mutability = Var, isChild = true)
        +field("contextReceiverParametersCount", int, mutable = true)
        +field("body", body, mutable = true, nullable = true, isChild = true)
    }
    val constructor by element(Declaration) {
        visitorParent = function

        parent(function)

        +descriptor("ClassConstructorDescriptor")
        +symbol(constructorSymbolType)
        +field("isPrimary", boolean)
    }
    val enumEntry by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(declarationWithName)

        +descriptor("ClassDescriptor")
        +symbol(enumEntrySymbolType)
        +field("initializerExpression", expressionBody, mutable = true, nullable = true, isChild = true)
        +field("correspondingClass", `class`, mutable = true, nullable = true, isChild = true)
    }
    val errorDeclaration by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)

        +field("symbol", symbolType) {
            baseGetter = code("error(\"Should never be called\")")
        }
    }
    val fakeOverrideFunction by element(Declaration) {
        typeKind = TypeKind.Interface

        parent(declaration)

        +symbol(simpleFunctionSymbolType)
        +field("modality", type<Modality>(), mutable = true)
        +field("isBound", boolean)
        generationCallback = {
            addFunction(
                FunSpec.builder("acquireSymbol")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("symbol", simpleFunctionSymbolType.toPoet())
                    .returns(simpleFunction.toPoet())
                    .build()
            )
        }
    }
    val fakeOverrideProperty by element(Declaration) {
        typeKind = TypeKind.Interface

        parent(declaration)

        +symbol(propertySymbolType)
        +field("modality", type<Modality>(), mutable = true)
        +field("getter", simpleFunction, mutable = true, nullable = true)
        +field("setter", simpleFunction, mutable = true, nullable = true)
        +field("isBound", boolean)
        generationCallback = {
            addFunction(
                FunSpec.builder("acquireSymbol")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("symbol", propertySymbolType.toPoet())
                    .returns(property.toPoet())
                    .build()
            )
        }
    }
    val field by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(possiblyExternalDeclaration)
        parent(declarationWithVisibility)
        parent(declarationParent)
        parent(metadataSourceOwner)

        +descriptor("PropertyDescriptor")
        +symbol(fieldSymbolType)
        +field("type", irTypeType, mutable = true)
        +field("isFinal", boolean)
        +field("isStatic", boolean)
        +field("initializer", expressionBody, mutable = true, nullable = true, isChild = true)
        +field("correspondingPropertySymbol", propertySymbolType, mutable = true, nullable = true)
    }
    val localDelegatedProperty by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(declarationWithName)
        parent(symbolOwner)
        parent(metadataSourceOwner)

        +descriptor("VariableDescriptorWithAccessors")
        +symbol(localDelegatedPropertySymbolType)
        +field("type", irTypeType, mutable = true)
        +field("isVar", boolean)
        +field("delegate", variable, mutable = true, isChild = true)
        +field("getter", simpleFunction, mutable = true, isChild = true)
        +field("setter", simpleFunction, mutable = true, nullable = true, isChild = true)
    }
    val moduleFragment: ElementConfig by element(Declaration) {
        visitorParent = baseElement
        transform = true
        transformByChildren = true

        +descriptor("ModuleDescriptor")
        +field("name", type<Name>())
        +field("irBuiltins", type(Packages.tree, "IrBuiltIns"))
        +listField("files", file, mutability = List, isChild = true)
        val UNDEFINED_OFFSET = MemberName(Packages.tree, "UNDEFINED_OFFSET")
        +field("startOffset", int) {
            baseGetter = code("%M", UNDEFINED_OFFSET)
        }
        +field("endOffset", int) {
            baseGetter = code("%M", UNDEFINED_OFFSET)
        }
    }
    val property by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(possiblyExternalDeclaration)
        parent(overridableDeclaration.withArgs("S" to propertySymbolType))
        parent(metadataSourceOwner)
        parent(attributeContainer)
        parent(memberWithContainerSource)

        +descriptor("PropertyDescriptor")
        +symbol(propertySymbolType)
        +field("isVar", boolean)
        +field("isConst", boolean)
        +field("isLateinit", boolean)
        +field("isDelegated", boolean)
        +field("isExpect", boolean)
        +field("isFakeOverride", boolean)
        +field("backingField", field, mutable = true, nullable = true, isChild = true)
        +field("getter", simpleFunction, mutable = true, nullable = true, isChild = true)
        +field("setter", simpleFunction, mutable = true, nullable = true, isChild = true)
    }

    //TODO: make IrScript as IrPackageFragment, because script is used as a file, not as a class
    //NOTE: declarations and statements stored separately
    val script by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(declarationWithName)
        parent(declarationParent)
        parent(statementContainer)
        parent(metadataSourceOwner)

        +symbol(scriptSymbolType)
        // NOTE: is the result of the FE conversion, because there script interpreted as a class and has receiver
        // TODO: consider removing from here and handle appropriately in the lowering
        +field("thisReceiver", valueParameter, mutable = true, isChild = true)
        +field("baseClass", irTypeType, mutable = true)
        +listField("explicitCallParameters", valueParameter, mutability = Var, isChild = true)
        +listField("implicitReceiversParameters", valueParameter, mutability = Var, isChild = true)
        +listField("providedProperties", propertySymbolType, mutability = Var)
        +listField("providedPropertiesParameters", valueParameter, mutability = Var, isChild = true)
        +field("resultProperty", propertySymbolType, mutable = true, nullable = true)
        +field("earlierScriptsParameter", valueParameter, mutable = true, nullable = true, isChild = true)
        +listField("earlierScripts", scriptSymbolType, mutability = Var, nullable = true)
        +field("targetClass", classSymbolType, mutable = true, nullable = true)
        +field("constructor", constructor, mutable = true, nullable = true)
    }
    val simpleFunction by element(Declaration) {
        visitorParent = function

        parent(function)
        parent(overridableDeclaration.withArgs("S" to simpleFunctionSymbolType))
        parent(attributeContainer)

        +symbol(simpleFunctionSymbolType)
        +field("isTailrec", boolean)
        +field("isSuspend", boolean)
        +field("isFakeOverride", boolean)
        +field("isOperator", boolean)
        +field("isInfix", boolean)
        +field("correspondingPropertySymbol", propertySymbolType, mutable = true, nullable = true)
    }
    val typeAlias by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(declarationWithName)
        parent(declarationWithVisibility)
        parent(typeParametersContainer)

        +descriptor("TypeAliasDescriptor")
        +symbol(typeAliasSymbolType)
        +field("isActual", boolean)
        +field("expandedType", irTypeType, mutable = true)
    }
    val variable by element(Declaration) {
        visitorParent = declarationBase

        parent(declarationBase)
        parent(valueDeclaration)

        +descriptor("VariableDescriptor")
        +symbol(variableSymbolType)
        +field("isVar", boolean)
        +field("isConst", boolean)
        +field("isLateinit", boolean)
        +field("initializer", expression, nullable = true, mutable = true, isChild = true)
    }
    val packageFragment by element(Declaration) {
        visitorParent = baseElement

        parent(declarationContainer)
        parent(symbolOwner)

        +symbol(packageFragmentSymbolType)
        +field("packageFragmentDescriptor", type(Packages.descriptors, "PackageFragmentDescriptor"))
        +field("fqName", type<FqName>())
    }
    val externalPackageFragment by element(Declaration) {
        visitorParent = packageFragment
        transformByChildren = true

        parent(packageFragment)

        +symbol(externalPackageFragmentSymbolType)
        +field("containerSource", type<DeserializedContainerSource>(), nullable = true)
    }
    val file by element(Declaration) {
        transform = true
        transformByChildren = true
        visitorParent = packageFragment

        parent(packageFragment)
        parent(mutableAnnotationContainerType)
        parent(metadataSourceOwner)

        +symbol(fileSymbolType)
        +field("module", moduleFragment)
        +field("fileEntry", type(Packages.tree, "IrFileEntry"))
    }

    val expression by element(Expression) {
        visitorParent = baseElement
        transform = true
        transformByChildren = true

        parent(statement)
        parent(varargElement)
        parent(attributeContainer)

        +field("attributeOwnerId", attributeContainer, mutable = true) {
            baseDefaultValue = code("this")
        }
        +field("type", irTypeType, mutable = true)
    }
    val statementContainer by element(Expression) {
        +listField("statements", statement, mutability = List, isChild = true)
    }
    val body by element(Expression) {
        transform = true
        visitorParent = baseElement
        visitorParam = "body"
        transformByChildren = true
    }
    val expressionBody by element(Expression) {
        transform = true
        visitorParent = body
        visitorParam = "body"

        parent(body)

        +factory
        +field("expression", expression, mutable = true, isChild = true)
    }
    val blockBody by element(Expression) {
        visitorParent = body
        visitorParam = "body"

        parent(body)
        parent(statementContainer)

        +factory
    }
    val declarationReference by element(Expression) {
        visitorParent = expression

        parent(expression)

        +symbol(symbolType)
        //diff: no accept
    }
    val memberAccessExpression by element(Expression) {
        suppressPrint = true //todo: generate this element too
        visitorParent = declarationReference
        visitorName = "memberAccess"
        transformerReturnType = baseElement
        val s = +param("S", symbolType)

        parent(declarationReference)

        +field("dispatchReceiver", expression, nullable = true, mutable = true, isChild = true) {
            baseDefaultValue = code("this")
        }
        +field("extensionReceiver", expression, nullable = true, mutable = true, isChild = true) {
            baseDefaultValue = code("this")
        }
        +symbol(s)
        +field("origin", statementOriginType, nullable = true)
        +field("typeArgumentsCount", int)
        +field("typeArgumentsByIndex", type<Array<*>>(irTypeType.copy(nullable = true)))
    }
    val functionAccessExpression by element(Expression) {
        visitorParent = memberAccessExpression
        visitorName = "functionAccess"
        transformerReturnType = baseElement

        parent(memberAccessExpression.withArgs("S" to functionSymbolType))

        +field("contextReceiversCount", int, mutable = true)
    }
    val constructorCall by element(Expression) {
        visitorParent = functionAccessExpression
        transformerReturnType = baseElement

        parent(functionAccessExpression)

        +symbol(constructorSymbolType)
        +field("constructorTypeArgumentsCount", int)
    }
    val getSingletonValue by element(Expression) {
        visitorParent = declarationReference
        visitorName = "SingletonReference"

        parent(declarationReference)
    }
    val getObjectValue by element(Expression) {
        visitorParent = getSingletonValue

        parent(getSingletonValue)

        +symbol(classSymbolType)
    }
    val getEnumValue by element(Expression) {
        visitorParent = getSingletonValue

        parent(getSingletonValue)

        +symbol(enumEntrySymbolType)
    }

    /**
     * Platform-specific low-level reference to function.
     *
     * On JS platform it represents a plain reference to JavaScript function.
     * On JVM platform it represents a MethodHandle constant.
     */
    val rawFunctionReference by element(Expression) {
        visitorParent = declarationReference

        parent(declarationReference)

        +symbol(functionSymbolType)
    }
    val containerExpression by element(Expression) {
        visitorParent = expression

        parent(expression)
        parent(statementContainer)

        +field("origin", statementOriginType, nullable = true)
        +field("isTransparentScope", boolean)
        +listField("statements", statement, mutability = List, isChild = true) {
            generationCallback = {
                addModifiers(KModifier.OVERRIDE)
            }
            baseDefaultValue = code("ArrayList(2)")
        }
    }
    val block by element(Expression) {
        visitorParent = containerExpression
        accept = true

        parent(containerExpression)

        +field("isTransparentScope", boolean) {
            baseGetter = code("false")
        }
    }
    val composite by element(Expression) {
        visitorParent = containerExpression

        parent(containerExpression)

        +field("isTransparentScope", boolean) {
            baseGetter = code("true")
        }
    }
    val returnableBlock by element(Expression) {
        parent(block)
        parent(symbolOwner)
        parent(returnTarget)

        +symbol(returnableBlockSymbolType)
        +field("inlineFunctionSymbol", functionSymbolType, nullable = true)
    }
    val syntheticBody by element(Expression) {
        visitorParent = body
        visitorParam = "body"

        parent(body)

        +field("kind", type(Packages.exprs, "IrSyntheticBodyKind"))
    }
    val breakContinue by element(Expression) {
        visitorParent = expression
        visitorParam = "jump"

        parent(expression)

        +field("loop", loop, mutable = true)
        +field("label", string, nullable = true, mutable = true) {
            baseDefaultValue = code("null")
        }
    }
    val `break` by element(Expression) {
        visitorParent = breakContinue
        visitorParam = "jump"

        parent(breakContinue)
    }
    val `continue` by element(Expression) {
        visitorParent = breakContinue
        visitorParam = "jump"

        parent(breakContinue)
    }
    val call by element(Expression) {
        visitorParent = functionAccessExpression

        parent(functionAccessExpression)

        +symbol(simpleFunctionSymbolType)
        +field("superQualifierSymbol", classSymbolType, nullable = true)
    }
    val callableReference by element(Expression) {
        visitorParent = memberAccessExpression
        val s = +param("S", symbolType)

        parent(memberAccessExpression.withArgs("S" to s))

        +field("referencedName", type<Name>())
    }
    val functionReference by element(Expression) {
        visitorParent = callableReference

        parent(callableReference.withArgs("S" to functionSymbolType))

        +field("reflectionTarget", functionSymbolType, nullable = true)
    }
    val propertyReference by element(Expression) {
        visitorParent = callableReference

        parent(callableReference.withArgs("S" to propertySymbolType))

        +field("field", fieldSymbolType, nullable = true)
        +field("getter", simpleFunctionSymbolType, nullable = true)
        +field("setter", simpleFunctionSymbolType, nullable = true)
    }
    val localDelegatedPropertyReference by element(Expression) {
        visitorParent = callableReference

        parent(callableReference.withArgs("S" to localDelegatedPropertySymbolType))

        +field("delegate", variableSymbolType)
        +field("getter", simpleFunctionSymbolType)
        +field("setter", simpleFunctionSymbolType, nullable = true)
    }
    val classReference by element(Expression) {
        visitorParent = declarationReference

        parent(declarationReference)

        +symbol(classifierSymbolType)
        +field("classType", irTypeType, mutable = true)

    }
    val const by element(Expression) {
        visitorParent = expression
        val t = +param("T")

        parent(expression)

        +field("kind", type(Packages.exprs, "IrConstKind").withArgs(t))
        +field("value", t)
    }
    val constantValue: ElementConfig by element(Expression) {
        visitorParent = expression
        transformByChildren = true

        parent(expression)

        generationCallback = {
            addFunction(
                FunSpec.builder("contentEquals")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("other", constantValue.toPoet())
                    .returns(boolean.toPoet())
                    .build()
            )
            addFunction(
                FunSpec.builder("contentHashCode")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(int.toPoet())
                    .build()
            )
        }
    }
    val constantPrimitive by element(Expression) {
        visitorParent = constantValue

        parent(constantValue)

        +field("value", const.withArgs("T" to TypeRef.Star), mutable = true, isChild = true)
    }
    val constantObject by element(Expression) {
        visitorParent = constantValue

        parent(constantValue)

        +field("constructor", constructorSymbolType)
        +listField("valueArguments", constantValue, mutability = List, isChild = true)
        +listField("typeArguments", irTypeType)
    }
    val constantArray by element(Expression) {
        visitorParent = constantValue

        parent(constantValue)

        +listField("elements", constantValue, mutability = List, isChild = true)
    }
    val delegatingConstructorCall by element(Expression) {
        visitorParent = functionAccessExpression

        parent(functionAccessExpression)

        +symbol(constructorSymbolType)
    }
    val dynamicExpression by element(Expression) {
        visitorParent = expression

        parent(expression)
    }
    val dynamicOperatorExpression by element(Expression) {
        visitorParent = dynamicExpression

        parent(dynamicExpression)

        +field("operator", type(Packages.exprs, "IrDynamicOperator"))
        +field("receiver", expression, mutable = true, isChild = true)
        +listField("arguments", expression, mutability = List, isChild = true)
    }
    val dynamicMemberExpression by element(Expression) {
        visitorParent = dynamicExpression

        parent(dynamicExpression)

        +field("memberName", string)
        +field("receiver", expression, mutable = true, isChild = true)
    }
    val enumConstructorCall by element(Expression) {
        visitorParent = functionAccessExpression

        parent(functionAccessExpression)

        +symbol(constructorSymbolType)
    }
    val errorExpression by element(Expression) {
        visitorParent = expression
        accept = true

        parent(expression)

        +field("description", string)
    }
    val errorCallExpression by element(Expression) {
        visitorParent = errorExpression

        parent(errorExpression)

        +field("explicitReceiver", expression, nullable = true, mutable = true, isChild = true)
        +listField("arguments", expression, mutability = List, isChild = true)
    }
    val fieldAccessExpression by element(Expression) {
        visitorParent = declarationReference
        visitorName = "fieldAccess"

        parent(declarationReference)

        +symbol(fieldSymbolType)
        +field("superQualifierSymbol", classSymbolType, nullable = true)
        +field("receiver", expression, nullable = true, mutable = true, isChild = true) {
            baseDefaultValue = code("null")
        }
        +field("origin", statementOriginType, nullable = true)
    }
    val getField by element(Expression) {
        visitorParent = fieldAccessExpression

        parent(fieldAccessExpression)
    }
    val setField by element(Expression) {
        visitorParent = fieldAccessExpression

        parent(fieldAccessExpression)

        +field("value", expression, mutable = true, isChild = true)
    }
    val functionExpression by element(Expression) {
        visitorParent = expression
        transformerReturnType = baseElement

        parent(expression)

        +field("origin", statementOriginType)
        +field("function", simpleFunction, mutable = true, isChild = true)
    }
    val getClass by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("argument", expression, mutable = true, isChild = true)
    }
    val instanceInitializerCall by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("classSymbol", classSymbolType)
    }
    val loop by element(Expression) {
        visitorParent = expression
        visitorParam = "loop"

        parent(expression)

        +field("origin", statementOriginType, nullable = true)
        +field("body", expression, nullable = true, mutable = true, isChild = true) {
            baseDefaultValue = code("null")
        }
        +field("condition", expression, mutable = true, isChild = true)
        +field("label", string, nullable = true, mutable = true) {
            baseDefaultValue = code("null")
        }
    }
    val whileLoop by element(Expression) {
        visitorParent = loop
        visitorParam = "loop"

        parent(loop)
    }
    val doWhileLoop by element(Expression) {
        visitorParent = loop
        visitorParam = "loop"

        parent(loop)
    }
    val `return` by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("value", expression, mutable = true, isChild = true)
        +field("returnTargetSymbol", returnTargetSymbolType)
    }
    val stringConcatenation by element(Expression) {
        visitorParent = expression

        parent(expression)

        +listField("arguments", expression, mutability = List, isChild = true)
    }
    val suspensionPoint by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("suspensionPointIdParameter", variable, mutable = true, isChild = true)
        +field("result", expression, mutable = true, isChild = true)
        +field("resumeResult", expression, mutable = true, isChild = true)
    }
    val suspendableExpression by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("suspensionPointId", expression, mutable = true, isChild = true)
        +field("result", expression, mutable = true, isChild = true)
    }
    val `throw` by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("value", expression, mutable = true, isChild = true)
    }
    val `try` by element(Expression) {
        visitorParent = expression
        visitorParam = "aTry"

        parent(expression)

        +field("tryResult", expression, mutable = true, isChild = true)
        +listField("catches", catch, mutability = List, isChild = true)
        +field("finallyExpression", expression, nullable = true, mutable = true, isChild = true)
    }
    val catch by element(Expression) {
        visitorParent = baseElement
        visitorParam = "aCatch"
        transform = true
        transformByChildren = true

        +field("catchParameter", variable, mutable = true, isChild = true)
        +field("result", expression, mutable = true, isChild = true)
    }
    val typeOperatorCall by element(Expression) {
        visitorParent = expression
        visitorName = "typeOperator"

        parent(expression)

        +field("operator", type(Packages.exprs, "IrTypeOperator"))
        +field("argument", expression, mutable = true, isChild = true)
        +field("typeOperand", irTypeType, mutable = true)
        +field("typeOperandClassifier", classifierSymbolType)
    }
    val valueAccessExpression by element(Expression) {
        visitorParent = declarationReference
        visitorName = "valueAccess"

        parent(declarationReference)

        +symbol(valueSymbolType)
        +field("origin", statementOriginType, nullable = true)
    }
    val getValue: ElementConfig by element(Expression) {
        visitorParent = valueAccessExpression

        parent(valueAccessExpression)

        generationCallback = {
            addFunction(
                FunSpec.builder("copyWithOffsets")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("newStartOffset", int.toPoet())
                    .addParameter("newEndOffset", int.toPoet())
                    .returns(getValue.toPoet())
                    .build()
            )
        }
    }
    val setValue by element(Expression) {
        visitorParent = valueAccessExpression

        parent(valueAccessExpression)

        +symbol(valueSymbolType)
        +field("value", expression, mutable = true, isChild = true)
    }
    val varargElement by element(Expression)
    val vararg by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("varargElementType", irTypeType, mutable = true)
        +listField("elements", varargElement, mutability = List, isChild = true)
    }
    val spreadElement by element(Expression) {
        visitorParent = baseElement
        visitorParam = "spread"
        transform = true
        transformByChildren = true

        parent(varargElement)

        +field("expression", expression, mutable = true, isChild = true)
    }
    val `when` by element(Expression) {
        visitorParent = expression

        parent(expression)

        +field("origin", statementOriginType, nullable = true)
        +listField("branches", branch, mutability = List, isChild = true)
    }
    val branch by element(Expression) {
        visitorParent = baseElement
        visitorParam = "branch"
        accept = true
        transform = true
        transformByChildren = true

        +field("condition", expression, mutable = true, isChild = true)
        +field("result", expression, mutable = true, isChild = true)
    }
    val elseBranch by element(Expression) {
        visitorParent = branch
        visitorParam = "branch"
        transform = true
        transformByChildren = true

        parent(branch)
    }
}
