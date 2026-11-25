package iq.exam.maker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/** يقدّم الملفات المؤقتة (مجلد cache/share) لتطبيقات المشاركة مثل تيليكرام وواتساب */
class ShareProvider : ContentProvider() {

    companion object {
        const val AUTH = "iq.exam.maker.share"
    }

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val seg = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        val f = File(File(ctx.cacheDir, "share"), File(seg).name)
        if (!f.exists()) throw FileNotFoundException(f.name)
        return f
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        return try {
            val f = fileFor(uri)
            val cols: Array<String> =
                projection?.map { it }?.toTypedArray()
                    ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val c = MatrixCursor(cols)
            val row = arrayOfNulls<Any>(cols.size)
            for (i in cols.indices) {
                row[i] = when (cols[i]) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }
            c.addRow(row)
            c
        } catch (e: Exception) {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
