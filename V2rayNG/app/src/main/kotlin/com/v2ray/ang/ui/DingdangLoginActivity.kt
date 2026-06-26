package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.view.Gravity
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.gson.GsonBuilder
import com.v2ray.ang.AppConfig
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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
        private const val PREF_DDCAT_SERVICE = "ddcat_service_url"
        private const val PREF_DDCAT_EMAIL = "ddcat_email"
        private const val DEFAULT_SERVICE_BASE = "https://buy.aisuper.top"
        private const val MONTH_PLAN_URL = "https://buy.aisuper.top/buy/1"
        private const val QUARTER_PLAN_URL = "https://buy.aisuper.top/buy/15"
        private const val YEAR_PLAN_URL = "https://buy.aisuper.top/buy/16"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var emailInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var connectionSubText: TextView
    private lateinit var loginButton: TextView
    private lateinit var refreshButton: TextView
    private lateinit var startButton: TextView
    private lateinit var accountEmailValue: TextView
    private lateinit var accountStatusValue: TextView
    private lateinit var accountExpireValue: TextView
    private lateinit var accountTrafficDetailValue: TextView
    private lateinit var accountTrafficPercentValue: TextView
    private lateinit var accountTrafficProgress: ProgressBar
    private var activeIndex: Int = -1
    private var isAccelerating: Boolean = false
    private var serviceReceiverRegistered: Boolean = false

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    setAcceleratingState("加速成功，正在为你加速。")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DdmNG"
        defaultDPreference.setPrefString(PREF_DDCAT_SERVICE, DEFAULT_SERVICE_BASE.trim().trimEnd('/'))
        buildUi()
        emailInput.setText(defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, ""))
        startButton.isEnabled = AngConfigManager.configs.index >= 0
        activeIndex = AngConfigManager.configs.index
        if (activeIndex >= 0) {
            setReadyState("专属线路已准备，可直接一键加速")
            val bean = AngConfigManager.configs.vmess.getOrNull(activeIndex)
            if (bean != null) {
                accountEmailValue.text = defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, "已导入线路")
                accountStatusValue.text = "已准备"
                accountExpireValue.text = "登录后自动刷新"
                accountTrafficDetailValue.text = "登录后自动刷新"
                accountTrafficPercentValue.text = "--"
                accountTrafficProgress.progress = 0
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!serviceReceiverRegistered) {
            registerReceiver(serviceStateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
            serviceReceiverRegistered = true
        }
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
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
        box.setPadding(dp(18), dp(16), dp(18), dp(18))
        scroll.addView(box, FrameLayout.LayoutParams(-1, -2))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        box.addView(top, LinearLayout.LayoutParams(-1, dp(48)))

        val menuBtn = smallTopButton("☰")
        menuBtn.setOnClickListener { showTopMenu(menuBtn) }
        top.addView(menuBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        val topTitle = TextView(this)
        topTitle.text = "DdmNG"
        topTitle.setTextColor(primaryText)
        topTitle.textSize = 22f
        topTitle.gravity = Gravity.CENTER
        topTitle.typeface = Typeface.DEFAULT_BOLD
        top.addView(topTitle, LinearLayout.LayoutParams(0, -1, 1f))

        val topRightSpacer = TextView(this)
        top.addView(topRightSpacer, LinearLayout.LayoutParams(dp(44), dp(44)))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ddmng_logo)
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        val logoLp = LinearLayout.LayoutParams(dp(132), dp(132))
        logoLp.gravity = Gravity.CENTER_HORIZONTAL
        logoLp.topMargin = dp(4)
        box.addView(logo, logoLp)

        val heroTitle = TextView(this)
        heroTitle.text = "DdmNG"
        heroTitle.setTextColor(accent)
        heroTitle.textSize = 34f
        heroTitle.gravity = Gravity.CENTER
        heroTitle.typeface = Typeface.DEFAULT_BOLD
        box.addView(heroTitle, LinearLayout.LayoutParams(-1, -2))

        val heroSub = TextView(this)
        heroSub.text = "安全 · 稳定 · 高效"
        heroSub.setTextColor(Color.rgb(173, 190, 215))
        heroSub.textSize = 17f
        heroSub.gravity = Gravity.CENTER
        heroSub.setPadding(0, dp(4), 0, dp(16))
        box.addView(heroSub, LinearLayout.LayoutParams(-1, -2))

        val loginCard = card()
        box.addView(loginCard, cardLp())
        loginCard.addView(sectionTitle("👤", "登录 / 查询账号"))

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

        loginButton = primaryButton("登录 / 查询账号")
        loginButton.setOnClickListener { doLogin() }
        loginRow.addView(loginButton, LinearLayout.LayoutParams(0, -1, 1f))

        refreshButton = outlineButton("刷新")
        refreshButton.setOnClickListener { doLogin() }
        val refreshLp = LinearLayout.LayoutParams(dp(104), -1)
        refreshLp.leftMargin = dp(12)
        loginRow.addView(refreshButton, refreshLp)

        val connCard = card()
        box.addView(connCard, cardLp())
        val connHeader = LinearLayout(this)
        connHeader.gravity = Gravity.CENTER_VERTICAL
        connHeader.orientation = LinearLayout.HORIZONTAL
        connCard.addView(connHeader, LinearLayout.LayoutParams(-1, -2))
        connHeader.addView(sectionTitle("▮", "连接状态"), LinearLayout.LayoutParams(0, -2, 1f))
        connectionBadge = TextView(this)
        connectionBadge.text = "● 未连接"
        connectionBadge.setTextColor(Color.rgb(64, 232, 143))
        connectionBadge.textSize = 14f
        connectionBadge.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        connHeader.addView(connectionBadge, LinearLayout.LayoutParams(dp(110), -2))

        connectionSubText = TextView(this)
        connectionSubText.text = "当前未连接到任何服务"
        connectionSubText.setTextColor(secondText)
        connectionSubText.textSize = 13f
        connectionSubText.setPadding(0, dp(12), 0, dp(16))
        connCard.addView(connectionSubText, LinearLayout.LayoutParams(-1, -2))

        startButton = primaryButton("🚀  一键加速")
        startButton.textSize = 20f
        startButton.isEnabled = false
        startButton.setOnClickListener { toggleProxy() }
        startButton.setOnLongClickListener {
            copyDiagnostic()
            true
        }
        connCard.addView(startButton, LinearLayout.LayoutParams(-1, dp(60)))

        val accountCard = card()
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

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val actionsLp = LinearLayout.LayoutParams(-1, dp(58))
        actionsLp.topMargin = dp(8)
        box.addView(actions, actionsLp)
        val renew = actionButton("网络续费")
        val order = actionButton("访问网站")
        val support = actionButton("联系客服")
        renew.setOnClickListener { showRenewPlansDialog() }
        order.setOnClickListener { openOfficialWebsite("访问网站") }
        support.setOnClickListener { openOfficialWebsite("联系客服") }
        actions.addView(renew, LinearLayout.LayoutParams(0, -1, 1f))
        val p2 = LinearLayout.LayoutParams(0, -1, 1f); p2.leftMargin = dp(10); actions.addView(order, p2)
        val p3 = LinearLayout.LayoutParams(0, -1, 1f); p3.leftMargin = dp(10); actions.addView(support, p3)

        statusText = TextView(this)
        statusText.text = "输入邮箱后即可自动查询账号并准备专属线路。服务地址由系统自动处理，无需填写。"
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

    private fun doLogin() {
        hideKeyboard()
        val base = resolveServiceBase()
        val email = emailInput.text.toString().trim()
        if (email.isEmpty()) {
            toast("请输入邮箱账号")
            return
        }
        status("正在登录并获取专属线路...")
        connectionSubText.text = "正在查询账号和线路信息"
        loginButton.isEnabled = false
        refreshButton.isEnabled = false
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
                activeIndex = index

                val statusTextValue = data.optString("status_text", obj.optString("status_text", "正常使用"))
                val expire = data.optString("expire_time", obj.optString("expire_time", "--"))
                val used = optDouble(data, obj, "traffic_used_gb")
                val total = optDouble(data, obj, "traffic_total_gb")
                val remain = optDouble(data, obj, "traffic_remain_gb")

                mainHandler.post {
                    accountEmailValue.text = email
                    accountStatusValue.text = statusTextValue.ifBlank { "正常使用" }
                    accountExpireValue.text = expire.ifBlank { "--" }
                    updateTrafficUsage(used, total, remain)
                    setReadyState("登录成功，专属线路已准备")
                    status("登录成功，专属线路已准备。")
                    applyExpireWarning(expire)
                    startButton.isEnabled = true
                    loginButton.isEnabled = true
                    refreshButton.isEnabled = true
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    status("登录失败：${e.message ?: e.javaClass.name}")
                    connectionSubText.text = "未能获取专属线路，请确认邮箱是否正确"
                    loginButton.isEnabled = true
                    refreshButton.isEnabled = true
                    startButton.isEnabled = AngConfigManager.configs.index >= 0
                }
            }
        }.start()
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
        forceFullDeviceVpnDefaults(host, port)
        // Let original v2rayNG config generator write PREF_CURR_CONFIG / DOMAIN / TAGS using VLESS type.
        AngConfigManager.genStoreV2rayConfig(index)
        defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "$host:$port")
        defaultDPreference.setPrefString("ddcat_vpn_last_setup", "尚未收到 VPN setup 回调；请启动后长按复制新的诊断。")
        defaultDPreference.setPrefString("ddcat_service_last_start", "尚未启动 Service。")
        return index
    }

    private fun forceFullDeviceVpnDefaults(host: String, port: Int) {
        defaultDPreference.setPrefString(AppConfig.PREF_MODE, "VPN")
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_LOCAL_DNS_ENABLED, false)
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_FORWARD_IPV6, false)
        defaultDPreference.setPrefString(SettingsActivity.PREF_REMOTE_DNS, "1.1.1.1,8.8.8.8")
        defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_MODE, "0")
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_PER_APP_PROXY, false)
        defaultDPreference.setPrefBoolean(PerAppProxyActivity.PREF_BYPASS_APPS, false)
        defaultDPreference.setPrefStringSet(PerAppProxyActivity.PREF_PER_APP_PROXY_SET, HashSet<String>())
        defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "$host:$port")
    }

    private fun toggleProxy() {
        if (isAccelerating) {
            stopProxy()
        } else {
            startProxy()
        }
    }

    private fun startProxy() {
        if (AngConfigManager.configs.index < 0) {
            toast("请先登录并导入线路")
            return
        }
        val bean = AngConfigManager.configs.vmess.getOrNull(AngConfigManager.configs.index)
        if (bean != null) {
            forceFullDeviceVpnDefaults(bean.address, bean.port)
            AngConfigManager.genStoreV2rayConfig(AngConfigManager.configs.index)
            defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "${bean.address}:${bean.port}")
        }
        status("正在启动加速服务...")
        connectionBadge.text = "● 连接中"
        connectionBadge.setTextColor(Color.rgb(255, 202, 89))
        connectionSubText.text = "正在建立安全连接，请稍候"
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
                setAcceleratingState("加速成功，正在为你加速。")
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
            connectionBadge.setTextColor(Color.rgb(255, 202, 89))
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
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) {
            startOriginalService()
        }
    }

    private fun copyDiagnostic() {
        val vpnDiag = defaultDPreference.getPrefString("ddcat_vpn_last_setup", "暂无 VPN setup 诊断信息")
        val svcDiag = defaultDPreference.getPrefString("ddcat_service_last_start", "暂无 Service 启动诊断信息")
        val cfg = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG, "")
        val all = "=== DdmNG VPN Diagnostic V1.2.0 ===\n" +
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

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: conn.inputStream, "UTF-8")).use { it.readText() }
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun setReadyState(message: String) {
        isAccelerating = false
        connectionBadge.text = "● 已准备"
        connectionBadge.setTextColor(Color.rgb(64, 232, 143))
        connectionSubText.text = message
        startButton.text = "🚀  一键加速"
        startButton.isEnabled = AngConfigManager.configs.index >= 0
    }

    private fun setAcceleratingState(message: String) {
        isAccelerating = true
        connectionBadge.text = "● 加速中"
        connectionBadge.setTextColor(Color.rgb(64, 232, 143))
        connectionSubText.text = message + " 不使用时请点击断开连接，可节省流量。"
        startButton.text = "🔌  断开连接"
        startButton.isEnabled = true
        status("不使用时请点击“断开连接”，可节省流量。")
    }

    private fun setDisconnectedState(message: String) {
        isAccelerating = false
        connectionBadge.text = "● 未连接"
        connectionBadge.setTextColor(Color.rgb(64, 232, 143))
        connectionSubText.text = message
        startButton.text = "🚀  一键加速"
        startButton.isEnabled = AngConfigManager.configs.index >= 0
        status(message + "，需要时可重新点击“一键加速”。")
    }

    private fun setStartFailureState(message: String) {
        isAccelerating = false
        connectionBadge.text = "● 失败"
        connectionBadge.setTextColor(Color.rgb(255, 108, 108))
        connectionSubText.text = message
        startButton.text = "🚀  一键加速"
        startButton.isEnabled = AngConfigManager.configs.index >= 0
        status(message)
    }

    private fun showTopMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("网络续费")
        popup.menu.add("访问网站")
        popup.menu.add("联系客服")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "网络续费" -> showRenewPlansDialog()
                "访问网站" -> openOfficialWebsite("访问网站")
                "联系客服" -> openOfficialWebsite("联系客服")
                else -> openOfficialWebsite(item.title.toString())
            }
            true
        }
        popup.show()
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
        title.text = "选择网络续费套餐"
        title.setTextColor(primaryText)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        wrap.addView(title, LinearLayout.LayoutParams(-1, -2))

        val tips = TextView(this)
        tips.text = "请选择适合你的使用时长，点击套餐卡片后将打开购买页面。"
        tips.setTextColor(secondText)
        tips.textSize = 13f
        tips.gravity = Gravity.CENTER
        tips.setPadding(0, dp(8), 0, dp(12))
        wrap.addView(tips, LinearLayout.LayoutParams(-1, -2))

        wrap.addView(renewPlanCard(dialog, "月套餐", "流量 30GB/月", "仅支持单台设备", MONTH_PLAN_URL), planCardLp())
        wrap.addView(renewPlanCard(dialog, "季度套餐", "流量 50GB/月 × 3个月", "支持 2 台设备", QUARTER_PLAN_URL), planCardLp())
        wrap.addView(renewPlanCard(dialog, "年套餐", "流量 100GB/月 × 12个月", "支持 3 台设备", YEAR_PLAN_URL), planCardLp())

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

    private fun applyExpireWarning(expire: String) {
        accountExpireValue.setTextColor(Color.rgb(198, 216, 238))
        val days = daysUntilExpire(expire)
        if (days != null && days in 0..3) {
            accountExpireValue.setTextColor(Color.rgb(255, 202, 89))
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
        b.text = text + "  ›"
        b.setTextColor(primaryText)
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.background = rounded(Color.argb(150, 13, 44, 90), dp(15).toFloat(), Color.argb(115, 88, 154, 240), 1)
        b.isClickable = true
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
