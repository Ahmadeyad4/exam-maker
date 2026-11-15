package iq.exam.maker

import android.annotation.SuppressLint
import android.app.Activity
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
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
        private const val REQ_SAVE = 1001
        private const val REQ_OPEN = 1002
        private const val REQ_FILE = 1003
    }
}
