package org.skepsun.kototoro.reader.translate.domain

import android.webkit.WebView
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Serializes requests to the hidden chatbot WebView hosted by ReaderActivity.
 * No WebView is created here, so this singleton is safe to inject into workers.
 */
@Singleton
class ChatbotWebViewBridge @Inject constructor() {

    private val requestLock = Mutex()

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    @Volatile
    private var pageLoaded = false

    fun isAttached(): Boolean = webViewRef?.get() != null

    @MainThread
    fun attach(webView: WebView) {
        pageLoaded = false
        webViewRef = WeakReference(webView)
    }

    @MainThread
    fun onPageFinished(webView: WebView) {
        if (webViewRef?.get() === webView) pageLoaded = true
    }

    @MainThread
    fun detach(webView: WebView) {
        if (webViewRef?.get() === webView) {
            pageLoaded = false
            webViewRef = null
        }
    }

    suspend fun complete(prompt: String): String {
        require(prompt.isNotBlank()) { "Chatbot prompt is empty" }
        requestLock.lock()
        try {
            val webView = withContext(Dispatchers.Main.immediate) { webViewRef?.get() }
                ?: throw IllegalStateException("Chatbot WebView is not attached. Open the manga reader first.")

            withTimeout(PAGE_READY_TIMEOUT_MS) {
                while (true) {
                    check(isCurrent(webView)) { "Chatbot WebView was detached during a request" }
                    if (pageLoaded && evaluate(webView, COMPOSER_READY_SCRIPT) == "READY") break
                    delay(POLL_INTERVAL_MS)
                }
            }

            val sendStatus = evaluate(webView, buildSendScript(prompt))
            check(sendStatus == "SENT") {
                when (sendStatus) {
                    "NO_COMPOSER" -> "Chatbot composer was not found. Sign in to ChatGPT in Translation Settings."
                    else -> "Chatbot send button was not found or was disabled."
                }
            }

            val response: String = withTimeout<String>(RESPONSE_TIMEOUT_MS) {
                var previousText = ""
                var stableReads = 0
                while (true) {
                    check(isCurrent(webView)) { "Chatbot WebView was detached during a request" }
                    val state = JSONObject(evaluate(webView, READ_RESPONSE_SCRIPT))
                    val text = state.optString("text").trim()
                    val done = state.optBoolean("done")
                    if (done && text.isNotBlank() && text == previousText) {
                        stableReads++
                    } else {
                        stableReads = 0
                    }
                    previousText = text
                    if (stableReads >= REQUIRED_STABLE_READS) return@withTimeout text
                    delay(POLL_INTERVAL_MS)
                }
            }
            check(response.isNotBlank()) { "Chatbot returned an empty response" }
            return response
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException(
                "Chatbot WebView timed out. Check that you are signed in and the page is responsive.",
                timeout,
            )
        } finally {
            requestLock.unlock()
        }
    }

    private suspend fun isCurrent(webView: WebView): Boolean =
        withContext(Dispatchers.Main.immediate) { webViewRef?.get() === webView }

    private suspend fun evaluate(webView: WebView, script: String): String =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                try {
                    webView.evaluateJavascript(script) { raw ->
                        if (continuation.isActive) {
                            val decoded = runCatching { JSONArray("[$raw]").optString(0) }
                                .getOrDefault("")
                            continuation.resume(decoded)
                        }
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private fun buildSendScript(prompt: String): String {
        val quotedPrompt = JSONObject.quote(prompt)
        return """
            (() => {
              const prompt = $quotedPrompt;
              const composer =
                document.querySelector('#prompt-textarea') ||
                document.querySelector('textarea[placeholder]') ||
                document.querySelector('[contenteditable="true"][role="textbox"]') ||
                document.querySelector('[contenteditable="true"]') ||
                document.querySelector('textarea');
              if (!composer) return 'NO_COMPOSER';

              const baseline = document.querySelectorAll('[data-message-author-role="assistant"]').length;
              window.__kototoroChatbotRequest = { baseline: baseline, startedAt: Date.now() };
              composer.focus();
              if (composer.tagName === 'TEXTAREA' || composer.tagName === 'INPUT') {
                const setter = Object.getOwnPropertyDescriptor(
                  Object.getPrototypeOf(composer), 'value'
                )?.set;
                if (setter) setter.call(composer, prompt);
                else composer.value = prompt;
              } else {
                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, prompt);
              }
              composer.dispatchEvent(new InputEvent('input', {
                bubbles: true, inputType: 'insertText', data: prompt
              }));
              composer.dispatchEvent(new Event('change', { bubbles: true }));

              const sendButton =
                document.querySelector('button[data-testid="send-button"]') ||
                document.querySelector('button[aria-label*="Send"]') ||
                document.querySelector('button[type="submit"]');
              if (!sendButton || sendButton.disabled) return 'NO_SEND_BUTTON';
              sendButton.click();
              return 'SENT';
            })()
        """.trimIndent()
    }

    private companion object {
        const val PAGE_READY_TIMEOUT_MS = 45_000L
        const val RESPONSE_TIMEOUT_MS = 150_000L
        const val POLL_INTERVAL_MS = 500L
        const val REQUIRED_STABLE_READS = 3

        const val COMPOSER_READY_SCRIPT = """
            (() => {
              const composer =
                document.querySelector('#prompt-textarea') ||
                document.querySelector('textarea[placeholder]') ||
                document.querySelector('[contenteditable="true"][role="textbox"]') ||
                document.querySelector('[contenteditable="true"]') ||
                document.querySelector('textarea');
              return composer ? 'READY' : 'WAIT';
            })()
        """

        const val READ_RESPONSE_SCRIPT = """
            (() => {
              const request = window.__kototoroChatbotRequest;
              const messages = Array.from(
                document.querySelectorAll('[data-message-author-role="assistant"]')
              );
              const isNew = !!request && messages.length > request.baseline;
              const last = isNew ? messages[messages.length - 1] : null;
              const text = last ? (last.innerText || last.textContent || '').trim() : '';
              const busy = !!document.querySelector(
                'button[data-testid="stop-button"], button[aria-label*="Stop"], [data-testid="stop-button"]'
              );
              return JSON.stringify({ done: !!last && !busy && text.length > 0, text: text });
            })()
        """
    }
}
