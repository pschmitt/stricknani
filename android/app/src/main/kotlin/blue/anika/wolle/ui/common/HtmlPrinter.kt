package blue.anika.wolle.ui.common

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Prints a self-contained HTML document through Android's print framework (printers and "Save as
 * PDF"). The page is laid out in an off-screen WebView with JavaScript off; the HTML must already
 * contain its images as data URIs, since the WebView has no app credentials.
 *
 * [context] must be an Activity context: the system print dialog opens on top of it.
 */
object HtmlPrinter {
    // The WebView has to stay alive until the print framework has taken the document; holding the
    // most recent one is enough because only one print dialog is open at a time.
    private var pending: WebView? = null

    fun print(context: Context, jobName: String, html: String) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = false
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val printManager = context.getSystemService(PrintManager::class.java)
                    printManager.print(
                        jobName,
                        view.createPrintDocumentAdapter(jobName),
                        PrintAttributes.Builder().build(),
                    )
                }
            }
        pending = webView
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
