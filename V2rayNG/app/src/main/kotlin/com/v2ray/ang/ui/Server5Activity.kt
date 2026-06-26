package com.v2ray.ang.ui

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import android.support.v7.app.AlertDialog
import com.v2ray.ang.R
import com.v2ray.ang.dto.AngConfig
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.Utils

/**
 * VLESS editor aligned with the verified v2rayNG 1.5.0 APK form.
 *
 * The previous V1.1.2 editor only exposed a minimal subset of fields. That was enough to display
 * a VLESS page, but it did not prove that the app-side model matched the verified APK's VLESS
 * configuration path. This editor exposes and saves the same key fields used by the verified APK:
 * flow, encryption, transport network, camouflage type, host, path, stream security and allowInsecure.
 */
class Server5Activity : BaseActivity() {
    private lateinit var configs: AngConfig
    private var editIndex: Int = -1
    private var isRunning: Boolean = false

    private lateinit var etRemarks: EditText
    private lateinit var etAddress: EditText
    private lateinit var etPort: EditText
    private lateinit var etId: EditText
    private lateinit var spFlow: Spinner
    private lateinit var etEncryption: EditText
    private lateinit var spNetwork: Spinner
    private lateinit var spHeaderType: Spinner
    private lateinit var etRequestHost: EditText
    private lateinit var etPath: EditText
    private lateinit var spStreamSecurity: Spinner
    private lateinit var spAllowInsecure: Spinner

    private val flowValues = arrayListOf("", "xtls-rprx-direct")
    private val allowInsecureValues = arrayListOf("true", "false")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configs = AngConfigManager.configs
        editIndex = intent.getIntExtra("position", -1)
        isRunning = intent.getBooleanExtra("isRunning", false)
        title = "VLESS"
        buildUi()
        if (editIndex >= 0) {
            bindServer(configs.vmess[editIndex])
        } else {
            clearServer()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(14), dp(18), dp(22))
        scroll.addView(root)

        etRemarks = addEdit(root, getString(R.string.server_lab_remarks), InputType.TYPE_CLASS_TEXT)
        etAddress = addEdit(root, getString(R.string.server_lab_address), InputType.TYPE_CLASS_TEXT)
        etPort = addEdit(root, getString(R.string.server_lab_port), InputType.TYPE_CLASS_NUMBER)
        etId = addEdit(root, getString(R.string.server_lab_id), InputType.TYPE_CLASS_TEXT)

        spFlow = addSpinner(root, getString(R.string.server_lab_flow), flowValues)
        etEncryption = addEdit(root, "加密(encryption)", InputType.TYPE_CLASS_TEXT)

        addSection(root, "底层传输方式(transport)")
        spNetwork = addResourceSpinner(root, "传输协议(network)", R.array.networks)
        spHeaderType = addResourceSpinner(root, "伪装类型(type)", R.array.headertypes)
        etRequestHost = addEdit(root, "伪装域名(host)(host/ws host/h2 host)/QUIC 加密方式", InputType.TYPE_CLASS_TEXT)
        etPath = addEdit(root, "path(ws path/h2 path)/QUIC 加密密钥/kcp seed", InputType.TYPE_CLASS_TEXT)

        spStreamSecurity = addResourceSpinner(root, "底层传输安全(tls)", R.array.streamsecuritys)
        spAllowInsecure = addSpinner(root, "跳过证书验证(allowInsecure)", allowInsecureValues)

        setContentView(scroll)
    }

    private fun addSection(root: LinearLayout, label: String) {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 18f
        tv.setPadding(0, dp(22), 0, dp(4))
        root.addView(tv, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addEdit(root: LinearLayout, label: String, inputType: Int): EditText {
        val tv = TextView(this)
        tv.text = label
        tv.setPadding(0, dp(12), 0, dp(2))
        root.addView(tv, LinearLayout.LayoutParams(-1, -2))
        val et = EditText(this)
        et.inputType = inputType
        et.setSingleLine(true)
        root.addView(et, LinearLayout.LayoutParams(-1, -2))
        return et
    }

    private fun addSpinner(root: LinearLayout, label: String, values: List<String>): Spinner {
        val tv = TextView(this)
        tv.text = label
        tv.setPadding(0, dp(12), 0, dp(2))
        root.addView(tv, LinearLayout.LayoutParams(-1, -2))
        val spinner = Spinner(this)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        root.addView(spinner, LinearLayout.LayoutParams(-1, -2))
        return spinner
    }

    private fun addResourceSpinner(root: LinearLayout, label: String, arrayId: Int): Spinner {
        val values = resources.getStringArray(arrayId).toList()
        return addSpinner(root, label, values)
    }

    private fun bindServer(vmess: AngConfig.VmessBean) {
        etRemarks.setText(vmess.remarks)
        etAddress.setText(vmess.address)
        etPort.setText(vmess.port.toString())
        etId.setText(vmess.id)
        setSpinnerValue(spFlow, flowValues, if (vmess.flow.isBlank()) "xtls-rprx-direct" else vmess.flow)
        etEncryption.setText(if (vmess.encryption.isBlank()) "none" else vmess.encryption)
        setSpinnerValue(spNetwork, resources.getStringArray(R.array.networks).toList(), if (vmess.network.isBlank()) "tcp" else vmess.network)
        setSpinnerValue(spHeaderType, resources.getStringArray(R.array.headertypes).toList(), if (vmess.headerType.isBlank()) "none" else vmess.headerType)
        etRequestHost.setText(vmess.requestHost)
        etPath.setText(vmess.path)
        setSpinnerValue(spStreamSecurity, resources.getStringArray(R.array.streamsecuritys).toList(), if (vmess.streamSecurity.isBlank()) "xtls" else vmess.streamSecurity)
        setSpinnerValue(spAllowInsecure, allowInsecureValues, "true")
    }

    private fun clearServer() {
        etRemarks.setText("")
        etAddress.setText("")
        etPort.setText("")
        etId.setText("")
        setSpinnerValue(spFlow, flowValues, "xtls-rprx-direct")
        etEncryption.setText("none")
        setSpinnerValue(spNetwork, resources.getStringArray(R.array.networks).toList(), "tcp")
        setSpinnerValue(spHeaderType, resources.getStringArray(R.array.headertypes).toList(), "none")
        etRequestHost.setText("")
        etPath.setText("")
        setSpinnerValue(spStreamSecurity, resources.getStringArray(R.array.streamsecuritys).toList(), "xtls")
        setSpinnerValue(spAllowInsecure, allowInsecureValues, "true")
    }

    private fun setSpinnerValue(spinner: Spinner, values: List<String>, value: String) {
        val idx = values.indexOf(value)
        spinner.setSelection(if (idx >= 0) idx else 0)
    }

    private fun selected(spinner: Spinner): String {
        return spinner.selectedItem?.toString()?.trim() ?: ""
    }

    private fun saveServer(): Boolean {
        val port = Utils.parseInt(etPort.text.toString())
        val bean = if (editIndex >= 0) configs.vmess[editIndex] else AngConfig.VmessBean()
        bean.remarks = etRemarks.text.toString().ifBlank { "VLESS" }
        bean.address = etAddress.text.toString().trim()
        bean.port = port
        bean.id = etId.text.toString().trim()
        bean.alterId = 0
        bean.security = "auto"
        bean.network = selected(spNetwork).ifBlank { "tcp" }
        bean.headerType = selected(spHeaderType).ifBlank { "none" }
        bean.requestHost = etRequestHost.text.toString().trim()
        bean.path = etPath.text.toString().trim()
        bean.streamSecurity = selected(spStreamSecurity).ifBlank { "xtls" }
        bean.flow = selected(spFlow).ifBlank { if (bean.streamSecurity == "xtls") "xtls-rprx-direct" else "" }
        bean.encryption = etEncryption.text.toString().trim().ifBlank { "none" }
        bean.configVersion = 2

        if (bean.address.isBlank() || bean.port <= 0 || bean.id.isBlank()) {
            toast(R.string.toast_incorrect_protocol)
            return false
        }
        val ret = AngConfigManager.addVlessServer(bean, editIndex)
        if (ret == 0) {
            toast(R.string.toast_success)
            finish()
            return true
        }
        toast(R.string.toast_failure)
        return false
    }

    private fun deleteServer() {
        if (editIndex >= 0) {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (AngConfigManager.removeServer(editIndex) == 0) {
                            toast(R.string.toast_success)
                            finish()
                        } else {
                            toast(R.string.toast_failure)
                        }
                    }
                    .show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        if (editIndex < 0) {
            menu.findItem(R.id.del_config)?.isVisible = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish(); true
            }
            R.id.save_config -> {
                saveServer(); true
            }
            R.id.del_config -> {
                deleteServer(); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
