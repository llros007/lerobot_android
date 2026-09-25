package com.leneo.ipdevices.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun ipv4Addresses(context: Context): List<String> {
        val fromCm = ipv4FromConnectivityManager(context)
        if (fromCm.isNotEmpty()) return fromCm
        return ipv4FromInterfaces()
    }

    private fun ipv4FromConnectivityManager(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val out = linkedSetOf<String>()
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
            val lp: LinkProperties = cm.getLinkProperties(network) ?: return@forEach
            lp.linkAddresses.forEach { la ->
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    out += addr.hostAddress ?: return@forEach
                }
            }
        }
        return out.toList()
    }

    private fun ipv4FromInterfaces(): List<String> {
        val out = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            nif.inetAddresses.toList().forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { out += it }
                }
            }
        }
        return out
    }

    fun primaryIpv4(context: Context): String =
        ipv4Addresses(context).firstOrNull() ?: "127.0.0.1"
}
