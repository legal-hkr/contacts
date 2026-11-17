package sh.hnet.contacts

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import sh.hnet.contacts.model.ListSettings

private val Context.settings by preferencesDataStore("settings")

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    /** Settings › List (SPEC §13.2). */
    val listSettings: Flow<ListSettings> = context.settings.data.map {
        ListSettings(sortByLast = it[SORT_BY_LAST] ?: false, lastFirst = it[LAST_FIRST] ?: false, companyLine = it[COMPANY_LINE] ?: false)
    }.distinctUntilChanged()

    suspend fun setSortByLast(on: Boolean) = context.settings.edit { it[SORT_BY_LAST] = on }

    suspend fun setLastFirst(on: Boolean) = context.settings.edit { it[LAST_FIRST] = on }

    suspend fun setCompanyLine(on: Boolean) = context.settings.edit { it[COMPANY_LINE] = on }

    /**
     * The accounts hidden in the list, by [sh.hnet.contacts.model.AccountKey.key] (SPEC §8.1): the
     * app's own setting, not the system's group visibility, so other apps are unaffected. An account
     * not in the set is shown.
     */
    val hiddenAccounts: Flow<Set<String>> = context.settings.data.map { it[HIDDEN_ACCOUNTS] ?: emptySet() }.distinctUntilChanged()

    suspend fun setAccountShown(key: String, shown: Boolean) = context.settings.edit {
        val hidden = it[HIDDEN_ACCOUNTS] ?: emptySet()
        it[HIDDEN_ACCOUNTS] = if (shown) hidden - key else hidden + key
    }

    /** Settings › Contacts (SPEC §13.3): the account new contacts are saved to, by [sh.hnet.contacts.model.AccountKey.key]; null before one was chosen. */
    val defaultAccount: Flow<String?> = context.settings.data.map { it[DEFAULT_ACCOUNT] }.distinctUntilChanged()

    suspend fun setDefaultAccount(key: String) = context.settings.edit { it[DEFAULT_ACCOUNT] = key }

    /** A restored backup: Settings › List, the default account and the accounts' visibility become the file's. */
    suspend fun replace(list: ListSettings, hidden: Set<String>, defaultAccount: String?) = context.settings.edit {
        // A backup from before the default account existed leaves the current one (a section absent from a file is left untouched)
        if (defaultAccount != null) it[DEFAULT_ACCOUNT] = defaultAccount
        it[SORT_BY_LAST] = list.sortByLast
        it[LAST_FIRST] = list.lastFirst
        it[COMPANY_LINE] = list.companyLine
        it[HIDDEN_ACCOUNTS] = hidden
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default, the accounts' visibility included (SPEC §13.6). */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }

    private companion object {
        val SORT_BY_LAST = booleanPreferencesKey("sort_by_last")
        val LAST_FIRST = booleanPreferencesKey("last_first")
        val COMPANY_LINE = booleanPreferencesKey("company_line")
        val HIDDEN_ACCOUNTS = stringSetPreferencesKey("hidden_accounts")
        val DEFAULT_ACCOUNT = stringPreferencesKey("default_account")
    }
}
