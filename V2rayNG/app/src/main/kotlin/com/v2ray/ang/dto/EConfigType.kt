package com.v2ray.ang.dto

enum class EConfigType(val value: Int) {
    VMESS(1),
    CUSTOM(2),
    SHADOWSOCKS(3),
    SOCKS(4),
    VLESS(5);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value }
    }
}