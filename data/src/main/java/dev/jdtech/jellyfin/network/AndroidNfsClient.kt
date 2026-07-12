package dev.jdtech.jellyfin.network

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.security.auth.Subject
import org.dcache.oncrpc4j.grizzly.GrizzlyRpcTransport
import org.dcache.oncrpc4j.rpc.OncRpcAcceptedException
import org.dcache.oncrpc4j.rpc.OncRpcRejectedException
import org.dcache.nfs.nfsstat
import org.dcache.nfs.v4.AttributeMap
import org.dcache.nfs.v4.ClientSession
import org.dcache.nfs.v4.CompoundBuilder
import org.dcache.nfs.v4.xdr.COMPOUND4args
import org.dcache.nfs.v4.xdr.COMPOUND4res
import org.dcache.nfs.v4.xdr.SEQUENCE4args
import org.dcache.nfs.v4.xdr.clientid4
import org.dcache.nfs.v4.xdr.count4
import org.dcache.nfs.v4.xdr.entry4
import org.dcache.nfs.v4.xdr.fattr4_size
import org.dcache.nfs.v4.xdr.fattr4_time_modify
import org.dcache.nfs.v4.xdr.fattr4_type
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_argop4
import org.dcache.nfs.v4.xdr.nfs_fh4
import org.dcache.nfs.v4.xdr.nfs_ftype4
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.sequenceid4
import org.dcache.nfs.v4.xdr.state_protect_how4
import org.dcache.nfs.v4.xdr.stateid4
import org.dcache.nfs.v4.xdr.uint32_t
import org.dcache.nfs.v4.xdr.verifier4
import org.dcache.oncrpc4j.rpc.ReplyQueue
import org.dcache.oncrpc4j.rpc.RpcAuth
import org.dcache.oncrpc4j.rpc.RpcAccepsStatus
import org.dcache.oncrpc4j.rpc.RpcAuthType
import org.dcache.oncrpc4j.rpc.RpcAuthVerifier
import org.dcache.oncrpc4j.rpc.RpcCall
import org.dcache.oncrpc4j.rpc.RpcMessageType
import org.dcache.oncrpc4j.rpc.RpcReply
import org.dcache.oncrpc4j.rpc.RpcTransport
import org.dcache.oncrpc4j.xdr.XdrDecodingStream
import org.dcache.oncrpc4j.xdr.XdrEncodingStream
import org.glassfish.grizzly.Connection
import org.glassfish.grizzly.filterchain.BaseFilter
import org.glassfish.grizzly.filterchain.FilterChainBuilder
import org.glassfish.grizzly.filterchain.FilterChainContext
import org.glassfish.grizzly.filterchain.NextAction
import org.glassfish.grizzly.filterchain.TransportFilter
import org.glassfish.grizzly.nio.transport.TCPNIOTransport
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder
import org.glassfish.grizzly.strategies.SameThreadIOStrategy

/**
 * Small NFSv4.1 client built from nfs4j-core and oncrpc4j public APIs.
 *
 * nfs4j's basic client is a command-line sample. Its connection setup reads JVM-only Unix account
 * APIs and its useful session sender is private, so it cannot be used safely from Android. This
 * class owns only the protocol state needed by SpatialFin's read-only data path.
 */
internal class AndroidNfsClient private constructor(
    private val rpcConnection: AndroidOncRpcConnection,
    private val rpcCall: RpcCall,
) : Closeable {

    private var clientId: clientid4? = null
    private var createSessionSequenceId: sequenceid4? = null
    private var clientSession: ClientSession? = null
    private var rootFileHandle: nfs_fh4? = null
    private var maximumOperationsPerCompound = SESSION_MAX_OPERATIONS
    private var maximumResponsePayloadSize = INITIAL_RESPONSE_PAYLOAD_SIZE

    @Volatile
    private var lastActivityMillis = 0L

    @Volatile
    private var closed = false

    private val leaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SpatialFin-NFS-lease").apply { isDaemon = true }
    }

    @Synchronized
    fun mount(exportPath: String) {
        check(!closed) { "NFS client is closed" }
        check(clientSession == null) { "NFS client is already mounted" }

        exchangeId()
        createSession()
        rootFileHandle = lookupExportRoot(exportPath)
        reclaimComplete()
        lastActivityMillis = System.currentTimeMillis()

        leaseExecutor.scheduleWithFixedDelay(
            ::renewLeaseIfIdle,
            LEASE_CHECK_INTERVAL_SECONDS,
            LEASE_CHECK_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    fun rootFileHandle(): nfs_fh4 = checkNotNull(rootFileHandle) { "NFS export is not mounted" }

    fun lookup(startFileHandle: nfs_fh4, path: String): nfs_fh4 {
        var currentFileHandle = startFileHandle
        lookupChunks(path, maximumOperationsPerCompound).forEach { components ->
            currentFileHandle = resolveLookupChunk(
                CompoundBuilder().withPutfh(currentFileHandle),
                components,
                "lookup",
            )
        }
        return currentFileHandle
    }

    fun list(fileHandle: nfs_fh4): List<String> {
        val names = mutableListOf<String>()
        var cookie = 0L
        var verifier = verifier4(ByteArray(nfs4_prot.NFS4_VERIFIER_SIZE))
        var complete: Boolean

        do {
            val responsePayloadSize = maximumResponsePayloadSize
            val result = sendCompoundInSession(
                CompoundBuilder()
                    .withPutfh(fileHandle)
                    .withReaddir(cookie, verifier, responsePayloadSize, responsePayloadSize)
                    .withTag("readdir")
                    .build(),
            )
            val readDirectory = result.resarray.last().opreaddir.resok4
            verifier = readDirectory.cookieverf
            complete = readDirectory.reply.eof

            var entry: entry4? = readDirectory.reply.entries
            while (entry != null) {
                cookie = entry.cookie.value
                names += String(entry.name.value, StandardCharsets.UTF_8)
                entry = entry.nextentry
            }
        } while (!complete)

        return names
    }

    fun stat(fileHandle: nfs_fh4): NfsFileStat {
        val result = sendCompoundInSession(
            CompoundBuilder()
                .withPutfh(fileHandle)
                .withGetattr(
                    nfs4_prot.FATTR4_SIZE,
                    nfs4_prot.FATTR4_TYPE,
                    nfs4_prot.FATTR4_TIME_MODIFY,
                )
                .withTag("getattr")
                .build(),
        )
        val attributes = AttributeMap(result.resarray.last().opgetattr.resok4.obj_attributes)
        val sizeAttribute = attributes.get<fattr4_size>(nfs4_prot.FATTR4_SIZE)
        val typeAttribute = attributes.get<fattr4_type>(nfs4_prot.FATTR4_TYPE)
        val modifiedAttribute = attributes.get<fattr4_time_modify>(nfs4_prot.FATTR4_TIME_MODIFY)

        return NfsFileStat(
            size = if (sizeAttribute.isPresent) sizeAttribute.get().value else 0L,
            isDirectory = typeAttribute.isPresent && typeAttribute.get().value == nfs_ftype4.NF4DIR,
            lastModifiedMillis = if (modifiedAttribute.isPresent) {
                val modified = modifiedAttribute.get()
                modified.seconds * MILLIS_PER_SECOND + modified.nseconds / NANOS_PER_MILLI
            } else {
                null
            },
        )
    }

    fun openForRead(directoryFileHandle: nfs_fh4, fileName: String): NfsOpenFile {
        require(fileName.isNotEmpty()) { "NFS file name is empty" }
        val mountedClientId = checkNotNull(clientId) { "NFS export is not mounted" }
        val arguments = CompoundBuilder()
            .withPutfh(directoryFileHandle)
            .withOpen(
                fileName,
                OPEN_SEQUENCE_ID,
                mountedClientId,
                nfs4_prot.OPEN4_SHARE_ACCESS_READ,
            )
            .withGetfh()
            .withTag("open_read")
            .build()
        arguments.argarray.first { it.argop == nfs_opnum4.OP_OPEN }.opopen.share_access = uint32_t(
            nfs4_prot.OPEN4_SHARE_ACCESS_READ or nfs4_prot.OPEN4_SHARE_ACCESS_WANT_NO_DELEG,
        )
        val result = sendCompoundInSession(arguments)
        val operationCount = result.resarray.size
        return NfsOpenFile(
            fileHandle = result.resarray[operationCount - 1].opgetfh.resok4.`object`,
            stateId = result.resarray[operationCount - 2].opopen.resok4.stateid,
        )
    }

    fun read(
        fileHandle: nfs_fh4,
        stateId: stateid4,
        offset: Long,
        count: Int,
    ): NfsReadResult {
        require(count > 0) { "NFS read count must be positive" }
        val boundedCount = count.coerceAtMost(maximumResponsePayloadSize)
        val result = sendCompoundInSession(
            CompoundBuilder()
                .withPutfh(fileHandle)
                .withRead(boundedCount, offset, stateId)
                .withTag("read")
                .build(),
        )
        val readResult = result.resarray.last().opread.resok4
        return NfsReadResult(readResult.data, readResult.eof)
    }

    fun closeFile(fileHandle: nfs_fh4, stateId: stateid4) {
        sendCompoundInSession(
            CompoundBuilder()
                .withPutfh(fileHandle)
                .withClose(stateId, CLOSE_SEQUENCE_ID)
                .withTag("close")
                .build(),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        leaseExecutor.shutdownNow()

        val mountedSession = clientSession
        if (mountedSession != null) {
            runCatching {
                sendCompound(
                    CompoundBuilder()
                        .withDestroysession(mountedSession.sessionId())
                        .withTag("destroy_session")
                        .build(),
                )
            }
            clientSession = null
        }

        clientId?.let { mountedClientId ->
            runCatching {
                sendCompound(
                    CompoundBuilder()
                        .withDestroyclientid(mountedClientId)
                        .withTag("destroy_clientid")
                        .build(),
                )
            }
            clientId = null
        }

        rpcConnection.close()
    }

    private fun exchangeId() {
        val ownerId = "SpatialFin-Android-${UUID.randomUUID()}"
        val result = sendCompound(
            CompoundBuilder()
                .withExchangeId(
                    CLIENT_DOMAIN,
                    CLIENT_NAME,
                    ownerId,
                    0,
                    state_protect_how4.SP4_NONE,
                )
                .withTag("exchange_id")
                .build(),
        )
        val exchangeResult = result.resarray.first().opexchange_id.eir_resok4
        clientId = exchangeResult.eir_clientid
        createSessionSequenceId = exchangeResult.eir_sequenceid
    }

    private fun createSession() {
        val mountedClientId = checkNotNull(clientId)
        val sequenceId = checkNotNull(createSessionSequenceId)
        val arguments = CompoundBuilder()
            .withCreatesession(mountedClientId, sequenceId)
            .withTag("create_session")
            .build()
        arguments.argarray.first().opcreate_session.csa_fore_chan_attrs.apply {
            ca_maxoperations = count4(SESSION_MAX_OPERATIONS)
            ca_maxrequests = count4(SESSION_MAX_REQUESTS)
            ca_maxrequestsize = count4(SESSION_CHANNEL_SIZE)
            ca_maxresponsesize = count4(SESSION_CHANNEL_SIZE)
            ca_maxresponsesize_cached = count4(SESSION_CACHED_RESPONSE_SIZE)
        }

        val result = sendCompound(arguments)
        val createResult = result.resarray.first().opcreate_session.csr_resok4
        sequenceId.value = 0
        val negotiatedAttributes = createResult.csr_fore_chan_attrs
        val maximumRequests = negotiatedAttributes.ca_maxrequests.value.coerceIn(
            1,
            SESSION_MAX_REQUESTS,
        )
        clientSession = ClientSession(createResult.csr_sessionid, maximumRequests)

        val negotiatedMaximumOperations = negotiatedAttributes.ca_maxoperations.value
        check(negotiatedMaximumOperations >= MINIMUM_SESSION_MAX_OPERATIONS) {
            "NFS server negotiated ca_maxoperations=$negotiatedMaximumOperations; " +
                "this client requires at least $MINIMUM_SESSION_MAX_OPERATIONS"
        }
        maximumOperationsPerCompound = negotiatedMaximumOperations.coerceAtMost(
            SESSION_MAX_OPERATIONS,
        )
        maximumResponsePayloadSize = (
            negotiatedAttributes.ca_maxresponsesize.value - COMPOUND_RESPONSE_OVERHEAD
        ).coerceIn(1, MAX_RESPONSE_PAYLOAD_SIZE)
    }

    private fun lookupExportRoot(exportPath: String): nfs_fh4 {
        val chunks = lookupChunks(exportPath, maximumOperationsPerCompound)
        if (chunks.isEmpty()) {
            return resolveLookupChunk(
                CompoundBuilder().withPutrootfh(),
                emptyList(),
                "get_rootfh",
            )
        }

        var currentFileHandle = resolveLookupChunk(
            CompoundBuilder().withPutrootfh(),
            chunks.first(),
            "get_rootfh",
        )
        chunks.drop(1).forEach { components ->
            currentFileHandle = resolveLookupChunk(
                CompoundBuilder().withPutfh(currentFileHandle),
                components,
                "get_rootfh",
            )
        }
        return currentFileHandle
    }

    private fun resolveLookupChunk(
        builder: CompoundBuilder,
        components: List<String>,
        tag: String,
    ): nfs_fh4 {
        components.forEach(builder::withLookup)
        val result = sendCompoundInSession(
            builder
                .withGetfh()
                .withTag(tag)
                .build(),
        )
        return result.resarray.last().opgetfh.resok4.`object`
    }

    private fun reclaimComplete() {
        val mountedRoot = checkNotNull(rootFileHandle)
        sendCompoundInSession(
            CompoundBuilder()
                .withPutfh(mountedRoot)
                .withReclaimComplete()
                .withTag("reclaim_complete")
                .build(),
        )
    }

    private fun renewLeaseIfIdle() {
        if (closed || clientSession == null) return
        if (System.currentTimeMillis() - lastActivityMillis < LEASE_RENEW_AFTER_MILLIS) return

        runCatching {
            sendCompoundInSession(
                CompoundBuilder()
                    .withTag("renew_lease")
                    .build(),
            )
        }
    }

    private fun sendCompound(arguments: COMPOUND4args): COMPOUND4res {
        var result: COMPOUND4res
        do {
            result = callCompound(arguments)
        } while (shouldRetry(result.status))

        nfsstat.throwIfNeeded(result.status)
        return result
    }

    private fun sendCompoundInSession(arguments: COMPOUND4args): COMPOUND4res {
        require(arguments.argarray.firstOrNull()?.argop != nfs_opnum4.OP_SEQUENCE) {
            "The NFS SEQUENCE operation is added by the session"
        }
        val operationCount = arguments.argarray.size + 1
        check(operationCount <= maximumOperationsPerCompound) {
            "NFS compound has $operationCount operations, exceeding the negotiated " +
                "ca_maxoperations=$maximumOperationsPerCompound"
        }
        val mountedSession = checkNotNull(clientSession) { "NFS export is not mounted" }
        val originalOperations = arguments.argarray
        arguments.argarray = Array(originalOperations.size + 1) { index ->
            if (index == 0) nfs_argop4() else originalOperations[index - 1]
        }

        val slot = mountedSession.acquireSlot()
        try {
            var result: COMPOUND4res
            do {
                val sequence = nfs_argop4().apply {
                    argop = nfs_opnum4.OP_SEQUENCE
                    opsequence = SEQUENCE4args().apply {
                        sa_cachethis = false
                        sa_slotid = slot.id
                        sa_highest_slotid = org.dcache.nfs.v4.xdr.slotid4(
                            mountedSession.maxRequests() - 1,
                        )
                        sa_sequenceid = slot.nextSequenceId()
                        sa_sessionid = mountedSession.sessionId()
                    }
                }
                arguments.argarray[0] = sequence
                result = callCompound(arguments)
                lastActivityMillis = System.currentTimeMillis()
            } while (shouldRetry(result.status))

            nfsstat.throwIfNeeded(result.status)
            return result
        } finally {
            mountedSession.releaseSlot(slot)
        }
    }

    private fun callCompound(arguments: COMPOUND4args): COMPOUND4res {
        val result = COMPOUND4res()
        rpcCall.call(
            nfs4_prot.NFSPROC4_COMPOUND_4,
            arguments,
            result,
            RPC_CALL_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return result
    }

    private fun shouldRetry(status: Int): Boolean {
        if (
            status != nfsstat.NFSERR_DELAY &&
            status != nfsstat.NFSERR_LAYOUTTRYLATER &&
            status != nfsstat.NFSERR_GRACE
        ) {
            return false
        }

        return try {
            Thread.sleep(RETRY_DELAY_MILLIS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    internal companion object {
        private const val NFS_PORT = 2049
        private const val NFS_PROGRAM = 100003
        private const val NFS_VERSION = 4
        private const val RPC_CALL_TIMEOUT_SECONDS = 30L
        private const val INITIAL_RESPONSE_PAYLOAD_SIZE = 4 * 1024
        private const val MAX_RESPONSE_PAYLOAD_SIZE = 64 * 1024
        private const val COMPOUND_RESPONSE_OVERHEAD = 1024
        private const val SESSION_CHANNEL_SIZE = 128 * 1024
        private const val SESSION_CACHED_RESPONSE_SIZE = 8 * 1024
        private const val SESSION_MAX_OPERATIONS = 16
        private const val LOOKUP_COMPOUND_FIXED_OPERATIONS = 3
        private const val MINIMUM_SESSION_MAX_OPERATIONS = LOOKUP_COMPOUND_FIXED_OPERATIONS + 1
        private const val SESSION_MAX_REQUESTS = 4
        private const val OPEN_SEQUENCE_ID = 0
        private const val CLOSE_SEQUENCE_ID = 1
        private const val AUTH_UNIX_UID = 65534
        private const val AUTH_UNIX_GID = 65534
        private const val AUTH_UNIX_MACHINE = "spatialfin-android"
        private const val CLIENT_DOMAIN = "spatialfin.app"
        private const val CLIENT_NAME = "SpatialFin Android NFS client"
        private const val LEASE_CHECK_INTERVAL_SECONDS = 30L
        private const val LEASE_RENEW_AFTER_MILLIS = 60_000L
        private const val RETRY_DELAY_MILLIS = 500L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000

        internal fun lookupChunks(path: String, maximumOperations: Int): List<List<String>> {
            require(maximumOperations >= MINIMUM_SESSION_MAX_OPERATIONS) {
                "NFS lookup requires at least $MINIMUM_SESSION_MAX_OPERATIONS operations per compound"
            }
            val components = path.split('/').filter(String::isNotEmpty)
            return components.chunked(maximumOperations - LOOKUP_COMPOUND_FIXED_OPERATIONS)
        }

        fun connect(address: InetAddress): AndroidNfsClient {
            val rpcConnection = AndroidOncRpcConnection.connect(address, NFS_PORT)
            return try {
                val credential = AndroidAuthUnix(
                    uid = AUTH_UNIX_UID,
                    gid = AUTH_UNIX_GID,
                    supplementalGroups = intArrayOf(AUTH_UNIX_GID),
                    machine = AUTH_UNIX_MACHINE,
                )
                AndroidNfsClient(
                    rpcConnection = rpcConnection,
                    rpcCall = RpcCall(
                        NFS_PROGRAM,
                        NFS_VERSION,
                        credential,
                        rpcConnection.rpcTransport,
                    ),
                )
            } catch (error: Exception) {
                rpcConnection.close()
                throw error
            }
        }
    }
}

internal data class NfsFileStat(
    val size: Long,
    val isDirectory: Boolean,
    val lastModifiedMillis: Long?,
)

internal data class NfsOpenFile(
    val fileHandle: nfs_fh4,
    val stateId: stateid4,
)

internal data class NfsReadResult(
    val data: ByteBuffer,
    val endOfFile: Boolean,
)

/** Client-only ONC/RPC TCP connection that never decodes inbound server credentials. */
private class AndroidOncRpcConnection private constructor(
    private val transport: TCPNIOTransport,
    private val connection: Connection<InetSocketAddress>,
    private val replyQueue: ReplyQueue,
    val rpcTransport: RpcTransport,
) : Closeable {

    override fun close() {
        connection.closeSilently()
        runCatching { transport.shutdownNow() }
        replyQueue.shutdown()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L

        fun connect(address: InetAddress, port: Int): AndroidOncRpcConnection {
            val replyQueue = ReplyQueue()
            val transport = TCPNIOTransportBuilder.newInstance()
                .setReuseAddress(true)
                .setKeepAlive(true)
                .setTcpNoDelay(true)
                .setIOStrategy(SameThreadIOStrategy.getInstance())
                .build()
            transport.processor = FilterChainBuilder.stateless()
                .add(TransportFilter())
                .add(org.dcache.oncrpc4j.rpc.RpcMessageParserTCP())
                .add(ClientRpcReplyFilter(replyQueue))
                .build()

            return try {
                transport.start()
                @Suppress("UNCHECKED_CAST")
                val connection = transport.connect(InetSocketAddress(address, port))
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS) as Connection<InetSocketAddress>
                AndroidOncRpcConnection(
                    transport = transport,
                    connection = connection,
                    replyQueue = replyQueue,
                    rpcTransport = GrizzlyRpcTransport(connection, replyQueue),
                )
            } catch (error: Exception) {
                runCatching { transport.shutdownNow() }
                replyQueue.shutdown()
                throw error
            }
        }
    }
}

/** Reply-only counterpart of oncrpc4j's combined client/server protocol filter. */
private class ClientRpcReplyFilter(
    private val replyQueue: ReplyQueue,
) : BaseFilter() {

    override fun handleRead(context: FilterChainContext): NextAction {
        val xdr = context.getMessage<org.dcache.oncrpc4j.xdr.Xdr>() ?: return context.stopAction
        xdr.beginDecoding()
        val transactionId = xdr.xdrDecodeInt()
        if (xdr.xdrDecodeInt() != RpcMessageType.REPLY) return context.stopAction

        @Suppress("UNCHECKED_CAST")
        val connection = context.connection as Connection<InetSocketAddress>
        val rpcTransport = GrizzlyRpcTransport(
            connection,
            context.address as InetSocketAddress,
            replyQueue,
        )
        val callback = replyQueue.get(transactionId) ?: return context.stopAction

        try {
            val reply = RpcReply(transactionId, xdr, rpcTransport)
            when {
                !reply.isAccepted -> callback.failed(
                    OncRpcRejectedException(reply.rejectStatus),
                    rpcTransport,
                )
                reply.acceptStatus != RpcAccepsStatus.SUCCESS -> callback.failed(
                    OncRpcAcceptedException(reply.acceptStatus),
                    rpcTransport,
                )
                else -> callback.completed(reply, rpcTransport)
            }
        } catch (error: Exception) {
            callback.failed(error, rpcTransport)
        }
        return context.stopAction
    }
}

/**
 * Minimal outbound AUTH_UNIX credential.
 *
 * RpcAuthTypeUnix cannot be used on Android even with explicit IDs: its constructor materializes
 * com.sun.security.auth principals. RpcCall only needs the public RpcAuth/XDR contract, so encode
 * the same wire representation and expose an empty Subject for client-side propagation.
 */
private class AndroidAuthUnix(
    private val uid: Int,
    private val gid: Int,
    private val supplementalGroups: IntArray,
    private val machine: String,
) : RpcAuth {

    private val verifier = RpcAuthVerifier(RpcAuthType.NONE, ByteArray(0))
    private val subject = Subject()
    private val stamp = (System.currentTimeMillis() / 1_000L).toInt()

    override fun type(): Int = RpcAuthType.UNIX

    override fun getVerifier(): RpcAuthVerifier = verifier

    override fun getSubject(): Subject = subject

    override fun xdrEncode(xdr: XdrEncodingStream) {
        val machineBytes = machine.toByteArray(StandardCharsets.UTF_8)
        val paddedMachineLength = (machineBytes.size + 3) and -4
        val credentialLength = 20 + paddedMachineLength + supplementalGroups.size * Int.SIZE_BYTES

        xdr.xdrEncodeInt(RpcAuthType.UNIX)
        xdr.xdrEncodeInt(credentialLength)
        xdr.xdrEncodeInt(stamp)
        xdr.xdrEncodeString(machine)
        xdr.xdrEncodeInt(uid)
        xdr.xdrEncodeInt(gid)
        xdr.xdrEncodeIntVector(supplementalGroups)
        verifier.xdrEncode(xdr)
    }

    override fun xdrDecode(xdr: XdrDecodingStream) {
        throw UnsupportedOperationException("Outbound AUTH_UNIX credentials cannot be decoded")
    }
}
