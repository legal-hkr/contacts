package sh.hnet.contacts.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/** What is remembered about the first run and the questions asked in it; kept through Reset and out of the backup. */
private fun Context.firstRun() = getSharedPreferences("first_run", Context.MODE_PRIVATE)

/** The first run (SPEC §12) and the contacts permission, as Calendar's calendar access. */
object ContactsAccess {
    private const val WIZARD_DONE = "wizard_done"
    private const val REQUESTED = "contacts_requested"

    /** Whether the first-run wizard was gone through; it is shown once, before the list. */
    fun wizardDone(context: Context): Boolean = context.firstRun().getBoolean(WIZARD_DONE, false)

    fun markWizardDone(context: Context) = context.firstRun().edit { putBoolean(WIZARD_DONE, true) }

    /**
     * Whether the system would still show its permission dialog: before the first request, and
     * after one refusal. After the second the system answers for the user, and only its settings
     * page helps (SPEC §4.8).
     */
    fun mayAsk(context: Context, activity: Activity?): Boolean =
        !context.firstRun().getBoolean(REQUESTED, false) || activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) == true

    internal fun markRequested(context: Context) = context.firstRun().edit { putBoolean(REQUESTED, true) }

    /** The app's page in the system settings, where Permissions › Contacts is. */
    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Asks for the contacts permission, reading and writing in one system dialog. [onAnswer] runs after the user answered, whatever the answer. */
@Composable
fun rememberContactsAccessAsk(onAnswer: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAnswer() }
    return {
        ContactsAccess.markRequested(context)
        launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
    }
}
