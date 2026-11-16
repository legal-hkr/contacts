package sh.hnet.contacts.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Pages of the one back stack: the contact list at its root and what opens over it. */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Contacts : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
    @Serializable data object SettingsLicenses : Screen
}
