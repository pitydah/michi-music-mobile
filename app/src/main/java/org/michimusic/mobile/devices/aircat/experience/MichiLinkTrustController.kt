package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A71-09: owns every transition of the trust screen.
 *
 * The composable never reads the Keystore and never decides that a revocation
 * succeeded; it renders [state] and emits [MichiLinkTrustIntent]. A confirmation
 * always carries the exact snapshot it displayed, and a second confirmation
 * while one is in flight is ignored so a double tap cannot issue two destructive
 * commits.
 */
internal class MichiLinkTrustController(
    private val scope: CoroutineScope,
    private val admin: MichiLinkTrustAdmin,
) {

    private val _state = MutableStateFlow(MichiLinkTrustState())
    val state: StateFlow<MichiLinkTrustState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadToken = 0

    fun dispatch(intent: MichiLinkTrustIntent) {
        when (intent) {
            MichiLinkTrustIntent.Refresh -> if (!_state.value.busy) load()

            is MichiLinkTrustIntent.RequestSourceRevocation -> if (_state.value.canRequest) {
                _state.value = _state.value.copy(
                    pending = MichiLinkTrustPending.Source(intent.source),
                    notice = null,
                )
            }

            is MichiLinkTrustIntent.RequestHomeRevocation -> if (_state.value.canRequest) {
                _state.value = _state.value.copy(
                    pending = MichiLinkTrustPending.Home(intent.home),
                    notice = null,
                )
            }

            MichiLinkTrustIntent.ConfirmRevocation -> confirm()
            MichiLinkTrustIntent.CancelRevocation ->
                _state.value = _state.value.copy(pending = null)
            MichiLinkTrustIntent.DismissNotice ->
                _state.value = _state.value.copy(notice = null)
        }
    }

    private fun load() {
        loadJob?.cancel()
        val token = ++loadToken
        _state.value = _state.value.copy(loading = true, loadFailure = null)
        loadJob = scope.launch {
            val result = admin.load()
            if (token != loadToken) return@launch
            _state.value = result.fold(
                onSuccess = { inventory ->
                    _state.value.copy(
                        loading = false,
                        loadFailure = null,
                        homes = inventory.homes,
                        sources = inventory.sources,
                    )
                },
                onFailure = {
                    _state.value.copy(
                        loading = false,
                        loadFailure = MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE,
                        homes = emptyList(),
                        sources = emptyList(),
                        pending = null,
                    )
                },
            )
        }
    }

    private fun confirm() {
        val pending = _state.value.pending ?: return
        if (_state.value.busy || _state.value.loading) return
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            val result = when (pending) {
                is MichiLinkTrustPending.Source -> admin.revokeSource(pending.expected)
                is MichiLinkTrustPending.Home -> admin.revokeHome(pending.expected)
            }
            val notice = MichiLinkTrustNotice(
                outcome = result.outcome,
                subject = when (pending) {
                    is MichiLinkTrustPending.Source -> MichiLinkTrustSubject.SOURCE
                    is MichiLinkTrustPending.Home -> MichiLinkTrustSubject.HOME
                },
            )
            val inventory = result.inventory
            _state.value = if (inventory == null) {
                // The mutation may have landed, but the inventory is unreadable,
                // so no row may be drawn from the old snapshot.
                _state.value.copy(
                    busy = false,
                    pending = null,
                    notice = notice,
                    homes = emptyList(),
                    sources = emptyList(),
                    loadFailure = MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE,
                )
            } else {
                _state.value.copy(
                    busy = false,
                    pending = null,
                    notice = notice,
                    homes = inventory.homes,
                    sources = inventory.sources,
                    loadFailure = null,
                    loading = false,
                )
            }
        }
    }
}
