package iq.exam.maker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingBytes: ByteArray? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // ---- التحديث داخل التطبيق ----
    private var downloader: ApkDownloader? = null
    private var dlDialog: AlertDialog? = null
    private var tvStatus: TextView? = null
    private var tvDetail: TextView? = null
    private var bar: ProgressBar? = null
    private var btnPause: Button? = null
    private var btnCancel: Button? = null
    private var paused = false
    private var pendingInstall: File? = null
    private var installReceiver: BroadcastReceiver? = null

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
        // نفس الجسر باسمين: AndroidBridge (القديم) و Android (تستخدمه دالة حفظ الملفات بالصفحة)
        val bridge = Bridge()
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.addJavascriptInterface(bridge, "Android")
        webView.loadUrl("file:///android_asset/index.html")

        registerInstallReceiver()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // رجع المستخدم من إعدادات "السماح بالتثبيت": كمّل التثبيت
        val f = pendingInstall
        if (f != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            doInstall(f)
        }
    }

    override fun onDestroy() {
        downloader?.pause()
        dlDialog?.dismiss()
        installReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        super.onDestroy()
    }

    // =====================  فحص التحديث  =====================
    // رقم هذه النسخة يكتبه الـ workflow داخل assets/version.txt (null بالبناء التجريبي)
    private fun currentVersion(): Int? = try {
        assets.open("version.txt").bufferedReader().use { it.readText().trim().toInt() }
    } catch (e: Exception) { null }

    // يبلّغ الصفحة بنتيجة الفحص (تنعرض بشاشة «عن التطبيق»)
    private fun notifyUpdate(r: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onUpdateResult&&onUpdateResult(" + JSONObject.quote(r) + ")", null
            )
        }
    }

    // manual=true: فحص يدوي من زر «فحص التحديث» ويبلّغ بكل النتائج
    private fun checkForUpdate(manual: Boolean = false) {
        val current = currentVersion()
        if (current == null) { if (manual) notifyUpdate("nover"); return }
        if (manual && dlDialog?.isShowing == true) {
            Toast.makeText(this, "التحميل شغّال هسه", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                val c = URL("https://api.github.com/repos/Ahmadeyad4/exam-maker/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "exam-maker-updater")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name")
                val latest = tag.substringAfterLast('.').toInt()
                if (latest > current) {
                    val files = json.getJSONArray("assets")
                    var url: String? = null
                    for (i in 0 until files.length()) {
                        val a = files.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                            url = a.getString("browser_download_url"); break
                        }
                    }
                    if (url != null) {
                        notifyUpdate("new:$tag")
                        runOnUiThread { if (dlDialog?.isShowing != true) showUpdateDialog(tag, url) }
                    } else if (manual) {
                        notifyUpdate("error")
                    }
                } else {
                    notifyUpdate("uptodate")
                    cleanUpdateFiles()
                }
            } catch (e: Exception) {
                if (manual) notifyUpdate("error") // بالفحص التلقائي نتجاهل بصمت
            }
        }.start()
    }

    private fun cleanUpdateFiles() {
        File(cacheDir, "update.apk").delete()
        File(cacheDir, "update.apk.part").delete()
        File(cacheDir, "update.tag").delete()
    }

    private fun showUpdateDialog(tag: String, url: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("يوجد تحديث جديد")
            .setMessage("الإصدار $tag متوفر الآن. تريد تحديث التطبيق؟")
            .setPositiveButton("تحديث الآن") { _, _ -> startDownload(tag, url) }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    // =====================  التحميل مع إيقاف مؤقت / استمرار  =====================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startDownload(tag: String, url: String) {
        val part = File(cacheDir, "update.apk.part")
        val apk = File(cacheDir, "update.apk")
        val tagFile = File(cacheDir, "update.tag")
        apk.delete()
        val oldTag = try { tagFile.readText() } catch (e: Exception) { "" }
        if (oldTag != tag) { part.delete(); tagFile.writeText(tag) }

        paused = false
        buildDownloadDialog(tag)

        val d = ApkDownloader(
            url, part,
            onProgress = { done, total -> runOnUiThread { showProgress(done, total) } },
            onDone = {
                apk.delete()
                part.renameTo(apk)
                runOnUiThread { onDownloaded(apk) }
            },
            onError = { runOnUiThread { showPaused("انقطع التحميل. اضغط استمرار") } },
            onPaused = { runOnUiThread { showPaused("متوقف مؤقتاً") } }
        )
        downloader = d
        d.start()
    }

    private fun buildDownloadDialog(tag: String) {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        tvStatus = TextView(this).apply { textSize = 16f; text = "جاري التحميل..." }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        tvDetail = TextView(this).apply { textSize = 14f; text = "" }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        btnPause = Button(this).apply { text = "إيقاف مؤقت" }
        btnCancel = Button(this).apply { text = "إلغاء" }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        row.addView(btnPause, lp())
        row.addView(btnCancel, lp())

        root.addView(tvStatus)
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(6) })
        root.addView(tvDetail)
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        btnPause!!.setOnClickListener {
            val d = downloader ?: return@setOnClickListener
            if (!paused) {
                d.pause()
                showPaused("متوقف مؤقتاً")
            } else {
                paused = false
                tvStatus?.text = "جاري التحميل..."
                btnPause?.text = "إيقاف مؤقت"
                d.start()
            }
        }
        btnCancel!!.setOnClickListener {
            downloader?.cancel()
            File(cacheDir, "update.apk.part").delete()
            File(cacheDir, "update.tag").delete()
            dlDialog?.dismiss()
        }

        dlDialog = AlertDialog.Builder(this)
            .setTitle("تحديث $tag")
            .setView(root)
            .setCancelable(false)
            .create()
        dlDialog!!.show()
    }

    private fun mb(b: Long) = String.format(Locale.US, "%.1f", b / 1048576.0)

    private fun showProgress(done: Long, total: Long) {
        if (paused) return
        if (total > 0) {
            bar?.isIndeterminate = false
            val pct = (done * 100 / total).toInt()
            bar?.progress = pct
            tvDetail?.text = "${mb(done)} / ${mb(total)} MB  ($pct%)"
        } else {
            tvDetail?.text = "${mb(done)} MB"
        }
    }

    private fun showPaused(msg: String) {
        paused = true
        tvStatus?.text = msg
        btnPause?.text = "استمرار"
    }

    private fun onDownloaded(apk: File) {
        tvStatus?.text = "اكتمل التحميل. جاري التثبيت..."
        bar?.isIndeterminate = false
        bar?.progress = 100
        btnPause?.visibility = View.GONE
        btnCancel?.visibility = View.GONE
        installApk(apk)
    }

    // =====================  التثبيت  =====================
    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            tvStatus?.text = "اسمح للتطبيق بتثبيت التحديثات ثم ارجع له"
            Toast.makeText(this, "فعّل «السماح من هذا المصدر» ثم ارجع للتطبيق", Toast.LENGTH_LONG).show()
            pendingInstall = apk
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) { /* نتجاهل */ }
            return
        }
        doInstall(apk)
    }

    private fun doInstall(apk: File) {
        try {
            val pi = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                apk.inputStream().use { ins ->
                    s.openWrite("update.apk", 0, apk.length()).use { out ->
                        ins.copyTo(out)
                        s.fsync(out)
                    }
                }
                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(
                    this, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            dlDialog?.dismiss()
            Toast.makeText(this, "تعذر بدء التثبيت: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun extraIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

    private fun registerInstallReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (st) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val next = extraIntent(i)
                        if (next != null) {
                            next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(next)
                        }
                        dlDialog?.dismiss()
                    }
                    PackageInstaller.STATUS_SUCCESS -> { /* التطبيق يتحدّث ويعاد تشغيله */ }
                    else -> {
                        dlDialog?.dismiss()
                        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                        val text = when (st) {
                            PackageInstaller.STATUS_FAILURE_ABORTED -> "تم إلغاء التثبيت"
                            PackageInstaller.STATUS_FAILURE_CONFLICT,
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                "توقيع النسخة الجديدة يختلف عن المثبتة. احذف النسخة القديمة مرة وحدة ثم ثبّت الجديدة"
                            else -> "فشل التثبيت ($st) $msg"
                        }
                        Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        installReceiver = r
        val f = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
    }

    // =====================  الجسر مع الصفحة  =====================
    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun launchSave(name: String, mime: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, REQ_SAVE)
    }

    // مشاركة ملف عبر قائمة المشاركة (تيليكرام / واتساب / ...) من غير ما ينحفظ بالهاتف
    private fun shareBytes(name: String, bytes: ByteArray) {
        try {
            val dir = File(cacheDir, "share")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val safe = File(name).name.ifBlank { "file" }
            File(dir, safe).writeBytes(bytes)
            val uri = Uri.Builder().scheme("content").authority(ShareProvider.AUTH).appendPath(safe).build()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(safe)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, safe)
                clipData = ClipData.newRawUri(safe, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "مشاركة الملف"))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذرت المشاركة: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

        // تصدير نص (النسخة الاحتياطية JSON): يفتح نافذة اختيار مكان حفظ الملف
        @JavascriptInterface
        fun saveFile(name: String, data: String) {
            pendingBytes = data.toByteArray(Charsets.UTF_8)
            runOnUiThread { launchSave(name, "application/json") }
        }

        // تصدير أي ملف (PDF، صورة، zip...) يجي من الصفحة بصيغة base64
        @JavascriptInterface
        fun save(name: String, b64: String) {
            val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return }
            pendingBytes = bytes
            runOnUiThread { launchSave(name, mimeFor(name)) }
        }

        // مشاركة أي ملف (PDF، Word...) من الصفحة بصيغة base64
        @JavascriptInterface
        fun share(name: String, b64: String) {
            val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return }
            runOnUiThread { shareBytes(name, bytes) }
        }

        // نسخة احتياطية تلقائية: تنكتب بمجلد التنزيلات/ورقة امتحان وتستبدل الملف السابق
        @JavascriptInterface
        fun autoSave(name: String, text: String): Boolean {
            return try {
                val resolver = contentResolver
                val rel = Environment.DIRECTORY_DOWNLOADS + "/ورقة امتحان"
                val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                var uri: Uri? = null
                resolver.query(
                    coll, arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    arrayOf(name, "$rel/"), null
                )?.use { c -> if (c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0)) }
                if (uri == null) {
                    val v = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    }
                    uri = resolver.insert(coll, v)
                }
                val u = uri ?: return false
                val out = resolver.openOutputStream(u, "wt") ?: return false
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                true
            } catch (e: Exception) {
                false
            }
        }

        // رقم النسخة المثبتة (مثال 1.47) أو فاضي إذا نسخة تجريبية
        @JavascriptInterface
        fun appVersion(): String {
            val n = currentVersion()
            return if (n != null) "1.$n" else ""
        }

        // فحص التحديث يدوياً: النتيجة ترجع للصفحة عبر onUpdateResult
        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread { checkForUpdate(true) }
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
        if (resultCode != RESULT_OK || uri == null) { pendingBytes = null; return }
        try {
            if (requestCode == REQ_SAVE) {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(pendingBytes ?: ByteArray(0))
                }
                pendingBytes = null
                Toast.makeText(this, "تم حفظ الملف", Toast.LENGTH_SHORT).show()
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
        // الصفحة تقرر: ترجع خطوة (من المشروع للقائمة، ومن القائمة للرئيسية) وإلا يسكّر التطبيق
        webView.evaluateJavascript(
            "(typeof __back==='function')?__back():0"
        ) { v -> if (v != "1") super@MainActivity.onBackPressed() }
    }

    companion object {
        private const val REQ_SAVE = 1001
        private const val REQ_OPEN = 1002
        private const val REQ_FILE = 1003
        private const val ACTION_INSTALL_RESULT = "iq.exam.maker.INSTALL_RESULT"
    }
}

/** تحميل ملف مع إمكانية الإيقاف المؤقت والاستمرار من نفس المكان (Range) */
class ApkDownloader(
    private val url: String,
    private val part: File,
    private val onProgress: (Long, Long) -> Unit,
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit,
    private val onPaused: () -> Unit
) {
    @Volatile private var stop = 0          // 0 شغّال، 1 إيقاف مؤقت، 2 إلغاء
    @Volatile private var running = false
    private var total = -1L

    @Synchronized
    fun start() {
        if (running) { stop = 0; return }
        stop = 0
        running = true
        Thread { work() }.start()
    }

    fun pause() { stop = 1 }
    fun cancel() { stop = 2 }

    private fun work() {
        var conn: HttpURLConnection? = null
        try {
            val have = if (part.exists()) part.length() else 0L
            var u = url
            var hops = 0
            while (true) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("User-Agent", "exam-maker-updater")
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                conn = c
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw Exception("redirect")
                    c.disconnect()
                    u = URL(URL(u), loc).toString()
                    if (++hops > 6) throw Exception("redirects")
                    continue
                }
                break
            }
            val r = conn!!
            var offset = have
            when (r.responseCode) {
                206 -> {
                    val cr = r.getHeaderField("Content-Range")
                    total = cr?.substringAfterLast('/')?.toLongOrNull() ?: (have + r.contentLengthLong)
                }
                200 -> {            // السيرفر تجاهل Range: نبدأ من الصفر
                    offset = 0
                    total = r.contentLengthLong
                    part.delete()
                }
                416 -> { part.delete(); throw Exception("restart") }
                else -> throw Exception("HTTP ${r.responseCode}")
            }

            var done = offset
            var last = 0L
            var reason = 0
            r.inputStream.use { ins ->
                FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (stop != 0) { reason = stop; break }
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 120) { last = now; onProgress(done, total) }
                    }
                }
            }
            when (reason) {
                2 -> part.delete()
                1 -> onPaused()
                else -> {
                    if (total > 0 && done < total) throw Exception("incomplete")
                    onProgress(done, total)
                    onDone()
                }
            }
        } catch (e: Exception) {
            when (stop) {
                2 -> part.delete()
                1 -> onPaused()
                else -> onError(e.message ?: "error")
            }
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
            running = false
        }
    }
}
