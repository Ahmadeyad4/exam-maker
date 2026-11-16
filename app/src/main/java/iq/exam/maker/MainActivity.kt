package iq.exam.maker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingData: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // لحفظ المشاريع (localStorage)
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            // لفتح معرض الصور من input type=file
            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE); true
                } catch (e: Exception) { fileCallback = null; false }
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
        checkForUpdate()
    }

    // يتحقق من آخر إصدار على GitHub ويطلع رسالة إذا أكو إصدار أحدث
    private fun checkForUpdate() {
        // رقم هذه النسخة يكتبه الـ workflow داخل assets/version.txt (غير موجود في بناء Android Studio، فيتخطى الفحص)
        val current = try {
            assets.open("version.txt").bufferedReader().use { it.readText().trim().toInt() }
        } catch (e: Exception) { return }

        Thread {
            try {
                val c = URL("https://api.github.com/repos/Ahmadeyad4/exam-maker/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name")
                val latest = tag.substringAfterLast('.').toInt()
                if (latest > current) {
                    val files = json.getJSONArray("assets")
                    val url = if (files.length() > 0)
                        files.getJSONObject(0).getString("browser_download_url")
                    else json.getString("html_url")
                    runOnUiThread { showUpdateDialog(tag, url) }
                }
            } catch (e: Exception) { /* بدون إنترنت أو خطأ: نتجاهل بصمت */ }
        }.start()
    }

    private fun showUpdateDialog(tag: String, url: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("يوجد تحديث جديد")
            .setMessage("الإصدار $tag متوفر الآن. تريد تنزيله؟")
            .setPositiveButton("تنزيل") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun printPage() {
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                val adapter = webView.createPrintDocumentAdapter("ملزمة")
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
                pm.print("ملزمة", adapter, attrs)
            }
        }

        // تصدير: يفتح نافذة اختيار مكان حفظ الملف
        @JavascriptInterface
        fun saveFile(name: String, data: String) {
            pendingData = data
            runOnUiThread {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                startActivityForResult(i, REQ_SAVE)
            }
        }

        // استيراد: يفتح نافذة اختيار ملف
        @JavascriptInterface
        fun pickFile() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, REQ_OPEN)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { pendingData = null; return }
        try {
            if (requestCode == REQ_SAVE) {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write((pendingData ?: "").toByteArray(Charsets.UTF_8))
                }
                pendingData = null
                Toast.makeText(this, "تم حفظ النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
            } else if (requestCode == REQ_OPEN) {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                webView.evaluateJavascript("handleImportedText(" + JSONObject.quote(text) + ")", null)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر قراءة أو كتابة الملف", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // يرجّع لصفحة "ورقة" الرئيسية قبل ما يسكّر التطبيق
        webView.evaluateJavascript(
            "(function(){var f=document.getElementById('fab');" +
            "if(f&&f.classList.contains('on')){goHome();return 1}return 0})()"
        ) { v -> if (v != "1") super@MainActivity.onBackPressed() }
    }

    companion object {
        private const val REQ_SAVE = 1001
        private const val REQ_OPEN = 1002
        private const val REQ_FILE = 1003
    }
}
