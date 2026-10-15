package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Real Android descriptor instances, including repeated interface IDs for alternate settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class IphoneCarPlayConfigurationTest {
    private fun iface(id: Int, alt: Int, cls: Int, sub: Int, protocol: Int, bulk: Boolean = false): UsbInterface {
        val value = ReflectionHelpers.callConstructor(UsbInterface::class.java,
            from(Int::class.javaPrimitiveType!!, id), from(Int::class.javaPrimitiveType!!, alt),
            from(String::class.java, "synthetic"), from(Int::class.javaPrimitiveType!!, cls),
            from(Int::class.javaPrimitiveType!!, sub), from(Int::class.javaPrimitiveType!!, protocol))
        val endpoints = if (bulk) arrayOf(endpoint(0x81), endpoint(2)) else emptyArray<UsbEndpoint>()
        ReflectionHelpers.setField(value, "mEndpoints", endpoints)
        return value
    }
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
        from(Int::class.javaPrimitiveType!!, address), from(Int::class.javaPrimitiveType!!, 2),
        from(Int::class.javaPrimitiveType!!, 512), from(Int::class.javaPrimitiveType!!, 0))
    private fun config(vararg interfaces: UsbInterface): UsbConfiguration {
        val config = ReflectionHelpers.callConstructor(UsbConfiguration::class.java,
            from(Int::class.javaPrimitiveType!!, 6), from(String::class.java, "synthetic"),
            from(Int::class.javaPrimitiveType!!, 0x80), from(Int::class.javaPrimitiveType!!, 250))
        ReflectionHelpers.setField(config, "mInterfaces", interfaces)
        return config
    }
    private fun mux() = iface(1, 0, 255, 254, 2, true)
    private fun ncm() = iface(2, 0, 2, 13, 0)
    private fun ethernet(protocol: Int = 1) = iface(4, 0, 255, 253, protocol)
    @Test fun fallbackIsNotConfirmedCarPlayMode() {
        assertFalse(IphoneCarPlayConfiguration.isCarPlayMode(config(mux(), ncm())))
        assertTrue(IphoneCarPlayConfiguration.isCarPlayMode(config(mux(), ncm(), ethernet())))
        assertFalse(IphoneCarPlayConfiguration.isCarPlayMode(config(mux(), ncm(), ethernet(2))))
        assertFalse(IphoneCarPlayConfiguration.isCarPlayMode(config(ncm(), ethernet())))
        assertFalse(IphoneCarPlayConfiguration.isCarPlayMode(config(mux(), ethernet())))
    }
    @Test fun actualNcmDiscoverySelectsBulkAlternateOneWithoutResettingDescriptors() {
        val empty = iface(3, 0, 10, 0, 0)
        val alternateTwo = iface(3, 2, 10, 0, 0, true)
        val alternateOne = iface(3, 1, 10, 0, 0, true)
        val configuration = config(mux(), ncm(), empty, alternateTwo, alternateOne, ethernet())
        val function = requireNotNull(NcmFunctionDiscovery.find(configuration))
        assertSame(alternateOne, function.data)
        assertEquals(1, function.data.alternateSetting)
        assertEquals(3, function.data.id)
        assertEquals(6, configuration.id)
    }
}
