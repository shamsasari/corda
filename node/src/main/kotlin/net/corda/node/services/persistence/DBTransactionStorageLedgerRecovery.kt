package net.corda.node.services.persistence

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.DistributionList
import net.corda.core.flows.RecoveryTimeWindow
import net.corda.core.flows.TransactionMetadata
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.vault.Sort
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.node.CordaClock
import net.corda.node.services.EncryptionService
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.persistence.Column
import javax.persistence.Embeddable
import javax.persistence.EmbeddedId
import javax.persistence.Entity
import javax.persistence.Lob
import javax.persistence.Table
import javax.persistence.criteria.Predicate
import kotlin.streams.toList

class DBTransactionStorageLedgerRecovery(private val database: CordaPersistence,
                                         cacheFactory: NamedCacheFactory,
                                         val clock: CordaClock,
                                         private val encryptionService: EncryptionService) : DBTransactionStorage(database, cacheFactory, clock) {
    @Embeddable
    @Immutable
    data class PersistentKey(
            @Column(name = "sequence_number", nullable = false)
            var sequenceNumber: Long,

            @Column(name = "timestamp", nullable = false)
            var timestamp: Instant
    ) : Serializable {
        constructor(key: Key) : this(key.sequenceNumber, key.timestamp)
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}sender_distribution_records")
    data class DBSenderDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "tx_id", length = 144, nullable = false)
            var txId: String,

            /** Flow peer **/
            @Column(name = "receiver", nullable = false)
            val receiver: String,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "states_to_record", nullable = false)
            var statesToRecord: StatesToRecord
    ) {
        fun toSenderDistributionRecord() =
            SenderDistributionRecord(
                    SecureHash.parse(this.txId),
                    CordaX500Name.parse(this.receiver),
                    this.statesToRecord,
                    this.compositeKey.timestamp
            )
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}receiver_distribution_records")
    class DBReceiverDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "tx_id", length = 144, nullable = false)
            var txId: String,

            /** Flow initiator **/
            @Column(name = "sender", nullable = true)
            val sender: String,

            /** Encrypted recovery information for sole use by Sender **/
            @Lob
            @Column(name = "distribution_list", nullable = false)
            val distributionList: ByteArray,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "receiver_states_to_record", nullable = false)
            val receiverStatesToRecord: StatesToRecord
    ) {
        constructor(key: Key, txId: SecureHash, initiator: CordaX500Name, encryptedDistributionList: ByteArray, receiverStatesToRecord: StatesToRecord) :
                this(
                        PersistentKey(key),
                        txId = txId.toString(),
                        sender = initiator.toString(),
                        distributionList = encryptedDistributionList,
                        receiverStatesToRecord = receiverStatesToRecord
                )

        fun toReceiverDistributionRecord(encryptionService: EncryptionService): ReceiverDistributionRecord {
            val distributionList = encryptionService.decrypt(this.distributionList).deserialize<DistributionList>()
            return ReceiverDistributionRecord(
                    SecureHash.parse(this.txId),
                    CordaX500Name.parse(this.sender),
                    distributionList.peersToStatesToRecord,
                    this.receiverStatesToRecord,
                    distributionList.senderStatesToRecord,
                    this.compositeKey.timestamp
            )
        }
    }

    class Key(
            val timestamp: Instant,
            val sequenceNumber: Long = nextSequenceNumber.andIncrement
    ) {
        companion object {
            private val nextSequenceNumber = AtomicLong()
        }
    }

    override fun addSenderTransactionRecoveryMetadata(txId: SecureHash, metadata: TransactionMetadata): ByteArray {
        return database.transaction {
            for (peer in metadata.distributionList.peersToStatesToRecord.keys) {
                val senderDistributionRecord = DBSenderDistributionRecord(
                        PersistentKey(Key(clock.instant())),
                        txId.toString(),
                        peer.toString(),
                        metadata.distributionList.senderStatesToRecord
                )
                session.save(senderDistributionRecord)
            }
            encryptionService.encrypt(metadata.distributionList.serialize().bytes)
        }
    }

    override fun addReceiverTransactionRecoveryMetadata(txId: SecureHash,
                                                        sender: CordaX500Name,
                                                        receiver: CordaX500Name,
                                                        receiverStatesToRecord: StatesToRecord,
                                                        encryptedDistributionList: ByteArray) {
        database.transaction {
            val receiverDistributionRecord = DBReceiverDistributionRecord(
                    Key(clock.instant()),
                    txId,
                    sender,
                    encryptedDistributionList,
                    receiverStatesToRecord
            )
            session.save(receiverDistributionRecord)
        }
    }

    override fun removeUnnotarisedTransaction(id: SecureHash): Boolean {
        return database.transaction {
            super.removeUnnotarisedTransaction(id)
            val criteriaBuilder = session.criteriaBuilder
            val deleteSenderDistributionRecords = criteriaBuilder.createCriteriaDelete(DBSenderDistributionRecord::class.java)
            val root = deleteSenderDistributionRecords.from(DBSenderDistributionRecord::class.java)
            deleteSenderDistributionRecords.where(criteriaBuilder.equal(root.get<String>(DBSenderDistributionRecord::txId.name), id.toString()))
            val deletedSenderDistributionRecords = session.createQuery(deleteSenderDistributionRecords).executeUpdate() != 0
            val deleteReceiverDistributionRecords = criteriaBuilder.createCriteriaDelete(DBReceiverDistributionRecord::class.java)
            val rootReceiverDistributionRecord = deleteReceiverDistributionRecords.from(DBReceiverDistributionRecord::class.java)
            deleteReceiverDistributionRecords.where(criteriaBuilder.equal(rootReceiverDistributionRecord.get<String>(DBReceiverDistributionRecord::txId.name), id.toString()))
            val deletedReceiverDistributionRecords = session.createQuery(deleteReceiverDistributionRecords).executeUpdate() != 0
            deletedSenderDistributionRecords || deletedReceiverDistributionRecords
        }
    }

    fun queryDistributionRecords(timeWindow: RecoveryTimeWindow,
                               recordType: DistributionRecordType = DistributionRecordType.ALL,
                               excludingTxnIds: Set<SecureHash>? = null,
                               orderByTimestamp: Sort.Direction? = null
    ): List<DistributionRecord> {
        return when(recordType) {
            DistributionRecordType.SENDER ->
                querySenderDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp)
            DistributionRecordType.RECEIVER ->
                queryReceiverDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp)
            DistributionRecordType.ALL ->
                querySenderDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp).plus(
                        queryReceiverDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp)
                )
        }
    }

    @Suppress("SpreadOperator")
    fun querySenderDistributionRecords(timeWindow: RecoveryTimeWindow,
                                       peers: Set<CordaX500Name> = emptySet(),
                                       excludingTxnIds: Set<SecureHash>? = null,
                                       orderByTimestamp: Sort.Direction? = null
                             ): List<SenderDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBSenderDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBSenderDistributionRecord::class.java)
            val predicates = mutableListOf<Predicate>()
            val compositeKey = txnMetadata.get<PersistentKey>("compositeKey")
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.fromTime))
            predicates.add(criteriaBuilder.and(criteriaBuilder.lessThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.untilTime)))
            excludingTxnIds?.let { excludingTxnIds ->
                predicates.add(criteriaBuilder.and(criteriaBuilder.notEqual(txnMetadata.get<String>(DBSenderDistributionRecord::txId.name),
                        excludingTxnIds.map { it.toString() })))
            }
            if (peers.isNotEmpty()) {
                val peerNames = peers.map { it.toString() }
                predicates.add(criteriaBuilder.and(txnMetadata.get<Long>(DBSenderDistributionRecord::receiver.name).`in`(peerNames)))
            }
            criteriaQuery.where(*predicates.toTypedArray())
            // optionally order by timestamp
            orderByTimestamp?.let {
                val orderCriteria =
                        when (orderByTimestamp) {
                            // when adding column position of 'group by' shift in case columns were removed
                            Sort.Direction.ASC -> criteriaBuilder.asc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                            Sort.Direction.DESC -> criteriaBuilder.desc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                        }
                criteriaQuery.orderBy(orderCriteria)
            }
            val results = session.createQuery(criteriaQuery).stream()
            results.map { it.toSenderDistributionRecord() }.toList()
        }
    }

    @Suppress("SpreadOperator")
    fun queryReceiverDistributionRecords(timeWindow: RecoveryTimeWindow,
                                       initiators: Set<CordaX500Name> = emptySet(),
                                       excludingTxnIds: Set<SecureHash>? = null,
                                       orderByTimestamp: Sort.Direction? = null
    ): List<ReceiverDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBReceiverDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBReceiverDistributionRecord::class.java)
            val predicates = mutableListOf<Predicate>()
            val compositeKey = txnMetadata.get<PersistentKey>("compositeKey")
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.fromTime))
            predicates.add(criteriaBuilder.and(criteriaBuilder.lessThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.untilTime)))
            excludingTxnIds?.let { excludingTxnIds ->
                predicates.add(criteriaBuilder.and(criteriaBuilder.notEqual(txnMetadata.get<String>(DBReceiverDistributionRecord::txId.name),
                        excludingTxnIds.map { it.toString() })))
            }
            if (initiators.isNotEmpty()) {
                val initiatorNames = initiators.map { it.toString() }
                predicates.add(criteriaBuilder.and(txnMetadata.get<String>(DBReceiverDistributionRecord::sender.name).`in`(initiatorNames)))
            }
            criteriaQuery.where(*predicates.toTypedArray())
            // optionally order by timestamp
            orderByTimestamp?.let {
                val orderCriteria =
                        when (orderByTimestamp) {
                            // when adding column position of 'group by' shift in case columns were removed
                            Sort.Direction.ASC -> criteriaBuilder.asc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                            Sort.Direction.DESC -> criteriaBuilder.desc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                        }
                criteriaQuery.orderBy(orderCriteria)
            }
            val results = session.createQuery(criteriaQuery).stream()
            results.map { it.toReceiverDistributionRecord(encryptionService) }.toList()
        }
    }
}


@CordaSerializable
open class DistributionRecord(
        open val txId: SecureHash,
        open val statesToRecord: StatesToRecord,
        open val timestamp: Instant
)

@CordaSerializable
data class SenderDistributionRecord(
        override val txId: SecureHash,
        val peer: CordaX500Name,
        override val statesToRecord: StatesToRecord,
        override val timestamp: Instant
) : DistributionRecord(txId, statesToRecord, timestamp)

@CordaSerializable
data class ReceiverDistributionRecord(
        override val txId: SecureHash,
        val initiator: CordaX500Name,
        val peersToStatesToRecord: Map<CordaX500Name, StatesToRecord>,
        override val statesToRecord: StatesToRecord,
        val senderStatesToRecord: StatesToRecord,
        override val timestamp: Instant
) : DistributionRecord(txId, statesToRecord, timestamp)

@CordaSerializable
enum class DistributionRecordType {
    SENDER, RECEIVER, ALL
}
