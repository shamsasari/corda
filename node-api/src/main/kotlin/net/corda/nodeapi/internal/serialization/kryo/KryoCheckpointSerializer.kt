package net.corda.nodeapi.internal.serialization.kryo

import co.paralleluniverse.fibers.CustomFiberWriter
import co.paralleluniverse.fibers.CustomFiberWriterSerializer
import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.FiberWriter
import co.paralleluniverse.fibers.ThreadLocalSerializer
import co.paralleluniverse.io.serialization.kryo.KryoUtil
import co.paralleluniverse.io.serialization.kryo.ReplaceableObjectKryo
import com.esotericsoftware.kryo.ClassResolver
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.Registration
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.ClosureSerializer
import com.esotericsoftware.kryo.serializers.FieldSerializer
import net.corda.core.internal.uncheckedCast
import net.corda.core.serialization.CheckpointCustomSerializer
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.internal.CheckpointSerializationContext
import net.corda.core.serialization.internal.CheckpointSerializer
import net.corda.core.utilities.ByteSequence
import net.corda.core.utilities.loggerFor
import net.corda.serialization.internal.AlwaysAcceptEncodingWhitelist
import net.corda.serialization.internal.ByteBufferInputStream
import net.corda.serialization.internal.CheckpointSerializationContextImpl
import net.corda.serialization.internal.CordaSerializationEncoding
import net.corda.serialization.internal.CordaSerializationMagic
import net.corda.serialization.internal.QuasarWhitelist
import net.corda.serialization.internal.SectionId
import net.corda.serialization.internal.encodingNotPermittedFormat
import org.objenesis.strategy.SerializingInstantiatorStrategy
import java.lang.reflect.InaccessibleObjectException
import java.util.concurrent.ConcurrentHashMap

val kryoMagic = CordaSerializationMagic("corda".toByteArray() + byteArrayOf(0, 0))

private object AutoCloseableSerialisationDetector : Serializer<AutoCloseable>() {
    override fun write(kryo: Kryo, output: Output, closeable: AutoCloseable) {
        val message = "${closeable.javaClass.name}, which is a closeable resource, has been detected during flow checkpointing. " +
                "Restoring such resources across node restarts is not supported. Make sure code accessing it is " +
                "confined to a private method or the reference is nulled out."
        throw UnsupportedOperationException(message)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out AutoCloseable>) = throw IllegalStateException("Should not reach here!")
}

object KryoCheckpointSerializer : CheckpointSerializer {
    private val kryoPoolsForContexts = ConcurrentHashMap<Triple<ClassWhitelist, ClassLoader, Iterable<CheckpointCustomSerializer<*,*>>>, KryoPool>()

    private fun getPool(context: CheckpointSerializationContext): KryoPool {
        return kryoPoolsForContexts.computeIfAbsent(Triple(context.whitelist, context.deserializationClassLoader, context.checkpointCustomSerializers)) {
            KryoPool {
                val kryo = newFiberKryo(context)
                kryo.apply {
                    DefaultKryoCustomizer.customize(this)
                    addDefaultSerializer(AutoCloseable::class.java, AutoCloseableSerialisationDetector)
                    register(ClosureSerializer.Closure::class.java, CordaClosureSerializer)
                    classLoader = it.second

                    // Add custom serializers
                    val customSerializers = buildCustomSerializerAdaptors(context)
                    warnAboutDuplicateSerializers(customSerializers)
                    val classToSerializer = mapInputClassToCustomSerializer(context.deserializationClassLoader, customSerializers)
                    addDefaultCustomSerializers(this, classToSerializer)
                    referenceResolver
                }
            }
        }
    }

    private fun newFiberKryo(context: CheckpointSerializationContext): Kryo {
//        val kryo = ReplaceableObjectKryoReplacement(CordaClassResolver(context))
        val kryo = Kryo(CordaClassResolver(context), null)
        // This Kryo 5 optimisation is buggy for Kotlin classes - disable it!
        // See https://github.com/EsotericSoftware/kryo/issues/864
        kryo.setOptimizedGenerics(false)
        kryo.isRegistrationRequired = false
        kryo.instantiatorStrategy = SerializingInstantiatorStrategy()
        KryoUtil.registerCommonClasses(kryo)

        val fiberSerializer = Class.forName("co.paralleluniverse.fibers.Fiber\$FiberSerializer")
                .getConstructor(java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
                .newInstance(false) as Serializer<*>

        val fiberWriterSerializer = Class.forName("co.paralleluniverse.fibers.FiberWriterSerializer")
                .getConstructor()
                .apply { isAccessible = true }
                .newInstance() as Serializer<*>

        kryo.addDefaultSerializer(Fiber::class.java, fiberSerializer)
        kryo.addDefaultSerializer(ThreadLocal::class.java, ThreadLocalSerializer())
        kryo.addDefaultSerializer(FiberWriter::class.java, fiberWriterSerializer)
        kryo.addDefaultSerializer(CustomFiberWriter::class.java, CustomFiberWriterSerializer())
//        kryo.register(Fiber::class.java)
//        kryo.register(ThreadLocal::class.java)
//        kryo.register(InheritableThreadLocal::class.java)
//        kryo.register(ThreadLocalSerializer.DEFAULT::class.java)
//        kryo.register(FiberWriter::class.java)

        return kryo
    }

    /**
     * Returns a sorted list of CustomSerializerCheckpointAdaptor based on the custom serializers inside context.
     *
     * The adaptors are sorted by serializerName which maps to javaClass.name for the serializer class
     */
    private fun buildCustomSerializerAdaptors(context: CheckpointSerializationContext) =
            context.checkpointCustomSerializers.map { CustomSerializerCheckpointAdaptor(it) }.sortedBy { it.serializerName }

    /**
     * Returns a list of pairs where the first element is the input class of the custom serializer and the second element is the
     * custom serializer.
     */
    private fun mapInputClassToCustomSerializer(classLoader: ClassLoader, customSerializers: Iterable<CustomSerializerCheckpointAdaptor<*, *>>) =
            customSerializers.map { getInputClassForCustomSerializer(classLoader, it) to it }

    /**
     * Returns the Class object for the serializers input type.
     */
    private fun getInputClassForCustomSerializer(classLoader: ClassLoader, customSerializer: CustomSerializerCheckpointAdaptor<*, *>): Class<*> {
        val typeNameWithoutGenerics = customSerializer.cordappType.typeName.substringBefore('<')
        return Class.forName(typeNameWithoutGenerics, false, classLoader)
    }

    /**
     * Emit a warning if two or more custom serializers are found for the same input type.
     */
    private fun warnAboutDuplicateSerializers(customSerializers: Iterable<CustomSerializerCheckpointAdaptor<*,*>>) =
            customSerializers
                    .groupBy({ it.cordappType }, { it.serializerName })
                    .filter { (_, serializerNames) -> serializerNames.distinct().size > 1 }
                    .forEach { (inputType, serializerNames) -> loggerFor<KryoCheckpointSerializer>().warn("Duplicate custom checkpoint serializer for type $inputType. Serializers: ${serializerNames.joinToString(", ")}") }

    /**
     * Register all custom serializers as default, this class + subclass, registrations.
     *
     * Serializers registered before this will take priority. This needs to run after registrations we want to keep otherwise it may
     * replace them.
     */
    private fun addDefaultCustomSerializers(kryo: Kryo, classToSerializer: Iterable<Pair<Class<*>, CustomSerializerCheckpointAdaptor<*, *>>>) =
            classToSerializer
                    .forEach { (clazz, customSerializer) -> kryo.addDefaultSerializer(clazz, customSerializer) }

    private fun <T : Any> CheckpointSerializationContext.kryo(task: Kryo.() -> T): T {
        return getPool(this).run {
            this.context.ensureCapacity(properties.size)
            properties.forEach { this.context.put(it.key, it.value) }
            try {
                this.task()
            } finally {
                this.context.clear()
            }
        }
    }

    override fun <T : Any> deserialize(byteSequence: ByteSequence, clazz: Class<T>, context: CheckpointSerializationContext): T {
        val dataBytes = kryoMagic.consume(byteSequence)
                ?: throw KryoException("Serialized bytes header does not match expected format.")
        return context.kryo {
            kryoInput(ByteBufferInputStream(dataBytes)) {
                val result: T
                loop@ while (true) {
                    when (SectionId.reader.readFrom(this)) {
                        SectionId.ENCODING -> {
                            val encoding = CordaSerializationEncoding.reader.readFrom(this)
                            context.encodingWhitelist.acceptEncoding(encoding) || throw KryoException(encodingNotPermittedFormat.format(encoding))
                            substitute(encoding::wrap)
                        }
                        SectionId.DATA_AND_STOP, SectionId.ALT_DATA_AND_STOP -> {
                            result = if (context.objectReferencesEnabled) {
                                uncheckedCast(readClassAndObject(this))
                            } else {
                                withoutReferences { uncheckedCast<Any?, T>(readClassAndObject(this)) }
                            }
                            break@loop
                        }
                    }
                }
                result
            }
        }
    }

    override fun <T : Any> serialize(obj: T, context: CheckpointSerializationContext): SerializedBytes<T> {
        return context.kryo {
            SerializedBytes(kryoOutput {
                kryoMagic.writeTo(this)
                context.encoding?.let { encoding ->
                    SectionId.ENCODING.writeTo(this)
                    (encoding as CordaSerializationEncoding).writeTo(this)
                    substitute(encoding::wrap)
                }
                SectionId.ALT_DATA_AND_STOP.writeTo(this) // Forward-compatible in null-encoding case.
                if (context.objectReferencesEnabled) {
                    writeClassAndObject(this, obj)
                } else {
                    withoutReferences { writeClassAndObject(this, obj) }
                }
            })
        }
    }

    private class ReplaceableObjectKryoReplacement(classResolver: ClassResolver) : ReplaceableObjectKryo(classResolver) {
        companion object {
            private val defaultSerializersField = Kryo::class.java.getDeclaredField("defaultSerializers").apply { isAccessible = true }
        }

        // These are only modified via their setters, so it's safe to have a local copy
        private var autoReset = true
        private var maxDepth = Int.MAX_VALUE
//        private defaultSerializers: List<DefaultSerializerEntry>

        override fun setAutoReset(autoReset: Boolean) {
            super.setAutoReset(autoReset)
            this.autoReset = autoReset
        }

        override fun setMaxDepth(maxDepth: Int) {
            super.setMaxDepth(maxDepth)
            this.maxDepth = maxDepth
        }

//        override fun getDefaultSerializer(type: Class<*>): Serializer<*> {
//            if (isAssociatedWithSerializer(type)) {
//                val serializerForAnnotation = getDefaultSerializerForAnnotatedType(type)
//                if (serializerForAnnotation != null) return serializerForAnnotation
//
//                {
//                    var i = 0
//                    val n = defaultSerializers.size
//                    while (i < n) {
//                        val entry = defaultSerializers[i]
//                        if (entry.type.isAssignableFrom(type) && entry.serializerFactory.isSupported(type)) return entry.serializerFactory.newSerializer(this, type)
//                        i++
//                    }
//                }
//
//                return newDefaultSerializer(type)
//            } else {
//                return super.getDefaultSerializer(type)
//            }
//        }

        override fun writeClass(output: Output, type: Class<*>?): Registration? {
            return if (isAssociatedWithSerializer(type)) {
                try {
                    classResolver.writeClass(output, type)
                } finally {
                    if (depth == 0 && autoReset) reset()
                }
            } else {
//                println("writeClass: replacement hack for ${type?.name} ${type?.let { classResolver.getRegistration(it) }} ${type?.let { getDefaultSerializer(it) }}")
                super.writeClass(output, type)
            }
        }

        override fun writeObject(output: Output, obj: Any, serializer: Serializer<Any>) {
            if (isAssociatedWithSerializer(obj.javaClass)) {
                beginObject()
                try {
                    if (references && writeReferenceOrNull(output, obj, false)) return
                    println("writeObject=${obj.javaClass.name} ${System.identityHashCode(obj).toString(16)}")
                    serializer.write(this, output, obj)
                } finally {
                    if (/*--depth == 0 && */autoReset) reset()
                }
            } else {
//                println("writeObject: replacement hack for ${obj.javaClass.name} ${classResolver.getRegistration(obj.javaClass)} ${getDefaultSerializer(obj.javaClass)}")
                super.writeObject(output, obj, serializer)
            }
        }

        override fun writeClassAndObject(output: Output, obj: Any?) {
            if (isAssociatedWithSerializer(obj?.javaClass)) {
                beginObject()
                try {
                    if (obj == null) {
                        writeClass(output, null)
                        return
                    }
                    val registration = writeClass(output, obj.javaClass)
                    if (references && writeReferenceOrNull(output, obj, false)) return
                    registration!!.serializer.write(this, output, obj)
                } finally {
                    if (/*--depth == 0 && */autoReset) reset()
                }
            } else {
                val javaClass = obj?.javaClass
//                println("writeClassAndObject: replacement hack for ${javaClass?.name} ${javaClass?.let { classResolver.getRegistration(it) }} ${javaClass?.let { getDefaultSerializer(it) }}")
                super.writeClassAndObject(output, obj)
            }
        }

        private fun isAssociatedWithSerializer(type: Class<*>?): Boolean {
            return type == null || classResolver.getRegistration(type) != null || getDefaultSerializer(type) !is FieldSerializer
        }

        private fun beginObject() {
            if (depth == maxDepth) throw KryoException("Max depth exceeded: $depth")
//            depth++
        }

        private fun writeReferenceOrNull(output: Output, obj: Any?, mayBeNull: Boolean): Boolean {
            if (obj == null) {
                output.writeByte(NULL)
                return true
            }
            if (!referenceResolver.useReferences(obj.javaClass)) {
                if (mayBeNull) {
                    output.writeByte(NOT_NULL)
                }
                return false
            }
            // Determine if this object has already been seen in this object graph.
            val id = referenceResolver.getWrittenId(obj)
            // If not the first time encountered, only write reference ID.
            if (id != -1) {
                output.writeVarInt(id + 2, true) // + 2 because 0 and 1 are used for NULL and NOT_NULL.
                return true
            }
            // Otherwise write NOT_NULL and then the object bytes.
            referenceResolver.addWrittenObject(obj)
            output.writeByte(NOT_NULL)
            return false
        }
    }
}

val KRYO_CHECKPOINT_CONTEXT = CheckpointSerializationContextImpl(
        SerializationDefaults.javaClass.classLoader,
        QuasarWhitelist,
        emptyMap(),
        true,
        null,
        AlwaysAcceptEncodingWhitelist
)
