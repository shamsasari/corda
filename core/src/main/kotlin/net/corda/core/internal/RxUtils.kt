package net.corda.core.internal

import rx.Observable
import rx.Observer
import rx.observers.Subscribers
import rx.subjects.PublishSubject
import rx.subjects.UnicastSubject

/**
 * Returns an Observable that buffers events until subscribed.
 * @see UnicastSubject
 */
fun <T> Observable<T>.bufferUntilSubscribed(): Observable<T> {
    val subject = UnicastSubject.create<T>()
    val subscription = subscribe(subject)
    return subject.doOnUnsubscribe { subscription.unsubscribe() }
}

/** Copy an [Observer] to multiple other [Observer]s. */
fun <T> Observer<T>.tee(vararg teeTo: Observer<T>): Observer<T> {
    val subject = PublishSubject.create<T>()
    // use unsafe subscribe, so that the teed subscribers will not get wrapped with SafeSubscribers,
    // therefore a potential raw exception (non Rx) coming from a child -unsafe subscribed- observer
    // will not unsubscribe all of the subscribers under the PublishSubject.
    subject.unsafeSubscribe(Subscribers.from(this))
    teeTo.forEach { subject.unsafeSubscribe(Subscribers.from(it)) }
    return subject
}
