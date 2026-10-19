package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.NodeTrafficTotal
import com.v2ray.ang.util.JsonUtil

internal object NodeTrafficStore {
    private val storage by lazy { MMKV.mmkvWithID("NODE_TRAFFIC", MMKV.MULTI_PROCESS_MODE) }

    fun load(): Map<String, NodeTrafficTotal> = storage.allKeys().orEmpty().mapNotNull { id ->
        storage.decodeString(id)?.let { JsonUtil.fromJsonSafe(it, NodeTrafficTotal::class.java) }
            ?.let { id to it }
    }.toMap()

    fun save(id: String, total: NodeTrafficTotal) {
        storage.encode(id, JsonUtil.toJson(total))
    }
}
