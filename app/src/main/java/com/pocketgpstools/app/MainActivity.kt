package com.pocketgpstools.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/** Enveloppe native : affiche la page web (dossier assets) avec GPS, import et export de fichiers. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var geoCb: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCb = callback
                    geoOrigin = origin
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
                    )
                }
            }

            override fun onShowFileChooser(
                webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                return try {
                    startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), 2)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        val o = geoOrigin
        if (o != null) geoCb?.invoke(o, ok, false)
        geoCb = null
        geoOrigin = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        }
    }

    /** Fonctions appelables depuis la page web (objet « Android »). */
    inner class Bridge {
        /** Enregistre un fichier dans Téléchargements/PocketGPSTools. Retourne "OK" ou un message d'erreur. */
        @JavascriptInterface
        fun saveFile(name: String, mime: String, text: String): String {
            return try {
                val v = ContentValues()
                v.put(MediaStore.Downloads.DISPLAY_NAME, name)
                v.put(MediaStore.Downloads.MIME_TYPE, mime)
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketGPSTools")
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: return "ERR: création impossible"
                contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                "OK"
            } catch (e: Exception) {
                "ERR: " + e.message
            }
        }

        /** Ouvre le menu de partage Android avec le contenu en texte. */
        @JavascriptInterface
        fun share(name: String, text: String) {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_SUBJECT, name)
            i.putExtra(Intent.EXTRA_TEXT, text)
            val c = Intent.createChooser(i, name)
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(c)
        }

        /** Ferme l'application. */
        @JavascriptInterface
        fun quit() {
            runOnUiThread { finishAndRemoveTask() }
        }
    }
}
