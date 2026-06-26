package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.gson.GsonBuilder
import com.v2ray.ang.AppConfig
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
 * DingdangCat launcher UI.
 *
 * This activity deliberately does NOT replace the original v2rayNG runtime UI/service chain.
 * It only logs in, writes a first-class VLESS Legacy XTLS config, selects it through AngConfigManager,
 * and then calls the original v2rayNG 1.5.0 start flow.
 */
class DingdangLoginActivity : AppCompatActivity() {
    companion object {
        private const val REQ_VPN_PREPARE = 1100
        private const val PREF_DDCAT_SERVICE = "ddcat_service_url"
        private const val PREF_DDCAT_EMAIL = "ddcat_email"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var serviceInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var loginButton: Button
    private lateinit var startButton: Button
    private var activeIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "叮当猫加速器"
        buildUi()
        serviceInput.setText(defaultDPreference.getPrefString(PREF_DDCAT_SERVICE, ""))
        emailInput.setText(defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, ""))
        startButton.isEnabled = AngConfigManager.configs.index >= 0
        activeIndex = AngConfigManager.configs.index
    }

    private fun buildUi() {
        val root = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(26), dp(22), dp(22))
        root.addView(box)

        val titleView = TextView(this)
        titleView.text = "叮当猫加速器"
        titleView.textSize = 26f
        titleView.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val sub = TextView(this)
        sub.text = "Legacy XTLS 兼容运行层 · v2rayNG 1.5.0"
        sub.gravity = Gravity.CENTER_HORIZONTAL
        sub.setPadding(0, dp(6), 0, dp(18))
        box.addView(sub, LinearLayout.LayoutParams(-1, -2))

        serviceInput = EditText(this)
        serviceInput.hint = "服务地址，例如：https://你的域名"
        serviceInput.inputType = InputType.TYPE_TEXT_VARIATION_URI
        box.addView(serviceInput, LinearLayout.LayoutParams(-1, -2))

        emailInput = EditText(this)
        emailInput.hint = "邮箱账号"
        emailInput.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        box.addView(emailInput, LinearLayout.LayoutParams(-1, -2))

        loginButton = Button(this)
        loginButton.text = "登录 / 查询账号"
        loginButton.setOnClickListener { doLogin() }
        box.addView(loginButton, LinearLayout.LayoutParams(-1, -2))

        startButton = Button(this)
        startButton.text = "一键加速"
        startButton.isEnabled = false
        startButton.setOnClickListener { startProxy() }
        startButton.setOnLongClickListener {
            val vpnDiag = defaultDPreference.getPrefString("ddcat_vpn_last_setup", "暂无 VPN setup 诊断信息")
            val svcDiag = defaultDPreference.getPrefString("ddcat_service_last_start", "暂无 Service 启动诊断信息")
            val cfg = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG, "")
            val all = "=== DingdangCat VPN Diagnostic V1.1.6.2 ===\n" +
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
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ddcat-vpn-diagnostic", all))
            toast("VPN 诊断信息已复制")
            true
        }
        box.addView(startButton, LinearLayout.LayoutParams(-1, -2))

        val openOriginal = Button(this)
        openOriginal.text = "打开原版线路列表 / 设置"
        openOriginal.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        box.addView(openOriginal, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this)
        statusText.text = "请输入服务地址和邮箱，登录后自动导入专属线路。"
        statusText.setPadding(0, dp(16), 0, dp(8))
        box.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        infoText = TextView(this)
        infoText.textSize = 13f
        infoText.setTextIsSelectable(true)
        box.addView(infoText, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun doLogin() {
        val base = serviceInput.text.toString().trim().trimEnd('/')
        val email = emailInput.text.toString().trim()
        if (base.isEmpty()) {
            toast("请输入服务地址")
            return
        }
        if (email.isEmpty()) {
            toast("请输入邮箱账号")
            return
        }
        status("正在登录并获取线路...")
        loginButton.isEnabled = false
        Thread {
            try {
                val url = base + "/api/app/login?email=" + URLEncoder.encode(email, "UTF-8")
                val text = httpGet(url)
                if (!text.trimStart().startsWith("{")) {
                    throw IllegalStateException("接口没有返回 JSON，请检查服务地址是否正确。返回开头：" + text.take(40))
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
                mainHandler.post {
                    statusText.text = "登录成功，Legacy XTLS 专属线路已导入。"
                    infoText.text = "邮箱：$email\n服务器：$host:$port\nUUID：$uuid\nflow：$flow\n当前线路 index：$index"
                    startButton.isEnabled = true
                    loginButton.isEnabled = true
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    statusText.text = "登录失败：${e.message ?: e.javaClass.name}"
                    loginButton.isEnabled = true
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
                remarks = "叮当猫专属线路 - $email",
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
        status("正在启动服务...")
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
            status(if (ok) "启动流程已调用，正在等待 VPN 接管系统流量。若仍不能上网，请长按一键加速复制诊断。" else "启动失败：当前线路配置无效")
        } catch (e: Throwable) {
            status("启动异常：${e.message ?: e.javaClass.name}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) {
            startOriginalService()
        }
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

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
