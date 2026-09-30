package io.codnect.procyon.component

import com.goide.psi.GoArrayOrSliceType
import com.goide.psi.GoChannelType
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoFunctionType
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMapType
import com.goide.psi.GoParType
import com.goide.psi.GoPointerType
import com.goide.psi.GoStructType
import com.goide.psi.GoType
import com.goide.psi.GoTypeSpec

/**
 * What Procyon accepts as a component constructor: a function with exactly one result, which is a struct,
 * a pointer to a struct or an interface. A function that is not accepted is not a component.
 */
internal object ConstructorRules {

    /** Why a function is not accepted as a constructor. */
    sealed interface Violation {
        class Results(val count: Int) : Violation
        class ResultType(val type: String) : Violation
    }

    private enum class Kind { VALID, INVALID, UNKNOWN }

    /** Returns what is wrong with the constructor, or null when it is fine or cannot be told. */
    fun violation(constructor: GoFunctionDeclaration): Violation? {
        val signature = constructor.signature ?: return null

        val result = signature.result
        val count = if (result == null) 0 else result.parameters?.parameterCount ?: 1
        if (count != 1) return Violation.Results(count)

        val type = ComponentFinder.returnedGoType(constructor) ?: return null
        return if (kindOf(type, allowPointer = true) == Kind.INVALID) Violation.ResultType(type.text) else null
    }

    /** Decides whether a result type is a struct, a pointer to a struct or an interface, without guessing. */
    private fun kindOf(type: GoType, allowPointer: Boolean, depth: Int = 0): Kind {
        if (depth > MAX_DEPTH) return Kind.UNKNOWN

        return when (type) {
            is GoParType -> kindOf(type.type, allowPointer, depth + 1)
            is GoPointerType ->
                if (!allowPointer) Kind.INVALID else type.type?.let { kindOf(it, allowPointer = false, depth + 1) } ?: Kind.UNKNOWN
            is GoStructType, is GoInterfaceType -> Kind.VALID
            is GoArrayOrSliceType, is GoMapType, is GoFunctionType, is GoChannelType -> Kind.INVALID
            else -> namedKind(type, depth)
        }
    }

    private fun namedKind(type: GoType, depth: Int): Kind {
        val reference = type.typeReferenceExpression ?: return Kind.UNKNOWN

        return when (val target = reference.reference.resolve()) {
            // A defined type has the kind of its underlying type.
            is GoTypeSpec -> target.specType.type?.let { kindOf(it, allowPointer = false, depth + 1) } ?: Kind.UNKNOWN
            // Not resolved: only the predeclared types can be told from their names.
            null -> if (reference.qualifier != null) Kind.UNKNOWN else builtinKind(reference.identifier.text)
            else -> Kind.UNKNOWN
        }
    }

    private fun builtinKind(name: String): Kind = when (name) {
        "error", "any" -> Kind.VALID
        in BUILTIN_VALUE_TYPES -> Kind.INVALID
        else -> Kind.UNKNOWN
    }

    private const val MAX_DEPTH = 8

    private val BUILTIN_VALUE_TYPES = setOf(
        "bool", "string", "byte", "rune", "uintptr", "complex64", "complex128", "float32", "float64",
        "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
    )
}
