package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.v2ray.ang.util.Utils
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    private lateinit var accountTrafficValue: TextView
    private lateinit var accountRemainValue: TextView
    private var activeIndex: Int = -1
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
                accountTrafficValue.text = "登录后自动刷新"
                accountRemainValue.text = "--"
            }
        }
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
        menuBtn.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        top.addView(menuBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        val topTitle = TextView(this)
        topTitle.text = "DdmNG"
        topTitle.setTextColor(primaryText)
        topTitle.textSize = 22f
        topTitle.gravity = Gravity.CENTER
        topTitle.typeface = Typeface.DEFAULT_BOLD
        top.addView(topTitle, LinearLayout.LayoutParams(0, -1, 1f))

        val moreBtn = smallTopButton("⋮")
        moreBtn.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        top.addView(moreBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

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
        startButton.setOnClickListener { startProxy() }
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
        accountTrafficValue = row(accountCard, "流量使用", "--")
        accountRemainValue = row(accountCard, "剩余流量", "--", accent)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val actionsLp = LinearLayout.LayoutParams(-1, dp(58))
        actionsLp.topMargin = dp(8)
        box.addView(actions, actionsLp)
        val renew = actionButton("套餐续费")
        val order = actionButton("在线下单")
        val support = actionButton("联系客服")
        renew.setOnClickListener { toast("请联系客服办理套餐续费") }
        order.setOnClickListener { toast("请联系客服办理在线下单") }
        support.setOnClickListener { toast("请联系客服获取帮助") }
        actions.addView(renew, LinearLayout.LayoutParams(0, -1, 1f))
        val p2 = LinearLayout.LayoutParams(0, -1, 1f); p2.leftMargin = dp(10); actions.addView(order, p2)
        val p3 = LinearLayout.LayoutParams(0, -1, 1f); p3.leftMargin = dp(10); actions.addView(support, p3)

        statusText = TextView(this)
        statusText.text = "输入邮箱后即可自动查询账号并准备专属线路。"
        statusText.setTextColor(Color.rgb(126, 151, 184))
        statusText.textSize = 12f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(16), 0, dp(8))
        box.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun doLogin() {
        val base = resolveServiceBase()
        val email = emailInput.text.toString().trim()
        if (base.isEmpty()) {
            status("后端服务地址未配置，请先在补丁脚本里写入正式服务域名。")
            toast("后端服务地址未配置")
            return
        }
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
                    accountTrafficValue.text = if (total > 0.0) String.format("%.2f GB / %.2f GB", used, total) else "--"
                    accountRemainValue.text = if (remain >= 0.0) String.format("%.2f GB", remain) else "--"
                    setReadyState("登录成功，专属线路已准备")
                    status("登录成功，已自动导入 Legacy XTLS 专属线路。")
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
                connectionBadge.text = "● 已启动"
                connectionBadge.setTextColor(Color.rgb(64, 232, 143))
                connectionSubText.text = "加速服务已启动，正在接管系统网络"
                status("加速服务已启动。如果需要排查，长按“一键加速”复制诊断。")
            } else {
                connectionBadge.text = "● 失败"
                connectionBadge.setTextColor(Color.rgb(255, 108, 108))
                connectionSubText.text = "启动失败：当前线路配置无效"
                status("启动失败：当前线路配置无效")
            }
        } catch (e: Throwable) {
            connectionBadge.text = "● 异常"
            connectionBadge.setTextColor(Color.rgb(255, 108, 108))
            connectionSubText.text = "启动异常，请长按一键加速复制诊断"
            status("启动异常：${e.message ?: e.javaClass.name}")
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
        val configured = DEFAULT_SERVICE_BASE.trim().trimEnd('/')
        if (configured.isNotEmpty() && !configured.contains("https://buy.aisuper.top") && !configured.contains("CHANGE_ME")) {
            return configured
        }
        return defaultDPreference.getPrefString(PREF_DDCAT_SERVICE, "").trim().trimEnd('/')
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
        connectionBadge.text = "● 已准备"
        connectionBadge.setTextColor(Color.rgb(64, 232, 143))
        connectionSubText.text = message
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
