package com.gamebex.client.together.net

import com.gamebex.client.together.LiveProbes
import com.gamebex.client.together.RaRoom
import com.gamebex.client.together.TogetherPorts

class LiveProbesImpl(
    private val raPort: Int = TogetherPorts.RA_NETPLAY,
) : LiveProbes {
    override suspend fun raRoom(address: String, timeoutMs: Long): RaRoom? =
        RaLanProbe.room(address, raPort, timeoutMs)

    override suspend fun tcpOpen(address: String, port: Int, timeoutMs: Long): Boolean =
        TcpProbe.tcpOpen(address, port, timeoutMs)
}
