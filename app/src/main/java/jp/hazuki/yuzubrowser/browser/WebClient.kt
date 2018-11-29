/*
 * Copyright (C) 2017-2018 Hazuki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.hazuki.yuzubrowser.browser

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.text.TextUtils
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.crashlytics.android.Crashlytics
import jp.hazuki.yuzubrowser.Constants
import jp.hazuki.yuzubrowser.R
import jp.hazuki.yuzubrowser.adblock.AdBlockActivity
import jp.hazuki.yuzubrowser.adblock.AdBlockController
import jp.hazuki.yuzubrowser.adblock.mining.MiningProtector
import jp.hazuki.yuzubrowser.bookmark.view.BookmarkActivity
import jp.hazuki.yuzubrowser.debug.DebugActivity
import jp.hazuki.yuzubrowser.download.ui.DownloadListActivity
import jp.hazuki.yuzubrowser.download.ui.fragment.DownloadDialog
import jp.hazuki.yuzubrowser.favicon.FaviconAsyncManager
import jp.hazuki.yuzubrowser.history.BrowserHistoryActivity
import jp.hazuki.yuzubrowser.history.BrowserHistoryAsyncManager
import jp.hazuki.yuzubrowser.pattern.action.OpenOthersPatternAction
import jp.hazuki.yuzubrowser.pattern.action.WebSettingPatternAction
import jp.hazuki.yuzubrowser.pattern.action.WebSettingResetAction
import jp.hazuki.yuzubrowser.pattern.url.PatternUrlManager
import jp.hazuki.yuzubrowser.readitlater.ReadItLaterActivity
import jp.hazuki.yuzubrowser.resblock.ResourceBlockListActivity
import jp.hazuki.yuzubrowser.resblock.ResourceBlockManager
import jp.hazuki.yuzubrowser.resblock.ResourceChecker
import jp.hazuki.yuzubrowser.settings.PreferenceConstants
import jp.hazuki.yuzubrowser.settings.activity.MainSettingsActivity
import jp.hazuki.yuzubrowser.settings.data.AppData
import jp.hazuki.yuzubrowser.speeddial.SpeedDialAsyncManager
import jp.hazuki.yuzubrowser.speeddial.SpeedDialHtml
import jp.hazuki.yuzubrowser.tab.manager.MainTabData
import jp.hazuki.yuzubrowser.toolbar.sub.GeolocationPermissionToolbar
import jp.hazuki.yuzubrowser.userjs.UserScript
import jp.hazuki.yuzubrowser.userjs.UserScriptDatabase
import jp.hazuki.yuzubrowser.utils.*
import jp.hazuki.yuzubrowser.utils.extensions.getFakeChromeUserAgent
import jp.hazuki.yuzubrowser.utils.extensions.readAssetsText
import jp.hazuki.yuzubrowser.utils.extensions.setClipboardWithToast
import jp.hazuki.yuzubrowser.webkit.*
import jp.hazuki.yuzubrowser.webkit.listener.OnWebStateChangeListener
import jp.hazuki.yuzubrowser.webrtc.WebRtcPermission
import org.jetbrains.anko.longToast
import java.net.URISyntaxException
import java.text.DateFormat
import kotlin.concurrent.thread

class WebClient(private val activity: BrowserBaseActivity, private val controller: BrowserController) {
    private val patternManager = PatternUrlManager(activity.applicationContext)
    private val speedDialManager = SpeedDialAsyncManager(activity.applicationContext)
    private val speedDialHtml = SpeedDialHtml(activity.applicationContext)
    private val faviconManager = FaviconAsyncManager(activity.applicationContext)
    private val onWebStateChangeListener: OnWebStateChangeListener = { web, tabData ->
        controller.getTabOrNull(web)?.let { tab ->
            tab.onStateChanged(tabData)
            if (tab == controller.currentTabData) {
                controller.notifyChangeWebState(tab)
            }
        }
    }
    private val webViewRenderingManager = WebViewRenderingManager()
    private val scrollableToolbarHeight = { controller.appBarLayout.totalScrollRange + controller.pagePaddingHeight }
    private var browserHistoryManager: BrowserHistoryAsyncManager? = null
    private var resourceCheckerList: ArrayList<ResourceChecker>? = null
    private var adBlockController: AdBlockController? = null
    private var miningProtector: MiningProtector? = null
    private var userScriptList: ArrayList<UserScript>? = null
    private var webUploadHandler: WebUploadHandler? = null
    private val invertJs by lazy(LazyThreadSafetyMode.NONE) { activity.readAssetsText("scripts/invert-min,js") }

    var renderingMode
        get() = webViewRenderingManager.mode
        set(value) {
            if (value == renderingMode) return

            webViewRenderingManager.mode = value
            val js = invertJs.replace("%s", webViewRenderingManager.isInvertMode.toString())
            controller.tabManager.loadedData.forEach {
                webViewRenderingManager.setWebViewRendering(it.mWebView)
                it.mWebView.evaluateJavascript(js, null)
            }
        }

    var isEnableHistory
        get() = browserHistoryManager != null
        set(enable) {
            if (enable == (browserHistoryManager != null)) return

            browserHistoryManager = if (enable) {
                BrowserHistoryAsyncManager(activity)
            } else {
                browserHistoryManager?.destroy()
                null
            }
        }

    var isEnableUserScript
        get() = userScriptList != null
        set(value) {
            if (value == isEnableUserScript) return

            resetUserScript(value)
        }

    var isEnableAdBlock
        get() = adBlockController != null
        set(value) {
            if (value == isEnableAdBlock) return

            adBlockController = if (value) AdBlockController(activity) else null
        }

    fun updateAdBlockList() {
        adBlockController?.update()
    }

    fun onStop() {
        adBlockController?.onResume()
    }

    fun destroy() {
        browserHistoryManager?.destroy()
        browserHistoryManager = null
        webUploadHandler?.destroy()
        webUploadHandler = null
        speedDialManager.destroy()
        faviconManager.destroy()
    }

    fun webUploadResult(resultCode: Int, data: Intent?) {
        webUploadHandler?.onActivityResult(resultCode, data)
    }

    fun onPreferenceReset() {
        patternManager.load(activity.applicationContext)
        webViewRenderingManager.onPreferenceReset()

        isEnableHistory = !AppData.private_mode.get() && AppData.save_history.get()

        resourceCheckerList = if (AppData.resblock_enable.get()) {
            ResourceBlockManager(activity.applicationContext).list
        } else {
            null
        }

        adBlockController = if (AppData.ad_block.get()) {
            AdBlockController(activity.applicationContext)
        } else {
            null
        }
        if (AppData.mining_protect.get()) {
            if (miningProtector == null) {
                miningProtector = MiningProtector()
            }
        } else {
            miningProtector = null
        }

        val js by lazy(LazyThreadSafetyMode.NONE) { invertJs.replace("%s", webViewRenderingManager.isInvertMode.toString()) }
        val isInverted = webViewRenderingManager.isInverted

        controller.tabManager.loadedData.forEach {
            initWebSetting(it.mWebView)
            it.mWebView.onPreferenceReset()
            if (isInverted) it.mWebView.evaluateJavascript(js, null)
        }

        controller.tabManager.currentTabData?.let {
            controller.toolbarManager.notifyChangeWebState(it)
        }

        val cookie = if (AppData.private_mode.get())
            AppData.accept_cookie.get() && AppData.accept_cookie_private.get()
        else
            AppData.accept_cookie.get()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(cookie)

        val thirdCookie = cookie && AppData.accept_third_cookie.get()
        controller.tabManager.loadedData.forEach {
            it.mWebView.setAcceptThirdPartyCookies(cookieManager, thirdCookie)
        }

        resetUserScript(AppData.userjs_enable.get())
    }

    fun initWebSetting(web: CustomWebView) {
        web.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY)
        web.setOverScrollModeMethod(View.OVER_SCROLL_IF_CONTENT_SCROLLS)

        webViewRenderingManager.setWebViewRendering(web)
        web.setScrollableHeight(scrollableToolbarHeight)

        web.setMyWebChromeClient(MyWebChromeClient())
        web.setMyWebViewClient(mWebViewClient)

        web.setDownloadListener(object : DownloadListener {
            override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String, contentLength: Long) {
                when (AppData.download_action.get()) {
                    PreferenceConstants.DOWNLOAD_DO_NOTHING -> {
                    }
                    PreferenceConstants.DOWNLOAD_AUTO -> if (WebDownloadUtils.shouldOpen(contentDisposition)) {
                        actionOpen(url, userAgent, contentDisposition, mimetype, contentLength)
                    } else {
                        actionDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                    }
                    PreferenceConstants.DOWNLOAD_DOWNLOAD -> actionDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                    PreferenceConstants.DOWNLOAD_OPEN -> actionOpen(url, userAgent, contentDisposition, mimetype, contentLength)
                    PreferenceConstants.DOWNLOAD_SHARE -> actionShare(url)
                    PreferenceConstants.DOWNLOAD_SELECT -> {

                        AlertDialog.Builder(activity)
                                .setTitle(R.string.download)
                                .setItems(
                                        arrayOf(getString(R.string.download), getString(R.string.open), getString(R.string.share))
                                ) { _, which ->
                                    when (which) {
                                        0 -> actionDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                                        1 -> actionOpen(url, userAgent, contentDisposition, mimetype, contentLength)
                                        2 -> actionShare(url)
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                    }
                }

                if (web.isBackForwardListEmpty) {
                    controller.removeTab(controller.indexOf(web.identityId))
                }
            }

            private fun actionDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String, contentLength: Long) {
                activity.showDialog(DownloadDialog(activity, url, userAgent, contentDisposition, mimetype, contentLength, null), "download")
            }

            private fun actionOpen(url: String, userAgent: String, contentDisposition: String, mimetype: String, contentLength: Long) {
                if (!WebDownloadUtils.openFile(activity, url, mimetype)) {
                    //application not found
                    Toast.makeText(activity.applicationContext, R.string.app_notfound, Toast.LENGTH_SHORT).show()
                    actionDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                }
            }

            private fun actionShare(url: String) {
                WebUtils.shareWeb(activity, url, null)
            }
        })

        web.setOnCustomWebViewStateChangeListener(onWebStateChangeListener)

        val setting = web.settings
        setting.setNeedInitialFocus(false)
        setting.defaultFontSize = 16
        setting.defaultFixedFontSize = 13
        setting.minimumLogicalFontSize = AppData.minimum_font.get()
        setting.minimumFontSize = AppData.minimum_font.get()

        setting.mixedContentMode = AppData.mixed_content.get()
        setting.setSupportMultipleWindows(AppData.newtab_blank.get() != BrowserManager.LOAD_URL_TAB_CURRENT)
        WebViewUtils.setTextSize(setting, AppData.text_size.get())
        setting.javaScriptEnabled = AppData.javascript.get()


        setting.allowContentAccess = AppData.allow_content_access.get()
        setting.allowFileAccess = AppData.file_access.get() == PreferenceConstants.FILE_ACCESS_ENABLE
        setting.defaultTextEncodingName = AppData.default_encoding.get()
        if (AppData.fake_chrome.get() && AppData.user_agent.get() == "") {
            setting.userAgentString = activity.getFakeChromeUserAgent()
        } else {
            setting.userAgentString = AppData.user_agent.get()
        }
        setting.loadWithOverviewMode = AppData.load_overview.get()
        setting.useWideViewPort = AppData.web_wideview.get()
        WebViewUtils.setDisplayZoomButtons(setting, AppData.show_zoom_button.get())
        setting.cacheMode = AppData.web_cache.get()
        setting.javaScriptCanOpenWindowsAutomatically = AppData.web_popup.get()
        setting.layoutAlgorithm = WebSettings.LayoutAlgorithm.valueOf(AppData.layout_algorithm.get())
        setting.loadsImagesAutomatically = !AppData.block_web_images.get()

        val noPrivate = !AppData.private_mode.get()
        setting.databaseEnabled = noPrivate && AppData.web_db.get()
        setting.domStorageEnabled = noPrivate && AppData.web_dom_db.get()
        setting.setGeolocationEnabled(noPrivate && AppData.web_geolocation.get())
        setting.setAppCacheEnabled(noPrivate && AppData.web_app_cache.get())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setting.safeBrowsingEnabled = AppData.safe_browsing.get()
        } else {
            @Suppress("DEPRECATION")
            setting.saveFormData = noPrivate && AppData.save_formdata.get()
        }

        setting.setAppCachePath(BrowserManager.getAppCacheFilePath(activity.applicationContext))

        web.resetTheme()
        web.swipeEnable = AppData.pull_to_refresh.get()

        //if add to this, should also add to AbstractCacheWebView#settingWebView
    }

    fun loadUrl(tab: MainTabData, url: String, shouldOpenInNewTab: Boolean) {
        if (tab.isNavLock && !WebViewUtils.shouldLoadSameTabUser(url)) {
            controller.performNewTabLink(BrowserManager.LOAD_URL_TAB_NEW_RIGHT, tab, url, TabType.WINDOW)
            return
        }
        val newUrl = if (AppData.file_access.get() == PreferenceConstants.FILE_ACCESS_SAFER && URLUtil.isFileUrl(url))
            SafeFileProvider.convertToSaferUrl(url)
        else
            url
        if (!checkUrl(tab, newUrl, Uri.parse(newUrl))) {
            if (checkPatternMatch(tab, newUrl, shouldOpenInNewTab) <= 0)
                tab.mWebView.loadUrl(newUrl)
        }
    }

    fun loadUrl(tab: MainTabData, url: String, target: Int, type: Int) {
        if (!checkNewTabLinkUser(target, tab, url, type))
            loadUrl(tab, url, false)
    }

    private fun checkNewTabLinkUser(perform: Int, tab: MainTabData, url: String, @TabType type: Int): Boolean {
        if (perform < 0)
            return false

        return if (perform == BrowserManager.LOAD_URL_TAB_CURRENT) false
        else !WebViewUtils.shouldLoadSameTabUser(url) && controller.performNewTabLink(perform, tab, url, type)
    }

    private val mWebViewClient = object : CustomWebViewClient() {
        override fun shouldOverrideUrlLoading(web: CustomWebView, url: String, uri: Uri): Boolean {
            val data = controller.getTabOrNull(web) ?: return true

            if (AppData.file_access.get() == PreferenceConstants.FILE_ACCESS_SAFER && URLUtil.isFileUrl(url)) {
                controller.loadUrl(data, SafeFileProvider.convertToSaferUrl(url))
                return true
            }
            val patternResult = checkPatternMatch(data, url, false)
            if (patternResult == 0) {
                web.loadUrl(url)
                return true
            }

            if (patternResult == 1 || checkNewTabLinkAuto(getNewTabPerformType(data), data, url)) {
                if (web.url == null || data.mWebView.isBackForwardListEmpty) {
                    controller.removeTab(controller.indexOf(data.id))
                }
                return true
            }

            return checkUrl(data, url, uri)
        }

        override fun onPageStarted(web: CustomWebView, url: String, favicon: Bitmap?) {
            val data = controller.getTabOrNull(web) ?: return

            if (AppData.toolbar_auto_open.get()) {
                controller.appBarLayout.setExpanded(true, true)
                data.mWebView.isNestedScrollingEnabledMethod = false
            }

            applyUserScript(web, url, true)

            data.onPageStarted(url, favicon)

            if (data === controller.currentTabData) {
                controller.notifyChangeWebState(data)
            }

            if (controller.isActivityPaused) {
                resumeWebViewTimers(data)
            }

            controller.stopAutoScroll()

            data.onStartPage()

            if (AppData.save_tabs_for_crash.get())
                controller.tabManager.saveData()

            controller.tabManager.removeThumbnailCache(url)
        }

        override fun onPageFinished(web: CustomWebView, url: String) {
            val data = controller.getTabOrNull(web) ?: return

            applyUserScript(web, url, false)

            if (webViewRenderingManager.isInvertMode) {
                web.evaluateJavascript(invertJs.replace("%s", "true"), null)
            }

            if (controller.isActivityPaused) {
                pauseWebViewTimers(data)
            }

            data.onPageFinished(web, url)

            controller.requestAdjustWebView()

            if (data === controller.currentTabData) {
                controller.notifyChangeWebState(data)

                web.view.postDelayed({ controller.adjustBrowserPadding(data) }, 50)
            }

            controller.tabManager.takeThumbnailIfNeeded(data)

            if (AppData.save_tabs_for_crash.get())
                controller.tabManager.saveData()

            if (speedDialManager.isNeedUpdate(data.originalUrl)) {
                web.evaluateJavascript(Scripts.GET_ICON_URL) {
                    val iconUrl = if (it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it

                    if (iconUrl.isEmpty() || iconUrl == "null") {
                        speedDialManager.updateAsync(data.originalUrl, faviconManager.get(data.originalUrl))
                    } else {
                        val userAgent = data.mWebView.getUserAgent()
                        thread {
                            speedDialManager.updateAsync(data.originalUrl,
                                    HttpUtils.getImage(iconUrl, userAgent, url, CookieManager.getInstance().getCookie(url)))
                        }
                    }
                }
            }
        }

        override fun onFormResubmission(web: CustomWebView, dontResend: Message, resend: Message) {
            AlertDialog.Builder(activity)
                    .setTitle(web.url)
                    .setMessage(R.string.form_resubmit)
                    .setPositiveButton(android.R.string.yes) { _, _ -> resend.sendToTarget() }
                    .setNegativeButton(android.R.string.no) { _, _ -> dontResend.sendToTarget() }
                    .setOnCancelListener { dontResend.sendToTarget() }
                    .show()
        }

        override fun doUpdateVisitedHistory(web: CustomWebView, url: String, isReload: Boolean) {
            val data = controller.getTabOrNull(web)?.originalUrl ?: return

            browserHistoryManager?.add(data)
        }

        override fun onReceivedHttpAuthRequest(web: CustomWebView, handler: HttpAuthHandler, host: String, realm: String) {
            HttpAuthRequestDialog(activity).requestHttpAuth(web, handler, host, realm)
        }

        override fun onReceivedError(view: CustomWebView, errorCode: Int, description: CharSequence, url: Uri) {
            if (errorCode == ERROR_UNSUPPORTED_SCHEME && url.toString().equals("yuzu:speeddial", true)) {
                view.view.postDelayed({ view.reload() }, 50)
            }
        }

        override fun onReceivedSslError(web: CustomWebView, handler: SslErrorHandler, error: SslError) {
            if (!AppData.ssl_error_alert.get()) {
                handler.cancel()
                return
            }

            if (!activity.isFinishing) {
                val view = View.inflate(activity, R.layout.dialog_ssl_error, null)
                view.findViewById<TextView>(R.id.urlTextView).apply {
                    text = error.url
                    setOnLongClickListener {
                        activity.setClipboardWithToast(text.toString())
                        true
                    }
                }
                view.findViewById<TextView>(R.id.messageTextView).text = activity.getString(R.string.ssl_error_mes, error.getErrorMessages(activity))

                AlertDialog.Builder(activity)
                        .setTitle(R.string.ssl_error_title)
                        .setView(view)
                        .setPositiveButton(android.R.string.yes) { _, _ -> handler.proceed() }
                        .setNegativeButton(android.R.string.no) { _, _ -> handler.cancel() }
                        .setOnCancelListener { handler.cancel() }
                        .show()
            }
        }

        override fun shouldInterceptRequest(web: CustomWebView, request: WebResourceRequest): WebResourceResponse? {
            if ("yuzu".equals(request.url.scheme, ignoreCase = true)) {
                val action = request.url.schemeSpecificPart

                if (action != null) {
                    when {
                        "speeddial".equals(action, ignoreCase = true) -> return speedDialHtml.createResponse()
                        "speeddial/base.css" == action -> return speedDialHtml.baseCss
                        "speeddial/custom.css" == action -> return speedDialHtml.customCss
                        action.startsWith("speeddial/img/") -> return speedDialHtml.getImage(action)
                    }
                }
            }

            val tabIndexData = controller.tabManager.getIndexData(web.identityId) ?: return null
            val uri = Uri.parse(tabIndexData.url ?: "")

            adBlockController?.run {
                try {
                    val result = isBlock(uri, request.url)
                    if (result != null) {
                        return if (request.isForMainFrame) {
                            createMainFrameDummy(activity, request.url, result.pattern)
                        } else {
                            createDummy(request.url)
                        }
                    }
                } catch (e: Exception) {
                    Crashlytics.logException(e)
                    throw e
                }
            }

            miningProtector?.run {
                if (isBlock(uri, request.url)) {
                    return dummy
                }
            }

            resourceCheckerList?.forEach {
                when (it.check(request.url)) {
                    ResourceChecker.SHOULD_RUN -> return it.getResource(activity.applicationContext)
                    ResourceChecker.SHOULD_BREAK -> return null
                    ResourceChecker.SHOULD_CONTINUE -> return@forEach
                    else -> throw RuntimeException("unknown : " + it.check(request.url))
                }
            }
            return null
        }
    }

    fun checkPatternMatch(tab: MainTabData, url: String?, shouldOpenInNewTab: Boolean): Int {
        if (url == null) return -1
        var normalSettings = true
        var changeSetting = false
        patternManager.list
                .filter { it.isMatchUrl(url) }
                .forEach {
                    if (it.action is WebSettingPatternAction) {
                        if (tab.resetAction != null && tab.resetAction.patternAction == it.action) {
                            normalSettings = false
                            return@forEach
                        }

                        /* save normal settings */
                        if (tab.resetAction == null)
                            tab.resetAction = WebSettingResetAction(tab)
                        tab.resetAction.patternAction = it.action as WebSettingPatternAction

                        /* change web settings */
                        it.action.run(activity, tab, url)
                        changeSetting = true
                    } else if (shouldOpenInNewTab && it.action is OpenOthersPatternAction) {
                        return@forEach
                    } else if (it.action.run(activity, tab, url))
                        return 1
                }

        if (changeSetting) {
            return 0
        }

        /* reset to normal */
        if (normalSettings && tab.resetAction != null) {
            tab.resetAction.reset(tab)
            tab.resetAction = null
            controller.notifyChangeWebState()
            return 0
        }
        return -1
    }

    private inner class MyWebChromeClient : CustomWebChromeClient() {
        private var geoView: GeolocationPermissionToolbar? = null

        override fun onProgressChanged(web: CustomWebView, newProgress: Int) {
            val data = controller.getTabOrNull(web) ?: return

            data.onProgressChanged(newProgress)
            if (newProgress == 100) {
                CookieManager.getInstance().flush()
            }

            if (data === controller.currentTabData) {
                if (data.isInPageLoad)
                    controller.notifyChangeProgress(data)
                else
                    controller.notifyChangeWebState(data)
            }

            if (data.isStartDocument && newProgress > 35) {
                applyUserScript(web, data.url, true)
                data.isStartDocument = false
            }
        }

        override fun onReceivedTitle(web: CustomWebView, title: String) {
            val data = controller.getTabOrNull(web) ?: return

            data.onReceivedTitle(title)

            browserHistoryManager?.update(data.originalUrl, title)
        }

        override fun onReceivedIcon(web: CustomWebView, icon: Bitmap) {
            val data = controller.getTabOrNull(web) ?: return

            faviconManager.updateAsync(data.originalUrl, icon)

            data.onReceivedIcon(icon)
        }

        override fun onRequestFocus(web: CustomWebView) {
            val i = controller.indexOf(web.identityId)
            if (i >= 0)
                controller.setCurrentTab(i)
        }


        override fun onCreateWindow(view: CustomWebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            controller.checkNewTabLink(AppData.newtab_blank.get(), resultMsg.obj as WebView.WebViewTransport)
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(web: CustomWebView) {
            val i = controller.indexOf(web.identityId)
            if (i >= 0)
                controller.removeTab(i)
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>?>, fileChooserParams: WebChromeClient.FileChooserParams): Boolean {
            if (webUploadHandler == null)
                webUploadHandler = WebUploadHandler()

            try {
                controller.startActivity(Intent.createChooser(webUploadHandler!!.onShowFileChooser(filePathCallback, fileChooserParams), getString(R.string.select_file)), BrowserController.REQUEST_WEB_UPLOAD)
            } catch (e: ActivityNotFoundException) {
                e.printStackTrace()
                Toast.makeText(activity.applicationContext, R.string.app_notfound, Toast.LENGTH_SHORT).show()
            }

            return true
        }

        override fun onJsAlert(view: CustomWebView, url: String, message: String, result: JsResult): Boolean {
            if (!activity.isFinishing) {
                AlertDialog.Builder(activity)
                        .setTitle(url)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.yes) { _, _ -> result.confirm() }
                        .setOnCancelListener { result.cancel() }
                        .show()
            }
            return true
        }

        override fun onJsConfirm(view: CustomWebView, url: String, message: String, result: JsResult): Boolean {
            if (!activity.isFinishing)
                AlertDialog.Builder(activity)
                        .setTitle(url)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.yes) { _, _ -> result.confirm() }
                        .setNegativeButton(android.R.string.no) { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
            return true
        }

        override fun onJsPrompt(view: CustomWebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
            val editText = EditText(activity)
            editText.setText(defaultValue)
            AlertDialog.Builder(activity)
                    .setTitle(url)
                    .setMessage(message)
                    .setView(editText)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(editText.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
            return true
        }


        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            controller.showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            controller.hideCustomView()
        }

        override fun getVideoLoadingProgressView(): View? = controller.getVideoLoadingProgressView()

        override fun onGeolocationPermissionsHidePrompt() {
            geoView?.let {
                controller.toolbarManager.hideGeolocationPermissionPrompt(it)
                geoView = null
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (geoView == null) {
                geoView = object : GeolocationPermissionToolbar(activity) {
                    override fun onHideToolbar() {
                        controller.toolbarManager.hideGeolocationPermissionPrompt(geoView!!)
                        geoView = null
                    }
                }
                controller.toolbarManager.showGeolocationPermissionPrompt(geoView!!)
            }
            geoView!!.onGeolocationPermissionsShowPrompt(origin, callback)
        }

        override fun getVisitedHistory(callback: ValueCallback<Array<String>>) {
            browserHistoryManager?.run {
                thread {
                    callback.onReceiveValue(getHistoryArray(3000))
                }
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            controller.activity.runOnUiThread {
                if (AppData.webRtc.get()) {
                    WebRtcPermission.getInstance(controller.applicationContextInfo).requestPermission(request, controller.webRtcRequest)
                } else {
                    request.deny()
                }
            }
        }
    }

    private fun checkUrl(data: MainTabData, url: String, uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false

        when (scheme.toLowerCase()) {
            "intent" -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

                    if (intent != null) {
                        if (BookmarkActivity::class.java.name == intent.component?.className) {
                            controller.startActivity(intent, BrowserController.REQUEST_BOOKMARK)
                        } else {
                            val info = activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                            if (info != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    activity.startActivity(intent)
                                    return true
                                } catch (e: SecurityException) {
                                    e.printStackTrace()
                                }

                            }
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (!TextUtils.isEmpty(fallbackUrl)) {
                                controller.loadUrl(data, fallbackUrl)
                            }
                        }
                        return true
                    }
                } catch (e: URISyntaxException) {
                    Logger.e(TAG, "Can't resolve intent://", e)
                }
            }
            "yuzu" -> {
                val action = uri.schemeSpecificPart

                val intent: Intent
                if (action.isNullOrEmpty()) {
                    return false
                } else
                    when (action.toLowerCase()) {
                        "settings", "setting" -> intent = Intent(activity, MainSettingsActivity::class.java)
                        "histories", "history" -> {
                            intent = Intent(activity, BrowserHistoryActivity::class.java)
                            intent.putExtra(Constants.intent.EXTRA_MODE_FULLSCREEN, controller.isFullscreenMode && DisplayUtils.isNeedFullScreenFlag())
                            intent.putExtra(Constants.intent.EXTRA_MODE_ORIENTATION, controller.requestedOrientationByCtrl)
                            controller.startActivity(intent, BrowserController.REQUEST_HISTORY)
                            return true
                        }
                        "downloads", "download" -> {
                            intent = Intent(activity, DownloadListActivity::class.java).apply {
                                putExtra(Constants.intent.EXTRA_MODE_FULLSCREEN, controller.isFullscreenMode && DisplayUtils.isNeedFullScreenFlag())
                                putExtra(Constants.intent.EXTRA_MODE_ORIENTATION, controller.requestedOrientationByCtrl)
                            }
                        }
                        "debug" -> intent = Intent(activity, DebugActivity::class.java)
                        "bookmarks", "bookmark" -> {
                            intent = Intent(activity, BookmarkActivity::class.java).apply {
                                putExtra(Constants.intent.EXTRA_MODE_FULLSCREEN, controller.isFullscreenMode && DisplayUtils.isNeedFullScreenFlag())
                                putExtra(Constants.intent.EXTRA_MODE_ORIENTATION, controller.requestedOrientationByCtrl)
                            }
                            controller.startActivity(intent, BrowserController.REQUEST_BOOKMARK)
                            return true
                        }
                        "search" -> {
                            controller.showSearchBox("", controller.indexOf(data.id), false, "reverse".equals(uri.fragment, ignoreCase = true))
                            return true
                        }
                        "speeddial" -> return false
                        "home" -> {
                            if ("yuzu:home".equals(AppData.home_page.get(), ignoreCase = true) || "yuzu://home".equals(AppData.home_page.get(), ignoreCase = true)) {
                                AppData.home_page.set("about:blank")
                                AppData.commit(activity, AppData.home_page)
                            }
                            controller.loadUrl(data, AppData.home_page.get())
                            return true
                        }
                        "resblock" -> intent = Intent(activity, ResourceBlockListActivity::class.java)
                        "adblock" -> intent = Intent(activity, AdBlockActivity::class.java)
                        "readitlater" -> intent = Intent(activity, ReadItLaterActivity::class.java)
                        else -> return false
                    }
                activity.startActivity(intent)
                return true
            }
            "mailto" -> {
                try {
                    activity.startActivity(Intent(Intent.ACTION_SENDTO, uri))
                } catch (e: ActivityNotFoundException) {
                    controller.activity.longToast(R.string.app_notfound)
                }
                return true
            }
            "tel" -> {
                try {
                    activity.startActivity(Intent(Intent.ACTION_DIAL, uri))
                } catch (e: ActivityNotFoundException) {
                    controller.activity.longToast(R.string.app_notfound)
                }
                return true
            }
        }

        if (AppData.share_unknown_scheme.get()) {
            if (WebUtils.isOverrideScheme(uri)) {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                val info = activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                if (info != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        activity.startActivity(intent)
                        return true
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                    }

                }
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!TextUtils.isEmpty(fallbackUrl)) {
                    controller.loadUrl(data, fallbackUrl)
                }
                return true
            }
        }
        data.url = url
        return false
    }

    fun resetUserScript(enable: Boolean) {
        if (enable) {
            userScriptList = UserScriptDatabase(activity.applicationContext).enableJsDataList
        } else {
            if (userScriptList != null)
                userScriptList = null
        }
    }

    private fun applyUserScript(web: CustomWebView, url: String, isStart: Boolean) {
        userScriptList?.let {
            SCRIPT_LOOP@ for (script in it) {
                if (isStart != script.isRunStart)
                    continue

                for (pattern in script.exclude) {
                    if (pattern.matcher(url).find())
                        continue@SCRIPT_LOOP
                }

                for (pattern in script.include) {
                    if (pattern.matcher(url).find()) {
                        web.evaluateJavascript(script.runnable, null)

                        continue@SCRIPT_LOOP
                    }
                }
            }
        }
    }

    private fun checkNewTabLinkAuto(perform: Int, tab: MainTabData, url: String): Boolean {
        if (tab.isNavLock && !WebViewUtils.shouldLoadSameTabAuto(url)) {
            controller.performNewTabLink(BrowserManager.LOAD_URL_TAB_NEW_RIGHT, tab, url, TabType.WINDOW)
            return true
        }

        if (perform == BrowserManager.LOAD_URL_TAB_CURRENT)
            return false

        if (WebViewUtils.shouldLoadSameTabAuto(url))
            return false

        return if (WebViewUtils.shouldLoadSameTabScheme(url)) false else !(TextUtils.equals(url, tab.url) || tab.mWebView.isBackForwardListEmpty) && controller.performNewTabLink(perform, tab, url, TabType.WINDOW)
    }

    private fun getNewTabPerformType(tab: MainTabData): Int {
        return if (UrlUtils.isSpeedDial(tab.originalUrl)) {
            AppData.newtab_speeddial.get()
        } else {
            AppData.newtab_link.get()
        }
    }

    fun pauseWebViewTimers(tab: MainTabData?): Boolean {
        Logger.d(TAG, "pauseWebViewTimers")
        if (tab == null) return true
        if (!tab.isInPageLoad) {
            Logger.d(TAG, "pauseTimers")
            tab.mWebView.pauseTimers()
            return true
        }
        return false
    }

    fun resumeWebViewTimers(tab: MainTabData?): Boolean {
        Logger.d(TAG, "resumeWebViewTimers")
        if (tab == null) return true
        val inLoad = tab.isInPageLoad
        val paused = controller.isActivityPaused
        if (!paused && !inLoad || paused && inLoad) {
            Logger.d(TAG, "resumeTimers")
            tab.mWebView.resumeTimers()
            return true
        }
        return false
    }

    private fun getString(id: Int): String = activity.getString(id)

    private fun SslError.getErrorMessages(context: Context): String {
        val builder = StringBuilder()
        if (hasError(SslError.SSL_DATE_INVALID)) {
            builder.appendError(context.getText(R.string.ssl_error_certificate_date_invalid))
        }
        if (hasError(SslError.SSL_EXPIRED)) {
            builder.appendError(context.getText(R.string.ssl_error_certificate_expired))
                    .appendErrorInfo(context.getText(R.string.ssl_error_certificate_expired_info), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.DEFAULT).format(certificate.validNotAfterDate))
        }
        if (hasError(SslError.SSL_IDMISMATCH)) {
            builder.appendError(context.getText(R.string.ssl_error_certificate_domain_mismatch))
                    .appendErrorInfo(context.getText(R.string.ssl_error_certificate_domain_mismatch_info), certificate.issuedTo.cName)
        }
        if (hasError(SslError.SSL_NOTYETVALID)) {
            builder.appendError(context.getText(R.string.ssl_error_certificate_not_yet_valid))
                    .appendErrorInfo(context.getText(R.string.ssl_error_certificate_not_yet_valid_info), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.DEFAULT).format(certificate.validNotBeforeDate))
        }
        if (hasError(SslError.SSL_UNTRUSTED)) {
            builder.appendError(context.getText(R.string.ssl_error_certificate_untrusted))
                    .appendErrorInfo(context.getText(R.string.ssl_error_certificate_untrusted_info), certificate.issuedBy.dName)
        }
        if (hasError(SslError.SSL_INVALID)) {
            builder.appendError(context.getText(R.string.ssl_error_certificate_invalid))
        }
        return builder.toString()
    }

    private fun StringBuilder.appendError(sequence: CharSequence): StringBuilder {
        append(" - ").append(sequence).append("\n")
        return this
    }

    private fun StringBuilder.appendErrorInfo(sequence: CharSequence, info: CharSequence): StringBuilder {
        append("   ").append(sequence).append(info).append('\n')
        return this
    }

    companion object {
        private const val TAG = "WebClient"
    }
}