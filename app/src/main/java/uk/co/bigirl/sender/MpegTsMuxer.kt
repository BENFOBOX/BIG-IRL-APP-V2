package uk.co.bigirl.sender

import com.pedro.common.AudioCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.srt.mpeg2ts.Codec
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.mpeg2ts.MpegTsPacketizer
import com.pedro.srt.mpeg2ts.packets.AacPacket
import com.pedro.srt.mpeg2ts.packets.H264Packet
import com.pedro.srt.mpeg2ts.psi.PsiManager
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

internal class MpegTsMuxer(
    private val sendPacket: (ByteArray) -> Unit,
) {

    private val service = Mpeg2TsService().apply {
        addTrack(Codec.AAC)
        addTrack(Codec.AVC)
        generatePmt()
    }
    private val psiManager = PsiManager(service).apply {
        upgradePatVersion()
        upgradeSdtVersion()
    }
    private val psiPacketizer = MpegTsPacketizer(psiManager)
    private val videoPacket = H264Packet(MAX_PACKET_SIZE, psiManager)
    private val audioPacket = AacPacket(MAX_PACKET_SIZE, psiManager)
    private val lock = Any()

    fun start() = synchronized(lock) {
        psiManager.reset()
        psiPacketizer.reset()
        videoPacket.reset(resetInfo = false)
        audioPacket.reset(resetInfo = false)
        val tables = buildList {
            add(psiManager.getPat())
            psiManager.getPmt()?.let(::add)
            add(psiManager.getSdt())
        }
        psiPacketizer.write(tables).forEach(sendPacket)
    }

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer) = synchronized(lock) {
        videoPacket.sendVideoInfo(sps, pps)
    }

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) = synchronized(lock) {
        audioPacket.sendAudioInfo(sampleRate, isStereo, AudioCodec.AAC)
    }

    fun sendVideo(data: ByteBuffer, info: MediaFrame.Info) = synchronized(lock) {
        runBlocking {
            videoPacket.createAndSendPacket(
                MediaFrame(data.duplicate(), info, MediaFrame.Type.VIDEO),
            ) { packets ->
                sendPackets(packets)
            }
        }
    }

    fun sendAudio(data: ByteBuffer, info: MediaFrame.Info) = synchronized(lock) {
        runBlocking {
            audioPacket.createAndSendPacket(
                MediaFrame(data.duplicate(), info, MediaFrame.Type.AUDIO),
            ) { packets ->
                sendPackets(packets)
            }
        }
    }

    private fun sendPackets(packets: List<MpegTsPacket>) {
        if (packets.isEmpty()) return
        val psiPackets = psiManager.checkSendInfo(
            packets.first().isKey,
            psiPacketizer,
            MAX_TS_PACKETS_PER_RIST_PACKET,
        )
        psiPackets.forEach { sendPacket(it.buffer) }
        packets.forEach { sendPacket(it.buffer) }
    }

    private companion object {
        const val MAX_PACKET_SIZE = 1316
        const val MAX_TS_PACKETS_PER_RIST_PACKET =
            MAX_PACKET_SIZE / MpegTsPacketizer.packetSize
    }
}
