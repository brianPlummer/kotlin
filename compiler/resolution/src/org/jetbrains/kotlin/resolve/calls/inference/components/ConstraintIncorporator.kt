/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.descriptors.impl.AbstractTypeParameterDescriptor
import org.jetbrains.kotlin.resolve.calls.components.CreateFreshVariablesSubstitutor.shouldBeFlexible
import org.jetbrains.kotlin.resolve.calls.inference.model.*
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.model.*
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.util.*

// todo problem: intersection types in constrains: A <: Number, B <: Inv<A & Any> =>? B <: Inv<out Number & Any>
class ConstraintIncorporator(
    val typeApproximator: AbstractTypeApproximator,
    val trivialConstraintTypeInferenceOracle: TrivialConstraintTypeInferenceOracle
) {

    interface Context : TypeSystemInferenceExtensionContext {
        val allTypeVariablesWithConstraints: Collection<VariableWithConstraints>

        // if such type variable is fixed then it is error
        fun getTypeVariable(typeConstructor: TypeConstructorMarker): TypeVariableMarker?

        fun getConstraintsForVariable(typeVariable: TypeVariableMarker): Collection<Constraint>

        fun addNewIncorporatedConstraint(
            lowerType: KotlinTypeMarker,
            upperType: KotlinTypeMarker,
            shouldTryUseDifferentFlexibilityForUpperType: Boolean
        )

        fun addNewIncorporatedConstraint(typeVariable: TypeVariableMarker, type: KotlinTypeMarker, constraintContext: ConstraintContext)
    }

    // \alpha is typeVariable, \beta -- other type variable registered in ConstraintStorage
    fun incorporate(c: Context, typeVariable: TypeVariableMarker, constraint: Constraint) {
        // we shouldn't incorporate recursive constraint -- It is too dangerous
        with(c) {
            if (constraint.type.contains { it.typeConstructor() == typeVariable.freshTypeConstructor() }) return
        }

        val cO = c.collectSubstitutedTypesO(typeVariable, constraint)
        val c1 = c.collectSubstitutedTypesI(typeVariable, constraint)
        val shouldBeApproximateTopLevelNotNull = c.hasDefNotNullInInvPosition(cO.values) || c.hasDefNotNullInInvPosition(c1.values)

        val sO = c.approximateTopLevelDefNotNullTypesIfNeeded(cO)
        val sI = c.approximateTopLevelDefNotNullTypesIfNeeded(c1)

        c.directWithVariable(typeVariable, constraint, shouldBeApproximateTopLevelNotNull)
        c.otherInsideMyConstraint(typeVariable, constraint, sO)
        c.insideOtherConstraint(typeVariable, constraint, sI)
    }

    // A <:(=) \alpha <:(=) B => A <: B
    private fun Context.directWithVariable(
        typeVariable: TypeVariableMarker,
        constraint: Constraint,
        shouldBeApproximateTopLevelNotNull: Boolean
    ) {
        val shouldBeTypeVariableFlexible =
            typeVariable is TypeVariableFromCallableDescriptor && typeVariable.originalTypeParameter.shouldBeFlexible()

        // \alpha <: constraint.type
        if (constraint.kind != ConstraintKind.LOWER) {
            getConstraintsForVariable(typeVariable).forEach {
                if (it.kind != ConstraintKind.UPPER) {
                    addNewIncorporatedConstraint(
                        if (shouldBeApproximateTopLevelNotNull) approximateTopLevelDefNotNullTypesIfNeeded(it.type) else it.type,
                        if (shouldBeApproximateTopLevelNotNull) approximateTopLevelDefNotNullTypesIfNeeded(constraint.type) else constraint.type,
                        shouldBeTypeVariableFlexible,
                    )
                }
            }
        }

        // constraint.type <: \alpha
        if (constraint.kind != ConstraintKind.UPPER) {
            getConstraintsForVariable(typeVariable).forEach {
                if (it.kind != ConstraintKind.LOWER) {
                    addNewIncorporatedConstraint(
                        if (shouldBeApproximateTopLevelNotNull) approximateTopLevelDefNotNullTypesIfNeeded(constraint.type) else constraint.type,
                        if (shouldBeApproximateTopLevelNotNull) approximateTopLevelDefNotNullTypesIfNeeded(it.type) else it.type,
                        shouldBeTypeVariableFlexible,
                    )
                }
            }
        }
    }

    private fun Context.collectSubstitutedTypesO(
        typeVariable: TypeVariableMarker,
        constraint: Constraint,
    ): Map<TypeVariableMarker, Map<Constraint, KotlinTypeMarker>> {
        val otherInMyConstraint = SmartSet.create<TypeVariableMarker>().apply {
            constraint.type.contains {
                addIfNotNull(getTypeVariable(it.typeConstructor()))
                false
            }
        }
        return otherInMyConstraint.associateWith { otherTypeVariable ->
            ArrayList(getConstraintsForVariable(otherTypeVariable)).associateWith { otherConstraint ->
                getSubstitutedType(constraint, otherTypeVariable, otherConstraint)
            }
        }
    }

    private fun Context.collectSubstitutedTypesI(typeVariable: TypeVariableMarker, constraint: Constraint) =
        allTypeVariablesWithConstraints.associate { typeVariableWithConstraint ->
            val constraintsWhichConstraintMyVariable = typeVariableWithConstraint.constraints.filter { otherConstraint ->
                otherConstraint.type.contains { it.typeConstructor() == typeVariable.freshTypeConstructor() }
            }
            val substitutedTypes = constraintsWhichConstraintMyVariable.associateWith { getSubstitutedType(it, typeVariable, constraint) }

            typeVariableWithConstraint.typeVariable to substitutedTypes
        }

    // \alpha <: Inv<\beta>, \beta <: Number => \alpha <: Inv<out Number>
    private fun Context.otherInsideMyConstraint(
        typeVariable: TypeVariableMarker,
        constraint: Constraint,
        substitutedTypesByVariables: Map<TypeVariableMarker, Map<Constraint, KotlinTypeMarker>>,
    ) {
        for ((otherTypeVariable, substitutedTypes) in substitutedTypesByVariables) {
            for ((otherConstraint, substitutedType) in substitutedTypes) {
                generateNewConstraint(typeVariable, constraint, otherTypeVariable, otherConstraint, substitutedType)
            }
        }
    }

    // \alpha <: Number, \beta <: Inv<\alpha> => \beta <: Inv<out Number>
    private fun Context.insideOtherConstraint(
        typeVariable: TypeVariableMarker, constraint: Constraint,
        substitutedTypesByVariables: Map<TypeVariableMarker, Map<Constraint, KotlinTypeMarker>>
    ) {
        for ((otherTypeVariable, substitutedTypes) in substitutedTypesByVariables) {
            for ((otherConstraint, substitutedType) in substitutedTypes) {
                generateNewConstraint(otherTypeVariable, otherConstraint, typeVariable, constraint, substitutedType)
            }
        }
    }

    private fun Context.approximateTopLevelDefNotNullTypesIfNeeded(type: KotlinTypeMarker) =
        if ((type as? DelegatingSimpleType)?.constructor is AbstractTypeConstructor && type is DefinitelyNotNullType) {
            type.original.withNullability(false)
        } else type

    private fun Context.approximateTopLevelDefNotNullTypesIfNeeded(
        substitutedTypesByVariables: Map<TypeVariableMarker, Map<Constraint, KotlinTypeMarker>>
    ): Map<TypeVariableMarker, Map<Constraint, KotlinTypeMarker>> {
        if (!hasTopLevelDefNotNull(substitutedTypesByVariables.values) && !hasDefNotNullInInvPosition(substitutedTypesByVariables.values)) {
            return substitutedTypesByVariables
        }

        return substitutedTypesByVariables.map { (typeVariable, constraints) ->
            typeVariable to constraints.map { (constraint, type) ->
                constraint to if (type is DefinitelyNotNullType) {
                    type.original.withNullability(false)
                } else type
            }.toMap()
        }.toMap()
    }

    private fun Context.hasTopLevelDefNotNull(substitutedTypesByVariables: Collection<Map<Constraint, KotlinTypeMarker>>) =
        substitutedTypesByVariables.any { constraints ->
            constraints.values.any { type -> type.isDefinitelyNotNullType() }
        }

    private fun Context.hasDefNotNullInInvPosition(substitutedTypesByVariables: Collection<Map<Constraint, KotlinTypeMarker>>) =
        substitutedTypesByVariables.any { constraints ->
            constraints.values.any { type -> hasDefNotNullInInvPosition(type) }
        }

    private fun Context.hasDefNotNullInInvPosition(type: KotlinTypeMarker): Boolean {
        for (i in 0 until type.argumentsCount()) {
            val typeArgument = type.getArgument(i)

            if (typeArgument.getVariance() == TypeVariance.INV && typeArgument.getType().isDefinitelyNotNullType()) return true
            if (!typeArgument.isStarProjection() && hasDefNotNullInInvPosition(typeArgument.getType())) return true
        }
        return false
    }

    private fun Context.getSubstitutedType(baseConstraint: Constraint, otherVariable: TypeVariableMarker, otherConstraint: Constraint) =
        when (otherConstraint.kind) {
            ConstraintKind.EQUALITY -> {
                baseConstraint.type.substitute(this, otherVariable, otherConstraint.type)
            }
            ConstraintKind.UPPER -> {
                val temporaryCapturedType = createCapturedType(
                    createTypeArgument(otherConstraint.type, TypeVariance.OUT),
                    listOf(otherConstraint.type),
                    null,
                    CaptureStatus.FOR_INCORPORATION,
                )
                baseConstraint.type.substitute(this, otherVariable, temporaryCapturedType)
            }
            ConstraintKind.LOWER -> {
                val temporaryCapturedType = createCapturedType(
                    createTypeArgument(otherConstraint.type, TypeVariance.IN),
                    emptyList(),
                    otherConstraint.type,
                    CaptureStatus.FOR_INCORPORATION,
                )

                baseConstraint.type.substitute(this, otherVariable, temporaryCapturedType)
            }
        }

    private fun Context.generateNewConstraint(
        targetVariable: TypeVariableMarker,
        baseConstraint: Constraint,
        otherVariable: TypeVariableMarker,
        otherConstraint: Constraint,
        typeForApproximation: KotlinTypeMarker
    ) {
        if (baseConstraint.kind != ConstraintKind.UPPER) {
            val generatedConstraintType = approximateCapturedTypes(typeForApproximation, toSuper = false)
            addNewConstraint(targetVariable, baseConstraint, otherVariable, otherConstraint, generatedConstraintType, isSubtype = true)
        }
        if (baseConstraint.kind != ConstraintKind.LOWER) {
            val generatedConstraintType = approximateCapturedTypes(typeForApproximation, toSuper = true)
            addNewConstraint(targetVariable, baseConstraint, otherVariable, otherConstraint, generatedConstraintType, isSubtype = false)
        }
    }

    private fun Context.addNewConstraint(
        targetVariable: TypeVariableMarker,
        baseConstraint: Constraint,
        otherVariable: TypeVariableMarker,
        otherConstraint: Constraint,
        newConstraint: KotlinTypeMarker,
        isSubtype: Boolean,
    ) {
        if (targetVariable in getNestedTypeVariables(newConstraint)) return

        val isUsefulForNullabilityConstraint =
            isPotentialUsefulNullabilityConstraint(newConstraint, otherConstraint.type, otherConstraint.kind)

        if (!isUsefulForNullabilityConstraint && !containsConstrainingTypeWithoutProjection(newConstraint, otherConstraint)) return
        if (trivialConstraintTypeInferenceOracle.isGeneratedConstraintTrivial(
                baseConstraint, otherConstraint, newConstraint, isSubtype,
            )
        ) return

        val derivedFrom = (baseConstraint.derivedFrom + otherConstraint.derivedFrom).toMutableSet()
        if (otherVariable in derivedFrom) return

        derivedFrom.add(otherVariable)

        val kind = if (isSubtype) ConstraintKind.LOWER else ConstraintKind.UPPER

        val inputTypePosition = baseConstraint.position.from.safeAs<OnlyInputTypeConstraintPosition>()

        val isNullabilityConstraint = isUsefulForNullabilityConstraint && newConstraint.isNullableNothing()
        val constraintContext = ConstraintContext(kind, derivedFrom, inputTypePosition, isNullabilityConstraint)

        addNewIncorporatedConstraint(targetVariable, newConstraint, constraintContext)
    }

    fun Context.containsConstrainingTypeWithoutProjection(
        newConstraint: KotlinTypeMarker,
        otherConstraint: Constraint,
    ): Boolean {
        return getNestedArguments(newConstraint).any {
            it.getType().typeConstructor() == otherConstraint.type.typeConstructor() && it.getVariance() == TypeVariance.INV
        }
    }

    private fun Context.isPotentialUsefulNullabilityConstraint(
        newConstraint: KotlinTypeMarker,
        otherConstraint: KotlinTypeMarker,
        kind: ConstraintKind,
    ): Boolean {
        val otherConstraintCanAddNullabilityToNewOne =
            !newConstraint.isNullableType() && otherConstraint.isNullableType() && kind == ConstraintKind.LOWER
        val newConstraintCanAddNullabilityToOtherOne =
            newConstraint.isNullableType() && !otherConstraint.isNullableType() && kind == ConstraintKind.UPPER

        return otherConstraintCanAddNullabilityToNewOne || newConstraintCanAddNullabilityToOtherOne
    }

    fun Context.getNestedTypeVariables(type: KotlinTypeMarker): List<TypeVariableMarker> =
        getNestedArguments(type).mapNotNull { getTypeVariable(it.getType().typeConstructor()) }


    private fun KotlinTypeMarker.substitute(c: Context, typeVariable: TypeVariableMarker, value: KotlinTypeMarker): KotlinTypeMarker {
        val substitutor = c.typeSubstitutorByTypeConstructor(mapOf(typeVariable.freshTypeConstructor(c) to value))
        return substitutor.safeSubstitute(c, this)
    }


    private fun approximateCapturedTypes(type: KotlinTypeMarker, toSuper: Boolean): KotlinTypeMarker =
        if (toSuper) typeApproximator.approximateToSuperType(type, TypeApproximatorConfiguration.IncorporationConfiguration) ?: type
        else typeApproximator.approximateToSubType(type, TypeApproximatorConfiguration.IncorporationConfiguration) ?: type
}

private fun TypeSystemInferenceExtensionContext.getNestedArguments(type: KotlinTypeMarker): List<TypeArgumentMarker> {
    val result = ArrayList<TypeArgumentMarker>()
    val stack = ArrayDeque<TypeArgumentMarker>()

    when (type) {
        is FlexibleTypeMarker -> {
            stack.push(createTypeArgument(type.lowerBound(), TypeVariance.INV))
            stack.push(createTypeArgument(type.upperBound(), TypeVariance.INV))
        }
        else -> stack.push(createTypeArgument(type, TypeVariance.INV))
    }

    stack.push(createTypeArgument(type, TypeVariance.INV))

    val addArgumentsToStack = { projectedType: KotlinTypeMarker ->
        for (argumentIndex in 0 until projectedType.argumentsCount()) {
            stack.add(projectedType.getArgument(argumentIndex))
        }
    }

    while (!stack.isEmpty()) {
        val typeProjection = stack.pop()
        if (typeProjection.isStarProjection()) continue

        result.add(typeProjection)

        when (val projectedType = typeProjection.getType()) {
            is FlexibleTypeMarker -> {
                addArgumentsToStack(projectedType.lowerBound())
                addArgumentsToStack(projectedType.upperBound())
            }
            else -> addArgumentsToStack(projectedType)
        }
    }
    return result
}
