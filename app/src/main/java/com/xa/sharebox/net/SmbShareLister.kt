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
 * Tries two transport methods:
 *  1. pipe.transact() — FSCTL_PIPE_TRANSCEIVE (single IOCTL write+read)
 *  2. pipe.write() + pipe.read() — separate SMB2 WRITE + READ
 *
 * Some servers (especially cheap routers) don't properly support FSCTL_PIPE_TRANSCEIVE
 * but do support separate write/read.
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

        // Try approach 1: DCERPC via transact (FSCTL_PIPE_TRANSCEIVE)
        val result1 = tryDcerpc(host, port, username, password, useTransact = true)
        if (result1 != null) {
            log("DCERPC via transact succeeded: ${result1.size} shares")
            return result1.filter { it.name != "IPC$" }
        }

        // Try approach 2: DCERPC via write+read (separate SMB2 WRITE + READ)
        val result2 = tryDcerpc(host, port, username, password, useTransact = false)
        if (result2 != null) {
            log("DCERPC via write+read succeeded: ${result2.size} shares")
            return result2.filter { it.name != "IPC$" }
        }

        // Fallback: try common share names + server name
        return tryConnectCommonShares(host, port, username, password)
    }

    /**
     * Try DCERPC Bind + NetShareEnumAll using the specified transport method.
     * Returns null on failure, or the list of shares on success.
     */
    private fun tryDcerpc(
        host: String, port: Int, username: String, password: String,
        useTransact: Boolean
    ): List<ShareInfo>? {
        val method = if (useTransact) "transact" else "write+read"
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()

        val client = SMBClient(config)
        try {
            log("[$method] Connecting to $host:$port")
            val connection = client.connect(host, port)
            log("[$method] Connected, authenticating...")
            val auth = if (username.isBlank() && password.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            val session = connection.authenticate(auth)
            log("[$method] Authenticated OK")

            val pipeShare = session.connectShare("IPC$") as PipeShare
            log("[$method] Connected to IPC$")

            val pipe = pipeShare.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                emptySet(),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                emptySet()
            )
            log("[$method] Opened srvsvc pipe")

            try {
                // === DCERPC Bind ===
                val bindReq = buildBindRequest()
                logHex("[$method] Bind request", bindReq)
                log("[$method] Sending Bind (${bindReq.size} bytes)")

                val bindResp = if (useTransact) {
                    pipe.transact(bindReq)
                } else {
                    pipeWriteRead(pipe, bindReq)
                }

                if (bindResp == null) {
                    log("[$method] Bind response: null")
                    return null
                }
                log("[$method] Bind response: ${bindResp.size} bytes")
                logHex("[$method] Bind response", bindResp)

                if (bindResp.size < 16) {
                    log("[$method] Bind response too short, aborting")
                    return null
                }

                val bindType = bindResp[2].toInt() and 0xFF
                val bindFlags = bindResp[3].toInt() and 0xFF
                log("[$method] Bind type=0x${String.format("%02x", bindType)} flags=0x${String.format("%02x", bindFlags)}")

                when (bindType) {
                    0x0C -> {
                        // BindAck — check if interface was accepted
                        log("[$method] BindAck received!")
                        // Check results list (at end of BindAck)
                        // Parse: max_xmit(2) + max_recv(2) + assoc_group(4) + sec_addr_len(2) + sec_addr + pad + num_results(4) + results[24]
                        // For simplicity, just check the last 4 bytes for the result
                        if (bindResp.size >= 68) {
                            val lastResult = bindResp[bindResp.size - 4].toInt() and 0xFF
                            log("[$method] Bind result byte: $lastResult (0=accept, 2=provider_rejection, 3=not_supported)")
                            if (lastResult != 0) {
                                log("[$method] Interface not accepted by server")
                                return null
                            }
                        }
                    }
                    0x0D -> {
                        // BindNak
                        val rejectReason = if (bindResp.size >= 18) {
                            (bindResp[16].toInt() and 0xFF) or ((bindResp[17].toInt() and 0xFF) shl 8)
                        } else -1
                        log("[$method] BindNak! reject_reason=$rejectReason")
                        // 0=reason_not_specified, 1=temporary_congestion, 2=local_limit_exceeded,
                        // 3=called_psmid_unknown, 4=protocol_version_not_supported
                        return null
                    }
                    0x03 -> {
                        // Fault
                        val status = if (bindResp.size >= 28) {
                            ByteBuffer.wrap(bindResp, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        } else 0
                        log("[$method] Fault! status=0x${String.format("%08x", status)}")
                        return null
                    }
                    else -> {
                        log("[$method] Unknown bind response type: 0x${String.format("%02x", bindType)}")
                        return null
                    }
                }

                // === NetShareEnumAll ===
                val enumReq = buildNetShareEnumAllRequest(host)
                log("[$method] Sending NetShareEnumAll (${enumReq.size} bytes)")

                val enumResp = if (useTransact) {
                    pipe.transact(enumReq)
                } else {
                    pipeWriteRead(pipe, enumReq)
                }

                if (enumResp == null || enumResp.size < 16) {
                    log("[$method] EnumAll response too short (${enumResp?.size ?: 0} bytes)")
                    return null
                }
                log("[$method] EnumAll response: ${enumResp.size} bytes")
                logHex("[$method] EnumAll response", enumResp)

                val shares = parseShareEnumResponse(enumResp)
                log("[$method] Parsed ${shares.size} shares: ${shares.joinToString { it.name }}")
                return shares

            } finally {
                try { pipe.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log("[$method] DCERPC FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Alternative to pipe.transact(): write data via SMB2 WRITE, then read response via SMB2 READ.
     * Some servers don't properly support FSCTL_PIPE_TRANSCEIVE but do support separate write/read.
     *
     * Reads the DCERPC header first (16 bytes) to determine frag_length, then reads the full PDU.
     */
    private fun pipeWriteRead(pipe: PipeShare.PipeHandle, data: ByteArray): ByteArray? {
        try {
            pipe.write(data, 0, data.size)
            log("[write+read] Wrote ${data.size} bytes, reading response...")

            // Read DCERPC header (16 bytes)
            val header = ByteArray(16)
            var totalRead = 0
            while (totalRead < 16) {
                val n = pipe.read(header, totalRead, 16 - totalRead)
                if (n <= 0) {
                    if (totalRead == 0) {
                        log("[write+read] No response data")
                        return null
                    }
                    break
                }
                totalRead += n
            }

            if (totalRead < 16) {
                log("[write+read] Only got $totalRead bytes of header")
                return header.copyOfRange(0, totalRead)
            }

            // Parse frag_length (bytes 8-9, little-endian)
            val fragLen = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
            log("[write+read] frag_length=$fragLen")

            if (fragLen <= 16) {
                return header
            }

            // Read the rest of the PDU
            val full = ByteArray(fragLen)
            System.arraycopy(header, 0, full, 0, 16)
            var remaining = fragLen - 16
            var offset = 16
            while (remaining > 0) {
                val n = pipe.read(full, offset, remaining)
                if (n <= 0) break
                offset += n
                remaining -= n
            }
            log("[write+read] Total read: ${fragLen - remaining} / $fragLen bytes")
            return full

        } catch (e: Exception) {
            log("[write+read] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /**
     * Fallback: try to tree-connect to common share names.
     * Also tries the server name from SMB2 negotiation as a share name.
     */
    private fun tryConnectCommonShares(
        host: String, port: Int, username: String, password: String
    ): List<ShareInfo> {
        log("Fallback: trying common share names")
        val config = SmbConfig.builder()
            .withTimeout(5, TimeUnit.SECONDS)
            .withSoTimeout(5, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        val shares = mutableListOf<ShareInfo>()
        try {
            val connection = client.connect(host, port)
            val auth = if (username.isBlank() && password.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            val session = connection.authenticate(auth)

            // Try to get server name from SMB2 negotiation
            var serverName: String? = null
            try {
                val m = connection.javaClass.getMethod("getRemoteServerName")
                serverName = m.invoke(connection) as? String
                log("Server name from negotiation: '$serverName'")
            } catch (e: Exception) {
                try {
                    val m = connection.javaClass.getMethod("getServerName")
                    serverName = m.invoke(connection) as? String
                    log("Server name: '$serverName'")
                } catch (e2: Exception) {
                    log("Could not get server name from connection")
                }
            }

            // Build candidate list
            val candidates = mutableListOf(
                "C$", "D$", "E$", "F$", "Public", "Share", "Shares",
                "Shared", "Data", "Files", "Documents", "Media", "Home", "Homes",
                "ADMIN$", "print$",
                // Router/NAS specific
                "sda", "sda1", "sdb", "sdb1", "usb", "usb1", "usbshare1", "sdcard",
                "nfs", "ftp", "download", "cloud",
                // Common NAS names
                "volume1", "volume2", "DataVolume", "Storage",
                "photo", "video", "music", "backup"
            )

            // Add server name as share name candidate (high priority)
            if (!serverName.isNullOrBlank()) {
                candidates.add(0, serverName!!)
                val noSpace = serverName!!.replace(" ", "")
                if (noSpace != serverName) {
                    candidates.add(1, noSpace)
                }
            }

            for (name in candidates) {
                try {
                    val share = session.connectShare(name)
                    shares.add(ShareInfo(name, 0, ""))
                    log("Fallback found: $name")
                    share.close()
                } catch (e: Exception) {
                    // Share doesn't exist or access denied — skip
                }
            }
            session.close()
            connection.close()
        } catch (e: Exception) {
            log("Fallback FAILED: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
        return shares
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
        // NDR: time_low(4B LE) + time_mid(2B LE) + time_hi(2B LE) + clock_seq(2B BE) + node(6B BE)
        body.put(0xc8.toByte()); body.put(0x4f.toByte()); body.put(0x32.toByte()); body.put(0x4b.toByte())  // 4b324fc8 LE
        body.put(0x70.toByte()); body.put(0x16.toByte())  // 1670 LE
        body.put(0xd3.toByte()); body.put(0x01.toByte())  // 01d3 LE
        body.put(0x12.toByte()); body.put(0x78.toByte())  // 1278 BE
        body.put(0x5a.toByte()); body.put(0x47.toByte()); body.put(0xbf.toByte()); body.put(0x6e.toByte()); body.put(0xe1.toByte()); body.put(0x88.toByte())  // node BE
        body.putShort(3.toShort()); body.putShort(0.toShort())   // interface version 3.0
        // NDR transfer syntax: 8a885d04-1ceb-106c-921f-00c04fc2c6f3
        body.put(0x04.toByte()); body.put(0x5d.toByte()); body.put(0x88.toByte()); body.put(0x8a.toByte())  // 8a885d04 LE
        body.put(0xeb.toByte()); body.put(0x1c.toByte())  // 1ceb LE
        body.put(0x6c.toByte()); body.put(0x10.toByte())  // 106c LE
        body.put(0x92.toByte()); body.put(0x1f.toByte())  // 921f BE
        body.put(0x00.toByte()); body.put(0xc0.toByte()); body.put(0x4f.toByte()); body.put(0xc2.toByte()); body.put(0xc6.toByte()); body.put(0xf3.toByte())  // node BE
        body.putShort(2.toShort()); body.putShort(0.toShort())   // transfer syntax version 2.0

        val bodyBytes = body.array()
        return buildDcerpcHeader(0x0B, bodyBytes.size, 1) + bodyBytes
    }

    private fun buildNetShareEnumAllRequest(host: String): ByteArray {
        // NetShareEnumAll (opnum 15) request body (after DCERPC header + request PDU header):
        //
        // Parameters (NDR encoded):
        // 1. ServerName: [in, string] unique pointer -> conformant string
        //    - referent_id (4 bytes, non-zero)
        //    - conformant string: max_count, offset, actual_count, chars (UTF-16LE) + null + padding
        // 2. InfoStruct: [in, out] reference pointer -> SHARE_ENUM struct
        //    - referent_id (4 bytes, non-zero)
        //    - Level: DWORD = 1
        //    - SHARE_ENUM_UNION: case 1 -> LPSHARE_INFO_1 Buf1 pointer = NULL
        // 3. PreferedMaximumLength: DWORD = 0xFFFFFFFF
        // 4. ResumeHandle: [in, out, unique] pointer = NULL

        // Server name: \\host\0 in UTF-16LE
        val nameStr = "\\\\$host\u0000"
        val nameBytes = nameStr.toByteArray(Charsets.UTF_16LE)
        val nameChars = nameBytes.size / 2  // including null terminator
        val namePad = (4 - (nameBytes.size % 4)) % 4

        // Build the body in a ByteArrayOutputStream
        val body = java.io.ByteArrayOutputStream()

        // 1. ServerName unique pointer
        writeIntLE(body, 0x00020000)  // referent_id (non-NULL)

        // 1a. Conformant string
        writeIntLE(body, nameChars)   // max_count (chars including null)
        writeIntLE(body, 0)          // offset
        writeIntLE(body, nameChars)  // actual_count (chars including null)
        body.write(nameBytes)        // string data including null
        for (i in 0 until namePad) body.write(0)  // padding to 4-byte boundary

        // 2. InfoStruct reference pointer (non-NULL for [in, out])
        writeIntLE(body, 0x00020004)  // referent_id

        // 2a. SHARE_ENUM struct
        writeIntLE(body, 1)  // Level = 1

        // 2b. SHARE_ENUM_UNION: case 1 -> LPSHARE_INFO_1 Buf1
        writeIntLE(body, 0)  // Buf1 pointer = NULL (server allocates)

        // 3. PreferedMaximumLength
        writeIntLE(body, -1)  // MAXDWORD = 0xFFFFFFFF

        // 4. ResumeHandle unique pointer = NULL
        writeIntLE(body, 0)

        val paramBytes = body.toByteArray()

        // DCERPC Request PDU: alloc_hint(4) + context_id(2) + opnum(2) + params
        val reqBody = java.io.ByteArrayOutputStream()
        writeIntLE(reqBody, paramBytes.size)  // alloc hint
        writeShortLE(reqBody, 0)              // context id
        writeShortLE(reqBody, 15)             // opnum: NetShareEnumAll
        reqBody.write(paramBytes)

        val bodyBytes = reqBody.toByteArray()
        return buildDcerpcHeader(0x00, bodyBytes.size, 2) + bodyBytes
    }

    private fun writeIntLE(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
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
