package com.v2ray.ang.ui

import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val appPrefs by lazy {
        getSharedPreferences(DDCAT_PREFS, Context.MODE_PRIVATE)
    }

    private val appApiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }

    private data class DingdangAccountInfo(
        val apiBaseUrl: String,
        val email: String,
        val success: Boolean,
        val message: String,
        val status: String,
        val statusText: String,
        val expireTime: String,
        val trafficUsedGb: Double,
        val trafficTotalGb: Double,
        val trafficRemainGb: Double,
        val hostAddress: String,
        val port: Int,
        val protocol: String,
        val network: String,
        val security: String,
        val flow: String,
        val uuid: String,
        val vlessUrl: String
    )

    private class AppLoginException(message: String) : Exception(message)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        binding.toolbar.navigationIcon = null
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // Keep the original ViewPager / tabs initialized for core compatibility,
        // but hide every manual v2rayNG-style entrance in the V1.2 shell UI.
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = false

        binding.fab.setOnClickListener { handleFabAction() }
        binding.btnConnect.setOnClickListener { handleFabAction() }
        binding.cardConnection.setOnClickListener { handleLayoutTestClick() }
        binding.btnRenew.setOnClickListener { toast("套餐续费入口将在下一阶段接入") }
        binding.btnOrder.setOnClickListener { toast("在线下单入口将在下一阶段接入") }
        binding.btnSupport.setOnClickListener { toast("请通过右下角客服气泡联系客服") }
        binding.btnLogin.setOnClickListener { loginWithAppApi(showToastOnSuccess = true) }
        binding.btnRefreshAccount.setOnClickListener { refreshSavedAccount() }

        loadSavedAppAccount()
        setupGroupTab()
        hideLegacyEntrances()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = false
        refreshGroupTabTitles(true)
        hideLegacyEntrances()
    }

    private fun hideLegacyEntrances() {
        binding.navView.isVisible = false
        binding.tabGroup.isVisible = false
        binding.viewPager.isVisible = false
        binding.layoutTest.isVisible = false
        binding.fab.isVisible = false
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groupsToRefresh = if (refreshAll || mainViewModel.subscriptionId.isEmpty()) {
            groupPagerAdapter.groups
        } else {
            groupPagerAdapter.groups.filter { it.id == mainViewModel.subscriptionId }
        }

        groupsToRefresh.forEach { group ->
            if (group.id.isEmpty()) {
                return@forEach
            }
            val tabIndex = groupPagerAdapter.groups.indexOfFirst { it.id == group.id }
            if (tabIndex >= 0) {
                val count = MmkvManager.decodeServerList(group.id).size
                binding.tabGroup.getTabAt(tabIndex)?.text = "${group.remarks} ($count)"
            }
        }
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            toast("请先启动加速通道")
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast("账号登录成功后，V1.4 将自动导入专属线路；当前版本先展示真实账号信息")
            applyRunningState(false, mainViewModel.isRunning.value == true)
            return
        }
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = "连接中..."
            binding.tvShellStatus.text = "当前状态：正在处理"
            binding.tvShellHint.text = "正在切换加速通道，请稍候。"
            return
        }

        binding.btnConnect.isEnabled = true
        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            binding.btnConnect.text = "一键停止"
            binding.tvShellStatus.text = "当前状态：已加速"
            binding.tvShellStatusDot.setBackgroundResource(R.drawable.bg_ddcat_status_dot_on)
            binding.tvShellHint.text = "加速通道运行中，点击卡片可测试当前连接。"
            binding.tvShellNode.text = "运行中"
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
            binding.btnConnect.text = "一键加速"
            binding.tvShellStatus.text = "当前状态：未加速"
            binding.tvShellStatusDot.setBackgroundResource(R.drawable.bg_ddcat_status_dot_off)
            binding.tvShellHint.text = buildShellHintForIdle()
            binding.tvShellNode.text = buildShellNodeText()
        }
    }


    private fun loadSavedAppAccount() {
        val savedBaseUrl = appPrefs.getString(KEY_API_BASE_URL, "") ?: ""
        val savedEmail = appPrefs.getString(KEY_EMAIL, "") ?: ""
        binding.etApiBaseUrl.setText(savedBaseUrl)
        binding.etLoginEmail.setText(savedEmail)

        if (savedEmail.isBlank()) {
            applyLoggedOutAccountState()
            return
        }

        val info = DingdangAccountInfo(
            apiBaseUrl = savedBaseUrl,
            email = savedEmail,
            success = appPrefs.getBoolean(KEY_SUCCESS, false),
            message = appPrefs.getString(KEY_MESSAGE, "") ?: "",
            status = appPrefs.getString(KEY_STATUS, "") ?: "",
            statusText = appPrefs.getString(KEY_STATUS_TEXT, "") ?: "",
            expireTime = appPrefs.getString(KEY_EXPIRE_TIME, "") ?: "",
            trafficUsedGb = java.lang.Double.longBitsToDouble(appPrefs.getLong(KEY_TRAFFIC_USED_GB, java.lang.Double.doubleToRawLongBits(-1.0))),
            trafficTotalGb = java.lang.Double.longBitsToDouble(appPrefs.getLong(KEY_TRAFFIC_TOTAL_GB, java.lang.Double.doubleToRawLongBits(-1.0))),
            trafficRemainGb = java.lang.Double.longBitsToDouble(appPrefs.getLong(KEY_TRAFFIC_REMAIN_GB, java.lang.Double.doubleToRawLongBits(-1.0))),
            hostAddress = appPrefs.getString(KEY_HOST_ADDRESS, "") ?: "",
            port = appPrefs.getInt(KEY_PORT, 0),
            protocol = appPrefs.getString(KEY_PROTOCOL, "") ?: "",
            network = appPrefs.getString(KEY_NETWORK, "") ?: "",
            security = appPrefs.getString(KEY_SECURITY, "") ?: "",
            flow = appPrefs.getString(KEY_FLOW, "") ?: "",
            uuid = appPrefs.getString(KEY_UUID, "") ?: "",
            vlessUrl = appPrefs.getString(KEY_VLESS_URL, "") ?: ""
        )
        applyAccountInfo(info, fromCache = true)
    }

    private fun applyLoggedOutAccountState() {
        binding.tvAccountEmail.text = "未登录"
        binding.tvPlanStatus.text = "待登录后查看"
        binding.tvExpireTime.text = "--"
        binding.tvTrafficUsage.text = "-- / --"
        binding.tvTrafficRemain.text = "--"
        binding.tvLineInfo.text = "登录后显示"
        binding.tvApiMessage.text = "请输入服务地址和邮箱，点击登录查询账号。"
        binding.tvShellHint.text = buildShellHintForIdle()
        binding.tvShellNode.text = buildShellNodeText()
    }

    private fun loginWithAppApi(showToastOnSuccess: Boolean) {
        val apiBaseUrl = normalizeApiBaseUrl(binding.etApiBaseUrl.text?.toString().orEmpty())
        val email = binding.etLoginEmail.text?.toString()?.trim().orEmpty()

        if (apiBaseUrl.isBlank()) {
            toast("请先填写服务地址，例如：https://你的域名")
            return
        }
        if (!apiBaseUrl.startsWith("http://") && !apiBaseUrl.startsWith("https://")) {
            toast("服务地址必须以 http:// 或 https:// 开头")
            return
        }
        if (email.isBlank() || !email.contains("@")) {
            toast("请输入正确的邮箱")
            return
        }

        setLoginLoading(true, "正在登录查询账号...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = requestAppLogin(apiBaseUrl, email)
                saveAccountInfo(info)
                withContext(Dispatchers.Main) {
                    binding.etApiBaseUrl.setText(info.apiBaseUrl)
                    binding.etLoginEmail.setText(info.email)
                    applyAccountInfo(info, fromCache = false)
                    setLoginLoading(false, info.message.ifBlank { "登录成功" })
                    if (showToastOnSuccess) {
                        toast("登录成功，账号信息已更新")
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "DingdangCat app login failed", e)
                withContext(Dispatchers.Main) {
                    setLoginLoading(false, e.message ?: "登录失败，请检查服务地址和邮箱")
                    binding.tvApiMessage.text = e.message ?: "登录失败，请检查服务地址和邮箱"
                    toast(e.message ?: "登录失败，请检查服务地址和邮箱")
                }
            }
        }
    }

    private fun refreshSavedAccount() {
        val apiBaseUrl = normalizeApiBaseUrl(binding.etApiBaseUrl.text?.toString().orEmpty())
        val email = binding.etLoginEmail.text?.toString()?.trim().orEmpty()
        if (apiBaseUrl.isBlank() || email.isBlank()) {
            toast("请先填写服务地址和邮箱")
            return
        }
        loginWithAppApi(showToastOnSuccess = true)
    }

    private fun setLoginLoading(isLoading: Boolean, message: String) {
        binding.btnLogin.isEnabled = !isLoading
        binding.btnRefreshAccount.isEnabled = !isLoading
        binding.btnLogin.text = if (isLoading) "查询中..." else "登录 / 查询账号"
        binding.tvApiMessage.text = message
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun requestAppLogin(apiBaseUrl: String, email: String): DingdangAccountInfo {
        val encodedEmail = URLEncoder.encode(email, "UTF-8")
        val requestUrl = "$apiBaseUrl/api/app/login?email=$encodedEmail"
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        appApiClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AppLoginException("接口请求失败：HTTP ${response.code}")
            }
            if (bodyText.isBlank()) {
                throw AppLoginException("接口返回为空")
            }
            val json = JSONObject(bodyText)
            if (!json.optBoolean("success", false)) {
                val message = json.optString("message").ifBlank {
                    when (json.optString("code")) {
                        "EMAIL_NOT_FOUND" -> "未找到该邮箱对应账号"
                        else -> "登录失败"
                    }
                }
                throw AppLoginException(message)
            }

            val data = json.optJSONObject("data") ?: json
            return DingdangAccountInfo(
                apiBaseUrl = apiBaseUrl,
                email = json.optString("email", data.optString("email", email)),
                success = true,
                message = json.optString("message", "登录成功"),
                status = json.optString("status", data.optString("status", "active")),
                statusText = json.optString("status_text", data.optString("status_text", "正常")),
                expireTime = json.optString("expire_time", data.optString("expire_time", "--")),
                trafficUsedGb = json.optDouble("traffic_used_gb", data.optDouble("traffic_used_gb", -1.0)),
                trafficTotalGb = json.optDouble("traffic_total_gb", data.optDouble("traffic_total_gb", -1.0)),
                trafficRemainGb = json.optDouble("traffic_remain_gb", data.optDouble("traffic_remain_gb", -1.0)),
                hostAddress = json.optString("host_address", data.optString("host_address", "")),
                port = json.optInt("port", data.optInt("port", 0)),
                protocol = json.optString("protocol", data.optString("protocol", "")),
                network = json.optString("network", data.optString("network", "")),
                security = json.optString("security", data.optString("security", "")),
                flow = json.optString("flow", data.optString("flow", "")),
                uuid = json.optString("uuid", data.optString("uuid", "")),
                vlessUrl = json.optString("vless_url", data.optString("vless_url", ""))
            )
        }
    }

    private fun saveAccountInfo(info: DingdangAccountInfo) {
        appPrefs.edit()
            .putString(KEY_API_BASE_URL, info.apiBaseUrl)
            .putString(KEY_EMAIL, info.email)
            .putBoolean(KEY_SUCCESS, info.success)
            .putString(KEY_MESSAGE, info.message)
            .putString(KEY_STATUS, info.status)
            .putString(KEY_STATUS_TEXT, info.statusText)
            .putString(KEY_EXPIRE_TIME, info.expireTime)
            .putLong(KEY_TRAFFIC_USED_GB, java.lang.Double.doubleToRawLongBits(info.trafficUsedGb))
            .putLong(KEY_TRAFFIC_TOTAL_GB, java.lang.Double.doubleToRawLongBits(info.trafficTotalGb))
            .putLong(KEY_TRAFFIC_REMAIN_GB, java.lang.Double.doubleToRawLongBits(info.trafficRemainGb))
            .putString(KEY_HOST_ADDRESS, info.hostAddress)
            .putInt(KEY_PORT, info.port)
            .putString(KEY_PROTOCOL, info.protocol)
            .putString(KEY_NETWORK, info.network)
            .putString(KEY_SECURITY, info.security)
            .putString(KEY_FLOW, info.flow)
            .putString(KEY_UUID, info.uuid)
            .putString(KEY_VLESS_URL, info.vlessUrl)
            .apply()
    }

    private fun applyAccountInfo(info: DingdangAccountInfo, fromCache: Boolean) {
        binding.tvAccountEmail.text = info.email.ifBlank { "未登录" }
        binding.tvPlanStatus.text = info.statusText.ifBlank { info.status.ifBlank { "正常" } }
        binding.tvExpireTime.text = info.expireTime.ifBlank { "--" }
        binding.tvTrafficUsage.text = formatTrafficUsage(info.trafficUsedGb, info.trafficTotalGb)
        binding.tvTrafficRemain.text = formatTrafficRemain(info.trafficRemainGb, info.trafficTotalGb)
        binding.tvLineInfo.text = buildLineInfo(info)
        binding.tvShellNode.text = buildShellNodeText(info)
        binding.tvShellHint.text = "账号信息已获取。V1.4 将自动导入专属线路，V1.5 完成一键加速闭环。"
        binding.tvApiMessage.text = if (fromCache) {
            "已读取上次登录信息，可点击刷新账号状态。"
        } else {
            info.message.ifBlank { "登录成功，账号信息已更新。" }
        }
    }

    private fun normalizeApiBaseUrl(raw: String): String {
        return raw.trim().trimEnd('/')
    }

    private fun formatTrafficUsage(used: Double, total: Double): String {
        return if (used >= 0 && total >= 0) {
            String.format("%.2f GB / %.2f GB", used, total)
        } else {
            "-- / --"
        }
    }

    private fun formatTrafficRemain(remain: Double, total: Double): String {
        return if (remain >= 0 && total >= 0 && total > 0) {
            val rate = ((total - remain) / total * 100.0).coerceIn(0.0, 100.0)
            String.format("剩余 %.2f GB · 已用 %.2f%%", remain, rate)
        } else if (remain >= 0) {
            String.format("剩余 %.2f GB", remain)
        } else {
            "--"
        }
    }

    private fun buildLineInfo(info: DingdangAccountInfo): String {
        val node = if (info.hostAddress.isNotBlank() && info.port > 0) {
            "${info.hostAddress}:${info.port}"
        } else {
            "--"
        }
        val protocol = listOf(info.protocol, info.network, info.security)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
            .ifBlank { "--" }
        val flow = info.flow.ifBlank { "--" }
        return "节点：$node\n协议：$protocol\nFlow：$flow"
    }

    private fun buildShellNodeText(info: DingdangAccountInfo? = null): String {
        val savedHost = info?.hostAddress ?: (appPrefs.getString(KEY_HOST_ADDRESS, "") ?: "")
        val savedPort = info?.port ?: appPrefs.getInt(KEY_PORT, 0)
        return if (savedHost.isNotBlank() && savedPort > 0) {
            "$savedHost:$savedPort"
        } else {
            "智能线路"
        }
    }

    private fun buildShellHintForIdle(): String {
        val savedEmail = appPrefs.getString(KEY_EMAIL, "") ?: ""
        return if (savedEmail.isNotBlank()) {
            "已登录账号：$savedEmail。当前版本已接入真实查询，自动导入线路将在 V1.4 接入。"
        } else {
            "请输入服务地址和邮箱，登录后显示真实账号、到期和流量信息。"
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // V1.2 shell mode: hide all manual configuration / filter / advanced actions.
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_proxy_chain -> {
            importManually(EConfigType.PROXYCHAIN.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
    companion object {
        private const val DDCAT_PREFS = "dingdang_cat_app_api"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_EMAIL = "email"
        private const val KEY_SUCCESS = "success"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STATUS = "status"
        private const val KEY_STATUS_TEXT = "status_text"
        private const val KEY_EXPIRE_TIME = "expire_time"
        private const val KEY_TRAFFIC_USED_GB = "traffic_used_gb"
        private const val KEY_TRAFFIC_TOTAL_GB = "traffic_total_gb"
        private const val KEY_TRAFFIC_REMAIN_GB = "traffic_remain_gb"
        private const val KEY_HOST_ADDRESS = "host_address"
        private const val KEY_PORT = "port"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_NETWORK = "network"
        private const val KEY_SECURITY = "security"
        private const val KEY_FLOW = "flow"
        private const val KEY_UUID = "uuid"
        private const val KEY_VLESS_URL = "vless_url"
    }

}