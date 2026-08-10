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

        // Try DCERPC via transact (FSCTL_PIPE_TRANSCEIVE)
        val result = tryDcerpc(host, port, username, password)
        if (result != null) {
            log("DCERPC succeeded: ${result.size} shares")
            return result.filter { it.name != "IPC$" }
        }

        // Try RAP (old SMB1 protocol via \PIPE\LANMAN)
        val rapResult = tryRap(host, port, username, password)
        if (rapResult != null) {
            log("RAP succeeded: ${rapResult.size} shares")
            return rapResult.filter { it.name != "IPC$" }
        }

        // Fallback: try common share names + NBNS
        return tryConnectCommonShares(host, port, username, password)
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

    /**
     * Try RAP (Remote Access Protocol) NetShareEnum via \PIPE\LANMAN.
     * This is the old SMB1 RAP protocol, much simpler than DCERPC.
     * Many cheap routers support RAP even when DCERPC Bind is rejected.
     */
    private fun tryRap(
        host: String, port: Int, username: String, password: String
    ): List<ShareInfo>? {
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        try {
            log("[RAP] Connecting to $host:$port")
            val connection = client.connect(host, port)
            val auth = if (username.isBlank() && password.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            val session = connection.authenticate(auth)
            log("[RAP] Authenticated OK")

            val pipeShare = session.connectShare("IPC$") as PipeShare
            log("[RAP] Connected to IPC$")

            val pipe = pipeShare.open(
                "LANMAN",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                emptySet(),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                emptySet()
            )
            log("[RAP] Opened LANMAN pipe")

            try {
                // Build RAP NetShareEnum request
                // Format: function_code(2) + param_desc + data_desc + detail_level(2) + buf_size(2)
                val paramDesc = "WrLeh\u0000"  // 6 bytes
                val dataDesc = "B13BWz\u0000"  // 7 bytes
                val req = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                req.putShort(0)  // function code: 0 = NetShareEnum
                req.put(paramDesc.toByteArray(Charsets.US_ASCII))
                req.put(dataDesc.toByteArray(Charsets.US_ASCII))
                req.putShort(1)  // detail level: 1
                req.putShort(65535)  // receive buffer size

                val reqBytes = req.array()
                log("[RAP] Sending RAP request (${reqBytes.size} bytes)")
                logHex("[RAP] Request", reqBytes)

                val resp = pipe.transact(reqBytes)
                log("[RAP] Response: ${resp.size} bytes")
                logHex("[RAP] Response", resp)

                if (resp.size < 8) {
                    log("[RAP] Response too short (${resp.size} bytes)")
                    return null
                }

                // Parse RAP response header
                val buf = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                val status = buf.short.toInt() and 0xFFFF
                val convert = buf.short.toInt() and 0xFFFF
                val count = buf.short.toInt() and 0xFFFF
                val available = buf.short.toInt() and 0xFFFF
                log("[RAP] status=$status convert=$convert count=$count available=$available")

                if (status != 0) {
                    log("[RAP] RAP failed with status=$status")
                    return null
                }

                val shares = mutableListOf<ShareInfo>()
                // RAP SHARE_INFO_1: 20 bytes per entry
                // - netname: 13 bytes (ASCII, null-padded)
                // - pad: 1 byte
                // - type: 2 bytes (LE)
                // - remark: 4 bytes (DWORD offset into data area)
                for (i in 0 until count) {
                    if (buf.remaining() < 20) break
                    val nameBytes = ByteArray(13)
                    buf.get(nameBytes)
                    val name = String(nameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()
                    buf.get()  // pad
                    val shareType = buf.short.toInt() and 0xFFFF
                    val remarkOffset = buf.int
                    log("[RAP] Share[$i]: name='$name' type=$shareType remark_offset=$remarkOffset")
                    if (name.isNotEmpty()) {
                        shares.add(ShareInfo(name, shareType, ""))
                    }
                }

                log("[RAP] Parsed ${shares.size} shares: ${shares.joinToString { it.name }}")
                return shares
            } finally {
                try { pipe.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log("[RAP] FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Fallback: try to tree-connect to common share names.
     * Also tries the server name discovered via NetBIOS Name Service (NBNS, UDP 137).
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

            // Try to get server name via NBNS (NetBIOS Name Service, UDP 137)
            val nbnsNames = queryNetbiosNames(host)
            log("NBNS discovered names: $nbnsNames")

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

            // Add NBNS-discovered names as high-priority share name candidates
            for (name in nbnsNames) {
                if (name.isNotBlank() && name != "WORKGROUP" && name != "MSBROWSE") {
                    candidates.add(0, name)
                    // Also try without spaces
                    val noSpace = name.replace(" ", "")
                    if (noSpace != name) {
                        candidates.add(1, noSpace)
                    }
                }
            }

            log("Trying ${candidates.size} share name candidates: $candidates")

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

    /**
     * Query NetBIOS Name Service (NBNS, UDP port 137) to discover server names.
     * Returns a list of unique NetBIOS names registered by the host.
     */
    private fun queryNetbiosNames(host: String): List<String> {
        val names = mutableListOf<String>()
        val socket = java.net.DatagramSocket()
        try {
            socket.soTimeout = 2000

            // Build NBNS Node Status Request (wildcard "*")
            // Query name: "*" padded to 15 chars + null suffix = 16 chars
            // NetBIOS encoding: each nibble + 'A' → 2 chars per byte
            val nameBytes = ByteArray(32)
            val rawName = ByteArray(16)
            rawName[0] = '*'.code.toByte()  // wildcard
            for (i in 1..14) rawName[i] = ' '.code.toByte()  // pad with spaces
            rawName[15] = 0  // suffix = 0
            for (i in 0..15) {
                val hi = (rawName[i].toInt() ushr 4) and 0x0F
                val lo = rawName[i].toInt() and 0x0F
                nameBytes[i * 2] = (hi + 'A'.code).toByte()
                nameBytes[i * 2 + 1] = (lo + 'A'.code).toByte()
            }

            // Build packet: header(12) + name_length(1) + name(32) + null(1) + type(2) + class(2)
            val packet = ByteArray(12 + 1 + 32 + 1 + 2 + 2)
            packet[0] = 0x00; packet[1] = 0x01  // Transaction ID = 1
            packet[2] = 0x00; packet[3] = 0x00  // Flags: standard query
            packet[4] = 0x00; packet[5] = 0x01  // Questions = 1
            packet[6] = 0x00; packet[7] = 0x00  // Answer RRs
            packet[8] = 0x00; packet[9] = 0x00  // Authority RRs
            packet[10] = 0x00; packet[11] = 0x00  // Additional RRs
            packet[12] = 0x20  // Name length = 32
            System.arraycopy(nameBytes, 0, packet, 13, 32)
            packet[45] = 0x00  // Null terminator
            packet[46] = 0x00; packet[47] = 0x21  // Type = NBSTAT (33)
            packet[48] = 0x00; packet[49] = 0x01  // Class = IN

            socket.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName(host), 137))

            val response = ByteArray(1024)
            val recv = java.net.DatagramPacket(response, response.size)
            socket.receive(recv)
            val len = recv.length

            // Parse response header (12 bytes)
            if (len < 12) return names

            // Skip header + question section to find answer section
            // After header(12), question is: name_len(1) + name(32) + null(1) + type(2) + class(2) = 38
            var pos = 12
            // Skip question name
            if (pos < len && response[pos].toInt() == 0x20) {
                pos += 1 + 32 + 1 + 2 + 2  // name_len + name + null + type + class
            } else {
                // Use a more robust skip: skip until we find the answer
                pos = 12
                while (pos < len) {
                    val b = response[pos].toInt() and 0xFF
                    if (b == 0) { pos++; break }
                    if (b and 0xC0 == 0xC0) { pos += 2; break }
                    pos += 1 + b
                }
                // Skip type and class
                pos += 4
            }

            // Answer section: name (compressed or full) + type(2) + class(2) + ttl(4) + rdlength(2) + rdata
            // Skip answer name (usually compressed: 2 bytes 0xC0 xx)
            if (pos < len && (response[pos].toInt() and 0xC0) == 0xC0) {
                pos += 2
            } else {
                // Full name
                while (pos < len) {
                    val b = response[pos].toInt() and 0xFF
                    if (b == 0) { pos++; break }
                    if (b and 0xC0 == 0xC0) { pos += 2; break }
                    pos += 1 + b
                }
            }

            // type(2) + class(2) + ttl(4) + rdlength(2)
            pos += 2 + 2 + 4
            if (pos + 2 > len) return names
            val rdLength = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            pos += 2

            if (pos + rdLength > len) return names

            // RDATA: num_names(1) + entries (18 bytes each)
            val numNames = response[pos].toInt() and 0xFF
            pos += 1
            log("NBNS: $numNames names in response")

            for (i in 0 until numNames) {
                if (pos + 18 > len) break
                // Name: 15 chars + 1 suffix byte
                val nameBytes2 = response.copyOfRange(pos, pos + 15)
                val name = String(nameBytes2, Charsets.US_ASCII).trim()
                val suffix = response[pos + 15].toInt() and 0xFF
                val flags = ((response[pos + 16].toInt() and 0xFF) shl 8) or (response[pos + 17].toInt() and 0xFF)
                pos += 18
                log("NBNS name[$i]: '$name' suffix=0x${String.format("%02x", suffix)} flags=0x${String.format("%04x", flags)}")
                // Type 0x00 = workstation name, 0x20 = file server service
                if (suffix == 0x00 || suffix == 0x20) {
                    if (name.isNotBlank() && !names.contains(name)) {
                        names.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            log("NBNS query failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            socket.close()
        }
        return names
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
