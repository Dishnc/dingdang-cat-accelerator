package com.v2ray.ang.ui

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.AngConfig
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.defaultDPreference
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

class MainActivity : BaseActivity() {
    companion object {
        private const val REQUEST_CODE_VPN_PREPARE = 0
        private const val PREF_DDCAT_API_BASE = "ddcat_api_base"
        private const val PREF_DDCAT_EMAIL = "ddcat_email"
        private const val PREF_DDCAT_LAST_JSON = "ddcat_last_json"
        private const val PREF_DDCAT_VLESS_URL = "ddcat_vless_url"
        private const val PREF_DDCAT_CONFIG_GUID = "ddcat_legacy_default"
        private const val DEFAULT_REMARK = "叮当猫加速器默认线路"
    }

    private val mainViewModel: MainViewModel by lazy { ViewModelProviders.of(this).get(MainViewModel::class.java) }
    private var lastStatusActive = false
    private var lastRemainGb = -1.0
    private var lastExpireTime = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "叮当猫加速器"
        setSupportActionBar(toolbar)

        defaultDPreference.setPrefString(AppConfig.PREF_MODE, "VPN")
        enforceLegacyNetworkPrefs()

        input_api_base_url.setText(defaultDPreference.getPrefString(PREF_DDCAT_API_BASE, ""))
        input_email.setText(defaultDPreference.getPrefString(PREF_DDCAT_EMAIL, ""))

        btn_login.setOnClickListener { loginOrRefresh(false) }
        btn_refresh.setOnClickListener { loginOrRefresh(true) }
        btn_connect.setOnClickListener { onConnectButtonClicked() }
        btn_connect.setOnLongClickListener {
            copyCurrentConfigForDebug()
            true
        }
        tv_line_info.setOnLongClickListener {
            copyCurrentConfigForDebug()
            true
        }
        btn_renew.setOnClickListener { openBusinessPage("/login.html") }
        btn_order.setOnClickListener { openBusinessPage("/login.html") }
        btn_service.setOnClickListener { toast("请点击网页右下角气泡窗口联系客服") }

        loadCachedAccount()
        setupViewModelObserver()
    }

    private fun setupViewModelObserver() {
        mainViewModel.isRunning.observe(this, Observer<Boolean> { runningValue ->
            val running = runningValue ?: return@Observer
            if (running) {
                status_dot.setBackgroundResource(R.drawable.bg_ddcat_status_dot_on)
                tv_connection_state.text = "当前状态：已加速"
                tv_connection_hint.text = "安全通道已建立。国内地址默认直连，中国之外流量走专属线路。"
                btn_connect.text = "停止加速"
            } else {
                status_dot.setBackgroundResource(R.drawable.bg_ddcat_status_dot_off)
                tv_connection_state.text = "当前状态：未加速"
                btn_connect.text = "一键加速"
                if (isLineReady()) {
                    tv_connection_hint.text = "专属线路已准备，点击一键加速即可建立安全通道。"
                } else {
                    tv_connection_hint.text = "请先登录邮箱账号，自动获取你的专属线路。"
                }
            }
        })
        mainViewModel.startListenBroadcast()
    }

    private fun onConnectButtonClicked() {
        enforceLegacyNetworkPrefs()
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this)
            return
        }

        if (!isLineReady()) {
            toast("请先登录邮箱账号，获取专属线路后再加速")
            return
        }
        if (!lastStatusActive) {
            toast("当前账号状态不可用，请刷新账号或续费后再试")
            return
        }
        if (lastRemainGb == 0.0) {
            toast("剩余流量不足，请续费后再加速")
            return
        }
        if (isExpired(lastExpireTime)) {
            toast("套餐已过期，请续费后再加速")
            return
        }

        val index = AngConfigManager.getIndexViaGuid(PREF_DDCAT_CONFIG_GUID)
        if (index < 0) {
            toast("专属线路尚未写入，请重新登录账号")
            return
        }
        AngConfigManager.setActiveServer(index)

        if (defaultDPreference.getPrefString(AppConfig.PREF_MODE, "VPN") == "VPN") {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                startActivityForResult(intent, REQUEST_CODE_VPN_PREPARE)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (AngConfigManager.configs.index < 0) {
            toast("专属线路未选中，请重新登录账号")
            return
        }
        if (!Utils.startVService(this)) {
            toast("启动失败，请检查线路参数或稍后重试")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN_PREPARE && resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    private fun loginOrRefresh(isRefresh: Boolean) {
        val apiBase = normalizeBaseUrl(input_api_base_url.text?.toString() ?: "")
        val email = input_email.text?.toString()?.trim() ?: ""
        if (apiBase.isBlank()) {
            toast("请先填写服务地址")
            return
        }
        if (email.isBlank() || !email.contains("@")) {
            toast("请填写正确的邮箱账号")
            return
        }

        defaultDPreference.setPrefString(PREF_DDCAT_API_BASE, apiBase)
        defaultDPreference.setPrefString(PREF_DDCAT_EMAIL, email)
        tv_login_message.text = if (isRefresh) "正在刷新账号状态..." else "正在登录并获取专属线路..."
        btn_login.isEnabled = false
        btn_refresh.isEnabled = false

        Thread {
            val result = requestLogin(apiBase, email)
            runOnUiThread {
                btn_login.isEnabled = true
                btn_refresh.isEnabled = true
                if (result.success && result.json != null) {
                    handleLoginSuccess(result.json)
                } else {
                    tv_login_message.text = result.message
                    toast(result.message)
                }
            }
        }.start()
    }

    private data class LoginResult(val success: Boolean, val message: String, val json: JSONObject?)

    private fun requestLogin(apiBase: String, email: String): LoginResult {
        var conn: HttpURLConnection? = null
        return try {
            val encodedEmail = URLEncoder.encode(email, "UTF-8")
            val url = URL("$apiBase/api/app/login?email=$encodedEmail")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: conn.inputStream, "UTF-8")).use { it.readText() }
            val json = JSONObject(text)
            if (json.optBoolean("success", false)) {
                LoginResult(true, json.optString("message", "登录成功"), json)
            } else {
                LoginResult(false, json.optString("message", "账号查询失败"), json)
            }
        } catch (e: Exception) {
            LoginResult(false, "接口请求失败：${e.message ?: e.javaClass.simpleName}", null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun handleLoginSuccess(json: JSONObject) {
        defaultDPreference.setPrefString(PREF_DDCAT_LAST_JSON, json.toString())
        defaultDPreference.setPrefString(PREF_DDCAT_VLESS_URL, readString(json, "vless_url"))

        val installed = installLegacyVlessConfig(json)
        renderAccount(json, installed)
        if (installed) {
            tv_login_message.text = "登录成功，Legacy XTLS 专属线路已准备"
            toast("登录成功，专属线路已准备")
        } else {
            tv_login_message.text = "登录成功，但线路写入失败，请检查后端返回参数"
            toast("线路写入失败")
        }
    }

    private fun loadCachedAccount() {
        val cached = defaultDPreference.getPrefString(PREF_DDCAT_LAST_JSON, "")
        if (!TextUtils.isEmpty(cached)) {
            try {
                val json = JSONObject(cached)
                renderAccount(json, isLineReady())
                tv_login_message.text = if (isLineReady()) "已加载上次登录信息，专属线路已准备" else "已加载上次登录信息，请刷新账号状态"
            } catch (e: Exception) {
                renderEmpty()
            }
        } else {
            renderEmpty()
        }
    }

    private fun renderEmpty() {
        tv_account_email.text = "账号：未登录"
        tv_account_status.text = "套餐状态：待登录后查看"
        tv_expire_time.text = "到期时间：--"
        tv_traffic_usage.text = "流量使用：-- / --"
        tv_traffic_remain.text = "剩余流量：--"
        tv_line_info.text = "线路信息：待登录后查看"
        tv_line_tag.text = "待登录"
        lastStatusActive = false
        lastRemainGb = -1.0
        lastExpireTime = ""
    }

    private fun renderAccount(json: JSONObject, lineReady: Boolean) {
        val email = readString(json, "email")
        val status = readString(json, "status")
        val statusText = readString(json, "status_text").ifBlank { status }
        val expire = readString(json, "expire_time")
        val used = readDouble(json, "traffic_used_gb")
        val total = readDouble(json, "traffic_total_gb")
        val remain = readDouble(json, "traffic_remain_gb")
        val host = readString(json, "host_address")
        val port = readInt(json, "port")
        val protocol = readString(json, "protocol")
        val network = readString(json, "network")
        val security = readString(json, "security")
        val flow = readString(json, "flow")
        // Legacy XTLS successful v2rayNG 1.5.0 exports keep SNI/serverName empty.
        val sni = ""

        lastStatusActive = status.equals("active", true) || statusText == "正常"
        lastRemainGb = remain
        lastExpireTime = expire

        tv_account_email.text = "账号：$email"
        tv_account_status.text = "套餐状态：$statusText"
        tv_expire_time.text = "到期时间：$expire"
        tv_traffic_usage.text = "流量使用：${formatGb(used)} GB / ${formatGb(total)} GB"
        tv_traffic_remain.text = "剩余流量：${formatGb(remain)} GB"
        tv_line_info.text = "线路信息：\n节点：$host:$port\n协议：$protocol / $network / $security\nFlow：$flow\nSNI：${if (sni.isBlank()) "--" else sni}"
        tv_line_tag.text = if (lineReady && host.isNotBlank()) "$host:$port" else "线路待准备"
        tv_connection_hint.text = if (lineReady) "专属线路已准备，点击一键加速即可建立安全通道。" else "登录成功后会自动准备旧 XTLS 兼容线路。"
    }

    private fun installLegacyVlessConfig(json: JSONObject): Boolean {
        return try {
            val host = readString(json, "host_address")
            val port = readInt(json, "port")
            val uuid = readString(json, "uuid")
            if (host.isBlank() || port <= 0 || uuid.isBlank()) {
                tv_login_message.text = "线路参数不完整，请检查后端返回的 host / port / uuid"
                return false
            }

            enforceLegacyNetworkPrefs()
            val configJson = buildLegacyVlessJson(json)
            defaultDPreference.setPrefString(AppConfig.ANG_CONFIG + PREF_DDCAT_CONFIG_GUID, configJson)

            val vmess = AngConfig.VmessBean()
            vmess.guid = PREF_DDCAT_CONFIG_GUID
            vmess.configVersion = 2
            vmess.configType = EConfigType.CUSTOM.value
            vmess.remarks = DEFAULT_REMARK
            vmess.address = host
            vmess.port = port
            vmess.id = uuid
            // Match the known-good v2rayNG 1.5.0 Legacy XTLS profile.
            vmess.network = "tcp"
            vmess.streamSecurity = "xtls"
            vmess.requestHost = ""
            vmess.security = "auto"
            vmess.alterId = 0
            vmess.headerType = "none"

            upsertLegacyCustomServer(vmess, configJson)
        } catch (e: Throwable) {
            tv_login_message.text = "线路写入失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun upsertLegacyCustomServer(vmess: AngConfig.VmessBean, configJson: String): Boolean {
        return try {
            vmess.guid = PREF_DDCAT_CONFIG_GUID
            vmess.configVersion = 2
            vmess.configType = EConfigType.CUSTOM.value

            val list = AngConfigManager.configs.vmess
            val oldIndex = list.indexOfFirst { it.guid == PREF_DDCAT_CONFIG_GUID }
            val finalIndex = if (oldIndex >= 0) {
                list[oldIndex] = vmess
                oldIndex
            } else {
                list.add(vmess)
                list.size - 1
            }

            AngConfigManager.configs.index = finalIndex
            AngConfigManager.storeConfigFile()
            defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG, configJson)
            defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_GUID, PREF_DDCAT_CONFIG_GUID)
            defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_NAME, DEFAULT_REMARK)

            // The legacy libv2ray VPN layer needs the remote server domain/IP:port
            // so the core connection itself is protected from being routed back into the VPN.
            val serverDomain = if (Utils.isIpv6Address(vmess.address)) {
                "[${vmess.address}]:${vmess.port}"
            } else {
                "${vmess.address}:${vmess.port}"
            }
            defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, serverDomain)
            val tags = LinkedHashSet<String>()
            tags.add("proxy")
            tags.add("direct")
            tags.add("block")
            defaultDPreference.setPrefStringOrderedSet(AppConfig.PREF_CURR_CONFIG_OUTBOUND_TAGS, tags)
            true
        } catch (e: Throwable) {
            tv_login_message.text = "线路保存失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun buildLegacyVlessJson(json: JSONObject): String {
        val host = readString(json, "host_address")
        val port = readInt(json, "port")
        val uuid = readString(json, "uuid")
        val flow = readString(json, "flow").ifBlank { "xtls-rprx-direct" }

        val root = JSONObject()

        // Match the JSON exported by the user's working v2rayNG_1.5.0 profile.
        root.put("dns", JSONObject()
                .put("hosts", JSONObject().put("domain:googleapis.cn", "googleapis.com"))
                .put("servers", JSONArray().put("1.1.1.1")))

        val inbounds = JSONArray()
        inbounds.put(JSONObject()
                .put("listen", "127.0.0.1")
                .put("port", 10808)
                .put("protocol", "socks")
                .put("settings", JSONObject()
                        .put("auth", "noauth")
                        .put("udp", true)
                        .put("userLevel", 8))
                .put("sniffing", JSONObject()
                        .put("destOverride", JSONArray().put("http").put("tls"))
                        .put("enabled", true))
                .put("tag", "socks"))
        inbounds.put(JSONObject()
                .put("listen", "127.0.0.1")
                .put("port", 10809)
                .put("protocol", "http")
                .put("settings", JSONObject().put("userLevel", 8))
                .put("tag", "http"))
        root.put("inbounds", inbounds)

        root.put("log", JSONObject().put("loglevel", "warning"))

        val user = JSONObject()
                .put("alterId", 0)
                .put("encryption", "none")
                .put("flow", flow)
                .put("id", uuid)
                .put("level", 8)
                .put("security", "auto")
        val settings = JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", host)
                .put("port", port)
                .put("users", JSONArray().put(user))))

        val stream = JSONObject()
                .put("network", "tcp")
                .put("security", "xtls")
                .put("xtlsSettings", JSONObject()
                        .put("allowInsecure", true)
                        .put("serverName", ""))

        val outbounds = JSONArray()
        outbounds.put(JSONObject()
                .put("mux", JSONObject()
                        .put("concurrency", -1)
                        .put("enabled", false))
                .put("protocol", "vless")
                .put("settings", settings)
                .put("streamSettings", stream)
                .put("tag", "proxy"))
        outbounds.put(JSONObject()
                .put("protocol", "freedom")
                .put("settings", JSONObject())
                .put("tag", "direct"))
        outbounds.put(JSONObject()
                .put("protocol", "blackhole")
                .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
                .put("tag", "block"))
        root.put("outbounds", outbounds)

        root.put("policy", JSONObject()
                .put("levels", JSONObject().put("8", JSONObject()
                        .put("connIdle", 300)
                        .put("downlinkOnly", 1)
                        .put("handshake", 4)
                        .put("uplinkOnly", 1)))
                .put("system", JSONObject()
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true)))

        // Keep rules empty first, exactly like the known-good v2rayNG 1.5.0 export.
        // After connectivity is confirmed, China-direct routing can be added as an optional mode.
        root.put("routing", JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", JSONArray()))
        root.put("stats", JSONObject())
        return root.toString(2)
    }

    private fun enforceLegacyNetworkPrefs() {
        // Keep the VPN/DNS behavior close to the working v2rayNG_1.5.0 profile
        // and avoid stale settings inherited from older test builds.
        defaultDPreference.setPrefString(AppConfig.PREF_MODE, "VPN")
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_LOCAL_DNS_ENABLED, false)
        defaultDPreference.setPrefString(SettingsActivity.PREF_REMOTE_DNS, "1.1.1.1")
        defaultDPreference.setPrefString(SettingsActivity.PREF_DOMESTIC_DNS, "223.5.5.5")
        defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_MODE, "0")
        defaultDPreference.setPrefString(SettingsActivity.PREF_ROUTING_DOMAIN_STRATEGY, "IPIfNonMatch")
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_PROXY_SHARING, false)
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_PER_APP_PROXY, false)
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_FORWARD_IPV6, false)
        defaultDPreference.setPrefBoolean(SettingsActivity.PREF_SNIFFING_ENABLED, true)
    }

    private fun copyCurrentConfigForDebug() {
        val config = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG, "")
                .ifBlank { defaultDPreference.getPrefString(AppConfig.ANG_CONFIG + PREF_DDCAT_CONFIG_GUID, "") }
        if (config.isBlank()) {
            toast("当前还没有生成线路配置，请先登录账号")
        } else {
            Utils.setClipboard(this, config)
            toast("当前线路配置已复制，可发给我对比排查")
        }
    }

    private fun isLineReady(): Boolean {
        return AngConfigManager.getIndexViaGuid(PREF_DDCAT_CONFIG_GUID) >= 0 && defaultDPreference.getPrefString(PREF_DDCAT_VLESS_URL, "").isNotBlank()
    }

    private fun normalizeBaseUrl(value: String): String {
        return value.trim().trimEnd('/')
    }

    private fun readData(json: JSONObject): JSONObject? {
        return json.optJSONObject("data")
    }

    private fun readString(json: JSONObject, key: String): String {
        val data = readData(json)
        val v = json.optString(key, "")
        if (v.isNotBlank()) return v
        return data?.optString(key, "") ?: ""
    }

    private fun readInt(json: JSONObject, key: String): Int {
        val data = readData(json)
        if (json.has(key)) return json.optInt(key, 0)
        return data?.optInt(key, 0) ?: 0
    }

    private fun readDouble(json: JSONObject, key: String): Double {
        val data = readData(json)
        if (json.has(key)) return json.optDouble(key, 0.0)
        return data?.optDouble(key, 0.0) ?: 0.0
    }

    private fun readSni(json: JSONObject): String {
        val data = readData(json)
        val direct = json.optString("sni", "")
        if (direct.isNotBlank()) return direct
        val dataDirect = data?.optString("sni", "") ?: ""
        if (dataDirect.isNotBlank()) return dataDirect
        val streamText = data?.optString("stream_settings", json.optString("stream_settings", "")) ?: json.optString("stream_settings", "")
        if (streamText.isBlank()) return ""
        return try {
            val stream = JSONObject(streamText)
            stream.optJSONObject("xtlsSettings")?.optString("serverName", "")
                    ?: stream.optJSONObject("tlsSettings")?.optString("serverName", "")
                    ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatGb(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun isExpired(expireTime: String): Boolean {
        if (expireTime.isBlank()) return false
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val date = sdf.parse(expireTime)
            date != null && date.before(Date())
        } catch (e: Exception) {
            false
        }
    }

    private fun openBusinessPage(path: String) {
        val base = normalizeBaseUrl(input_api_base_url.text?.toString() ?: defaultDPreference.getPrefString(PREF_DDCAT_API_BASE, ""))
        if (base.isBlank()) {
            toast("请先填写服务地址")
        } else {
            Utils.openUri(this, base + path)
        }
    }


    fun showCircle() {
        btn_connect.isEnabled = false
        btn_connect.text = "正在加速..."
    }

    fun hideCircle() {
        btn_connect.isEnabled = true
        btn_connect.text = if (mainViewModel.isRunning.value == true) "停止加速" else "一键加速"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
