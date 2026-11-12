package iq.exam.maker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private val repo = "Ahmadeyad4/exam-maker"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                chooser?.onReceiveValue(null)
                chooser = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
                startActivityForResult(i, 1)
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        checkUpdate()
    }

    private fun checkUpdate() {
        thread {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "ExamMaker")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val tag = JSONObject(c.inputStream.bufferedReader().readText()).getString("tag_name")
                val latest = tag.filter { it.isDigit() }.toLongOrNull() ?: return@thread
                val cur = packageManager.getPackageInfo(packageName, 0).longVersionCode
                if (latest > cur) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("يوجد تحديث جديد")
                            .setMessage("تتوفر نسخة أحدث من التطبيق. تريد تحديثه هسه؟")
                            .setPositiveButton("تحديث") { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/$repo/releases/latest/download/app-release.apk")))
                            }
                            .setNegativeButton("لاحقاً", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) {
            val u = data?.data
            chooser?.onReceiveValue(if (resultCode == RESULT_OK && u != null) arrayOf(u) else null)
            chooser = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    inner class Bridge {
        @JavascriptInterface
        fun save(name: String, b64: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val mime = if (name.endsWith(".pdf")) "application/pdf"
                else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            runOnUiThread {
                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    Toast.makeText(this@MainActivity, "تم الحفظ في مجلد التنزيلات: $name", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "فتح الملف"))
                    } catch (e: Exception) { }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "تعذر الحفظ", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
