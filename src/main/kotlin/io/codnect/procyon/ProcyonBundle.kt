package io.codnect.procyon

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.ProcyonBundle"

/** The texts the plugin shows, kept in `messages/ProcyonBundle.properties`. */
internal object ProcyonBundle : DynamicBundle(ProcyonBundle::class.java, BUNDLE) {

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
