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
 *
 * If DCERPC Bind fails (BindNak/Fault — common on cheap routers that don't support DCERPC),
 * falls back to trying common share names + the server name from SMB2 negotiation.
 */
object SmbShareLister {

    data class ShareInfo(
        val name: String,
        val type: Int,
        val comment: String
    )

    private val logFile = java.io.File("/storage/emulated/0/Download/smb_debug.log")

    private fun log(msg: String) {
        try {
            android.util.Log.i("SmbShareLister", msg)
            logFile.parentFile?.mkdirs()
            logFile.appendText("[${System.currentTimeMillis()}] $msg\n")
        } catch (_: Exception) {}
    }

    private fun logHex(label: String, data: ByteArray) {
        try {
            val hex = StringBuilder()
            for (i in data.indices) {
                if (i > 0) hex.append(' ')
                hex.append(String.format("%02x", data[i]))
                if (i >= 63) {
                    hex.append(" ... (${data.size} bytes total)")
                    break
                }
            }
            log("$label (${data.size} bytes): $hex")
        } catch (_: Exception) {}
    }

    fun listShares(
        host: String,
        port: Int = 445,
        username: String,
        password: String
    ): List<ShareInfo> {
        log("======== listShares host=$host port=$port user='$username' ========")

        // 1. Try DCERPC via smbj (SMB2) — fastest if server supports it
        val result = tryDcerpc(host, port, username, password)
        if (result != null) {
            log("DCERPC succeeded: ${result.size} shares")
            return result.filter { it.name != "IPC$" }
        }

        // 2. Try SMB1 RAP via jcifs — works on most routers even without DCERPC
        val jcifsResult = JcifsShareLister.listShares(host, port, username, password)
        if (jcifsResult != null) {
            log("jcifs (SMB1 RAP) succeeded: ${jcifsResult.size} shares")
            return jcifsResult.map { ShareInfo(it.name, it.type, it.comment) }
                .filter { it.name != "IPC$" }
        }

        // 3. All auto-discovery failed — return empty, user can manually input share name
        log("All auto-discovery methods failed, returning empty list")
        return emptyList()
    }

    /**
     * Try DCERPC Bind + NetShareEnumAll via pipe.transact().
     * Returns null on failure (BindNak, Fault, or exception).
     */
    private fun tryDcerpc(
        host: String, port: Int, username: String, password: String
    ): List<ShareInfo>? {
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()

        val client = SMBClient(config)
        try {
            log("Connecting to $host:$port")
            val connection = client.connect(host, port)
            log("Connected, authenticating...")
            val auth = if (username.isBlank() && password.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            val session = connection.authenticate(auth)
            log("Authenticated OK")

            val pipeShare = session.connectShare("IPC$") as PipeShare
            log("Connected to IPC$")

            val pipe = pipeShare.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                emptySet(),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                emptySet()
            )
            log("Opened srvsvc pipe")

            try {
                // === DCERPC Bind ===
                val bindReq = buildBindRequest()
                logHex("Bind request", bindReq)
                log("Sending Bind (${bindReq.size} bytes)")

                val bindResp = pipe.transact(bindReq)

                if (bindResp.size < 16) {
                    log("Bind response too short (${bindResp.size} bytes), aborting")
                    return null
                }
                log("Bind response: ${bindResp.size} bytes")
                logHex("Bind response", bindResp)

                val bindType = bindResp[2].toInt() and 0xFF
                val bindFlags = bindResp[3].toInt() and 0xFF
                log("Bind type=0x${String.format("%02x", bindType)} flags=0x${String.format("%02x", bindFlags)}")

                when (bindType) {
                    0x0C -> {
                        // BindAck — check if interface was accepted
                        log("BindAck received!")
                        // Check results list (at end of BindAck)
                        if (bindResp.size >= 68) {
                            val lastResult = bindResp[bindResp.size - 4].toInt() and 0xFF
                            log("Bind result byte: $lastResult (0=accept, 2=provider_rejection, 3=not_supported)")
                            if (lastResult != 0) {
                                log("Interface not accepted by server")
                                return null
                            }
                        }
                    }
                    0x0D -> {
                        // BindNak
                        val rejectReason = if (bindResp.size >= 18) {
                            (bindResp[16].toInt() and 0xFF) or ((bindResp[17].toInt() and 0xFF) shl 8)
                        } else -1
                        log("BindNak! reject_reason=$rejectReason")
                        // 0=reason_not_specified, 1=temporary_congestion, 2=local_limit_exceeded,
                        // 3=called_psmid_unknown, 4=protocol_version_not_supported
                        return null
                    }
                    0x03 -> {
                        // Fault
                        val status = if (bindResp.size >= 28) {
                            ByteBuffer.wrap(bindResp, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        } else 0
                        log("Fault! status=0x${String.format("%08x", status)}")
                        return null
                    }
                    else -> {
                        log("Unknown bind response type: 0x${String.format("%02x", bindType)}")
                        return null
                    }
                }

                // === NetShareEnumAll ===
                val enumReq = buildNetShareEnumAllRequest(host)
                log("Sending NetShareEnumAll (${enumReq.size} bytes)")

                val enumResp = pipe.transact(enumReq)

                if (enumResp.size < 16) {
                    log("EnumAll response too short (${enumResp.size} bytes)")
                    return null
                }
                log("EnumAll response: ${enumResp.size} bytes")
                logHex("EnumAll response", enumResp)

                val shares = parseShareEnumResponse(enumResp)
                log("Parsed ${shares.size} shares: ${shares.joinToString { it.name }}")
                return shares

            } finally {
                try { pipe.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log("DCERPC FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun buildBindRequest(): ByteArray {
        val body = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(4280.toShort())   // max transmit fragment
        body.putShort(4280.toShort())   // max receive fragment
        body.putInt(0)        // assoc group ID
        // Pctx: NDR transfer syntax
        body.put(0x01.toByte()) // context elements count (1, NOT 5!)
        body.put(0x00.toByte()) // reserved
        body.putShort(0)       // reserved
        body.putShort(0)       // context ID 0
        body.putShort(1)       // num transfer syntaxes
        // Interface: SRVSVC UUID
        body.put(0xc8.toByte()); body.put(0x4f.toByte()); body.put(0x32.toByte()); body.put(0x4b.toByte())
        body.put(0x70.toByte()); body.put(0x16.toByte())
        body.put(0xd3.toByte()); body.put(0x01.toByte())
        body.put(0x12.toByte()); body.put(0x78.toByte())
        body.put(0x5a.toByte()); body.put(0x47.toByte()); body.put(0xbf.toByte()); body.put(0x6e.toByte()); body.put(0xe1.toByte()); body.put(0x88.toByte())
        body.putInt(3)       // interface version
        // NDR transfer syntax UUID
        body.put(0x04.toByte()); body.put(0x5d.toByte()); body.put(0x88.toByte()); body.put(0x8a.toByte())
        body.put(0xeb.toByte()); body.put(0x1c.toByte())
        body.put(0x6c.toByte()); body.put(0x10.toByte())
        body.put(0x92.toByte()); body.put(0x1f.toByte())
        body.put(0x00.toByte()); body.put(0xc0.toByte()); body.put(0x4f.toByte()); body.put(0xc2.toByte()); body.put(0xc6.toByte()); body.put(0xf3.toByte())
        body.putInt(2)       // NDR version

        val header = buildDcerpcHeader(body.array().size, 0x0B) // 0x0B = Bind
        val result = ByteArray(header.size + body.array().size)
        System.arraycopy(header, 0, result, 0, header.size)
        System.arraycopy(body.array(), 0, result, header.size, body.array().size)
        return result
    }

    private fun buildNetShareEnumAllRequest(host: String): ByteArray {
        // NetShareEnumAll (opnum 15) request
        val serverName = "\\\\${host}"
        val serverChars = serverName.length
        // NDR: pointer(4) + max_count(4) + offset(4) + actual_count(4) + string(actual_count*2)
        val serverNameBytes = (4 + 4 + 4 + 4 + serverChars * 2 + padding(serverChars * 2))

        val body = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        // ServerName pointer (non-null)
        body.putInt(0x00020000) // unique pointer referent ID
        // Conformant string header
        body.putInt(serverChars)   // max count
        body.putInt(0)              // offset
        body.putInt(serverChars)    // actual count
        // Server name string (UTF-16LE)
        for (c in serverName) {
            body.putShort(c.code.toShort())
        }
        // 4-byte alignment padding
        val pad = (4 - ((serverChars * 2) % 4)) % 4
        for (i in 0 until pad) body.put(0)

        // Level = 1 (SHARE_INFO_1)
        body.putInt(1)

        body.flip()
        val bodyData = ByteArray(body.limit())
        body.get(bodyData)

        val header = buildDcerpcHeader(bodyData.size, 0x00) // 0x00 = Request
        val result = ByteArray(header.size + bodyData.size)
        System.arraycopy(header, 0, result, 0, header.size)
        System.arraycopy(bodyData, 0, result, header.size, bodyData.size)
        return result
    }

    private fun padding(dataSize: Int): Int {
        return (4 - (dataSize % 4)) % 4
    }

    private var callId = 1

    private fun buildDcerpcHeader(bodyLen: Int, pduType: Int): ByteArray {
        val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        h.put(5)           // rpc_vers
        h.put(0)           // rpc_vers_minor
        h.put(pduType.toByte()) // PDU type
        h.put(0x03.toByte())    // pfc_flags (first+last frag)
        // Data representation (8 bytes): little-endian, ASCII, IEEE
        h.put(0x10.toByte()) // integer rep: little-endian
        h.put(0x00.toByte()) // char rep: ASCII
        h.put(0x00.toByte()) // float rep: IEEE
        h.put(0x00.toByte()) // reserved
        // frag_length (2 bytes) — set later
        val totalLen = 16 + bodyLen
        h.putShort(totalLen.toShort())
        h.putShort(0)       // auth_length
        h.putInt(callId)     // call ID
        callId++
        return h.array()
    }

    private fun parseShareEnumResponse(data: ByteArray): List<ShareInfo> {
        val shares = mutableListOf<ShareInfo>()
        try {
            if (data.size < 24) {
                log("parseShareEnumResponse: data too short (${data.size} bytes)")
                return shares
            }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(16)  // skip DCERPC header
            // Response PDU: alloc_hint(4) + context_id(2) + opnum(2)
            buf.int; buf.short; buf.short

            // NetShareEnumAll return: win32 error (4 bytes)
            val win32Error = buf.int
            log("NetShareEnumAll win32_error=0x${String.format("%08x", win32Error)}")
            if (win32Error != 0) {
                log("NetShareEnumAll returned non-zero error, no shares")
                return shares
            }

            // Level (4 bytes)
            val level = buf.int
            log("Level=$level")

            // SHARE_ENUM_UNION unique pointer referent_id (4 bytes)
            buf.int

            // TotalEntries (4 bytes)
            val totalEntries = buf.int
            log("TotalEntries=$totalEntries")

            // ResumeHandle unique pointer referent_id (4 bytes, 0 = NULL)
            buf.int

            // Deferred SHARE_ENUM_UNION data:
            // switch_tag (4 bytes, should = Level)
            val switchTag = buf.int
            log("switch_tag=$switchTag")

            // Buf1 unique pointer referent_id (4 bytes)
            buf.int

            // Conformant array: max_count(4), offset(4), actual_count(4)
            val count = buf.int
            buf.int  // offset
            val actualCount = buf.int
            log("Array: max_count=$count actual_count=$actualCount")

            val numShares = minOf(actualCount, count)
            if (numShares <= 0 || numShares > 100) {
                log("numShares=$numShares out of range, returning empty")
                return shares
            }

            // Read ShareInfo1 entries
            // Each SHARE_INFO_1 = 12 bytes: netname_ptr(4) + type(4) + remark_ptr(4)
            val entries = mutableListOf<Pair<Int, Int>>()  // (netname_ptr, type)
            for (i in 0 until numShares) {
                val netnamePtr = buf.int
                val shareType = buf.int
                val remarkPtr = buf.int
                entries.add(Pair(netnamePtr, shareType))
                log("Entry[$i]: netname_ptr=0x${String.format("%08x", netnamePtr)} type=0x${String.format("%08x", shareType)} remark_ptr=0x${String.format("%08x", remarkPtr)}")
            }

            // Read deferred string data (netname + remark for each entry, in order)
            for ((index, _) in entries.withIndex()) {
                val name = readNdrString(buf)
                val comment = readNdrString(buf)
                log("String[$index]: name='$name' comment='$comment'")
                if (name.isNotEmpty()) {
                    shares.add(ShareInfo(name, entries[index].second, comment))
                }
            }
        } catch (e: Exception) {
            log("parseShareEnumResponse EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        }
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
