package com.gamebex.client.net

import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Default user-agent for the generic host's own direct HTTP (BIOS, cores, installers). */
const val HOST_UA = "GameBEXClient/1.0"

/**
 * Shared client for explicitly host-owned services (emulator vendors,
 * update APIs). Never used for source catalog/download/artwork traffic,
 * which goes through each source's checked client.
 */
fun hostHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
    .dns(
        object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                val v4 = all.filterIsInstance<java.net.Inet4Address>()
                return v4.ifEmpty { all }
            }
        },
    )
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", HOST_UA)
                .build(),
        )
    }
    .build()
