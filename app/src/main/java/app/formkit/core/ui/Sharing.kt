package app.formkit.core.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the share sheet for one file. Returns false if no app on the phone can receive it. */
fun Context.shareFile(uri: Uri, mimeType: String, chooserTitle: String): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The grant only reaches the target app through ClipData once the chooser wraps the intent.
    send.clipData = ClipData.newRawUri(null, uri)
    return startChooser(send, chooserTitle)
}

/** Opens the share sheet for several files of one type, such as a PDF's pages. */
fun Context.shareFiles(uris: List<Uri>, mimeType: String, chooserTitle: String): Boolean {
    require(uris.isNotEmpty()) { "Nothing to share" }
    if (uris.size == 1) return shareFile(uris.first(), mimeType, chooserTitle)
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType(mimeType)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    return startChooser(send, chooserTitle)
}

private fun Context.startChooser(send: Intent, chooserTitle: String): Boolean = try {
    startActivity(Intent.createChooser(send, chooserTitle))
    true
} catch (_: ActivityNotFoundException) {
    false
}
