package com.meenai.mina.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meenai.mina.server.ServerRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Live connection row (Mina Cloud / Composio). */
private data class ConnectorState(val id: String, val toolkit: String, val status: String)

private data class CatalogApp(val slug: String, val name: String, val description: String, val logo: String)

/** The account being connected in the bottom sheet + demo fake loading. */
data class ConnectTarget(val item: AccountItem)

data class AccountsUiState(
    val selectedTab: Int = 0,
    val query: String = "",
    val items: List<AccountItem> = seedAccounts(),
    val sheet: ConnectTarget? = null,
    val sheetLoading: Boolean = false,
    /** null = not checked yet; false = Mina Cloud session absent (mock rows remain, note is shown). */
    val signedIn: Boolean? = null,
    val error: String? = null,
) {
    val connected: List<AccountItem> get() = items.filter { it.status != AccountStatus.NOT_CONNECTED }
}

/**
 * Connected Accounts ViewModel. Real path for CONNECTION type: Mina Cloud's Composio-backed
 * endpoints (list / catalog / connect + OAuth in the browser / status poll / disconnect).
 * Logins and API keys stay mock until their real storage flow exists.
 */
class AccountsViewModel : ViewModel() {

    private var server: ServerRuntime.Instance? = null

    private val _ui = MutableStateFlow(AccountsUiState())
    val ui: StateFlow<AccountsUiState> = _ui.asStateFlow()

    fun onTab(index: Int) = _ui.update { it.copy(selectedTab = index) }

    fun onQuery(query: String) = _ui.update { it.copy(query = query) }

    /** Called once by the screen. server = null means signed out: the Connections tab keeps the mock rows and shows a note. */
    fun attach(server: ServerRuntime.Instance?, signedIn: Boolean) {
        this.server = server
        _ui.update { it.copy(signedIn = signedIn) }
        if (server != null && signedIn) loadReal()
    }

    /** Live connections + toolkit catalog; replaces the CONNECTION mock rows with real ones. */
    fun loadReal() {
        val s = server ?: return
        viewModelScope.launch {
            try {
                val conns = s.api.connections().jsonObject["items"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    ConnectorState(
                        id = id,
                        toolkit = (o["toolkit"] as? JsonPrimitive)?.contentOrNull ?: "?",
                        status = (o["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown",
                    )
                } ?: emptyList()
                val active = conns.filter { it.status.equals("ACTIVE", true) }
                // The catalog is best-effort: connecting works even without it.
                val apps = runCatching {
                    s.api.catalogApps().jsonObject["items"]?.jsonArray?.mapNotNull { el ->
                        val o = el.jsonObject
                        val slug = (o["slug"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        CatalogApp(
                            slug = slug,
                            name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: slug,
                            description = (o["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                            logo = (o["logo"] as? JsonPrimitive)?.contentOrNull ?: "",
                        )
                    } ?: emptyList()
                }.getOrDefault(emptyList())
                val connItems: List<AccountItem> = if (apps.isEmpty()) {
                    active.map {
                        AccountItem(
                            id = it.toolkit, name = it.toolkit, subtitle = null, logoRes = null,
                            type = AccountType.CONNECTION, status = AccountStatus.CONNECTED,
                            identity = it.id, remoteId = it.id,
                        )
                    }
                } else {
                    apps.map { app ->
                        val conn = active.firstOrNull { it.toolkit.equals(app.slug, true) }
                        AccountItem(
                            id = app.slug,
                            name = app.name,
                            subtitle = app.description.ifBlank { null },
                            logoRes = null,
                            type = AccountType.CONNECTION,
                            status = if (conn != null) AccountStatus.CONNECTED else AccountStatus.NOT_CONNECTED,
                            identity = conn?.id,
                            logoUrl = app.logo.ifBlank { null },
                            remoteId = conn?.id,
                        )
                    }
                }
                _ui.update { st ->
                    st.copy(error = null, items = st.items.filter { it.type != AccountType.CONNECTION } + connItems)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun openSheet(item: AccountItem) = _ui.update { it.copy(sheet = ConnectTarget(item), sheetLoading = false) }

    fun dismissSheet() = _ui.update { it.copy(sheet = null, sheetLoading = false) }

    /** CONNECTION + signed in: real OAuth. Continue -> Mina Cloud creates the connection, the
     * browser opens the OAuth page, and the status is polled in the background until it's done.
     * Other cases (mock rows / signed out): the old demo fake loading. */
    fun connect(item: AccountItem, openUrl: (String) -> Unit) {
        val s = server
        if (item.type == AccountType.CONNECTION && s != null) {
            viewModelScope.launch {
                _ui.update { it.copy(sheetLoading = true) }
                try {
                    val req = s.api.connectApp(item.id).jsonObject
                    val url = (req["redirect_url"] as? JsonPrimitive)?.contentOrNull
                    val id = (req["id"] as? JsonPrimitive)?.contentOrNull
                    if (!url.isNullOrBlank()) openUrl(url)
                    if (id != null) {
                        for (attempt in 0 until 30) {
                            delay(4_000)
                            val st = runCatching {
                                (s.api.connectionStatus(id).jsonObject["status"] as? JsonPrimitive)?.contentOrNull
                            }.getOrNull()
                            if (st != null && st != "INITIALIZING" && st != "PENDING") break
                        }
                    }
                    loadReal()
                } catch (e: Exception) {
                    _ui.update { it.copy(error = e.message) }
                }
                _ui.update { it.copy(sheet = null, sheetLoading = false) }
            }
        } else {
            confirmConnection()
        }
    }

    /** TODO: real OAuth (Mina backend POST /v1/connections/{app}/connect). Demo: fake loading for 1 second then marked connected. */
    fun confirmConnection() {
        val item = _ui.value.sheet?.item ?: return
        viewModelScope.launch {
            _ui.update { it.copy(sheetLoading = true) }
            delay(1_000)
            _ui.update { st ->
                st.copy(
                    sheet = null,
                    sheetLoading = false,
                    items = st.items.map {
                        if (it.id == item.id) it.copy(status = AccountStatus.CONNECTED, identity = MOCK_IDENTITY) else it
                    },
                )
            }
        }
    }

    /** TODO: store logins in EncryptedSharedPreferences / Keystore. Demo: saved as "saved but not verified". */
    fun saveLogin(email: String) {
        val item = _ui.value.sheet?.item ?: return
        _ui.update { st ->
            st.copy(
                sheet = null,
                items = st.items.map {
                    if (it.id == item.id) it.copy(status = AccountStatus.SAVED_NOT_VERIFIED, identity = email.ifBlank { MOCK_IDENTITY }) else it
                },
            )
        }
    }

    /** TODO: encrypted storage. Demo: keep only the last 4 digits, like sk-…9F2a. */
    fun saveKey(key: String) {
        val item = _ui.value.sheet?.item ?: return
        val suffix = key.filter { !it.isWhitespace() }.takeLast(4).ifEmpty { "9F2a" }
        _ui.update { st ->
            st.copy(
                sheet = null,
                items = st.items.map {
                    if (it.id == item.id) it.copy(status = AccountStatus.CONNECTED, identity = "sk-…$suffix") else it
                },
            )
        }
    }

    /** Real connection: DELETE /v1/connections/{id}; mock rows: local removal. */
    fun remove(item: AccountItem) {
        val s = server
        val remote = item.remoteId
        if (item.type == AccountType.CONNECTION && s != null && remote != null) {
            viewModelScope.launch {
                try {
                    s.api.disconnectApp(remote)
                } catch (e: Exception) {
                    _ui.update { it.copy(error = e.message) }
                }
                loadReal()
            }
        } else {
            _ui.update { st ->
                st.copy(items = st.items.map { if (it.id == item.id) it.copy(status = AccountStatus.NOT_CONNECTED, identity = null) else it })
            }
        }
    }
}
