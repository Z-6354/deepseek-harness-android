package com.labteto.dshmobile.browser

/** Local-only IndexedDB fixture host; debug APK only. It never loads a network page. */
class RuntimeFixtureActivity : android.app.Activity() {
    private var webView: android.webkit.WebView? = null

    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        val container = android.widget.FrameLayout(this)
        setContentView(container)
        val operation = intent.getStringExtra("operation") ?: "idle"
        if (operation == "idle") return

        val view = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("SessionCacheFixture", message.message())
                    return true
                }
            }
        }
        webView = view
        container.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        val bundledFixture = assets.open("session-cache-fixture.js").bufferedReader().use { it.readText() }
        val script = """
            $bundledFixture
            runSessionCacheDeviceFixture(${org.json.JSONObject.quote(operation)}).then(result => {
              console.log('SESSION_CACHE_FIXTURE:' + result.operation.toUpperCase() + '_OK:' + JSON.stringify(result.results));
            }).catch(error => console.log('SESSION_CACHE_FIXTURE:ERROR:' + String(error && (error.stack || error))));
        """.trimIndent()
        view.loadDataWithBaseURL(
            "https://session-cache-fixture.invalid/",
            "<html><body>local fixture</body><script>$script</script></html>",
            "text/html", "UTF-8", null,
        )
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
