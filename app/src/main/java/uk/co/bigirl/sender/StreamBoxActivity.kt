package uk.co.bigirl.sender

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

class StreamBoxActivity : ComponentActivity() {

    private lateinit var webView: WebView

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        title = "StreamBox"

        webView = WebView(this).apply {
  setBackgroundColor(Color.BLACK)

  settings.javaScriptEnabled = true
  settings.domStorageEnabled = true
  settings.mediaPlaybackRequiresUserGesture =
      true
  settings.allowFileAccess = false
  settings.allowContentAccess = false

  webViewClient = createStreamBoxClient()
        }

        setContentView(webView)
        webView.loadUrl(STREAMBOX_URL)
    }

    private fun createStreamBoxClient() =
        object : WebViewClient() {

  override fun shouldOverrideUrlLoading(
      view: WebView,
      request: WebResourceRequest,
  ): Boolean {
      val scheme = request.url.scheme

      if (
          scheme == "http" ||
          scheme == "https"
      ) {
          if (
              request.url.host ==
              STREAMBOX_HOST
          ) {
              return false
          }

          startActivity(
              Intent(
                  Intent.ACTION_VIEW,
                  request.url,
              )
          )

          return true
      }

      return false
  }

  override fun onReceivedHttpAuthRequest(
      view: WebView,
      handler: HttpAuthHandler,
      host: String,
      realm: String,
  ) {
      showLogin(handler)
  }

  override fun onReceivedError(
      view: WebView,
      request: WebResourceRequest,
      error: WebResourceError,
  ) {
      if (request.isForMainFrame) {
          showOfflinePage()
      }
  }
        }

    private fun showLogin(
        handler: HttpAuthHandler,
    ) {
        val username = EditText(this).apply {
  hint = "Username"
        }

        val password = EditText(this).apply {
  hint = "Password"
  inputType = 0x00000081
        }

        val form = LinearLayout(this).apply {
  orientation =
      LinearLayout.VERTICAL

  val padding = (
      20 *
      resources.displayMetrics.density
  ).toInt()

  setPadding(
      padding,
      padding / 2,
      padding,
      0,
  )

  addView(username)
  addView(password)
        }

        AlertDialog.Builder(this)
  .setTitle("StreamBox sign in")
  .setMessage(
      "Use the same login details as the recordings website."
  )
  .setView(form)
  .setPositiveButton(
      "SIGN IN",
  ) { _, _ ->
      handler.proceed(
          username.text.toString(),
          password.text.toString(),
      )
  }
  .setNegativeButton(
      "CANCEL",
  ) { _, _ ->
      handler.cancel()
  }
  .setOnCancelListener {
      handler.cancel()
  }
  .show()
    }

    private fun showOfflinePage() {
        val html = """
  <html>
  <body style="
      background:#071019;
      color:white;
      font-family:sans-serif;
      padding:24px;
  ">
  <h2>StreamBox unavailable</h2>
  <p>
      Turn on Tailscale on this phone,
      then retry.
  </p>
  <button
      style="padding:14px;margin:6px"
      onclick="location.href='retry:'"
  >
      RETRY
  </button>
  <button
      style="padding:14px;margin:6px"
      onclick="location.href='tailscale:'"
  >
      OPEN TAILSCALE
  </button>
  <button
      style="padding:14px;margin:6px"
      onclick="location.href='browser:'"
  >
      OPEN IN BROWSER
  </button>
  </body>
  </html>
        """.trimIndent()

        webView.webViewClient =
  object : WebViewClient() {

      override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
      ): Boolean {
          when (request.url.scheme) {
              "retry" -> {
                  webView.webViewClient =
                      createStreamBoxClient()

                  webView.loadUrl(
                      STREAMBOX_URL
                  )
              }

              "tailscale" -> {
                  openTailscale()
              }

              "browser" -> {
                  startActivity(
                      Intent(
                          Intent.ACTION_VIEW,
                          Uri.parse(
                              STREAMBOX_URL
                          ),
                      )
                  )
              }
          }

          return true
      }
  }

        webView.loadDataWithBaseURL(
  null,
  html,
  "text/html",
  "UTF-8",
  null,
        )
    }

    private fun openTailscale() {
        val launchIntent =
  packageManager
      .getLaunchIntentForPackage(
          "com.tailscale.ipn"
      )

        if (launchIntent != null) {
  startActivity(launchIntent)
  return
        }

        Toast.makeText(
  this,
  "Tailscale is not installed",
  Toast.LENGTH_LONG,
        ).show()

        startActivity(
  Intent(
      Intent.ACTION_VIEW,
      Uri.parse(
          "https://play.google.com/store/apps/details?id=com.tailscale.ipn"
      ),
  )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (
  ::webView.isInitialized &&
  webView.canGoBack()
        ) {
  webView.goBack()
        } else {
  super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
  webView.stopLoading()
  webView.webViewClient = WebViewClient()
  webView.destroy()
        }

        super.onDestroy()
    }

    private companion object {
        const val STREAMBOX_HOST =
  "100.68.249.56"

        const val STREAMBOX_URL =
  "http://100.68.249.56/recordings"
    }
}
