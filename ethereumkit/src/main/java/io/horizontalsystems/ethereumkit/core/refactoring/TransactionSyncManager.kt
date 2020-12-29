package io.horizontalsystems.ethereumkit.core.refactoring

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.models.BloomFilter
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.horizontalsystems.ethereumkit.models.NotSyncedTransaction
import io.horizontalsystems.ethereumkit.models.TransactionSyncerState
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.math.BigInteger
import java.util.logging.Logger

interface ITransactionSyncer {
    val id: String
    val state: EthereumKit.SyncState
    val stateAsync: Flowable<EthereumKit.SyncState>

    fun onEthereumKitSynced()
    fun onLastBlockBloomFilter(bloomFilter: BloomFilter)
    fun onUpdateNonce(nonce: Long)
    fun onUpdateBalance(balance: BigInteger)
    fun onLastBlockNumber(blockNumber: Long)

    fun set(delegate: ITransactionSyncerDelegate)
}

interface ITransactionSyncerDelegate {
    val notSyncedTransactionsSignal: Flowable<Unit>

    fun getNotSyncedTransactions(limit: Int): List<NotSyncedTransaction>
    fun add(notSyncedTransactions: List<NotSyncedTransaction>)
    fun remove(notSyncedTransaction: NotSyncedTransaction)
    fun update(notSyncedTransaction: NotSyncedTransaction)

    fun getTransactionSyncerState(id: String): TransactionSyncerState?
    fun update(transactionSyncerState: TransactionSyncerState)
}

class TransactionSyncManager(
        private val notSyncedTransactionManager: NotSyncedTransactionManager
) : ITransactionSyncerListener {
    private val logger = Logger.getLogger(this.javaClass.simpleName)

    private val disposables = CompositeDisposable()
    private val syncerStateDisposables = hashMapOf<String, Disposable>()

    private val stateSubject = PublishSubject.create<EthereumKit.SyncState>()
    private val transactionsSubject = PublishSubject.create<List<FullTransaction>>()
    private val syncers: MutableList<ITransactionSyncer> = mutableListOf()

    private lateinit var ethereumKit: EthereumKit

    var syncState: EthereumKit.SyncState = EthereumKit.SyncState.NotSynced(EthereumKit.SyncError.NotStarted())
        private set(value) {
            field = value
            stateSubject.onNext(value)
        }
    val syncStateAsync: Flowable<EthereumKit.SyncState> = stateSubject.toFlowable(BackpressureStrategy.BUFFER)

    val transactionsAsync: Flowable<List<FullTransaction>> = transactionsSubject.toFlowable(BackpressureStrategy.BUFFER)

    fun set(ethereumKit: EthereumKit) {
        this.ethereumKit = ethereumKit

        subscribe(ethereumKit)
    }

    fun add(syncer: ITransactionSyncer) {
        syncer.set(delegate = notSyncedTransactionManager)

        syncers.add(syncer)

        syncer.stateAsync
                .subscribeOn(Schedulers.io())
                .subscribe { syncState() }
                .let { syncerStateDisposables[syncer.id] = it }

        syncState()
    }

    fun removeSyncer(id: String) {
        syncerStateDisposables.remove(id)?.dispose()

        syncers.removeIf { it.id == id }
    }

    override fun onTransactionsSynced(transactions: List<FullTransaction>) {
        transactionsSubject.onNext(transactions)
    }

    private fun subscribe(ethereumKit: EthereumKit) {
        ethereumKit.balanceFlowable
                .subscribeOn(Schedulers.io())
                .subscribe {
                    logger.info(" ---> balanceFlowable: $it, syncers: ${syncers.size}")
                    onUpdateBalance(it)
                }
                .let { disposables.add(it) }

        ethereumKit.nonceFlowable
                .subscribeOn(Schedulers.io())
                .subscribe {
                    logger.info(" ---> nonceFlowable: $it, syncers: ${syncers.size}")
                    onUpdateNonce(it)
                }
                .let { disposables.add(it) }

        ethereumKit.lastBlockBloomFilterFlowable
                .subscribeOn(Schedulers.io())
                .subscribe {
                    logger.info(" ---> lastBlockBloomFilterFlowable: $it, syncers: ${syncers.size}")
                    onLastBlockBloomFilter(it)
                }
                .let { disposables.add(it) }

        ethereumKit.lastBlockHeightFlowable
                .subscribeOn(Schedulers.io())
                .subscribe {
                    logger.info(" ---> lastBlockHeightFlowable: $it, syncers: ${syncers.size}")
                    onLastBlockNumber(it)
                }
                .let { disposables.add(it) }

        ethereumKit.syncStateFlowable
                .subscribeOn(Schedulers.io())
                .subscribe {
                    logger.info(" ---> syncStateFlowable: $it, syncers: ${syncers.size}")
                    onEthereumKitSyncState(it)
                }
                .let { disposables.add(it) }
    }

    private fun onEthereumKitSyncState(state: EthereumKit.SyncState) {
        logger.info(" ---> onEthKitSyncState: $state, syncers: ${syncers.size}")

        if (state is EthereumKit.SyncState.Synced) { //?? resync on network reconnection
            performOnSyncers { syncer -> syncer.onEthereumKitSynced() }
        }
    }

    private fun onLastBlockBloomFilter(bloomFilter: BloomFilter) {
        performOnSyncers { syncer -> syncer.onLastBlockBloomFilter(bloomFilter) }
    }

    private fun onUpdateNonce(nonce: Long) {
        performOnSyncers { syncer -> syncer.onUpdateNonce(nonce) }
    }

    private fun onUpdateBalance(balance: BigInteger) {
        performOnSyncers { syncer -> syncer.onUpdateBalance(balance) }
    }

    private fun onLastBlockNumber(blockNumber: Long) {
        performOnSyncers { syncer -> syncer.onLastBlockNumber(blockNumber) }
    }

    private fun performOnSyncers(action: (ITransactionSyncer) -> Unit) {
        syncers.forEach { action(it) }
    }

    private fun syncState() {
        val notSyncedSyncerState = syncers.firstOrNull { it.state is EthereumKit.SyncState.NotSynced }?.state as? EthereumKit.SyncState.NotSynced
        syncState = when {
            notSyncedSyncerState != null -> EthereumKit.SyncState.NotSynced(notSyncedSyncerState.error)
            syncers.any { it.state is EthereumKit.SyncState.Syncing } -> EthereumKit.SyncState.Syncing()
            else -> EthereumKit.SyncState.Synced()
        }
    }


}