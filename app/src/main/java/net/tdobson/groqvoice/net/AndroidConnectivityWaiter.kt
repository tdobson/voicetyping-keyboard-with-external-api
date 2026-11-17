package net.tdobson.groqvoice.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import net.tdobson.groqvoice.core.controller.ConnectivityGate
import net.tdobson.groqvoice.core.net.GroqReachabilityProbe
import net.tdobson.groqvoice.core.net.awaitReachable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Where "is there a validated network?" comes from; the Android one is [AndroidNetworkSource]. */
interface ValidatedNetworkSource {
    fun isValidatedNow(): Boolean
    /** Calls [onValidated] (on any thread, possibly more than once) when a validated default network appears. Returns unregister. */
    fun register(onValidated: () -> Unit): () -> Unit
}

/** Suspends until [source] has a validated network. Registers a callback only if needed; always unregisters it. */
suspend fun awaitValidatedNetwork(source: ValidatedNetworkSource) {
    if (source.isValidatedNow()) return
    suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val unregistered = AtomicBoolean(false)
        val registration = AtomicReference<(() -> Unit)?>(null)
        fun unregisterOnce() {
            val r = registration.get() ?: return
            if (unregistered.compareAndSet(false, true)) r()
        }
        fun finish(): Boolean {
            if (!done.compareAndSet(false, true)) return false
            unregisterOnce()
            return true
        }
        registration.set(source.register { if (finish()) cont.resume(Unit) })
        // The callback can fire during register() itself (Android replays the current default network).
        if (done.get()) unregisterOnce()
        cont.invokeOnCancellation { finish() }
    }
}

/**
 * Spec "waiting for connectivity": wait for a validated default network, then confirm Groq answers a HEAD,
 * probing every [intervalMs] (desktop: 3 s) while the network stays validated. Cancellable at any point (the
 * keyboard's ✕).
 */
class AndroidConnectivityWaiter(
    private val source: ValidatedNetworkSource,
    private val reachable: suspend () -> Boolean,
    private val intervalMs: Long = 3_000,
) : ConnectivityGate {
    constructor(cm: ConnectivityManager, probe: GroqReachabilityProbe, intervalMs: Long = 3_000) :
        this(AndroidNetworkSource(cm), probe::isReachable, intervalMs)

    /** Validation is re-checked before every probe: if the network loses it, probing stops until it returns. */
    override suspend fun awaitOnline() = awaitReachable(reachable, intervalMs) { awaitValidatedNetwork(source) }
}

class AndroidNetworkSource(private val cm: ConnectivityManager) : ValidatedNetworkSource {
    override fun isValidatedNow(): Boolean {
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    override fun register(onValidated: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) onValidated()
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        return {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
        }
    }
}
