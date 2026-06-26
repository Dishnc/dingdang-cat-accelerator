package com.v2ray.ang.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.system.OsConstants
import android.support.annotation.RequiresApi
import android.util.Log
import com.v2ray.ang.R
import com.v2ray.ang.extension.defaultDPreference
import com.v2ray.ang.ui.PerAppProxyActivity
import com.v2ray.ang.ui.SettingsActivity
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.SoftReference

class V2RayVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor

    /**
        * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
        *
        * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
        * satisfies default network capabilities but only THE default network. Unfortunately we need to have
        * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
        *
        * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
        */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
    }

    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        stopV2Ray()
    }

    override fun onLowMemory() {
        stopV2Ray()
        super.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopV2Ray()
    }

    private fun setup(parameters: String) {

        val prepare = prepare(this)
        if (prepare != null) {
            defaultDPreference.setPrefString("ddcat_vpn_last_setup", "VpnService.prepare still requires user confirmation. params=$parameters")
            return
        }

        // Configure a builder while parsing the parameters returned by the native core.
        // Some repackaged legacy cores report a successful outbound test but do not provide
        // enough VPN DNS/route parameters for Android browser traffic. We keep the original
        // behavior, but add safe IPv4 fallback route/DNS so full-device VPN traffic is actually captured.
        val builder = Builder()
        val enableLocalDns = false
        val routingMode = "0"
        val diag = StringBuilder()
        var hasAddress = false
        var hasDefaultIpv4Route = false
        var hasDns = false

        try {
            // Force IPv4 family for full-device VPN capture. Some Android builds will not route DNS reliably
            // unless the VPN declares supported address families explicitly.
            builder.allowFamily(OsConstants.AF_INET)
            diag.append("allowFamily=AF_INET\n")
        } catch (e: Exception) {
            diag.append("allowFamily failed=").append(e.message).append("\n")
        }

        diag.append("setup called\nparams=").append(parameters).append("\n")
        diag.append("enableLocalDns=").append(enableLocalDns)
                .append(" routingMode=").append(routingMode)
                .append(" remoteDns=").append(defaultDPreference.getPrefString(SettingsActivity.PREF_REMOTE_DNS, ""))
                .append("\n")

        parameters.split(" ")
                .map { it.split(",") }
                .filter { it.isNotEmpty() && it[0].isNotEmpty() }
                .forEach {
                    try {
                        when (it[0][0]) {
                            'm' -> if (it.size >= 2) {
                                builder.setMtu(java.lang.Short.parseShort(it[1]).toInt())
                                diag.append("mtu=").append(it[1]).append("\n")
                            }
                            's' -> if (it.size >= 2) {
                                builder.addSearchDomain(it[1])
                                diag.append("search=").append(it[1]).append("\n")
                            }
                            'a' -> if (it.size >= 3) {
                                builder.addAddress(it[1], Integer.parseInt(it[2]))
                                hasAddress = true
                                diag.append("address=").append(it[1]).append('/').append(it[2]).append("\n")
                            }
                            'r' -> if (it.size >= 3) {
                                if (it[1] == "0.0.0.0" && it[2] == "0") {
                                    hasDefaultIpv4Route = true
                                }
                                if (routingMode == "1" || routingMode == "3") {
                                    if (it[1] == "::") {
                                        builder.addRoute("2000::", 3)
                                    } else {
                                        resources.getStringArray(R.array.bypass_private_ip_address).forEach { cidr ->
                                            val addr = cidr.split('/')
                                            builder.addRoute(addr[0], addr[1].toInt())
                                            diag.append("bypassRoute=").append(cidr).append("\n")
                                        }
                                    }
                                } else {
                                    builder.addRoute(it[1], Integer.parseInt(it[2]))
                                    diag.append("route=").append(it[1]).append('/').append(it[2]).append("\n")
                                }
                            }
                            'd' -> if (it.size >= 2) {
                                builder.addDnsServer(it[1])
                                hasDns = true
                                diag.append("dnsFromCore=").append(it[1]).append("\n")
                            }
                        }
                    } catch (e: Exception) {
                        diag.append("parse item failed: ").append(it.joinToString(",")).append(" err=").append(e.message).append("\n")
                    }
                }

        if (!hasAddress) {
            try {
                builder.addAddress("10.10.10.10", 30)
                diag.append("fallbackAddress=10.10.10.10/30\n")
            } catch (e: Exception) {
                diag.append("fallbackAddress failed=").append(e.message).append("\n")
            }
        }

        if (routingMode == "0" && !hasDefaultIpv4Route) {
            try {
                builder.addRoute("0.0.0.0", 0)
                hasDefaultIpv4Route = true
                diag.append("fallbackRoute=0.0.0.0/0\n")
            } catch (e: Exception) {
                diag.append("fallbackRoute failed=").append(e.message).append("\n")
            }
        }

        if(!enableLocalDns) {
            Utils.getRemoteDnsServers(defaultDPreference)
                .forEach {
                    try {
                        builder.addDnsServer(it)
                        hasDns = true
                        diag.append("dnsRemote=").append(it).append("\n")
                    } catch (e: Exception) {
                        diag.append("dnsRemote failed ").append(it).append(" err=").append(e.message).append("\n")
                    }
                }
        }
        if (!hasDns) {
            try {
                builder.addDnsServer("1.1.1.1")
                diag.append("fallbackDns=1.1.1.1\n")
            } catch (e: Exception) {
                diag.append("fallbackDns failed=").append(e.message).append("\n")
            }
        }

        builder.setSession(V2RayServiceManager.currentConfigName)

        // DingdangCat forces full-device VPN mode here. Do not apply per-app allow/deny lists,
        // otherwise the browser may be outside the VPN and DNS will still fail.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                defaultDPreference.getPrefBoolean(SettingsActivity.PREF_PER_APP_PROXY, false)) {
            diag.append("perAppProxy was enabled in prefs, ignored for full-device VPN capture\n")
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close()
        } catch (ignored: Exception) {
            // ignored
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Create a new interface using the builder and save the parameters.
        try {
            mInterface = builder.establish()!!
            diag.append("builder.establish=success\n")
            diag.append("before sendFd call\n")
            defaultDPreference.setPrefString("ddcat_vpn_last_setup", diag.toString())
        } catch (e: Exception) {
            diag.append("builder.establish=failed err=").append(e.message).append("\n")
            defaultDPreference.setPrefString("ddcat_vpn_last_setup", diag.toString())
            e.printStackTrace()
            stopV2Ray()
            return
        }

        sendFd()
    }

    private fun appendVpnDiag(line: String) {
        try {
            val oldDiag = defaultDPreference.getPrefString("ddcat_vpn_last_setup", "")
            defaultDPreference.setPrefString("ddcat_vpn_last_setup", oldDiag + "\n" + line)
        } catch (ignored: Exception) {
        }
    }

    private fun sendFd() {
        val fd = mInterface.fileDescriptor
        val paths = ArrayList<String>()
        fun addPath(path: String?) {
            if (!path.isNullOrBlank() && !paths.contains(path)) {
                paths.add(path)
            }
        }
        addPath(File(Utils.packagePath(applicationContext), "sock_path").absolutePath)
        addPath(File(applicationInfo.dataDir, "sock_path").absolutePath)
        addPath(File(filesDir.parentFile, "sock_path").absolutePath)

        appendVpnDiag("sendFd=start; paths=" + paths.joinToString("|") + "; fd=" + fd.toString())

        GlobalScope.launch(Dispatchers.IO) {
            var tries = 0
            var lastError = ""
            while (tries <= 12) {
                try {
                    val delayMs = when (tries) {
                        0 -> 100L
                        1 -> 300L
                        2 -> 700L
                        else -> 1000L
                    }
                    Thread.sleep(delayMs)
                } catch (ignored: Exception) {
                }

                for (path in paths) {
                    try {
                        Log.d(packageName, "sendFd tries: $tries path=$path")
                        LocalSocket().use { localSocket ->
                            localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                            localSocket.setFileDescriptorsForSend(arrayOf(fd))
                            localSocket.outputStream.write(42)
                            localSocket.outputStream.flush()
                        }
                        appendVpnDiag("sendFd=success; tries=" + tries + "; path=" + path)
                        return@launch
                    } catch (e: Exception) {
                        lastError = "try=" + tries + "; path=" + path + "; err=" + e.javaClass.simpleName + ":" + (e.message ?: "")
                        Log.d(packageName, lastError)
                    }
                }

                // Keep the diagnostic short but prove that fd handoff is still retrying.
                if (tries == 0 || tries == 2 || tries == 5 || tries == 12) {
                    appendVpnDiag("sendFd=retry; " + lastError)
                }
                tries += 1
            }
            appendVpnDiag("sendFd=failed after retries; lastError=" + lastError)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2RayServiceManager.startV2rayPoint()
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        V2RayServiceManager.stopV2rayPoint()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                mInterface.close()
            } catch (ignored: Exception) {
                // ignored
            }

        }
    }

    override fun getService(): Service {
        return this
    }

    override fun startService(parameters: String) {
        setup(parameters)
    }

    override fun stopService() {
        stopV2Ray(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

}
