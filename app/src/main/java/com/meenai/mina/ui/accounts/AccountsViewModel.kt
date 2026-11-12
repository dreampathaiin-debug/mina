package com.meenai.mina.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 正在连接的账号 + demo 假加载。 */
data class ConnectTarget(val item: AccountItem)

data class AccountsUiState(
    val selectedTab: Int = 0,
    val query: String = "",
    val items: List<AccountItem> = seedAccounts(),
    val sheet: ConnectTarget? = null,
    val sheetLoading: Boolean = false,
) {
    val connected: List<AccountItem> get() = items.filter { it.status != AccountStatus.NOT_CONNECTED }
}

/**
 * UI only 的 mock ViewModel:全部状态在内存里,真实动作都留了 TODO 钩子
 * (OAuth 交给后端 /v1/connections,登录存 EncryptedSharedPreferences,key 存 Keystore)。
 */
class AccountsViewModel : ViewModel() {

    private val _ui = MutableStateFlow(AccountsUiState())
    val ui: StateFlow<AccountsUiState> = _ui.asStateFlow()

    fun onTab(index: Int) = _ui.update { it.copy(selectedTab = index) }

    fun onQuery(query: String) = _ui.update { it.copy(query = query) }

    fun openSheet(item: AccountItem) = _ui.update { it.copy(sheet = ConnectTarget(item), sheetLoading = false) }

    fun dismissSheet() = _ui.update { it.copy(sheet = null, sheetLoading = false) }

    /** TODO: 走真的 OAuth(Mina 后端 POST /v1/connections/{app}/connect)。demo:1 秒假加载后标已连。 */
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

    /** TODO: EncryptedSharedPreferences / Keystore 存登录。demo:存成「已保存未验证」。 */
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

    /** TODO: 加密存储。demo:只留末 4 位,像 sk-…9F2a。 */
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

    fun remove(item: AccountItem) = _ui.update { st ->
        st.copy(items = st.items.map { if (it.id == item.id) it.copy(status = AccountStatus.NOT_CONNECTED, identity = null) else it })
    }
}
