package com.fireflysource.common.reflect

import java.lang.reflect.Modifier

/**
 * Resolves name of java classes.
 *
 * @author Pengtao Qiu
 */
object KotlinNameResolver {
    /**
     * Get class name for function by the package of the function.
     *
     * @param func Get class name for the function.
     */
    fun name(func: () -> Unit): String {
        val name = func.javaClass.name
        return when {
            name.contains("Kt$") -> name.substringBefore("Kt$")
            name.contains("$") -> name.substringBefore("$")
            else -> name
        }
    }

    /**
     * Get class name for java class (that usually represents kotlin class)
     *
     * @param forClass Get the name for the class.
     */
    fun <T : Any> name(forClass: Class<T>): String = unwrapCompanionClass(forClass).name


    /**
     * unwrap companion class to enclosing class given a Java Class
     */
    private fun <T : Any> unwrapCompanionClass(clazz: Class<T>): Class<*> {
        if (clazz.enclosingClass != null) {
            try {
                val field = clazz.enclosingClass.getField(clazz.simpleName)
                if (Modifier.isStatic(field.modifiers) && field.type == clazz) {
                    // && field.get(null) === obj
                    // the above might be safer but problematic with initialization order
                    return clazz.enclosingClass
                }
            } catch (e: Exception) {
                //ok, it is not a companion object
            }
        }
        return clazz
    }
}