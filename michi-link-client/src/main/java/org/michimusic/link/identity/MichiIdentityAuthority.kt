package org.michimusic.link.identity

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observable lifecycle of the device identity.
 *
 * Building [MichiIdentity] opens the Android Keystore and an EncryptedSharedPreferences
 * file, which is disk I/O. Koin used to resolve it while the first frame was being
 * composed, so StrictMode reported a main-thread read at every cold start. The
 * authority keeps that work behind an explicit, injectable dispatcher and publishes
 * the outcome so callers can wait instead of blocking.
 */
sealed interface IdentityState {
    /** The identity has not been resolved yet; nothing may sign or pair in this state. */
    data object Loading : IdentityState

    /** The identity is available and safe to use. */
    data class Ready(val identity: MichiIdentity) : IdentityState

    /** Resolution failed (for example the Keystore is unavailable). Never usable. */
    data class Failed(val reason: String) : IdentityState
}

/**
 * Single owner of the identity resolution.
 *
 * [start] is idempotent and returns immediately, [resolve] performs the blocking work
 * on [ioDispatcher] at most once, and [await] gives suspending callers a nullable
 * identity: pairing, signing and remote commands must simply not proceed when it is
 * null instead of inventing a placeholder identity.
 */
class MichiIdentityAuthority(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val factory: () -> MichiIdentity = { MichiIdentity(context.applicationContext) },
    initialState: IdentityState = IdentityState.Loading,
) {
    private val _state = MutableStateFlow<IdentityState>(initialState)
    val state: StateFlow<IdentityState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val resolution = Mutex()

    /** Starts the background resolution. Safe to call from several places. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) { resolve() }
    }

    /** Resolves the identity once. A failed resolution is kept, not retried on every call. */
    suspend fun resolve(): IdentityState {
        val current = _state.value
        if (current !is IdentityState.Loading) return current
        return resolution.withLock {
            val rechecked = _state.value
            if (rechecked !is IdentityState.Loading) return@withLock rechecked
            val outcome = withContext(ioDispatcher) { runCatching { factory() } }
            val resolved = outcome.fold(
                onSuccess = { identity -> IdentityState.Ready(identity) },
                onFailure = { error ->
                    IdentityState.Failed(error.message ?: error::class.java.simpleName)
                },
            )
            _state.value = resolved
            resolved
        }
    }

    /** Suspending accessor: resolves on demand and returns null when the identity is unusable. */
    suspend fun await(): MichiIdentity? =
        (resolve() as? IdentityState.Ready)?.identity

    /** Non-suspending snapshot for callbacks that cannot wait; null until resolved. */
    fun peek(): MichiIdentity? = (_state.value as? IdentityState.Ready)?.identity

    /**
     * Clears a previous failure and resolves again. Kept explicit so a transient
     * Keystore problem does not turn into a tight retry loop.
     */
    fun retry(scope: CoroutineScope) {
        if ((_state.value as? IdentityState.Failed) == null) return
        _state.value = IdentityState.Loading
        started.set(false)
        start(scope)
    }
}
