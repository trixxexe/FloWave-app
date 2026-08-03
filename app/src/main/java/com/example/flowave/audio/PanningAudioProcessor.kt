package com.example.flowave.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class PanningAudioProcessor : BaseAudioProcessor() {
    private var leftGain = 1.0f
    private var rightGain = 1.0f

    fun setBalance(balance: Float) {
        // balance ranges from -1.0f (fully Left) to 1.0f (fully Right)
        leftGain = (1f - balance).coerceIn(0f, 1f)
        rightGain = (1f + balance).coerceIn(0f, 1f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // This processor works with 16-bit PCM stereo (2 channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val limit = inputBuffer.limit()
        var position = inputBuffer.position()
        val size = limit - position
        val buffer = replaceOutputBuffer(size)

        if (inputAudioFormat.channelCount == 2) {
            while (position < limit) {
                val left = inputBuffer.getShort(position)
                val right = inputBuffer.getShort(position + 2)

                val newLeft = (left * leftGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val newRight = (right * rightGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                buffer.putShort(newLeft)
                buffer.putShort(newRight)
                position += 4
            }
        } else {
            while (position < limit) {
                val sample = inputBuffer.getShort(position)
                buffer.putShort(sample)
                position += 2
            }
        }
        inputBuffer.position(limit)
        buffer.flip()
    }
}
