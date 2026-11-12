package com.meenai.mina.ui.accounts

import androidx.annotation.DrawableRes
import com.meenai.mina.R

/** 三类条目:OAuth 连接、存下来的网站登录、自己的 API key。 */
enum class AccountType { CONNECTION, LOGIN, API_KEY }

enum class AccountStatus { NOT_CONNECTED, CONNECTED, SAVED_NOT_VERIFIED }

data class AccountItem(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    @DrawableRes val logoRes: Int? = null,
    val type: AccountType,
    val status: AccountStatus = AccountStatus.NOT_CONNECTED,
    val identity: String? = null,
)

/** demo 用的假身份,等真的 OAuth / 存储接上后就换成真数据。 */
const val MOCK_IDENTITY = "meenaispeaks6@gmail.com"

/** mock 数据:Connections / Logins / API Keys。 */
fun seedAccounts(): List<AccountItem> = listOf(
    AccountItem("google", "Google", "Gmail, Calendar, Drive", R.drawable.logo_google, AccountType.CONNECTION, AccountStatus.CONNECTED, MOCK_IDENTITY),
    AccountItem("microsoft", "Microsoft 365", "Outlook, Calendar, OneDrive", R.drawable.logo_microsoft, AccountType.CONNECTION),
    AccountItem("slack", "Slack", null, R.drawable.ic_conn_slack, AccountType.CONNECTION),
    AccountItem("github", "GitHub", null, R.drawable.ic_conn_github, AccountType.CONNECTION),
    AccountItem("atlassian", "Atlassian", null, R.drawable.logo_atlassian, AccountType.CONNECTION),
    AccountItem("linear", "Linear", null, R.drawable.logo_linear, AccountType.CONNECTION),
    AccountItem("notion", "Notion", null, R.drawable.ic_conn_notion, AccountType.CONNECTION),
    AccountItem("linkedin", "LinkedIn", null, R.drawable.ic_conn_linkedin, AccountType.LOGIN),
    AccountItem("amazon", "Amazon", null, null, AccountType.LOGIN),
    AccountItem("flipkart", "Flipkart", null, null, AccountType.LOGIN),
    AccountItem("swiggy", "Swiggy", null, null, AccountType.LOGIN),
    AccountItem("zomato", "Zomato", null, null, AccountType.LOGIN),
    AccountItem("groww", "Groww", null, null, AccountType.LOGIN, AccountStatus.SAVED_NOT_VERIFIED, MOCK_IDENTITY),
    AccountItem("zerodha", "Zerodha", null, null, AccountType.LOGIN),
    AccountItem("uber", "Uber", null, null, AccountType.LOGIN),
    AccountItem("instagram", "Instagram", null, R.drawable.logo_instagram, AccountType.LOGIN),
    AccountItem("openai", "OpenAI", "API key", R.drawable.logo_openai, AccountType.API_KEY),
    AccountItem("gemini", "Google Gemini", "API key", R.drawable.logo_gemini, AccountType.API_KEY),
    AccountItem("claude", "Anthropic Claude", "API key", R.drawable.logo_claude, AccountType.API_KEY),
    AccountItem("deepseek", "DeepSeek", "API key", R.drawable.logo_deepseek, AccountType.API_KEY),
    AccountItem("grok", "Grok", "API key", R.drawable.logo_grok, AccountType.API_KEY),
    AccountItem("mistral", "Mistral", "API key", R.drawable.logo_mistral, AccountType.API_KEY),
    AccountItem("openrouter", "OpenRouter", "API key", R.drawable.logo_openrouter, AccountType.API_KEY),
    AccountItem("groq", "Groq", "API key", R.drawable.logo_groq, AccountType.API_KEY),
)
