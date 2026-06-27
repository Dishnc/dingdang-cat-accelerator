package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.support.v4.content.FileProvider
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.google.gson.GsonBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.AngConfig
import com.v2ray.ang.extension.defaultDPreference
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.PerAppProxyActivity
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DdmNG launcher UI.
 *
 * This activity keeps the verified Legacy XTLS runtime from V1.1.8, but replaces the
 * user-facing launcher with a simplified DdmNG interface. The backend service address is
 * intentionally hidden from end users and is injected at build/patch time.
 */
class DingdangLoginActivity : AppCompatActivity() {
    companion object {
        private const val REQ_VPN_PREPARE = 1100
        private const val REQ_SUPPORT_FILE_CHOOSER = 1201
        private const val PREF_DDCAT_SERVICE = "ddcat_service_url"
        private const val PREF_DDCAT_EMAIL = "ddcat_email"
        private const val PREF_DDCAT_LOGGED_IN = "ddcat_logged_in"
        private const val PREF_DDCAT_ACCOUNT_BLOCKED = "ddcat_account_blocked"
        private const val PREF_DDCAT_LAST_STATUS_TEXT = "ddcat_last_status_text"
        private const val PREF_DDCAT_LAST_EXPIRE = "ddcat_last_expire"
        private const val DEFAULT_SERVICE_BASE = "https://buy.aisuper.top"
        private const val MONTH_PLAN_URL = "https://buy.aisuper.top/buy/1"
        private const val QUARTER_PLAN_URL = "https://buy.aisuper.top/buy/15"
        private const val YEAR_PLAN_URL = "https://buy.aisuper.top/buy/16"
        private const val TRAFFIC_PACKAGE_URL = "https://buy.aisuper.top/buy/26"
        private const val APP_UPDATE_API_PATH = "/api/app/update"
        private const val APP_SUPPORT_PATH = "/app-support"
        private const val ROUTING_MODE_GLOBAL = "0"
        private const val ROUTING_MODE_SMART = "3"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var emailInput: EditText
    private lateinit var loginCard: LinearLayout
    private lateinit var connCard: LinearLayout
    private lateinit var accountCard: LinearLayout
    private lateinit var actionsRow: LinearLayout
    private lateinit var logoutButton: TextView
    private lateinit var versionBadge: TextView
    private lateinit var brandLogo: ImageView
    private lateinit var brandTitle: TextView
    private lateinit var brandSub: TextView
    private lateinit var statusText: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var connectionSubText: TextView
    private lateinit var connectionEffectText: TextView
    private lateinit var smartModeButton: TextView
    private lateinit var globalModeButton: TextView
    private lateinit var routingBox: LinearLayout
    private lateinit var loginButton: TextView
    private lateinit var refreshButton: TextView
    private lateinit var loginLoadingView: LinearLayout
    private lateinit var loginLoadingText: TextView
    private lateinit var startButton: TextView
    private lateinit var accountEmailValue: TextView
    private lateinit var accountStatusValue: TextView
    private lateinit var accountExpireValue: TextView
    private lateinit var accountTrafficDetailValue: TextView
    private lateinit var accountTrafficPercentValue: TextView
    private lateinit var accountTrafficProgress: ProgressBar
    private var activeIndex: Int = -1
    private var isLoggedIn: Boolean = false
    private var isAccelerating: Boolean = false
    private var serviceReceiverRegistered: Boolean = false
    private var pendingUpdateApk: File? = null
    private var connectionPulseAnimation: AlphaAnimation? = null
    private var supportFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var useGlobalRouting: Boolean = false
    private var accountServiceExpired: Boolean = false

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    setAcceleratingState("加速成功，当前为" + currentRoutingLabel() + "。")
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    setStartFailureState("启动失败：当前线路配置无效")
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    setDisconnectedState("已断开连接")
                }
            }
        }
    }

    private val accent = Color.rgb(36, 216, 242)
    private val accent2 = Color.rgb(28, 140, 255)
    private val bgTop = Color.rgb(4, 18, 45)
    private val bgBottom = Color.rgb(3, 10, 28)
    private val cardBg = Color.argb(178, 8, 32, 72)
    private val border = Color.argb(125, 68, 156, 255)
    private val primaryText = Color.rgb(236, 247, 255)
    private val secondText = Color.rgb(156, 178, 207)
    private val disconnectedRed = Color.rgb(255, 96, 96)
    private val connectedGreen = Color.rgb(64, 232, 143)
    private val connectingYellow = Color.rgb(255, 202, 89)

    private data class UpdateInfo(
            val latestVersionCode: Int,
            val latestVersionName: String,
            val minSupportedVersionCode: Int,
            val forceUpdate: Boolean,
            val forceRequired: Boolean,
            val updateAvailable: Boolean,
            val title: String,
            val changelog: List<String>,
            val apkUrl: String,
            val apkSize: Long,
            val sha256: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DdmNG"
        defaultDPreference.setPrefString(PREF_DDCAT_SERVICE, DEFAULT_SERVICE_BASE.trim().trimEnd('/'))
        resetSmartRoutingForNewSession()
        buildUi()
        activeIndex = AngConfigManager.configs.index
        val savedEmail = defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, "").trim()
        emailInput.setText(savedEmail)
        val hasSavedLogin = defaultDPreference.getPrefBoolean(PREF_DDCAT_LOGGED_IN, false) && savedEmail.isNotBlank()
        accountServiceExpired = defaultDPreference.getPrefBoolean(PREF_DDCAT_ACCOUNT_BLOCKED, false)
        if (hasSavedLogin) {
            accountEmailValue.text = savedEmail
            setLoggedInUi(true)
            if (activeIndex >= 0) {
                setReadyState("专属线路已准备，可直接一键加速")
                accountStatusValue.text = "已准备"
                accountExpireValue.text = "正在刷新账号信息"
                accountTrafficDetailValue.text = "正在刷新账号信息"
                accountTrafficPercentValue.text = "--"
                accountTrafficProgress.progress = 0
                startButton.isEnabled = true
            } else {
                setReadyState("正在恢复账号线路信息")
                startButton.isEnabled = false
            }
            mainHandler.postDelayed({ doLogin(true) }, 350)
        } else {
            setLoggedInUi(false)
            resetAccountInfoForLoggedOut()
        }
        mainHandler.postDelayed({ checkForUpdates(false) }, 1500)
    }

    override fun onResume() {
        super.onResume()
        if (!serviceReceiverRegistered) {
            registerReceiver(serviceStateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
            serviceReceiverRegistered = true
        }
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        val pending = pendingUpdateApk
        if (pending != null && pending.exists() && canInstallPackagesNow()) {
            pendingUpdateApk = null
            installDownloadedApk(pending)
        }
    }

    override fun onPause() {
        if (serviceReceiverRegistered) {
            try {
                unregisterReceiver(serviceStateReceiver)
            } catch (ignored: Throwable) {
            }
            serviceReceiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.background = verticalGradient(intArrayOf(bgTop, Color.rgb(4, 24, 58), bgBottom), 0f)

        val scroll = ScrollView(this)
        scroll.isFillViewport = false
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(12), dp(18), dp(18))
        scroll.addView(box, FrameLayout.LayoutParams(-1, -2))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        box.addView(top, LinearLayout.LayoutParams(-1, dp(44)))

        val menuBtn = smallTopButton("☰")
        menuBtn.setOnClickListener { showTopMenu(menuBtn) }
        top.addView(menuBtn, LinearLayout.LayoutParams(dp(78), dp(44)))

        versionBadge = TextView(this)
        versionBadge.text = "V" + BuildConfig.VERSION_NAME
        versionBadge.setTextColor(Color.rgb(182, 220, 255))
        versionBadge.textSize = 12f
        versionBadge.gravity = Gravity.CENTER
        versionBadge.maxLines = 1
        versionBadge.typeface = Typeface.DEFAULT_BOLD
        versionBadge.background = rounded(Color.argb(92, 10, 42, 88), dp(15).toFloat(), Color.argb(115, 86, 165, 245), 1)
        val versionLp = LinearLayout.LayoutParams(0, dp(34), 1f)
        versionLp.leftMargin = dp(8)
        versionLp.rightMargin = dp(8)
        top.addView(versionBadge, versionLp)

        val supportTopButton = TextView(this)
        supportTopButton.text = "💬 客服"
        supportTopButton.setTextColor(Color.WHITE)
        supportTopButton.textSize = 12f
        supportTopButton.gravity = Gravity.CENTER
        supportTopButton.typeface = Typeface.DEFAULT_BOLD
        supportTopButton.background = horizontalGradient(intArrayOf(Color.rgb(28, 126, 255), Color.rgb(38, 214, 240)), dp(15).toFloat())
        supportTopButton.isClickable = true
        supportTopButton.setOnClickListener { openInAppSupportChat() }
        top.addView(supportTopButton, LinearLayout.LayoutParams(dp(78), dp(34)))

        brandLogo = ImageView(this)
        brandLogo.setImageResource(R.drawable.ddmng_logo)
        brandLogo.adjustViewBounds = true
        brandLogo.scaleType = ImageView.ScaleType.FIT_CENTER
        val logoLp = LinearLayout.LayoutParams(dp(64), dp(64))
        logoLp.gravity = Gravity.CENTER_HORIZONTAL
        logoLp.topMargin = dp(0)
        box.addView(brandLogo, logoLp)

        brandTitle = TextView(this)
        brandTitle.text = "DdmNG"
        brandTitle.setTextColor(accent)
        brandTitle.textSize = 19f
        brandTitle.gravity = Gravity.CENTER
        brandTitle.typeface = Typeface.DEFAULT_BOLD
        box.addView(brandTitle, LinearLayout.LayoutParams(-1, -2))

        brandSub = TextView(this)
        brandSub.text = "安全 · 稳定 · 高效"
        brandSub.setTextColor(Color.rgb(173, 190, 215))
        brandSub.textSize = 12f
        brandSub.gravity = Gravity.CENTER
        brandSub.setPadding(0, dp(0), 0, dp(8))
        box.addView(brandSub, LinearLayout.LayoutParams(-1, -2))

        loginCard = card()
        box.addView(loginCard, cardLp())
        loginCard.addView(sectionTitle("👤", "账号登录"))

        emailInput = EditText(this)
        emailInput.hint = "请输入邮箱账号"
        emailInput.setHintTextColor(Color.rgb(104, 124, 154))
        emailInput.setTextColor(primaryText)
        emailInput.textSize = 15f
        emailInput.singleLineCompat()
        emailInput.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        emailInput.background = rounded(Color.argb(105, 5, 22, 50), dp(14).toFloat(), Color.argb(110, 78, 151, 224), 1)
        emailInput.setPadding(dp(16), 0, dp(16), 0)
        val emailLp = LinearLayout.LayoutParams(-1, dp(56))
        emailLp.topMargin = dp(12)
        loginCard.addView(emailInput, emailLp)

        val loginRow = LinearLayout(this)
        loginRow.orientation = LinearLayout.HORIZONTAL
        val loginRowLp = LinearLayout.LayoutParams(-1, dp(58))
        loginRowLp.topMargin = dp(14)
        loginCard.addView(loginRow, loginRowLp)

        loginButton = primaryButton("登录")
        loginButton.setOnClickListener { doLogin() }
        loginRow.addView(loginButton, LinearLayout.LayoutParams(0, -1, 1f))

        refreshButton = outlineButton("开通")
        refreshButton.setOnClickListener { showRenewPlansDialog() }
        val refreshLp = LinearLayout.LayoutParams(dp(104), -1)
        refreshLp.leftMargin = dp(12)
        loginRow.addView(refreshButton, refreshLp)

        loginLoadingView = LinearLayout(this)
        loginLoadingView.orientation = LinearLayout.HORIZONTAL
        loginLoadingView.gravity = Gravity.CENTER_VERTICAL
        loginLoadingView.setPadding(dp(16), dp(12), dp(16), dp(12))
        loginLoadingView.background = horizontalGradient(intArrayOf(Color.argb(175, 21, 86, 151), Color.argb(175, 31, 189, 229)), dp(18).toFloat())
        loginLoadingView.visibility = View.GONE
        val loginLoadingLp = LinearLayout.LayoutParams(-1, -2)
        loginLoadingLp.topMargin = dp(14)
        loginCard.addView(loginLoadingView, loginLoadingLp)

        val loginSpinner = ProgressBar(this)
        loginSpinner.isIndeterminate = true
        loginLoadingView.addView(loginSpinner, LinearLayout.LayoutParams(dp(38), dp(38)))

        val loginLoadingTexts = LinearLayout(this)
        loginLoadingTexts.orientation = LinearLayout.VERTICAL
        val loginLoadingTextsLp = LinearLayout.LayoutParams(0, -2, 1f)
        loginLoadingTextsLp.leftMargin = dp(12)
        loginLoadingView.addView(loginLoadingTexts, loginLoadingTextsLp)

        loginLoadingText = TextView(this)
        loginLoadingText.text = "正在登录中"
        loginLoadingText.setTextColor(Color.WHITE)
        loginLoadingText.textSize = 15f
        loginLoadingText.typeface = Typeface.DEFAULT_BOLD
        loginLoadingTexts.addView(loginLoadingText, LinearLayout.LayoutParams(-1, -2))

        val loginLoadingSub = TextView(this)
        loginLoadingSub.text = "正在获取账号状态和专属线路，请稍候…"
        loginLoadingSub.setTextColor(Color.rgb(222, 246, 255))
        loginLoadingSub.textSize = 12f
        loginLoadingSub.setPadding(0, dp(3), 0, 0)
        loginLoadingTexts.addView(loginLoadingSub, LinearLayout.LayoutParams(-1, -2))

        connCard = card()
        box.addView(connCard, cardLp())
        val connHeader = LinearLayout(this)
        connHeader.gravity = Gravity.CENTER_VERTICAL
        connHeader.orientation = LinearLayout.HORIZONTAL
        connCard.addView(connHeader, LinearLayout.LayoutParams(-1, -2))
        connHeader.addView(sectionTitle("▮", "连接状态"), LinearLayout.LayoutParams(0, -2, 1f))
        connectionBadge = TextView(this)
        connectionBadge.text = "● 未连接"
        connectionBadge.setTextColor(disconnectedRed)
        connectionBadge.textSize = 14f
        connectionBadge.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        connHeader.addView(connectionBadge, LinearLayout.LayoutParams(dp(110), -2))

        connectionSubText = TextView(this)
        connectionSubText.text = "当前未连接到任何服务"
        connectionSubText.setTextColor(secondText)
        connectionSubText.textSize = 13f
        connectionSubText.setPadding(0, dp(12), 0, dp(10))
        connCard.addView(connectionSubText, LinearLayout.LayoutParams(-1, -2))

        connectionEffectText = TextView(this)
        connectionEffectText.text = "待命 · 登录后即可建立安全加速通道"
        connectionEffectText.setTextColor(Color.rgb(178, 207, 236))
        connectionEffectText.textSize = 12f
        connectionEffectText.gravity = Gravity.CENTER
        connectionEffectText.typeface = Typeface.DEFAULT_BOLD
        connectionEffectText.setPadding(dp(12), 0, dp(12), 0)
        connectionEffectText.background = rounded(Color.argb(86, 18, 55, 106), dp(16).toFloat(), Color.argb(92, 90, 168, 245), 1)
        val effectLp = LinearLayout.LayoutParams(-1, dp(38))
        effectLp.bottomMargin = dp(14)
        connCard.addView(connectionEffectText, effectLp)

        startButton = primaryButton("🚀  一键加速")
        startButton.textSize = 18f
        startButton.isEnabled = false
        startButton.setOnClickListener {
            animatePrimaryActionButton(startButton)
            toggleProxy()
        }
        startButton.setOnLongClickListener {
            copyDiagnostic()
            true
        }
        connCard.addView(startButton, LinearLayout.LayoutParams(-1, dp(60)))

        accountCard = card()
        box.addView(accountCard, cardLp())
        accountCard.addView(sectionTitle("👤", "账号信息"))
        accountEmailValue = row(accountCard, "账号", defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, "--"))
        accountStatusValue = row(accountCard, "套餐状态", "未查询")
        accountExpireValue = row(accountCard, "到期时间", "--")
        accountTrafficDetailValue = row(accountCard, "流量用量", "--")
        accountTrafficPercentValue = row(accountCard, "流量使用率", "--", accent)
        accountTrafficProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        accountTrafficProgress.max = 100
        accountTrafficProgress.progress = 0
        val progressLp = LinearLayout.LayoutParams(-1, dp(10))
        progressLp.topMargin = dp(10)
        progressLp.bottomMargin = dp(4)
        accountCard.addView(accountTrafficProgress, progressLp)

        actionsRow = LinearLayout(this)
        actionsRow.orientation = LinearLayout.HORIZONTAL
        val actionsLp = LinearLayout.LayoutParams(-1, dp(66))
        actionsLp.topMargin = dp(10)
        box.addView(actionsRow, actionsLp)
        val renew = actionButton("网络续费")
        val order = actionButton("访问网站")
        val trafficPackage = actionButton("购买流量包")
        renew.setOnClickListener { showRenewPlansDialog() }
        order.setOnClickListener { openOfficialWebsite("访问网站") }
        trafficPackage.setOnClickListener { openUrl("购买流量包", TRAFFIC_PACKAGE_URL) }
        actionsRow.addView(renew, LinearLayout.LayoutParams(0, -1, 1f))
        val p2 = LinearLayout.LayoutParams(0, -1, 1f); p2.leftMargin = dp(8); actionsRow.addView(order, p2)
        val p3 = LinearLayout.LayoutParams(0, -1, 1f); p3.leftMargin = dp(8); actionsRow.addView(trafficPackage, p3)

        routingBox = LinearLayout(this)
        routingBox.orientation = LinearLayout.VERTICAL
        routingBox.setPadding(dp(12), dp(10), dp(12), dp(10))
        routingBox.background = rounded(Color.argb(88, 12, 50, 96), dp(16).toFloat(), Color.argb(96, 85, 168, 245), 1)
        val routingBoxLp = LinearLayout.LayoutParams(-1, -2)
        routingBoxLp.bottomMargin = dp(14)
        box.addView(routingBox, routingBoxLp)

        val routingTitle = TextView(this)
        routingTitle.text = "加速模式"
        routingTitle.setTextColor(Color.rgb(204, 229, 255))
        routingTitle.textSize = 13f
        routingTitle.typeface = Typeface.DEFAULT_BOLD
        routingBox.addView(routingTitle, LinearLayout.LayoutParams(-1, -2))

        val routingHint = TextView(this)
        routingHint.text = "默认智能模式：大陆地址直连，其他地址走加速通道。全局模式需手动选择，本次生效。"
        routingHint.setTextColor(Color.rgb(150, 180, 212))
        routingHint.textSize = 11f
        routingHint.setPadding(0, dp(4), 0, dp(8))
        routingBox.addView(routingHint, LinearLayout.LayoutParams(-1, -2))

        val routingRow = LinearLayout(this)
        routingRow.orientation = LinearLayout.HORIZONTAL
        routingBox.addView(routingRow, LinearLayout.LayoutParams(-1, dp(40)))

        smartModeButton = routingModeButton("智能模式")
        globalModeButton = routingModeButton("全局模式")
        smartModeButton.setOnClickListener { selectRoutingMode(false) }
        globalModeButton.setOnClickListener { selectRoutingMode(true) }
        routingRow.addView(smartModeButton, LinearLayout.LayoutParams(0, -1, 1f))
        val globalLp = LinearLayout.LayoutParams(0, -1, 1f)
        globalLp.leftMargin = dp(8)
        routingRow.addView(globalModeButton, globalLp)
        updateRoutingModeButtons()

        logoutButton = outlineButton("退出登录 / 更换邮箱")
        logoutButton.setOnClickListener { logoutAccount() }
        val logoutLp = LinearLayout.LayoutParams(-1, dp(52))
        logoutLp.topMargin = dp(4)
        logoutLp.bottomMargin = dp(10)
        box.addView(logoutButton, logoutLp)

        statusText = TextView(this)
        statusText.text = "输入邮箱后即可自动查询账号并准备专属线路。"
        statusText.setTextColor(Color.rgb(126, 151, 184))
        statusText.textSize = 12f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(16), 0, dp(8))
        box.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val serviceDomainText = TextView(this)
        serviceDomainText.text = "服务域名：" + DEFAULT_SERVICE_BASE.trim().trimEnd('/')
        serviceDomainText.setTextColor(Color.rgb(98, 122, 154))
        serviceDomainText.textSize = 11f
        serviceDomainText.gravity = Gravity.CENTER
        serviceDomainText.setPadding(0, dp(4), 0, dp(14))
        box.addView(serviceDomainText, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun setLoginLoading(loading: Boolean) {
        if (::loginLoadingView.isInitialized) {
            loginLoadingView.visibility = if (loading && ::loginCard.isInitialized && loginCard.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            loginLoadingText.text = if (loading) "正在登录中" else ""
            if (loading) {
                val pulse = AlphaAnimation(0.72f, 1.0f)
                pulse.duration = 720
                pulse.repeatMode = Animation.REVERSE
                pulse.repeatCount = Animation.INFINITE
                loginLoadingView.startAnimation(pulse)
            } else {
                loginLoadingView.clearAnimation()
            }
        }
        if (::emailInput.isInitialized) emailInput.isEnabled = !loading
        if (::loginButton.isInitialized) {
            loginButton.text = "登录"
            loginButton.isEnabled = !loading
            loginButton.alpha = if (loading) 0.62f else 1f
        }
        if (::refreshButton.isInitialized) {
            refreshButton.isEnabled = !loading
            refreshButton.alpha = if (loading) 0.62f else 1f
        }
    }

    private fun doLogin(auto: Boolean = false) {
        hideKeyboard()
        val base = resolveServiceBase()
        val email = emailInput.text.toString().trim()
        if (email.isEmpty()) {
            toast("请输入邮箱账号")
            return
        }
        status("正在登录并获取专属线路...")
        connectionSubText.text = "正在查询账号和线路信息"
        setLoginLoading(true)
        Thread {
            try {
                val url = base + "/api/app/login?email=" + URLEncoder.encode(email, "UTF-8")
                val text = httpGet(url)
                if (!text.trimStart().startsWith("{")) {
                    throw IllegalStateException("接口没有返回 JSON，请检查后端服务。返回开头：" + text.take(40))
                }
                val obj = JSONObject(text)
                if (!obj.optBoolean("success", false)) {
                    throw IllegalStateException(obj.optString("message", "登录失败"))
                }
                val data = obj.optJSONObject("data") ?: obj
                val host = data.optString("host_address", obj.optString("host_address", "")).trim()
                val port = data.optInt("port", obj.optInt("port", 0))
                val uuid = data.optString("uuid", obj.optString("uuid", "")).trim()
                val flow = data.optString("flow", obj.optString("flow", "xtls-rprx-direct")).ifEmpty { "xtls-rprx-direct" }
                if (host.isEmpty() || port <= 0 || uuid.isEmpty()) {
                    throw IllegalStateException("后端返回线路不完整：host=$host port=$port uuid=$uuid")
                }

                val index = storeAsVerifiedVlessConfig(host, port, uuid, flow, email)
                defaultDPreference.setPrefString(PREF_DDCAT_SERVICE, base)
                defaultDPreference.setPrefString(PREF_DDCAT_EMAIL, email)
                defaultDPreference.setPrefBoolean(PREF_DDCAT_LOGGED_IN, true)
                activeIndex = index

                val statusTextValue = data.optString("status_text", obj.optString("status_text", "正常使用"))
                val expire = data.optString("expire_time", obj.optString("expire_time", "--"))
                defaultDPreference.setPrefString(PREF_DDCAT_LAST_STATUS_TEXT, statusTextValue)
                defaultDPreference.setPrefString(PREF_DDCAT_LAST_EXPIRE, expire)
                val used = optDouble(data, obj, "traffic_used_gb")
                val total = optDouble(data, obj, "traffic_total_gb")
                val remain = optDouble(data, obj, "traffic_remain_gb")

                mainHandler.post {
                    accountEmailValue.text = email
                    updateAccountStatusAndExpire(statusTextValue, expire)
                    updateTrafficUsage(used, total, remain)
                    setLoggedInUi(true)
                    setReadyState("登录成功，专属线路已准备")
                    status("登录成功，专属线路已准备。")
                    startButton.isEnabled = true
                    setLoginLoading(false)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    val rawMessage = e.message ?: e.javaClass.name
                    val accountNotFound = isAccountNotFoundMessage(rawMessage)
                    status(if (accountNotFound) "未查询到该邮箱账号，请先点击开通购买套餐。" else "登录失败：$rawMessage")
                    if (auto && AngConfigManager.configs.index >= 0) {
                        setLoggedInUi(true)
                        connectionSubText.text = "账号信息暂时刷新失败，可继续使用已保存线路"
                        startButton.isEnabled = true
                    } else {
                        setLoggedInUi(false)
                        connectionSubText.text = if (accountNotFound) {
                            "未查询到账号，请点击开通购买套餐，开通后使用购买时填写的邮箱登录。"
                        } else {
                            "未能获取专属线路，请确认邮箱是否正确"
                        }
                        if (accountNotFound) {
                            toast("请先点击开通按钮购买套餐，开通后再登录")
                            highlightOpenButton()
                        }
                        startButton.isEnabled = false
                    }
                    setLoginLoading(false)
                }
            }
        }.start()
    }

    private fun isAccountNotFoundMessage(message: String): Boolean {
        val msg = message.toLowerCase(Locale.getDefault())
        return msg.contains("not found") || msg.contains("no account") || msg.contains("account not") ||
                msg.contains("不存在") || msg.contains("未找到") || msg.contains("未查询") ||
                msg.contains("查询不到") || msg.contains("没有找到") || msg.contains("账号不存在") ||
                msg.contains("用户不存在") || msg.contains("邮箱不存在") || msg.contains("未开通")
    }

    private fun highlightOpenButton() {
        if (!::refreshButton.isInitialized) return
        refreshButton.text = "开通"
        refreshButton.isEnabled = true
        refreshButton.alpha = 1f
        val pulse = AlphaAnimation(0.58f, 1.0f)
        pulse.duration = 520
        pulse.repeatMode = Animation.REVERSE
        pulse.repeatCount = 5
        refreshButton.startAnimation(pulse)
    }

    private fun storeAsVerifiedVlessConfig(host: String, port: Int, uuid: String, flow: String, email: String): Int {
        // Keep the list clean to avoid old CUSTOM/V1.0.x residue interfering with the verified VLESS path.
        AngConfigManager.configs.vmess.clear()
        AngConfigManager.configs.index = -1
        AngConfigManager.storeConfigFile()

        val bean = AngConfig.VmessBean(
                remarks = "DdmNG 专属线路 - $email",
                address = host,
                port = port,
                id = uuid,
                alterId = 0,
                security = "auto",
                network = "tcp",
                headerType = "none",
                requestHost = "",
                path = "",
                streamSecurity = "xtls",
                flow = if (flow.isBlank()) "xtls-rprx-direct" else flow,
                encryption = "none",
                configVersion = 2
        )
        AngConfigManager.addVlessServer(bean, -1)
        val index = AngConfigManager.configs.vmess.size - 1
        AngConfigManager.setActiveServer(index)
        applyDdmngRoutingDefaults(host, port)
        // Let original v2rayNG config generator write PREF_CURR_CONFIG / DOMAIN / TAGS using VLESS type.
        AngConfigManager.genStoreV2rayConfig(index)
        defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "$host:$port")
        defaultDPreference.setPrefString("ddcat_vpn_last_setup", "尚未收到 VPN setup 回调；请启动后长按复制新的诊断。")
        defaultDPreference.setPrefString("ddcat_service_last_start", "尚未启动 Service。")
        return index
    }

    private fun resetSmartRoutingForNewSession() {
        useGlobalRouting = false
        defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_MODE, ROUTING_MODE_SMART)
    }

    private fun applyDdmngRoutingDefaults(host: String, port: Int) {
        defaultDPreference.setPrefString(AppConfig.PREF_MODE, "VPN")
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_LOCAL_DNS_ENABLED, false)
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_FORWARD_IPV6, false)
        defaultDPreference.setPrefString(SettingsActivity.PREF_REMOTE_DNS, "1.1.1.1,8.8.8.8")
        defaultDPreference.setPrefString(SettingsActivity.PREF_DOMESTIC_DNS, "223.5.5.5,119.29.29.29")
        defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_DOMAIN_STRATEGY, "IPIfNonMatch")
        defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_MODE, if (useGlobalRouting) ROUTING_MODE_GLOBAL else ROUTING_MODE_SMART)
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_PER_APP_PROXY, false)
        defaultDPreference.setPrefBoolean(PerAppProxyActivity.PREF_BYPASS_APPS, false)
        defaultDPreference.setPrefStringSet(PerAppProxyActivity.PREF_PER_APP_PROXY_SET, HashSet<String>())
        defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "$host:$port")
    }

    private fun selectRoutingMode(global: Boolean) {
        useGlobalRouting = global
        val bean = AngConfigManager.configs.vmess.getOrNull(AngConfigManager.configs.index)
        if (bean != null) {
            applyDdmngRoutingDefaults(bean.address, bean.port)
            try {
                AngConfigManager.genStoreV2rayConfig(AngConfigManager.configs.index)
            } catch (ignored: Throwable) {
            }
        } else {
            defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_MODE, if (global) ROUTING_MODE_GLOBAL else ROUTING_MODE_SMART)
        }
        updateRoutingModeButtons()
        val label = if (global) "全局模式" else "智能模式"
        if (isAccelerating) {
            toast(label + "已选择，断开后重新一键加速即可完全生效")
            status("已选择" + label + "，建议断开后重新一键加速。")
        } else {
            toast("已选择" + label)
            status("当前加速模式：" + label)
        }
    }

    private fun updateRoutingModeButtons() {
        if (!::smartModeButton.isInitialized || !::globalModeButton.isInitialized) return
        smartModeButton.background = if (!useGlobalRouting) {
            horizontalGradient(intArrayOf(Color.rgb(30, 168, 236), Color.rgb(57, 223, 184)), dp(13).toFloat())
        } else {
            rounded(Color.argb(66, 20, 56, 98), dp(13).toFloat(), Color.argb(90, 101, 177, 246), 1)
        }
        globalModeButton.background = if (useGlobalRouting) {
            horizontalGradient(intArrayOf(Color.rgb(255, 139, 64), Color.rgb(255, 92, 118)), dp(13).toFloat())
        } else {
            rounded(Color.argb(66, 20, 56, 98), dp(13).toFloat(), Color.argb(90, 101, 177, 246), 1)
        }
        smartModeButton.setTextColor(if (!useGlobalRouting) Color.WHITE else Color.rgb(190, 218, 244))
        globalModeButton.setTextColor(if (useGlobalRouting) Color.WHITE else Color.rgb(190, 218, 244))
    }

    private fun currentRoutingLabel(): String {
        return if (useGlobalRouting) "全局模式" else "智能模式"
    }

    private fun toggleProxy() {
        if (isAccelerating) {
            stopProxy()
        } else {
            startProxy()
        }
    }

    private fun isCurrentAccountBlockedForAcceleration(): Boolean {
        if (accountServiceExpired) return true
        val statusTextNow = if (::accountStatusValue.isInitialized) accountStatusValue.text.toString() else ""
        val expireTextNow = if (::accountExpireValue.isInitialized) accountExpireValue.text.toString() else ""
        return statusTextNow.contains("停用") || statusTextNow.contains("已到期") ||
                expireTextNow.contains("已到期") || expireTextNow.contains("过期")
    }

    private fun showExpiredPlanDialog() {
        try {
            setDisconnectedState("套餐已到期，无法加速")
        } catch (ignored: Throwable) {
        }
        toast("您当前套餐已到期，续费后重新加速即可使用")
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(22), dp(20), dp(22), dp(18))
        wrap.background = rounded(Color.rgb(10, 34, 72), dp(22).toFloat(), Color.argb(150, 255, 96, 96), 1)

        val title = TextView(this)
        title.text = "套餐已到期"
        title.setTextColor(Color.rgb(255, 232, 232))
        title.textSize = 19f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        wrap.addView(title, LinearLayout.LayoutParams(-1, -2))

        val msg = TextView(this)
        msg.text = "您当前套餐已到期无法加速，续费后重新加速即可使用。"
        msg.setTextColor(Color.rgb(220, 234, 248))
        msg.textSize = 14f
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, dp(12), 0, dp(16))
        wrap.addView(msg, LinearLayout.LayoutParams(-1, -2))

        val renew = primaryButton("立即续费")
        renew.setOnClickListener {
            dialog.dismiss()
            showRenewPlansDialog()
        }
        wrap.addView(renew, LinearLayout.LayoutParams(-1, dp(52)))

        val cancel = outlineButton("稍后再说")
        cancel.setOnClickListener { dialog.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(-1, dp(48))
        cancelLp.topMargin = dp(10)
        wrap.addView(cancel, cancelLp)

        dialog.setContentView(wrap)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun startProxy() {
        if (AngConfigManager.configs.index < 0) {
            toast("请先登录并导入线路")
            return
        }
        if (isCurrentAccountBlockedForAcceleration()) {
            showExpiredPlanDialog()
            return
        }
        val bean = AngConfigManager.configs.vmess.getOrNull(AngConfigManager.configs.index)
        if (bean != null) {
            applyDdmngRoutingDefaults(bean.address, bean.port)
            AngConfigManager.genStoreV2rayConfig(AngConfigManager.configs.index)
            defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "${bean.address}:${bean.port}")
        }
        status("正在启动加速服务...")
        connectionBadge.text = "● 连接中"
        connectionBadge.setTextColor(connectingYellow)
        setConnectionCardConnecting()
        connectionSubText.text = "正在以" + currentRoutingLabel() + "建立连接，请稍候"
        if (defaultDPreference.getPrefString(AppConfig.PREF_MODE, "VPN") == "VPN") {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startOriginalService()
            } else {
                startActivityForResult(intent, REQ_VPN_PREPARE)
            }
        } else {
            startOriginalService()
        }
    }

    private fun startOriginalService() {
        try {
            defaultDPreference.setPrefString("ddcat_vpn_last_setup", "等待 VPN setup 回调；如果无法上网，请长按一键加速复制诊断。")
            val ok = Utils.startVService(this, AngConfigManager.configs.index)
            if (ok) {
                setAcceleratingState("加速成功，当前为" + currentRoutingLabel() + "。")
                verifyNetworkAfterStart()
                toast("加速成功")
            } else {
                setStartFailureState("启动失败：当前线路配置无效")
            }
        } catch (e: Throwable) {
            setStartFailureState("启动异常：${e.message ?: e.javaClass.name}")
        }
    }

    private fun stopProxy() {
        try {
            status("正在断开连接...")
            connectionBadge.text = "● 断开中"
            connectionBadge.setTextColor(connectingYellow)
            setConnectionCardConnecting()
            connectionSubText.text = "正在断开加速服务"
            Utils.stopVService(this)
            setDisconnectedState("断开成功")
            toast("断开成功")
        } catch (e: Throwable) {
            status("断开异常：${e.message ?: e.javaClass.name}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SUPPORT_FILE_CHOOSER) {
            val callback = supportFilePathCallback
            supportFilePathCallback = null
            if (callback != null) {
                val results = if (resultCode == RESULT_OK) collectFileChooserUris(data) else null
                callback.onReceiveValue(results)
            }
            return
        }
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) {
            startOriginalService()
        }
    }

    private fun collectFileChooserUris(data: Intent?): Array<Uri>? {
        return try {
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 0) {
                val list = ArrayList<Uri>()
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { list.add(it) }
                }
                if (list.isEmpty()) null else list.toTypedArray()
            } else {
                data?.data?.let { arrayOf(it) }
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun copyDiagnostic() {
        val vpnDiag = defaultDPreference.getPrefString("ddcat_vpn_last_setup", "暂无 VPN setup 诊断信息")
        val svcDiag = defaultDPreference.getPrefString("ddcat_service_last_start", "暂无 Service 启动诊断信息")
        val cfg = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG, "")
        val all = "=== DdmNG Diagnostic V1.2.2.7 ===\n" +
                "mode=" + defaultDPreference.getPrefString(AppConfig.PREF_MODE, "") + "\n" +
                "routingMode=" + defaultDPreference.getPrefString(SettingsActivity.PREF_ROUTING_MODE, "") + "\n" +
                "localDns=" + defaultDPreference.getPrefBoolean(SettingsActivity.PREF_LOCAL_DNS_ENABLED, false) + "\n" +
                "remoteDns=" + defaultDPreference.getPrefString(SettingsActivity.PREF_REMOTE_DNS, "") + "\n" +
                "perAppProxy=" + defaultDPreference.getPrefBoolean(SettingsActivity.PREF_PER_APP_PROXY, false) + "\n" +
                "forwardIpv6=" + defaultDPreference.getPrefBoolean(SettingsActivity.PREF_FORWARD_IPV6, false) + "\n" +
                "currDomain=" + defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "") + "\n" +
                "currConfigLen=" + cfg.length + "\n\n" +
                "--- service start ---\n" + svcDiag + "\n\n" +
                "--- vpn setup ---\n" + vpnDiag + "\n\n" +
                "--- current config ---\n" + cfg
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ddmng-vpn-diagnostic", all))
        toast("VPN 诊断信息已复制")
    }

    private fun buildLegacyJson(host: String, port: Int, uuid: String, flow: String): String {
        val root = linkedMapOf<String, Any>(
                "dns" to linkedMapOf(
                        "hosts" to linkedMapOf("domain:googleapis.cn" to "googleapis.com"),
                        "servers" to listOf("1.1.1.1")
                ),
                "inbounds" to listOf(
                        linkedMapOf(
                                "listen" to "127.0.0.1",
                                "port" to 10808,
                                "protocol" to "socks",
                                "settings" to linkedMapOf("auth" to "noauth", "udp" to true, "userLevel" to 8),
                                "sniffing" to linkedMapOf("destOverride" to listOf("http", "tls"), "enabled" to true),
                                "tag" to "socks"
                        ),
                        linkedMapOf(
                                "listen" to "127.0.0.1",
                                "port" to 10809,
                                "protocol" to "http",
                                "settings" to linkedMapOf("userLevel" to 8),
                                "tag" to "http"
                        )
                ),
                "log" to linkedMapOf("loglevel" to "warning"),
                "outbounds" to listOf(
                        linkedMapOf(
                                "mux" to linkedMapOf("concurrency" to -1, "enabled" to false),
                                "protocol" to "vless",
                                "settings" to linkedMapOf(
                                        "vnext" to listOf(linkedMapOf(
                                                "address" to host,
                                                "port" to port,
                                                "users" to listOf(linkedMapOf(
                                                        "alterId" to 0,
                                                        "encryption" to "none",
                                                        "flow" to flow,
                                                        "id" to uuid,
                                                        "level" to 8,
                                                        "security" to "auto"
                                                ))
                                        ))
                                ),
                                "streamSettings" to linkedMapOf(
                                        "network" to "tcp",
                                        "security" to "xtls",
                                        "xtlsSettings" to linkedMapOf("allowInsecure" to true, "serverName" to "")
                                ),
                                "tag" to "proxy"
                        ),
                        linkedMapOf("protocol" to "freedom", "settings" to linkedMapOf<String, Any>(), "tag" to "direct"),
                        linkedMapOf("protocol" to "blackhole", "settings" to linkedMapOf("response" to linkedMapOf("type" to "http")), "tag" to "block")
                ),
                "policy" to linkedMapOf(
                        "levels" to linkedMapOf("8" to linkedMapOf("connIdle" to 300, "downlinkOnly" to 1, "handshake" to 4, "uplinkOnly" to 1)),
                        "system" to linkedMapOf("statsOutboundUplink" to true, "statsOutboundDownlink" to true)
                ),
                "routing" to linkedMapOf("domainStrategy" to "IPIfNonMatch", "rules" to listOf<Any>()),
                "stats" to linkedMapOf<String, Any>()
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    private fun resolveServiceBase(): String {
        val base = DEFAULT_SERVICE_BASE.trim().trimEnd('/')
        defaultDPreference.setPrefString(PREF_DDCAT_SERVICE, base)
        return base
    }

    private fun setLoggedInUi(loggedIn: Boolean) {
        isLoggedIn = loggedIn
        if (loggedIn && ::loginLoadingView.isInitialized) setLoginLoading(false)
        loginCard.visibility = if (loggedIn) View.GONE else View.VISIBLE
        connCard.visibility = if (loggedIn) View.VISIBLE else View.GONE
        accountCard.visibility = if (loggedIn) View.VISIBLE else View.GONE
        actionsRow.visibility = if (loggedIn) View.VISIBLE else View.GONE
        if (::routingBox.isInitialized) routingBox.visibility = if (loggedIn) View.VISIBLE else View.GONE
        logoutButton.visibility = if (loggedIn) View.VISIBLE else View.GONE
        updateBrandCompact(loggedIn)
        if (!loggedIn) {
            startButton.isEnabled = false
        }
    }

    private fun resetAccountInfoForLoggedOut() {
        accountServiceExpired = false
        defaultDPreference.setPrefBoolean(PREF_DDCAT_ACCOUNT_BLOCKED, false)
        defaultDPreference.setPrefString(PREF_DDCAT_LAST_STATUS_TEXT, "")
        defaultDPreference.setPrefString(PREF_DDCAT_LAST_EXPIRE, "")
        accountEmailValue.text = "--"
        setAccountStatusPill("未登录", Color.rgb(150, 168, 190), Color.argb(72, 92, 112, 138), Color.argb(95, 130, 155, 188))
        accountExpireValue.text = "--"
        accountExpireValue.setTextColor(Color.rgb(198, 216, 238))
        accountExpireValue.background = null
        accountTrafficDetailValue.text = "--"
        accountTrafficPercentValue.text = "--"
        accountTrafficProgress.progress = 0
        setDisconnectedState("当前未登录")
        status("请输入邮箱账号登录。")
    }

    private fun logoutAccount() {
        hideKeyboard()
        try {
            if (isAccelerating) {
                Utils.stopVService(this)
            }
        } catch (ignored: Throwable) {
        }
        try {
            AngConfigManager.configs.vmess.clear()
            AngConfigManager.configs.index = -1
            AngConfigManager.storeConfigFile()
        } catch (ignored: Throwable) {
        }
        activeIndex = -1
        isAccelerating = false
        defaultDPreference.setPrefBoolean(PREF_DDCAT_LOGGED_IN, false)
        defaultDPreference.setPrefString(PREF_DDCAT_EMAIL, "")
        emailInput.setText("")
        resetAccountInfoForLoggedOut()
        setLoggedInUi(false)
        toast("已退出登录")
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Pragma", "no-cache")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: conn.inputStream, "UTF-8")).use { it.readText() }
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun setReadyState(message: String) {
        isAccelerating = false
        stopConnectionPulse()
        connectionBadge.text = "● 已准备"
        connectionBadge.setTextColor(connectedGreen)
        connectionSubText.text = message
        updateConnectionEffect("已就绪 · 默认智能模式，可手动切换全局", Color.rgb(178, 224, 255), Color.argb(98, 22, 77, 133), Color.argb(132, 86, 174, 255))
        startButton.text = "🚀  一键加速"
        setStartButtonVisual(false)
        startButton.isEnabled = AngConfigManager.configs.index >= 0
    }

    private fun setAcceleratingState(message: String) {
        isAccelerating = true
        connectionBadge.text = "● 加速中"
        connectionBadge.setTextColor(connectedGreen)
        startConnectionPulse()
        connectionSubText.text = message + " 不使用时请点击断开连接，可节省流量。"
        updateConnectionEffect("✓ 加速已启动 · 安全通道运行中", Color.rgb(210, 255, 232), Color.argb(124, 15, 111, 83), Color.argb(230, 64, 232, 143))
        startButton.text = "🔌  断开连接"
        setStartButtonVisual(true)
        startButton.isEnabled = true
        showAccelerationSuccessEffect()
        status("不使用时请点击“断开连接”，可节省流量。")
    }

    private fun setDisconnectedState(message: String) {
        isAccelerating = false
        stopConnectionPulse()
        connectionBadge.text = "● 未连接"
        connectionBadge.setTextColor(disconnectedRed)
        connectionSubText.text = message
        updateConnectionEffect("未连接 · 默认智能模式，大陆地址直连", Color.rgb(255, 196, 196), Color.argb(86, 96, 32, 54), Color.argb(122, 255, 96, 96))
        startButton.text = "🚀  一键加速"
        setStartButtonVisual(false)
        startButton.isEnabled = AngConfigManager.configs.index >= 0
        status(message + "，需要时可重新点击“一键加速”。")
    }

    private fun setStartFailureState(message: String) {
        isAccelerating = false
        stopConnectionPulse()
        connectionBadge.text = "● 失败"
        connectionBadge.setTextColor(disconnectedRed)
        connectionSubText.text = message
        updateConnectionEffect("启动失败 · 请稍后重试或联系客服", Color.rgb(255, 196, 196), Color.argb(94, 112, 28, 42), Color.argb(150, 255, 96, 96))
        startButton.text = "🚀  一键加速"
        setStartButtonVisual(false)
        startButton.isEnabled = AngConfigManager.configs.index >= 0
        status(message)
    }

    private fun setConnectionCardConnecting() {
        try {
            connCard.background = verticalGradient(intArrayOf(Color.argb(214, 18, 44, 83), Color.argb(208, 11, 59, 95)), dp(20).toFloat())
            connCard.background = rounded(Color.argb(206, 12, 48, 86), dp(20).toFloat(), Color.argb(190, 255, 202, 89), 2)
            updateConnectionEffect("正在连接 · 正在建立安全隧道…", Color.rgb(255, 235, 181), Color.argb(116, 116, 82, 28), Color.argb(160, 255, 202, 89))
            connectionBadge.clearAnimation()
        } catch (ignored: Throwable) {
        }
    }

    private fun startConnectionPulse() {
        try {
            connCard.background = rounded(Color.argb(198, 4, 45, 65), dp(20).toFloat(), Color.argb(230, 64, 232, 143), 2)
            val anim = AlphaAnimation(0.48f, 1.0f)
            anim.duration = 900
            anim.repeatMode = Animation.REVERSE
            anim.repeatCount = Animation.INFINITE
            anim.interpolator = LinearInterpolator()
            connectionPulseAnimation = anim
            connectionBadge.startAnimation(anim)
        } catch (ignored: Throwable) {
        }
    }

    private fun stopConnectionPulse() {
        try {
            connectionBadge.clearAnimation()
            connectionPulseAnimation = null
            connCard.background = rounded(cardBg, dp(18).toFloat(), border, 1)
        } catch (ignored: Throwable) {
        }
    }

    private fun updateBrandCompact(loggedIn: Boolean) {
        try {
            val size = if (loggedIn) dp(54) else dp(64)
            val lp = brandLogo.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(size, size)
            lp.width = size
            lp.height = size
            brandLogo.layoutParams = lp
            brandTitle.textSize = if (loggedIn) 17f else 19f
            brandSub.textSize = if (loggedIn) 11f else 12f
            brandSub.setPadding(0, 0, 0, if (loggedIn) dp(6) else dp(8))
        } catch (ignored: Throwable) {
        }
    }

    private fun updateConnectionEffect(text: String, textColor: Int, bgColor: Int, borderColor: Int) {
        try {
            if (!::connectionEffectText.isInitialized) return
            connectionEffectText.text = text
            connectionEffectText.setTextColor(textColor)
            connectionEffectText.background = rounded(bgColor, dp(16).toFloat(), borderColor, 1)
        } catch (ignored: Throwable) {
        }
    }

    private fun setStartButtonVisual(disconnectMode: Boolean) {
        try {
            startButton.background = if (disconnectMode) {
                horizontalGradient(intArrayOf(Color.rgb(24, 202, 124), Color.rgb(35, 226, 170), Color.rgb(40, 170, 255)), dp(18).toFloat())
            } else {
                horizontalGradient(intArrayOf(Color.rgb(27, 118, 255), Color.rgb(34, 218, 246)), dp(18).toFloat())
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun animatePrimaryActionButton(view: View) {
        try {
            view.animate().cancel()
            view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.86f).setDuration(90).withEndAction {
                view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(160).start()
            }.start()
        } catch (ignored: Throwable) {
        }
    }

    private fun showAccelerationSuccessEffect() {
        try {
            connCard.animate().cancel()
            connCard.animate().scaleX(1.018f).scaleY(1.018f).setDuration(180).withEndAction {
                connCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(260).start()
            }.start()
            if (::connectionEffectText.isInitialized) {
                connectionEffectText.animate().cancel()
                connectionEffectText.alpha = 1.0f
                connectionEffectText.animate().alpha(0.58f).setDuration(220).withEndAction {
                    connectionEffectText.animate().alpha(1.0f).setDuration(420).start()
                }.start()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun showTopMenu(anchor: View) {
        hideKeyboard()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(18), dp(18), dp(18), dp(18))
        wrap.background = rounded(Color.rgb(7, 26, 61), dp(22).toFloat(), Color.argb(170, 86, 174, 255), 1)

        val title = TextView(this)
        title.text = "快捷菜单"
        title.setTextColor(primaryText)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        wrap.addView(title, LinearLayout.LayoutParams(-1, -2))

        val tip = TextView(this)
        tip.text = "请选择需要使用的服务"
        tip.setTextColor(secondText)
        tip.textSize = 13f
        tip.gravity = Gravity.CENTER
        tip.setPadding(0, dp(6), 0, dp(14))
        wrap.addView(tip, LinearLayout.LayoutParams(-1, -2))

        wrap.addView(menuDialogItem(dialog, "💎", "网络续费", "选择套餐并完成续费") { showRenewPlansDialog() }, menuItemLp())
        wrap.addView(menuDialogItem(dialog, "⬆", "检查更新", "检查是否有新版本可用") { checkForUpdates(true) }, menuItemLp())
        wrap.addView(menuDialogItem(dialog, "🌐", "访问网站", "打开 DdmNG 官方服务页面") { openOfficialWebsite("访问网站") }, menuItemLp())
        wrap.addView(menuDialogItem(dialog, "💬", "联系客服", "打开 APP 内在线客服聊天窗口") { openInAppSupportChat() }, menuItemLp())
        wrap.addView(menuDialogItem(dialog, "↩", "退出登录 / 更换邮箱", "清除当前账号并返回登录页") { logoutAccount() }, menuItemLp())

        val close = outlineButton("关闭")
        close.setOnClickListener { dialog.dismiss() }
        val closeLp = LinearLayout.LayoutParams(-1, dp(48))
        closeLp.topMargin = dp(8)
        wrap.addView(close, closeLp)

        dialog.setContentView(wrap)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun menuDialogItem(dialog: android.app.Dialog, icon: String, titleText: String, descText: String, action: () -> Unit): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER_VERTICAL
        item.setPadding(dp(14), dp(12), dp(14), dp(12))
        item.background = rounded(Color.argb(154, 10, 40, 86), dp(16).toFloat(), Color.argb(105, 86, 168, 245), 1)
        item.isClickable = true
        item.setOnClickListener {
            dialog.dismiss()
            action()
        }

        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 21f
        ic.gravity = Gravity.CENTER
        item.addView(ic, LinearLayout.LayoutParams(dp(40), dp(42)))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(8), 0, 0, 0)
        item.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val title = TextView(this)
        title.text = titleText
        title.setTextColor(primaryText)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        texts.addView(title, LinearLayout.LayoutParams(-1, -2))

        val desc = TextView(this)
        desc.text = descText
        desc.setTextColor(secondText)
        desc.textSize = 12f
        desc.setPadding(0, dp(4), 0, 0)
        texts.addView(desc, LinearLayout.LayoutParams(-1, -2))

        val arrow = TextView(this)
        arrow.text = "›"
        arrow.setTextColor(accent)
        arrow.textSize = 24f
        arrow.gravity = Gravity.CENTER
        item.addView(arrow, LinearLayout.LayoutParams(dp(28), -1))
        return item
    }

    private fun menuItemLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        return lp
    }

    private fun showRenewPlansDialog() {
        hideKeyboard()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(18), dp(18), dp(18), dp(18))
        wrap.background = rounded(Color.rgb(7, 26, 61), dp(20).toFloat(), border, 1)

        val title = TextView(this)
        title.text = "开通网络套餐"
        title.setTextColor(primaryText)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        wrap.addView(title, LinearLayout.LayoutParams(-1, -2))

        val tips = TextView(this)
        tips.text = "购买时填写的邮箱就是 APP 登录账号。支付开通后，回到 APP 使用购买邮箱登录即可使用。"
        tips.setTextColor(secondText)
        tips.textSize = 13f
        tips.gravity = Gravity.CENTER
        tips.setPadding(0, dp(8), 0, dp(12))
        wrap.addView(tips, LinearLayout.LayoutParams(-1, -2))

        wrap.addView(renewPlanCard(dialog, "月套餐 \uFFE530", "流量 30GB/月", "仅支持单台设备", MONTH_PLAN_URL), planCardLp())
        wrap.addView(renewPlanCard(dialog, "季度套餐 \uFFE585", "流量 50GB/月 × 3个月", "支持 2 台设备", QUARTER_PLAN_URL), planCardLp())
        wrap.addView(renewPlanCard(dialog, "年套餐 \uFFE5265", "流量 100GB/月 × 12个月", "支持 3 台设备", YEAR_PLAN_URL), planCardLp())

        val close = outlineButton("稍后再说")
        close.setOnClickListener { dialog.dismiss() }
        val closeLp = LinearLayout.LayoutParams(-1, dp(48))
        closeLp.topMargin = dp(8)
        wrap.addView(close, closeLp)

        dialog.setContentView(wrap)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun renewPlanCard(dialog: android.app.Dialog, name: String, traffic: String, devices: String, url: String): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = rounded(Color.argb(180, 10, 40, 86), dp(16).toFloat(), Color.argb(150, 85, 180, 255), 1)
        card.isClickable = true
        card.setOnClickListener {
            dialog.dismiss()
            openUrl(name, url)
        }

        val nameRow = LinearLayout(this)
        nameRow.orientation = LinearLayout.HORIZONTAL
        nameRow.gravity = Gravity.CENTER_VERTICAL
        card.addView(nameRow, LinearLayout.LayoutParams(-1, -2))

        val title = TextView(this)
        title.text = name
        title.setTextColor(accent)
        title.textSize = 18f
        title.typeface = Typeface.DEFAULT_BOLD
        nameRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val arrow = TextView(this)
        arrow.text = "去购买  ›"
        arrow.setTextColor(primaryText)
        arrow.textSize = 13f
        arrow.gravity = Gravity.RIGHT
        nameRow.addView(arrow, LinearLayout.LayoutParams(dp(86), -2))

        val trafficView = TextView(this)
        trafficView.text = traffic
        trafficView.setTextColor(primaryText)
        trafficView.textSize = 15f
        trafficView.setPadding(0, dp(8), 0, dp(2))
        card.addView(trafficView, LinearLayout.LayoutParams(-1, -2))

        val deviceView = TextView(this)
        deviceView.text = devices
        deviceView.setTextColor(secondText)
        deviceView.textSize = 13f
        card.addView(deviceView, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun planCardLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        return lp
    }

    private fun openOfficialWebsite(actionName: String) {
        openUrl(actionName, DEFAULT_SERVICE_BASE.trim().trimEnd('/'))
    }

    private fun buildAppSupportUrl(): String {
        val base = DEFAULT_SERVICE_BASE.trim().trimEnd('/') + APP_SUPPORT_PATH
        val email = defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, "").trim()
        val params = ArrayList<String>()
        params.add("from=app")
        params.add("source=ddmng")
        params.add("abi=arm64-v8a")
        params.add("mode=fullscreen")
        params.add("layout=app")
        params.add("package_name=" + URLEncoder.encode(packageName, "UTF-8"))
        params.add("version_name=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8"))
        params.add("version_code=" + BuildConfig.VERSION_CODE)
        if (email.isNotBlank()) {
            params.add("email=" + URLEncoder.encode(email, "UTF-8"))
        }
        params.add("_t=" + System.currentTimeMillis())
        return base + "?" + params.joinToString("&")
    }

    private fun openInAppSupportChat() {
        hideKeyboard()
        status("联系客服：正在打开 APP 内在线客服聊天窗口。")

        try {
            val supportUrl = buildAppSupportUrl()
            val dialog = android.app.Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val wrap = LinearLayout(this)
            wrap.orientation = LinearLayout.VERTICAL
            wrap.setBackgroundColor(Color.rgb(5, 18, 43))

            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(dp(14), dp(10), dp(10), dp(10))
            head.setBackgroundColor(Color.rgb(7, 31, 68))
            wrap.addView(head, LinearLayout.LayoutParams(-1, dp(56)))

            val title = TextView(this)
            title.text = "💬 DdmNG 在线客服"
            title.setTextColor(primaryText)
            title.textSize = 16f
            title.typeface = Typeface.DEFAULT_BOLD
            head.addView(title, LinearLayout.LayoutParams(0, -1, 1f))

            val close = TextView(this)
            close.text = "关闭"
            close.gravity = Gravity.CENTER
            close.setTextColor(primaryText)
            close.textSize = 13f
            close.typeface = Typeface.DEFAULT_BOLD
            close.background = rounded(Color.argb(120, 15, 58, 115), dp(14).toFloat(), Color.argb(90, 105, 190, 255), 1)
            head.addView(close, LinearLayout.LayoutParams(dp(72), dp(38)))

            val progress = ProgressBar(this)
            wrap.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

            val webView = WebView(this)
            webView.setBackgroundColor(Color.rgb(6, 20, 47))
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true

            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            try { settings.textZoom = 100 } catch (ignored: Throwable) {}
            settings.setSupportZoom(false)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW } catch (ignored: Throwable) {}
            }
            try {
                settings.userAgentString = settings.userAgentString + " DdmNGApp/" + BuildConfig.VERSION_NAME + " InAppSupportWebView"
            } catch (ignored: Throwable) {}
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.setAcceptThirdPartyCookies(webView, true)
            } catch (ignored: Throwable) {}

            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progress.visibility = if (newProgress >= 95) View.GONE else View.VISIBLE
                }

                override fun onShowFileChooser(view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
                    supportFilePathCallback?.onReceiveValue(null)
                    supportFilePathCallback = filePathCallback
                    return try {
                        val chooserIntent = try {
                            fileChooserParams?.createIntent()
                        } catch (ignored: Throwable) {
                            null
                        } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivityForResult(Intent.createChooser(chooserIntent, "选择要上传的图片或文件"), REQ_SUPPORT_FILE_CHOOSER)
                        true
                    } catch (e: Throwable) {
                        supportFilePathCallback?.onReceiveValue(null)
                        supportFilePathCallback = null
                        toast("无法打开文件选择器")
                        true
                    }
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    // 客服智能回复按钮可能使用 target=_blank / window.open 打开购买页或帮助页。
                    // 不能把已经显示中的 webView 直接塞给 WebViewTransport，否则部分 Android WebView 会崩溃。
                    // 这里创建一个临时 popup WebView 捕获目标 URL，然后交给统一导航逻辑处理。
                    return try {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val popup = WebView(this@DingdangLoginActivity)
                        popup.settings.javaScriptEnabled = true
                        popup.settings.domStorageEnabled = true
                        popup.settings.javaScriptCanOpenWindowsAutomatically = true
                        popup.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(popupView: WebView?, url: String?): Boolean {
                                val handled = handleSupportWebViewNavigation(webView, url)
                                try { popupView?.stopLoading() } catch (ignored: Throwable) {}
                                return handled
                            }

                            override fun shouldOverrideUrlLoading(popupView: WebView?, request: WebResourceRequest?): Boolean {
                                val handled = handleSupportWebViewNavigation(webView, request?.url?.toString())
                                try { popupView?.stopLoading() } catch (ignored: Throwable) {}
                                return handled
                            }

                            override fun onPageStarted(popupView: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(popupView, url, favicon)
                                if (!url.isNullOrBlank() && !url.startsWith("about:", true)) {
                                    handleSupportWebViewNavigation(webView, url)
                                    try { popupView?.stopLoading() } catch (ignored: Throwable) {}
                                }
                            }
                        }
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        true
                    } catch (ignored: Throwable) {
                        true
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return handleSupportWebViewNavigation(view, url)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return handleSupportWebViewNavigation(view, request?.url?.toString())
                }
            }

            wrap.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
            close.setOnClickListener { dialog.dismiss() }
            dialog.setOnDismissListener {
                try {
                    supportFilePathCallback?.onReceiveValue(null)
                    supportFilePathCallback = null
                } catch (ignored: Throwable) {}
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                } catch (ignored: Throwable) {}
            }

            dialog.setContentView(wrap)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.show()
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.setGravity(Gravity.CENTER)
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                val attrs = win.attributes
                attrs.width = WindowManager.LayoutParams.MATCH_PARENT
                attrs.height = WindowManager.LayoutParams.MATCH_PARENT
                attrs.dimAmount = 0.18f
                win.attributes = attrs
                win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
            webView.loadUrl(supportUrl)
        } catch (e: Throwable) {
            // 不再回退到外部浏览器，避免用户误以为仍然是网页跳转。
            toast("客服窗口打开失败，请检查系统 WebView 后重试")
            status("联系客服：APP 内客服窗口打开失败。")
        }
    }

    private fun handleSupportWebViewNavigation(view: WebView?, rawUrl: String?): Boolean {
        val target = rawUrl?.trim() ?: return false
        if (target.isBlank()) return false
        val lower = target.toLowerCase(Locale.US)

        // JS、about:blank、data/blob/file 这类 WebView 内部地址不拦截，交给 WebView 自己处理。
        if (lower.startsWith("javascript:") || lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("file:")) {
            return false
        }

        // http/https 链接分两类：
        // 1) /app-support 客服页面自身，继续留在 APP 内 WebView；
        // 2) 购买页、帮助页、支付页等智能回复按钮链接，交给系统浏览器/对应 App 打开，避免客服 WebView 被跳走或崩溃。
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (isAppSupportInternalUrl(target)) {
                try {
                    if (view != null && view.url != target) view.loadUrl(target)
                } catch (ignored: Throwable) {}
            } else {
                openSupportExternalLink(target)
            }
            return true
        }

        // intent:// 优先按 Android intent 处理；如果没有对应 App，则尝试 browser_fallback_url。
        if (lower.startsWith("intent:")) {
            return openSupportIntentLink(target)
        }

        // 支付宝、微信、market、电话、邮件、短信等自定义协议全部拦截并安全外部打开。
        openSupportExternalLink(target)
        return true
    }

    private fun isAppSupportInternalUrl(rawUrl: String): Boolean {
        return try {
            val uri = Uri.parse(rawUrl)
            val serviceHost = Uri.parse(DEFAULT_SERVICE_BASE).host ?: return false
            val host = uri.host ?: return false
            val path = uri.path ?: ""
            host.equals(serviceHost, true) && (
                    path == APP_SUPPORT_PATH ||
                    path.startsWith(APP_SUPPORT_PATH + "/") ||
                    path.startsWith("/api/support/")
            )
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun openSupportIntentLink(rawUrl: String): Boolean {
        return try {
            val intent = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (!fallback.isNullOrBlank()) {
                openSupportExternalLink(fallback)
            } else {
                toast("未找到可打开此链接的应用")
            }
            true
        } catch (ignored: Throwable) {
            toast("链接打开失败，请稍后再试")
            true
        }
    }

    private fun openSupportExternalLink(rawUrl: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(intent)
            true
        } catch (ignored: Throwable) {
            try {
                Utils.openUri(this, rawUrl)
                true
            } catch (ignored2: Throwable) {
                toast("链接打开失败，请稍后再试")
                true
            }
        }
    }

    private fun openUrl(actionName: String, url: String) {
        try {
            status(actionName + "：正在打开页面。")
            Utils.openUri(this, url)
        } catch (e: Throwable) {
            toast("无法打开页面，请稍后重试")
        }
    }

    private fun verifyNetworkAfterStart() {
        mainHandler.postDelayed({
            if (!isAccelerating) return@postDelayed
            Thread {
                try {
                    httpGet(DEFAULT_SERVICE_BASE.trim().trimEnd('/'))
                    mainHandler.post {
                        if (isAccelerating) {
                            status("网络连接已验证，加速服务正在运行。")
                        }
                    }
                } catch (ignored: Throwable) {
                    mainHandler.post {
                        if (isAccelerating) {
                            status("加速已启动，如网页仍无法打开，请断开后重试或联系客服。")
                        }
                    }
                }
            }.start()
        }, 2500)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) {
            status("正在检查版本更新...")
        }
        Thread {
            try {
                val base = resolveServiceBase()
                val currentRawCode = BuildConfig.VERSION_CODE
                val currentNormalizedCode = normalizeVersionCodeForCompare(currentRawCode)
                val url = base + APP_UPDATE_API_PATH +
                        "?current_version_code=" + currentRawCode +
                        "&current_version_code_raw=" + currentRawCode +
                        "&current_version_code_normalized=" + currentNormalizedCode +
                        "&current_version_name=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8") +
                        "&package_name=" + URLEncoder.encode(packageName, "UTF-8") +
                        "&abi=" + URLEncoder.encode("arm64-v8a", "UTF-8") +
                        "&_ts=" + System.currentTimeMillis()
                val text = httpGet(url)
                if (!text.trimStart().startsWith("{")) {
                    throw IllegalStateException("更新接口没有返回 JSON")
                }
                val obj = JSONObject(text)
                if (!obj.optBoolean("success", false)) {
                    throw IllegalStateException(obj.optString("message", "检查更新失败"))
                }
                val enabled = obj.optBoolean("enabled", true)
                if (!enabled) {
                    if (manual) mainHandler.post {
                        status("当前已是最新版本：" + BuildConfig.VERSION_NAME)
                        showUpdateLatestDialog()
                    }
                    return@Thread
                }
                val info = parseUpdateInfo(obj)
                mainHandler.post {
                    if (info.updateAvailable || info.forceRequired) {
                        showUpdateDialog(info)
                    } else if (manual) {
                        status("当前已是最新版本：" + BuildConfig.VERSION_NAME)
                        showUpdateLatestDialog(info)
                    }
                }
            } catch (e: Throwable) {
                if (manual) {
                    mainHandler.post {
                        val msg = "检查更新失败：" + (e.message ?: e.javaClass.name)
                        status(msg)
                        showUpdateMessageDialog("检查更新失败", msg, false)
                    }
                }
            }
        }.start()
    }

    private fun parseUpdateInfo(obj: JSONObject): UpdateInfo {
        val changelog = ArrayList<String>()
        val arr = obj.optJSONArray("changelog")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val line = arr.optString(i).trim()
                if (line.isNotEmpty()) changelog.add(line)
            }
        }

        val latestRaw = obj.optInt("latest_version_code_raw", obj.optInt("latest_version_code", 0))
        val latestForDisplay = obj.optInt("latest_version_code", latestRaw)
        val latestNormalized = obj.optInt("latest_version_code_normalized", normalizeVersionCodeForCompare(latestRaw))
        val currentRaw = BuildConfig.VERSION_CODE
        val currentNormalized = normalizeVersionCodeForCompare(currentRaw)
        val minRaw = obj.optInt("min_supported_version_code_raw", obj.optInt("min_supported_version_code", 0))
        val minNormalized = normalizeVersionCodeForCompare(minRaw)
        val serverSaysUpdate = obj.optBoolean("update_available", false)
        val localSaysUpdate = latestNormalized > 0 && latestNormalized > currentNormalized
        val forceRequired = obj.optBoolean("force_required", false) ||
                (minNormalized > 0 && currentNormalized < minNormalized)

        return UpdateInfo(
                latestVersionCode = latestForDisplay,
                latestVersionName = obj.optString("latest_version_name", ""),
                minSupportedVersionCode = obj.optInt("min_supported_version_code", minRaw),
                forceUpdate = obj.optBoolean("force_update", false),
                forceRequired = forceRequired,
                updateAvailable = serverSaysUpdate || localSaysUpdate,
                title = obj.optString("title", "发现新版本"),
                changelog = changelog,
                apkUrl = obj.optString("apk_url", ""),
                apkSize = obj.optLong("apk_size", 0L),
                sha256 = obj.optString("sha256", "")
        )
    }

    private fun normalizeVersionCodeForCompare(code: Int): Int {
        val positive = if (code < 0) -code else code
        if (positive >= 1000000) {
            val normalized = positive % 1000000
            if (normalized > 0) return normalized
        }
        return positive
    }

    private fun showUpdateLatestDialog(info: UpdateInfo? = null) {
        val latest = info?.latestVersionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        val body = "当前版本号：" + BuildConfig.VERSION_NAME + "\n" +
                "最新版本号：" + latest + "\n\n" +
                "当前已是最新版本，无需更新。"
        showUpdateMessageDialog("版本检查", body, false)
    }

    private fun showUpdateMessageDialog(titleText: String, bodyText: String, force: Boolean) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = updateDialogCardBase(titleText)
        val body = TextView(this)
        body.text = bodyText
        body.setTextColor(primaryText)
        body.textSize = 14f
        body.setLineSpacing(dp(3).toFloat(), 1.0f)
        body.setPadding(0, dp(12), 0, dp(14))
        wrap.addView(body, LinearLayout.LayoutParams(-1, -2))
        val ok = primaryButton("确定")
        ok.setOnClickListener { dialog.dismiss() }
        wrap.addView(ok, LinearLayout.LayoutParams(-1, dp(50)))
        showCenterCardDialog(dialog, wrap, force)
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val versionName = if (info.latestVersionName.isBlank()) info.latestVersionCode.toString() else info.latestVersionName
        val lines = if (info.changelog.isNotEmpty()) {
            info.changelog.joinToString("\n") { "• " + it }
        } else {
            "• 优化使用体验\n• 修复已知问题"
        }
        val sizeText = if (info.apkSize > 0L) "\n安装包大小：" + humanFileSize(info.apkSize) else ""
        val force = info.forceRequired || info.forceUpdate
        val forceText = if (force) "\n\n当前版本需要更新后继续使用。" else ""

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = updateDialogCardBase(if (info.title.isBlank()) "发现新版本" else info.title)

        val versionBox = LinearLayout(this)
        versionBox.orientation = LinearLayout.VERTICAL
        versionBox.setPadding(dp(14), dp(12), dp(14), dp(12))
        versionBox.background = rounded(Color.argb(120, 5, 22, 50), dp(15).toFloat(), Color.argb(110, 80, 150, 238), 1)
        val current = TextView(this)
        current.text = "当前版本号：" + BuildConfig.VERSION_NAME
        current.setTextColor(primaryText)
        current.textSize = 14f
        versionBox.addView(current, LinearLayout.LayoutParams(-1, -2))
        val latest = TextView(this)
        latest.text = "最新版本号：" + versionName
        latest.setTextColor(accent)
        latest.textSize = 15f
        latest.typeface = Typeface.DEFAULT_BOLD
        latest.setPadding(0, dp(6), 0, 0)
        versionBox.addView(latest, LinearLayout.LayoutParams(-1, -2))
        val versionBoxLp = LinearLayout.LayoutParams(-1, -2)
        versionBoxLp.topMargin = dp(12)
        wrap.addView(versionBox, versionBoxLp)

        val message = TextView(this)
        message.text = "有新版本待更新。\n\n更新内容：\n" + lines + sizeText + forceText
        message.setTextColor(primaryText)
        message.textSize = 14f
        message.setLineSpacing(dp(3).toFloat(), 1.0f)
        message.setPadding(0, dp(14), 0, dp(12))
        wrap.addView(message, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        if (!force) {
            val later = outlineButton("稍后再说")
            later.setOnClickListener { dialog.dismiss() }
            buttons.addView(later, LinearLayout.LayoutParams(0, dp(50), 1f))
            val p = LinearLayout.LayoutParams(0, dp(50), 1f)
            p.leftMargin = dp(12)
            val now = primaryButton("立即更新")
            now.setOnClickListener {
                dialog.dismiss()
                downloadAndInstallUpdate(info)
            }
            buttons.addView(now, p)
        } else {
            val now = primaryButton("立即更新")
            now.setOnClickListener {
                dialog.dismiss()
                downloadAndInstallUpdate(info)
            }
            buttons.addView(now, LinearLayout.LayoutParams(-1, dp(50)))
        }
        wrap.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        showCenterCardDialog(dialog, wrap, force)
    }

    private fun updateDialogCardBase(titleText: String): LinearLayout {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(20), dp(20), dp(20))
        wrap.background = rounded(Color.rgb(7, 26, 61), dp(22).toFloat(), Color.argb(160, 86, 174, 255), 1)

        val badge = TextView(this)
        badge.text = "DdmNG"
        badge.setTextColor(accent)
        badge.textSize = 13f
        badge.gravity = Gravity.CENTER
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.background = rounded(Color.argb(85, 22, 84, 138), dp(18).toFloat(), Color.argb(120, 75, 180, 255), 1)
        val badgeLp = LinearLayout.LayoutParams(dp(86), dp(32))
        badgeLp.gravity = Gravity.CENTER_HORIZONTAL
        wrap.addView(badge, badgeLp)

        val title = TextView(this)
        title.text = titleText
        title.setTextColor(primaryText)
        title.textSize = 21f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(12), 0, 0)
        wrap.addView(title, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun showCenterCardDialog(dialog: android.app.Dialog, content: View, force: Boolean) {
        dialog.setContentView(content)
        dialog.setCancelable(!force)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun downloadAndInstallUpdate(info: UpdateInfo) {
        if (info.apkUrl.isBlank()) {
            toast("暂无可下载的安装包")
            return
        }
        status("正在下载新版本安装包...")
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("下载失败，HTTP " + code)
                }
                val total = if (info.apkSize > 0L) info.apkSize else conn.contentLength.toLong()
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "updates")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("无法创建下载目录")
                }
                val safeVersion = (if (info.latestVersionName.isBlank()) info.latestVersionCode.toString() else info.latestVersionName)
                        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                val apk = File(dir, "DdmNG_update_" + safeVersion + ".apk")
                var downloaded = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    FileOutputStream(apk).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            if (total > 0L) {
                                val percent = (downloaded * 100L / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    lastPercent = percent
                                    mainHandler.post { status("正在下载新版本：" + percent + "%") }
                                }
                            }
                        }
                    }
                }
                if (apk.length() <= 0L) {
                    throw IllegalStateException("下载文件为空")
                }
                if (info.sha256.isNotBlank()) {
                    val actual = sha256File(apk)
                    if (!actual.equals(info.sha256.trim(), ignoreCase = true)) {
                        apk.delete()
                        throw IllegalStateException("安装包校验失败，请重新下载")
                    }
                }
                mainHandler.post {
                    status("新版本下载完成，正在打开安装界面。")
                    installDownloadedApk(apk)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    status("更新失败：" + (e.message ?: e.javaClass.name))
                    toast("更新失败，请稍后重试")
                }
            } finally {
                try {
                    conn?.disconnect()
                } catch (ignored: Throwable) {
                }
            }
        }.start()
    }

    private fun installDownloadedApk(apk: File) {
        if (!apk.exists()) {
            toast("安装包不存在，请重新下载")
            return
        }
        if (!canInstallPackagesNow()) {
            pendingUpdateApk = apk
            toast("请允许本应用安装未知应用，然后返回继续安装")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName))
                startActivity(intent)
            } catch (e: Throwable) {
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                startActivity(intent)
            }
            return
        }
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, packageName + ".fileprovider", apk)
            } else {
                Uri.fromFile(apk)
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Throwable) {
            status("无法打开安装界面：" + (e.message ?: e.javaClass.name))
            toast("无法打开安装界面")
        }
    }

    private fun canInstallPackagesNow(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun humanFileSize(bytes: Long): String {
        if (bytes <= 0L) return "--"
        val mb = bytes.toDouble() / 1024.0 / 1024.0
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun updateAccountStatusAndExpire(rawStatus: String, expire: String) {
        val days = daysUntilExpire(expire)
        val stopped = isStoppedStatus(rawStatus) || (days != null && days < 0)
        accountServiceExpired = stopped
        defaultDPreference.setPrefBoolean(PREF_DDCAT_ACCOUNT_BLOCKED, stopped)
        defaultDPreference.setPrefString(PREF_DDCAT_LAST_STATUS_TEXT, rawStatus)
        defaultDPreference.setPrefString(PREF_DDCAT_LAST_EXPIRE, expire)
        val expiring = !stopped && days != null && days in 0..6
        when {
            stopped -> {
                setAccountStatusPill("停用", Color.rgb(255, 235, 235), Color.argb(150, 126, 32, 46), Color.argb(220, 255, 96, 96))
                accountExpireValue.setTextColor(disconnectedRed)
            }
            expiring -> {
                setAccountStatusPill("即将到期", Color.rgb(255, 246, 218), Color.argb(145, 122, 84, 24), Color.argb(220, 255, 174, 74))
                accountExpireValue.setTextColor(Color.rgb(255, 202, 89))
                status("账号剩余不足 7 天，请及时续费。")
            }
            else -> {
                setAccountStatusPill("正常", Color.rgb(218, 255, 236), Color.argb(130, 18, 104, 72), Color.argb(210, 64, 232, 143))
                accountExpireValue.setTextColor(Color.rgb(198, 216, 238))
            }
        }
        accountExpireValue.text = formatExpireForDisplay(expire, days)
    }

    private fun setAccountStatusPill(text: String, textColor: Int, bgColor: Int, borderColor: Int) {
        if (!::accountStatusValue.isInitialized) return
        accountStatusValue.text = text
        accountStatusValue.setTextColor(textColor)
        accountStatusValue.gravity = Gravity.CENTER
        accountStatusValue.setPadding(dp(10), dp(4), dp(10), dp(4))
        accountStatusValue.background = rounded(bgColor, dp(12).toFloat(), borderColor, 1)
    }

    private fun isStoppedStatus(rawStatus: String): Boolean {
        val msg = rawStatus.trim().toLowerCase(Locale.getDefault())
        return msg.contains("停用") || msg.contains("禁用") || msg.contains("暂停") ||
                msg.contains("封禁") || msg.contains("已过期") || msg.contains("过期") ||
                msg.contains("expired") || msg.contains("disabled") || msg.contains("inactive") ||
                msg.contains("suspended")
    }

    private fun formatExpireForDisplay(expire: String, days: Int?): String {
        val date = expireDateOnly(expire)
        if (date == "--") return "--"
        val dayText = when {
            days == null -> ""
            days < 0 -> "（已到期）"
            days == 0 -> "（今日到期）"
            else -> "（剩余" + days + "天）"
        }
        return date + dayText
    }

    private fun expireDateOnly(expire: String): String {
        val clean = expire.trim()
        if (clean.isEmpty() || clean == "--") return "--"
        val patterns = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd")
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val time = sdf.parse(clean) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(time)
            } catch (ignored: Throwable) {
            }
        }
        return if (clean.length >= 10) clean.substring(0, 10).replace('/', '-') else clean
    }

    private fun applyExpireWarning(expire: String) {
        val days = daysUntilExpire(expire)
        accountExpireValue.setTextColor(if (days != null && days in 0..6) Color.rgb(255, 202, 89) else Color.rgb(198, 216, 238))
        if (days != null && days in 0..6) {
            status("账号将在 " + days + " 天内到期，请及时续费。")
        }
    }

    private fun daysUntilExpire(expire: String): Int? {
        val clean = expire.trim()
        if (clean.isEmpty() || clean == "--") return null
        val patterns = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd")
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val time = sdf.parse(clean)?.time ?: continue
                val diff = time - System.currentTimeMillis()
                return Math.ceil(diff.toDouble() / 86400000.0).toInt()
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(emailInput.windowToken, 0)
            emailInput.clearFocus()
        } catch (ignored: Throwable) {
        }
    }

    private fun updateTrafficUsage(used: Double, total: Double, remainFromApi: Double) {
        if (total > 0.0) {
            val safeUsed = if (used >= 0.0) used else 0.0
            val remain = if (remainFromApi >= 0.0) remainFromApi else total - safeUsed
            val percent = (safeUsed * 100.0 / total).coerceIn(0.0, 100.0)
            val percentInt = (percent + 0.5).toInt()
            accountTrafficProgress.progress = percentInt
            accountTrafficDetailValue.text = String.format("已用 %.2f GB / 总量 %.2f GB，剩余 %.2f GB", safeUsed, total, remain.coerceAtLeast(0.0))
            accountTrafficPercentValue.text = percentInt.toString() + "%"
        } else {
            accountTrafficProgress.progress = 0
            accountTrafficDetailValue.text = "--"
            accountTrafficPercentValue.text = "--"
        }
    }

    private fun optDouble(data: JSONObject, root: JSONObject, key: String): Double {
        return if (data.has(key)) data.optDouble(key, -1.0) else root.optDouble(key, -1.0)
    }

    private fun card(): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(dp(16), dp(16), dp(16), dp(16))
        v.background = rounded(cardBg, dp(18).toFloat(), border, 1)
        return v
    }

    private fun cardLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(14)
        return lp
    }

    private fun sectionTitle(icon: String, text: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 18f
        ic.setTextColor(accent)
        ic.gravity = Gravity.CENTER
        row.addView(ic, LinearLayout.LayoutParams(dp(32), dp(30)))
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(accent)
        tv.textSize = 17f
        tv.typeface = Typeface.DEFAULT_BOLD
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun row(parent: LinearLayout, label: String, value: String, valueColor: Int = Color.rgb(198, 216, 238)): TextView {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(0, dp(11), 0, dp(8))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        val l = TextView(this)
        l.text = label
        l.setTextColor(Color.rgb(198, 216, 238))
        l.textSize = 14f
        line.addView(l, LinearLayout.LayoutParams(dp(105), -2))
        val r = TextView(this)
        r.text = value
        r.setTextColor(valueColor)
        r.textSize = 14f
        r.gravity = Gravity.RIGHT
        r.maxLines = 1
        line.addView(r, LinearLayout.LayoutParams(0, -2, 1f))
        val div = View(this)
        div.setBackgroundColor(Color.argb(45, 124, 178, 238))
        parent.addView(div, LinearLayout.LayoutParams(-1, 1))
        return r
    }

    private fun primaryButton(text: String): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.textSize = 16f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.background = horizontalGradient(intArrayOf(accent2, accent), dp(14).toFloat())
        b.isClickable = true
        b.setPadding(dp(10), 0, dp(10), 0)
        return b
    }

    private fun outlineButton(text: String): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(Color.rgb(111, 228, 250))
        b.textSize = 16f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.background = rounded(Color.argb(70, 5, 24, 55), dp(14).toFloat(), Color.argb(180, 82, 158, 255), 1)
        b.isClickable = true
        return b
    }

    private fun actionButton(text: String): TextView {
        val b = TextView(this)
        val icon = when (text) {
            "网络续费" -> "💎"
            "访问网站" -> "🌐"
            "购买流量包" -> "📦"
            "联系客服" -> "💬"
            else -> "•"
        }
        b.text = icon + "\n" + text
        b.setTextColor(primaryText)
        b.textSize = 13f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.maxLines = 2
        b.includeFontPadding = false
        b.setLineSpacing(0f, 0.94f)
        b.background = horizontalGradient(intArrayOf(Color.argb(225, 18, 62, 122), Color.argb(225, 12, 92, 154)), dp(17).toFloat())
        b.isClickable = true
        b.setPadding(dp(6), dp(6), dp(6), dp(6))
        return b
    }


    private fun routingModeButton(text: String): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(Color.rgb(190, 218, 244))
        b.textSize = 13f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.isClickable = true
        b.setPadding(dp(6), 0, dp(6), 0)
        b.background = rounded(Color.argb(66, 20, 56, 98), dp(13).toFloat(), Color.argb(90, 101, 177, 246), 1)
        return b
    }

    private fun smallTopButton(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 27f
        tv.gravity = Gravity.CENTER
        tv.isClickable = true
        return tv
    }

    private fun rounded(color: Int, radius: Float, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radius
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor)
        return d
    }

    private fun verticalGradient(colors: IntArray, radius: Float): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
        d.cornerRadius = radius
        return d
    }

    private fun horizontalGradient(colors: IntArray, radius: Float): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        d.cornerRadius = radius
        return d
    }

    private fun EditText.singleLineCompat() {
        setSingleLine(true)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
