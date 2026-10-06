package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MmkvManagerLocalGroupTest {
    @Test
    fun emptyLocalGroupIsRemovedAndRestoredOnlyWhenLocalNodesExist() {
        val data = mutableMapOf<String, MutableMap<String, String>>()
        val storages = mutableMapOf<String, MMKV>()
        Mockito.mockStatic(MMKV::class.java).use { factory ->
            factory.`when`<MMKV> { MMKV.mmkvWithID(any<String>(), eq(MMKV.MULTI_PROCESS_MODE)) }
                .thenAnswer { call ->
                    val id = call.getArgument<String>(0)
                    storages.getOrPut(id) { storage(data.getOrPut(id) { mutableMapOf() }) }
                }
            val localId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            MmkvManager.encodeSubscription(localId, SubscriptionItem(remarks = "Local"))
            MmkvManager.encodeSubscription("remote", SubscriptionItem(remarks = "Remote", url = "https://example.org/sub"))
            MmkvManager.encodeSubscription("empty-remote", SubscriptionItem(remarks = "Empty Remote"))
            MmkvManager.encodeServerList(mutableListOf("local-node"), localId)
            MmkvManager.encodeServerList(mutableListOf("remote-node"), "remote")

            assertEquals(listOf(localId, "remote", "empty-remote"), MmkvManager.decodeSubscriptions().map { it.guid })
            MmkvManager.encodeServerList(mutableListOf(), localId)
            assertEquals(listOf("remote", "empty-remote"), MmkvManager.decodeSubscriptions().map { it.guid })
            assertNull(MmkvManager.decodeSubscription(localId))
            assertEquals(listOf("remote-node"), MmkvManager.decodeServerList("remote"))
            assertEquals(listOf("remote", "empty-remote"), MmkvManager.decodeSubscriptions().map { it.guid })

            // Importing local profiles also repairs missing group metadata.
            MmkvManager.encodeServerList(mutableListOf("new-local-node"), localId)
            assertEquals(listOf(localId, "remote", "empty-remote"), MmkvManager.decodeSubscriptions().map { it.guid })
            assertNotNull(MmkvManager.decodeSubscription(localId))
            assertEquals(listOf("new-local-node"), MmkvManager.decodeServerList(localId))

            MmkvManager.encodeServerList(mutableListOf(), localId)
            MmkvManager.removeSubscription("remote")
            MmkvManager.removeSubscription("empty-remote")
            assertEquals(emptyList<String>(), MmkvManager.decodeSubscriptions().map { it.guid })
            assertNull(MmkvManager.decodeSubscription(localId))
        }
    }

    private fun storage(data: MutableMap<String, String>): MMKV = mock<MMKV>().also { store ->
        whenever(store.decodeString(any<String>())).thenAnswer { data[it.getArgument<String>(0)] }
        whenever(store.allKeys()).thenAnswer { data.keys.toTypedArray() }
        whenever(store.encode(any<String>(), any<String>())).thenAnswer {
            data[it.getArgument<String>(0)] = it.getArgument<String>(1)
            true
        }
        doAnswer { data.remove(it.getArgument<String>(0)); null }
            .whenever(store).remove(any<String>())
    }
}
