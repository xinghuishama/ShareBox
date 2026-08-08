package com.xa.sharebox.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.PipeShare
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Enumerates SMB shares via DCERPC NetShareEnumAll (opnum 15) through the srvsvc named pipe.
 */
object SmbShareLister {

    data class ShareInfo(
        val name: String,
        val type: Int,
        val comment: String
    )

    fun listShares(
        host: String,
        port: Int = 445,
        username: String,
        password: String
    ): List<ShareInfo> {
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()

        val client = SMBClient(config)
        try {
            val connection = client.connect(host, port)
            val auth = if (username.isBlank() && password.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            val session = connection.authenticate(auth)

            val pipeShare = session.connectShare("IPC$") as PipeShare
            val pipe = pipeShare.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                emptySet(),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                emptySet()
            )

            try {
                // DCERPC Bind to SRVSVC interface
                val bindReq = buildBindRequest()
                pipe.transact(bindReq)

                // NetShareEnumAll (opnum 15)
                val enumReq = buildNetShareEnumAllRequest()
                val enumResp = pipe.transact(enumReq)

                return parseShareEnumResponse(enumResp)
            } finally {
                pipe.close()
            }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun buildBindRequest(): ByteArray {
        val body = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(4280.toShort())   // max transmit fragment
        body.putShort(4280.toShort())   // max receive fragment
        body.putInt(0)        // assoc group ID
        body.putInt(1)        // num context elements
        body.putShort(0.toShort())      // context ID
        body.putShort(1.toShort())      // num transfer syntaxes
        // SRVSVC UUID: 4b324fc8-1670-01d3-1278-5a47bf6ee188
        body.putInt(0xC84F324B.toInt())
        body.putShort(0x0116.toShort())
        body.putShort(0x1278.toShort())
        body.put(0x5A.toByte()); body.put(0x47.toByte())
        body.put(0xBF.toByte()); body.put(0x6E.toByte())
        body.put(0xE1.toByte()); body.put(0x88.toByte())
        body.putShort(3.toShort()); body.putShort(0.toShort())   // interface version 3.0
        // NDR transfer syntax: 8a885d04-1ceb-106c-921f-00c04fc2c6f3
        body.putInt(0x045D888A.toInt())
        body.putShort(0x1C1E.toShort())
        body.putShort(0x9210.toShort())
        body.put(0x00.toByte()); body.put(0xC0.toByte())
        body.put(0x4F.toByte()); body.put(0xC2.toByte())
        body.put(0xC6.toByte()); body.put(0xF3.toByte())
        body.putShort(2.toShort()); body.putShort(0.toShort())   // transfer syntax version 2.0

        val bodyBytes = body.array()
        return buildDcerpcHeader(0x0B, bodyBytes.size, 1) + bodyBytes
    }

    private fun buildNetShareEnumAllRequest(): ByteArray {
        // Request PDU body: alloc_hint(4) + context_id(2) + opnum(2) + params
        val params = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        // Server name pointer (referent)
        params.putInt(0x00020000)
        // Server name conformant array
        params.putInt(1)   // max count
        params.putInt(0)   // offset
        params.putInt(1)   // actual count
        // Server name "\\" (UTF-16LE) + null + pad
        params.put('\\'.code.toByte()); params.put(0)
        params.put('\\'.code.toByte()); params.put(0)
        params.put(0); params.put(0)
        // Level: 1
        params.putInt(1)
        // InfoStruct: NULL
        params.putInt(0)
        // PreferedMaximumLength
        params.putInt(0xFFFFFFFF.toInt())
        // ResumeHandle: NULL
        params.putInt(0)

        val paramBytes = ByteArray(params.position())
        System.arraycopy(params.array(), 0, paramBytes, 0, paramBytes.size)

        // Request PDU header: alloc_hint(4) + context_id(2) + opnum(2) = 8 bytes
        val reqHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        reqHeader.putInt(paramBytes.size)   // alloc hint
        reqHeader.putShort(0.toShort())               // context id
        reqHeader.putShort(15.toShort())              // opnum: NetShareEnumAll

        val bodyBytes = reqHeader.array() + paramBytes
        return buildDcerpcHeader(0x00, bodyBytes.size, 2) + bodyBytes
    }

    private fun buildDcerpcHeader(ptype: Int, bodyLen: Int, callId: Int): ByteArray {
        val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        h.put(5); h.put(0)        // version 5.0
        h.put(ptype.toByte())     // PDU type
        h.put(0x03)               // flags: first+last fragment
        h.put(0x10); h.put(0); h.put(0); h.put(0)  // data rep
        h.putShort((bodyLen + 16).toShort())   // frag length
        h.putShort(0.toShort())             // auth length
        h.putInt(callId)          // call ID
        return h.array()
    }

    private fun parseShareEnumResponse(data: ByteArray): List<ShareInfo> {
        val shares = mutableListOf<ShareInfo>()
        try {
            if (data.size < 24) return shares
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(16)  // skip DCERPC header
            // Response PDU: alloc_hint(4) + context_id(2) + opnum(2)
            buf.int; buf.short; buf.short

            // NetShareEnumAll return: win32 error (4 bytes)
            buf.int

            // Level (4 bytes)
            val level = buf.int

            // Pointer to ShareInfo (4 bytes referent)
            buf.int

            // Conformant array: max count (4), offset (4), actual count (4)
            val count = buf.int
            buf.int  // offset
            val actualCount = buf.int

            val numShares = minOf(actualCount, count)
            if (numShares <= 0 || numShares > 100) return shares

            // Pointer to share entries (4 bytes)
            buf.int

            // Read ShareInfo1 entries
            // Each: netname ptr(4) + type(4) + remark ptr(4) + permissions(4) + max_uses(4)
            val entries = mutableListOf<Triple<Int, Int, Int>>()
            for (i in 0 until numShares) {
                entries.add(Triple(buf.int, buf.int, buf.int))
                buf.int  // permissions
                buf.int  // max_uses
            }

            for ((_, shareType, _) in entries) {
                val name = readNdrString(buf)
                val comment = readNdrString(buf)
                if (name.isNotEmpty()) {
                    shares.add(ShareInfo(name, shareType, comment))
                }
            }
        } catch (_: Exception) {}
        return shares
    }

    private fun readNdrString(buf: ByteBuffer): String {
        val maxCount = buf.int
        buf.int  // offset
        val actualCount = buf.int
        if (actualCount <= 0 || actualCount > 256) return ""

        val sb = StringBuilder()
        for (i in 0 until actualCount) {
            val c = buf.short.toInt() and 0xFFFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        // 4-byte alignment
        val padding = (4 - ((actualCount * 2) % 4)) % 4
        if (padding > 0) buf.position(buf.position() + padding)
        return sb.toString()
    }
}
