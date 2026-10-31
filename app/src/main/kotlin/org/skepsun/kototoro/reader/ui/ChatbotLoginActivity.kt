package org.skepsun.kototoro.reader.ui

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.skepsun.kototoro.core.ui.BaseComposeActivity

/**
 * Visible login surface. Android WebView instances in this app share the same cookie store.
 */
class ChatbotLoginActivity : BaseComposeActivity() {

    private var loginWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    TextButton(onClick = { finish() }) {
                        Text("Done")
                    }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).also { webView ->
                            loginWebView = webView
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = true
                            webView.settings.databaseEnabled = true
                            webView.webChromeClient = WebChromeClient()
                            webView.webViewClient = WebViewClient()
                            webView.loadUrl(kototoroAppSettings.readerTranslationChatbotUrl)
                        }
                    },
                    update = {},
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loginWebView?.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        loginWebView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        loginWebView?.let { webView ->
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        loginWebView = null
        super.onDestroy()
    }
}
