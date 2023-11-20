@file:Suppress("MatchingDeclarationName")  // Remove this if more functions are added to this file

package net.corda.core.internal

import rx.Observable
import rx.Subscription
import rx.functions.Action1
import java.io.Serializable

private fun interface SerializableAction1<T> : Action1<T>, Serializable

fun <T> Observable<T>.subscribeShim(onNext: (T) -> Unit, onError: (Throwable) -> Unit): Subscription {
    return subscribe(SerializableAction1(onNext), SerializableAction1(onError))
}
