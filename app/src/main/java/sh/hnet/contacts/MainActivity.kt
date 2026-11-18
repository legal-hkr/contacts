package sh.hnet.contacts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.hnet.contacts.about.WhatsNew
import sh.hnet.contacts.ui.ContactsApp
import sh.hnet.contacts.ui.Screen
import sh.hnet.contacts.ui.theme.ContactsTheme

class MainActivity : ComponentActivity() {
    /** What another app started the app for – the Phone app's "Create contact" –, until the shell has opened it. */
    private var asked by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            WhatsNew.recordStart(this)
            asked = insert(intent) ?: import(intent)
        }
        setContent {
            ContactsTheme { ContactsApp(asked, onOpened = { asked = null }) }
        }
    }

    /**
     * "Create contact" reaching the app while it runs: nobody waits for a result here (the result
     * goes to whoever started the activity), so the editor opens over what is open, as an ordinary one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        insert(intent)?.let { asked = it.copy(external = false) }
        import(intent)?.let { asked = it.copy(external = false) }
    }

    /** The app leaves the screen: a delete still offering Undo is carried out, as nobody is left to undo it (as in Calendar). */
    override fun onStop() {
        super.onStop()
        (application as ContactsApplication).deletes.commit()
    }

    /** A vCard file opened in the app or shared to it (SPEC §10.1): the import page; several shared files are one import. */
    private fun import(intent: Intent?): Screen.Import? {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        if (uris.isEmpty()) return null
        return Screen.Import(uris.map { it.toString() }, external = true)
    }

    /** "Create contact" from another app (SPEC §3.4): the editor for a new contact with the number, e-mail and name handed over. */
    private fun insert(intent: Intent?): Screen.Editor? {
        if (intent?.action != Intent.ACTION_INSERT) return null
        return Screen.Editor(
            lookupKey = null,
            id = 0,
            session = System.nanoTime(),
            name = intent.getStringExtra(ContactsContract.Intents.Insert.NAME),
            phone = intent.getStringExtra(ContactsContract.Intents.Insert.PHONE),
            email = intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL),
            external = true,
        )
    }
}
