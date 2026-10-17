package com.openautolink.app.carplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/** Process scope outlives presentation; never stores an Activity strongly. */
internal object CarPlayHostUi {
    private val controller by lazy { CarPlayHostUiController(Handler(Looper.getMainLooper())::post) }
    fun register(activity: Activity) = controller.register(activity)
    fun unregister(activity: Activity) = controller.unregister(activity)
    fun request(isCurrent: () -> Boolean) = controller.request(isCurrent)
}

/** Presentation-only action. It does not own or retire any projection resources. */
internal class CarPlayHostUiController(private val post: (Runnable) -> Boolean) {
    private class Registration(target: Activity) {
        val activity = WeakReference(target)
        var minimized = false
    }
    private class Request(val registration: Registration, val isCurrent: () -> Boolean)
    private val lock = Any()
    private var registration: Registration? = null
    private var pending: Request? = null
    private var queued = false

    fun register(target: Activity) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Activity registration must run on main" }
        synchronized(lock) { registration = Registration(target) }
    }

    fun unregister(target: Activity) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Activity retirement must run on main" }
        synchronized(lock) { if (registration?.activity?.get() === target) registration = null }
    }

    fun request(isCurrent: () -> Boolean) = synchronized(lock) {
        val captured = registration ?: return
        if (captured.minimized) return
        // One slot plus one main task, including when a receiver floods us while main is busy.
        pending = Request(captured, isCurrent)
        if (queued) return
        queued = true
        if (!post(Runnable { deliver() })) {
            pending = null
            queued = false
        }
    }

    private fun deliver() {
        val request = synchronized(lock) {
            queued = false
            pending.also { pending = null }
        } ?: return
        val captured = request.registration
        val target = captured.activity.get() ?: return
        if (target.isFinishing || target.isDestroyed || !request.isCurrent()) return
        synchronized(lock) {
            if (registration !== captured || captured.minimized) return
            captured.minimized = true
        }
        target.moveTaskToBack(true)
    }
}
