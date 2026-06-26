package com.v2ray.ang.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
 * Minimal VLESS editor restored from the verified APK behavior.
 * This is intentionally small: it only exposes the fields needed by Legacy XTLS.
 */
class Server5Activity : BaseActivity() {
    private lateinit var configs: AngConfig
    private var editIndex: Int = -1
    private var isRunning: Boolean = false

    private lateinit var etRemarks: EditText
    private lateinit var etAddress: EditText
    private lateinit var etPort: EditText
    private lateinit var etId: EditText
    private lateinit var etFlow: EditText
    private lateinit var etSni: EditText

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
        root.setPadding(dp(18), dp(18), dp(18), dp(18))
        scroll.addView(root)

        etRemarks = addEdit(root, getString(R.string.server_lab_remarks), InputType.TYPE_CLASS_TEXT)
        etAddress = addEdit(root, getString(R.string.server_lab_address), InputType.TYPE_CLASS_TEXT)
        etPort = addEdit(root, getString(R.string.server_lab_port), InputType.TYPE_CLASS_NUMBER)
        etId = addEdit(root, getString(R.string.server_lab_id), InputType.TYPE_CLASS_TEXT)
        etFlow = addEdit(root, getString(R.string.server_lab_flow), InputType.TYPE_CLASS_TEXT)
        etSni = addEdit(root, "SNI / serverName（可留空）", InputType.TYPE_CLASS_TEXT)

        val hint = TextView(this)
        hint.text = "默认：network=tcp, security=xtls, encryption=none, allowInsecure=true, mux=false"
        hint.gravity = Gravity.CENTER_HORIZONTAL
        hint.setPadding(0, dp(12), 0, dp(12))
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        setContentView(scroll)
    }

    private fun addEdit(root: LinearLayout, label: String, inputType: Int): EditText {
        val tv = TextView(this)
        tv.text = label
        tv.setPadding(0, dp(10), 0, dp(2))
        root.addView(tv, LinearLayout.LayoutParams(-1, -2))
        val et = EditText(this)
        et.inputType = inputType
        root.addView(et, LinearLayout.LayoutParams(-1, -2))
        return et
    }

    private fun bindServer(vmess: AngConfig.VmessBean) {
        etRemarks.setText(vmess.remarks)
        etAddress.setText(vmess.address)
        etPort.setText(vmess.port.toString())
        etId.setText(vmess.id)
        etFlow.setText(if (vmess.flow.isBlank()) "xtls-rprx-direct" else vmess.flow)
        etSni.setText(vmess.requestHost)
    }

    private fun clearServer() {
        etRemarks.setText("")
        etAddress.setText("")
        etPort.setText("")
        etId.setText("")
        etFlow.setText("xtls-rprx-direct")
        etSni.setText("")
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
        bean.network = "tcp"
        bean.headerType = "none"
        bean.requestHost = etSni.text.toString().trim()
        bean.path = ""
        bean.streamSecurity = "xtls"
        bean.flow = etFlow.text.toString().trim().ifBlank { "xtls-rprx-direct" }
        bean.encryption = "none"
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
