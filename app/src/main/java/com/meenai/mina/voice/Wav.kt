package com.meenai.mina.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 把 16 位单声道 PCM 包成标准 WAV(44 字节头 + 小端数据),给服务商的转文字接口上传用。 */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray {
        val dataSize = pcm16.size * 2
        val buf = ByteBuffer.allocate(HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)              // fmt chunk length
        buf.putShort(1)             // PCM
        buf.putShort(1)             // mono
        buf.putInt(rate)
        buf.putInt(rate * 2)        // bytes per second = sample rate x channels x 2 bytes
        buf.putShort(2)             // block align
        buf.putShort(16)            // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm16) buf.putShort(s)
        return buf.array()
    }

    /** 一段全零的静音 WAV,用来试探某个模型能不能转文字(不需要真录音)。 */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
