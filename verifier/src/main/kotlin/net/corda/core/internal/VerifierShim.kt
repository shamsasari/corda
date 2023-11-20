@file:Suppress("unused")

package net.corda.core.internal

import rx.Observable
import rx.Subscription

fun <T> Observable<T>.subscribeShim(onNext: (T) -> Unit, onError: (Throwable) -> Unit): Subscription = subscribe(onNext, onError)
