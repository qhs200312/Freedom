package com.v2ray.ang.contracts

/**
 * Interface that defines the control operations for tun2socks implementations.
 * 
 * This interface is implemented by different tunnel solutions like:
 */
interface Tun2SocksControl {
    /**
     * Starts the tun2socks process with the appropriate parameters.
     * This initializes the VPN tunnel and connects it to the SOCKS proxy.
     */
    fun startTun2Socks(): Boolean

    /** Native counters: tx packets, tx bytes, rx packets, rx bytes. */
    fun getTrafficStats(): LongArray? = null

    /**
     * Stops the tun2socks process and cleans up resources.
     */
    fun stopTun2Socks()
}
