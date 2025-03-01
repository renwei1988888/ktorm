/*
 * Copyright 2018-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ktorm.entity

import org.ktorm.database.Database
import org.ktorm.schema.Table
import java.io.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.jvmName
import kotlin.reflect.jvm.kotlinFunction

@Suppress("CanBePrimaryConstructorProperty")
internal class EntityImplementation(
    entityClass: KClass<*>, fromDatabase: Database?, fromTable: Table<*>?, parent: EntityImplementation?
) : InvocationHandler, Serializable {

    var entityClass: KClass<*> = entityClass
    var values = LinkedHashMap<String, Any?>()
    @Transient var fromDatabase: Database? = fromDatabase
    @Transient var fromTable: Table<*>? = fromTable
    @Transient var parent: EntityImplementation? = parent
    @Transient var changedProperties = LinkedHashSet<String>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        return when (method.declaringClass.kotlin) {
            Any::class -> {
                when (method.name) {
                    "equals" -> this == args!![0]
                    "hashCode" -> this.hashCode()
                    "toString" -> this.toString()
                    else -> throw IllegalStateException("Unrecognized method: $method")
                }
            }
            Entity::class -> {
                when (method.name) {
                    "getEntityClass" -> this.entityClass
                    "getProperties" -> Collections.unmodifiableMap(this.values)
                    "flushChanges" -> this.doFlushChanges()
                    "discardChanges" -> this.doDiscardChanges()
                    "delete" -> this.doDelete()
                    "get" -> this.values[args!![0] as String]
                    "set" -> this.doSetProperty(args!![0] as String, args[1])
                    "copy" -> this.copy()
                    else -> throw IllegalStateException("Unrecognized method: $method")
                }
            }
            else -> {
                handleMethodCall(proxy, method, args)
            }
        }
    }

    private fun handleMethodCall(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val ktProp = method.kotlinProperty
        if (ktProp != null) {
            val (prop, isGetter) = ktProp
            if (prop.isAbstract) {
                if (isGetter) {
                    val result = this.getProperty(prop, unboxInlineValues = true)
                    if (result != null || prop.returnType.isMarkedNullable) {
                        return result
                    } else {
                        return prop.defaultValue.also { cacheDefaultValue(prop, it) }
                    }
                } else {
                    this.setProperty(prop, args!![0])
                    return null
                }
            } else {
                return DefaultMethodHandler.forMethod(method).invoke(proxy, args)
            }
        } else {
            val func = method.kotlinFunction
            if (func != null && !func.isAbstract) {
                return DefaultMethodHandler.forMethod(method).invoke(proxy, args)
            } else {
                throw IllegalStateException("Cannot invoke entity abstract method: $method")
            }
        }
    }

    private val KProperty1<*, *>.defaultValue: Any get() {
        try {
            return javaGetter!!.returnType.defaultValue
        } catch (e: Throwable) {
            val msg = "" +
                "The value of non-null property [$this] doesn't exist, " +
                "an error occurred while trying to create a default one. " +
                "Please ensure its value exists, or you can mark the return type nullable [${this.returnType}?]"
            throw IllegalStateException(msg, e)
        }
    }

    private fun cacheDefaultValue(prop: KProperty1<*, *>, value: Any) {
        val type = prop.javaGetter!!.returnType

        // No need to cache primitive types, enums and string,
        // because their default values always share the same instance.
        if (type == Boolean::class.javaPrimitiveType) return
        if (type == Char::class.javaPrimitiveType) return
        if (type == Byte::class.javaPrimitiveType) return
        if (type == Short::class.javaPrimitiveType) return
        if (type == Int::class.javaPrimitiveType) return
        if (type == Long::class.javaPrimitiveType) return
        if (type == String::class.java) return
        if (type.isEnum) return

        // Cache the default value to avoid the weird case that entity.prop !== entity.prop
        setProperty(prop, value)
    }

    fun hasProperty(prop: KProperty1<*, *>): Boolean {
        return prop.name in values
    }

    fun getProperty(prop: KProperty1<*, *>, unboxInlineValues: Boolean = false): Any? {
        if (unboxInlineValues) {
            return values[prop.name]?.unboxTo(prop.javaGetter!!.returnType)
        } else {
            return values[prop.name]
        }
    }

    fun setProperty(prop: KProperty1<*, *>, value: Any?, forceSet: Boolean = false) {
        doSetProperty(prop.name, prop.returnType.boxFrom(value), forceSet)
    }

    private fun doSetProperty(name: String, value: Any?, forceSet: Boolean = false) {
        if (!forceSet && isPrimaryKey(name) && name in values) {
            val msg = "Cannot modify the primary key `$name` because it's already set to ${values[name]}"
            throw UnsupportedOperationException(msg)
        }

        values[name] = value
        changedProperties.add(name)
    }

    private fun copy(): Entity<*> {
        val entity = Entity.create(entityClass, parent, fromDatabase, fromTable)
        entity.implementation.changedProperties.addAll(changedProperties)

        for ((name, value) in values) {
            if (value is Entity<*>) {
                val copied = value.copy()

                // Keep the parent relationship.
                if (copied.implementation.parent == this) {
                    copied.implementation.parent = entity.implementation
                }

                entity.implementation.values[name] = copied
            } else {
                entity.implementation.values[name] = value?.let { deserialize(serialize(it)) }
            }
        }

        return entity
    }

    private fun serialize(obj: Any): ByteArray {
        ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { output ->
                output.writeObject(obj)
                output.flush()
                return buffer.toByteArray()
            }
        }
    }

    private fun deserialize(bytes: ByteArray): Any {
        ByteArrayInputStream(bytes).use { buffer ->
            ObjectInputStream(buffer).use { input ->
                return input.readObject()
            }
        }
    }

    private fun writeObject(output: ObjectOutputStream) {
        output.writeUTF(entityClass.jvmName)
        output.writeObject(values)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readObject(input: ObjectInputStream) {
        val javaClass = Class.forName(input.readUTF())
        entityClass = javaClass.kotlin
        values = input.readObject() as LinkedHashMap<String, Any?>
        changedProperties = LinkedHashSet()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        return when (other) {
            is EntityImplementation -> entityClass == other.entityClass && values == other.values
            is Entity<*> -> entityClass == other.implementation.entityClass && values == other.implementation.values
            else -> false
        }
    }

    override fun hashCode(): Int {
        var result = 1
        result = 31 * result + entityClass.hashCode()
        result = 31 * result + values.hashCode()
        return result
    }

    override fun toString(): String {
        return entityClass.simpleName + values
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
