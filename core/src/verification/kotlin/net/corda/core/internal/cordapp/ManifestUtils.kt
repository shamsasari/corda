package net.corda.core.internal.cordapp

import java.util.jar.Attributes
import java.util.jar.Manifest

const val TARGET_PLATFORM_VERSION = "Target-Platform-Version"
const val MIN_PLATFORM_VERSION = "Min-Platform-Version"

operator fun Manifest.set(key: String, value: String): String? = mainAttributes.putValue(key, value)

operator fun Manifest.set(key: Attributes.Name, value: String): Any? = mainAttributes.put(key, value)

operator fun Manifest.get(key: String): String? = mainAttributes.getValue(key)

val Manifest.targetPlatformVersion: Int
    get() {
        val minPlatformVersion = this[MIN_PLATFORM_VERSION]?.toIntOrNull() ?: 1
        return this[TARGET_PLATFORM_VERSION]?.toIntOrNull() ?: minPlatformVersion
    }
