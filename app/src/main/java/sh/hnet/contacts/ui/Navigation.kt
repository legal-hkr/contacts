package sh.hnet.contacts.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Pages of the one back stack: the contact list at its root and what opens over it. */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Contacts : Screen
    /**
     * A contact's page, by its lookup key (which survives syncs and joins) and the id it had when
     * opened; the [name] and [photo] the list showed, so the page's header stands at once. [ask] is a quick action the one-contact widget couldn't carry out alone ("call", "message": several numbers, none the default): the page opens with the number sheet for it.
     */
    @Serializable data class Contact(val lookupKey: String, val id: Long, val name: String? = null, val photo: String? = null, val ask: String? = null) : Screen
    /**
     * The editor: the contact [lookupKey] / [id], or a new one when [lookupKey] is null, with what
     * another app handed over; [external] when that app waits for the result (SPEC §3.4). [session]
     * tells one opening from the next, so each starts with a form of its own.
     */
    @Serializable data class Editor(
        val lookupKey: String?,
        val id: Long,
        val session: Long,
        val name: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val external: Boolean = false,
    ) : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsContacts : Screen
    @Serializable data object Accounts : Screen
    @Serializable data object Duplicates : Screen
    /** Import of the vCard files [uris] (SPEC §10); [external] when another app started the app for it (Close goes back there). */
    @Serializable data class Import(val uris: List<String>, val external: Boolean = false) : Screen
    @Serializable data object Export : Screen
    /** "Same person?" for the contacts [ids]; [suggested] when they came from the suggestions. */
    @Serializable data class Review(val ids: List<Long>, val suggested: Boolean) : Screen
    /** Join [id] (named [name]) with another contact, picked by search. */
    @Serializable data class JoinPicker(val id: Long, val name: String) : Screen
    @Serializable data object SettingsList : Screen
    @Serializable data object SettingsBackup : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
    @Serializable data object SettingsLicenses : Screen
}
