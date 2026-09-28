package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.VoiceTranscriptData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 语音相关的 REST 调用（voice 域，目前只有转文字）。与 iOS `IMHTTPService
 * transcribeVoiceWithToken:convID:convSeq:completion:`、Web `sdk/voiceApi.ts#transcribeVoice`
 * 同一个接口；见 `../IMServer/docs/design/VOICE_TRANSCRIBE_DESIGN.md` §3.1。
 */
class VoiceApi(private val http: HttpClient) {

    /**
     * 语音转文字：`POST /api/v1/voice/transcripts`。**只传消息坐标，不传音频路径**——
     * 服务端自己按 `(conv_id, conv_seq)` 反查 content 并过路径白名单（content 是客户端
     * 此前自己写进消息表的，不可信）。
     *
     * 返回的 `status`：`done` 带 `text`；`pending` 表示已入队，结果随后经 WS
     * `voice_transcript` 帧到达（见 [com.libeyond.imandroid.data.MessageService.voiceTranscripts]）。
     * 失败（未启用 / 队列满 / 限流等）走 [com.libeyond.imandroid.sdk.http.ApiException]。
     */
    suspend fun transcribe(convId: String, convSeq: Long): VoiceTranscriptData {
        val body = buildJsonObject {
            put("conv_id", convId)
            put("conv_seq", convSeq)
        }
        return decode(http.call("POST", "/api/v1/voice/transcripts", body), VoiceTranscriptData.serializer())
    }
}
