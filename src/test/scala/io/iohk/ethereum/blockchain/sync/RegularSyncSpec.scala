package io.iohk.ethereum.blockchain.sync

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.testkit.{ TestActorRef, TestKit, TestProbe }
import akka.util.ByteString
import akka.util.ByteString.{ empty => bEmpty }
import com.miguno.akka.testing.VirtualTime
import io.iohk.ethereum.ObjectGenerators
import io.iohk.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import io.iohk.ethereum.blockchain.sync.RegularSync.MinedBlock
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import io.iohk.ethereum.network.EtcPeerManagerActor.{ HandshakedPeers, PeerInfo }
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.p2p.messages.CommonMessages.{ NewBlock, Status }
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63.NodeData
import io.iohk.ethereum.network.{ EtcPeerManagerActor, Peer }
import io.iohk.ethereum.nodebuilder.{ SecureRandomBuilder, SyncConfigBuilder }
import io.iohk.ethereum.ommers.OmmersPool.{ AddOmmers, RemoveOmmers }
import io.iohk.ethereum.transactions.PendingTransactionsManager.{ AddTransactions, RemoveTransactions }
import io.iohk.ethereum.utils.Config.SyncConfig
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Second, Span }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.collection.immutable
import scala.concurrent.duration._

// scalastyle:off magic.number
class RegularSyncSpec extends TestKit(ActorSystem("RegularSync_system")) with WordSpecLike
  with Matchers with MockFactory with BeforeAndAfterAll with Eventually {

  // We just need the reference in order to override the ActorSystem in TestSetup
  private val testKitActorSystem: ActorSystem = system

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "RegularSync" when {

    "receiving NewBlock msg" should {

      "handle import to the main chain" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(BlockImportedToTop(List(block), List(defaultTd)))
        (broadcaster.broadcastBlock _).expects(NewBlock(block, defaultTd), handshakedPeers)

        sendBlockHeaders(Seq.empty)

        sendNewBlockMsg(block)

        ommersPool.expectMsg(RemoveOmmers(block.header :: block.body.uncleNodesList.toList))
        txPool.expectMsg(RemoveTransactions(block.body.transactionList.toList))
      }

      "handle chain reorganisation" in new TestSetup {
        val newBlock: Block = getBlock()
        val oldBlock: Block = getBlock()

        (ledger.importBlock _).expects(newBlock)
          .returning(ChainReorganised(List(oldBlock), List(newBlock), List(defaultTd)))
        (broadcaster.broadcastBlock _).expects(NewBlock(newBlock, defaultTd), handshakedPeers)

        sendBlockHeaders(Seq.empty)

        sendNewBlockMsg(newBlock)

        ommersPool.expectMsg(AddOmmers(List(oldBlock.header)))
        txPool.expectMsg(AddTransactions(oldBlock.body.transactionList.toList))

        ommersPool.expectMsg(RemoveOmmers(newBlock.header :: newBlock.body.uncleNodesList.toList))
        txPool.expectMsg(RemoveTransactions(newBlock.body.transactionList.toList))
      }

      "handle duplicate" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(DuplicateBlock)
        (broadcaster.broadcastBlock _).expects(*, *).never()

        sendBlockHeaders(Seq.empty)

        sendNewBlockMsg(block)

        ommersPool.expectNoMsg(1.second)
        txPool.expectNoMsg()
      }

      "handle enqueuing" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(BlockEnqueued)
        (broadcaster.broadcastBlock _).expects(*, *).never()

        sendBlockHeaders(Seq.empty)

        sendNewBlockMsg(block)

        ommersPool.expectMsg(AddOmmers(List(block.header)))
        txPool.expectNoMsg()
      }

      "handle block error" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(BlockImportFailed("error"))
        (broadcaster.broadcastBlock _).expects(*, *).never()

        sendBlockHeaders(Seq.empty)

        sendNewBlockMsg(block)

        ommersPool.expectNoMsg(1.second)
        txPool.expectNoMsg()

        regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe true
      }
    }

    "receiving NewBlockHashes msg" should {

      "handle newBlockHash message" in new TestSetup {
        val blockHash: BlockHash = randomBlockHash()

        (ledger.checkBlockStatus _).expects(blockHash.hash).returning(UnknownBlock)
        sendBlockHeaders(Seq.empty)
        sendNewBlockHashMsg(Seq(blockHash))

        etcPeerManager.expectMsg(EtcPeerManagerActor.GetHandshakedPeers)
        val headers = GetBlockHeaders(Right(blockHash.hash), 1, 0, reverse = false)
        etcPeerManager.expectMsg(EtcPeerManagerActor.SendMessage(headers, peer1.id))
      }

      "filter out hashes that are already in chain or queue" in new TestSetup {
        val blockHashes: immutable.IndexedSeq[BlockHash] = (1 to 4).map(num => randomBlockHash(num))

        blockHashes.foreach(blockHash =>
          if (blockHash.number == 1)
            (ledger.checkBlockStatus _).expects(blockHash.hash).returning(InChain)
          else if (blockHash.number == 2)
            (ledger.checkBlockStatus _).expects(blockHash.hash).returning(Queued)
          else
            (ledger.checkBlockStatus _).expects(blockHash.hash).returning(UnknownBlock)
        )

        sendBlockHeaders(Seq.empty)
        sendNewBlockHashMsg(blockHashes)

        val hashesRequested: immutable.IndexedSeq[BlockHash] = blockHashes.takeRight(2)
        val headers = GetBlockHeaders(Right(hashesRequested.head.hash), hashesRequested.length, 0, reverse = false)

        etcPeerManager.expectMsg(EtcPeerManagerActor.GetHandshakedPeers)
        etcPeerManager.expectMsg(EtcPeerManagerActor.SendMessage(headers, peer1.id))
      }

      "blacklist peer sending ancient blockhashes" in new TestSetup {
        val blockHash: BlockHash = randomBlockHash()
        storagesInstance.storages.appStateStorage.putBestBlockNumber(blockHash.number + syncConfig.maxNewBlockHashAge + 1)
        sendBlockHeaders(Seq.empty)
        sendNewBlockHashMsg(Seq(blockHash))

        regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe true
      }

      "handle at most 64 new hashes in one request" in new TestSetup {
        val blockHashes: immutable.Seq[BlockHash] = (1 to syncConfig.maxNewHashes + 1).map(num => randomBlockHash(num))
        val headers = GetBlockHeaders(Right(blockHashes.head.hash), syncConfig.maxNewHashes, 0, reverse = false)

        blockHashes.take(syncConfig.maxNewHashes).foreach { blockHash =>
          (ledger.checkBlockStatus _).expects(blockHash.hash).returning(UnknownBlock)
        }

        sendBlockHeaders(Seq.empty)
        sendNewBlockHashMsg(blockHashes)

        etcPeerManager.expectMsg(EtcPeerManagerActor.GetHandshakedPeers)
        etcPeerManager.expectMsg(EtcPeerManagerActor.SendMessage(headers, peer1.id))
      }
    }

    "receiving MinedBlock msg" should {

      "handle import to the main chain" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(BlockImportedToTop(List(block), List(defaultTd)))
        (broadcaster.broadcastBlock _).expects(NewBlock(block, defaultTd), handshakedPeers)

        sendMinedBlockMsg(block)

        txPool.expectMsg(RemoveTransactions(block.body.transactionList.toList))
      }

      "handle chain reorganisation" in new TestSetup {
        val newBlock: Block = getBlock()
        val oldBlock: Block = getBlock()

        (ledger.importBlock _).expects(newBlock)
          .returning(ChainReorganised(List(oldBlock), List(newBlock), List(defaultTd)))
        (broadcaster.broadcastBlock _).expects(NewBlock(newBlock, defaultTd), handshakedPeers)

        sendMinedBlockMsg(newBlock)

        ommersPool.expectMsg(AddOmmers(List(oldBlock.header)))
        txPool.expectMsg(AddTransactions(oldBlock.body.transactionList.toList))

        ommersPool.expectMsg(RemoveOmmers(newBlock.header :: newBlock.body.uncleNodesList.toList))
        txPool.expectMsg(RemoveTransactions(newBlock.body.transactionList.toList))
      }

      "handle duplicate" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(DuplicateBlock)
        (broadcaster.broadcastBlock _).expects(*, *).never()

        sendMinedBlockMsg(block)

        ommersPool.expectNoMsg(1.second)
        txPool.expectNoMsg()
      }

      "handle enqueuing" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(BlockEnqueued)
        (broadcaster.broadcastBlock _).expects(*, *).never()

        sendMinedBlockMsg(block)

        ommersPool.expectMsg(AddOmmers(List(block.header)))
        txPool.expectNoMsg()
      }

      "handle block error" in new TestSetup {
        val block: Block = getBlock()

        (ledger.importBlock _).expects(block).returning(BlockImportFailed("error"))
        (broadcaster.broadcastBlock _).expects(*, *).never()

        sendMinedBlockMsg(block)

        ommersPool.expectNoMsg(1.second)
        txPool.expectNoMsg()
      }
    }

    "receiving BlockHeaders msg" should {

      "handle a new better branch" in new TestSetup {
        val oldBlocks: immutable.IndexedSeq[Block] = (1 to 2).map(_ => getBlock())
        val newBlocks: immutable.IndexedSeq[Block] = (1 to 2).map(_ => getBlock())

        (ledger.resolveBranch _).expects(newBlocks.map(_.header)).returning(NewBetterBranch(oldBlocks))

        sendBlockHeadersFromBlocks(newBlocks)

        etcPeerManager.expectMsg(EtcPeerManagerActor.GetHandshakedPeers)
        etcPeerManager.expectMsg(EtcPeerManagerActor.SendMessage(GetBlockBodies(newBlocks.map(_.header.hash)), peer1.id))
        txPool.expectMsg(AddTransactions(oldBlocks.flatMap(_.body.transactionList).toList))
        ommersPool.expectMsg(AddOmmers(oldBlocks.head.header))
      }

      "handle no branch change" in new TestSetup {
        val newBlocks: immutable.IndexedSeq[Block] = (1 to 2).map(_ => getBlock())

        (ledger.resolveBranch _).expects(newBlocks.map(_.header)).returning(NoChainSwitch)

        sendBlockHeadersFromBlocks(newBlocks)

        ommersPool.expectMsg(AddOmmers(newBlocks.head.header))
      }

      // TODO: the following 3 tests are very poor, but fixing that requires re-designing much of the sync actors, with testing in mind
      "handle unknown branch about to be resolved" in new TestSetup {
        val newBlocks: immutable.IndexedSeq[Block] = (1 to 10).map(_ => getBlock())

        (ledger.resolveBranch _).expects(newBlocks.map(_.header)).returning(UnknownBranch)

        sendBlockHeadersFromBlocks(newBlocks)

        eventually {
          regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe false
        }
      }

      "handle unknown branch that can't be resolved" in new TestSetup {
        val additionalHeaders: immutable.IndexedSeq[BlockHeader] = (1 to 2).map(_ => getBlock().header)
        val newHeaders: immutable.IndexedSeq[BlockHeader] =
          getBlock().header.copy(parentHash = additionalHeaders.head.hash) +: (1 to 9).map(_ => getBlock().header)

        inSequence {
          (ledger.resolveBranch _).expects(newHeaders).returning(UnknownBranch)
          (ledger.resolveBranch _).expects(additionalHeaders.reverse ++ newHeaders).returning(UnknownBranch)
        }

        sendBlockHeaders(newHeaders)

        sendBlockHeaders(additionalHeaders)

        eventually (timeout(Span(1, Second))) {
          regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe true
        }
      }

      "return to normal syncing mode after successful branch resolution" in new TestSetup {
        val additionalHeaders: immutable.IndexedSeq[BlockHeader] = (1 to 2).map(_ => getBlock().header)
        val newHeaders: immutable.IndexedSeq[BlockHeader] =
          getBlock().header.copy(parentHash = additionalHeaders.head.hash) +: (1 to 9).map(_ => getBlock().header)

        inSequence {
          (ledger.resolveBranch _).expects(newHeaders).returning(UnknownBranch)
          (ledger.resolveBranch _).expects(additionalHeaders.reverse ++ newHeaders).returning(NoChainSwitch)
        }

        sendBlockHeaders(newHeaders)
        sendBlockHeaders(additionalHeaders)

        eventually {
          regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe false
        }
      }

      "return to normal syncing mode after branch resolution request failed" in new ShortResponseTimeout with TestSetup {
        val newHeaders: immutable.IndexedSeq[BlockHeader] = (1 to 10).map(_ => getBlock().header)

        (ledger.resolveBranch _).expects(newHeaders).returning(UnknownBranch)

        sendBlockHeaders(newHeaders)

        eventually {
          regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe true
        }
      }

      "handle invalid branch" in new TestSetup {
        val newBlocks: immutable.IndexedSeq[Block] = (1 to 2).map(_ => getBlock())

        (ledger.resolveBranch _).expects(newBlocks.map(_.header)).returning(InvalidBranch)

        sendBlockHeadersFromBlocks(newBlocks)

        eventually {
          regularSync.underlyingActor.isBlacklisted(peer1.id) shouldBe true
        }
      }
    }

    "receiving BlockBodies msg" should {

      "handle missing state nodes" in new TestSetup {
        val newBlock: Block = getBlock()
        val missingNodeValue = ByteString("42")
        val missingNodeHash: ByteString = kec256(missingNodeValue)

        inSequence {
          (ledger.resolveBranch _).expects(Seq(newBlock.header)).returning(NewBetterBranch(Nil))
          (ledger.importBlock _).expects(newBlock).throwing(new MissingNodeException(missingNodeHash))
          (ledger.importBlock _).expects(newBlock).returning(BlockImportedToTop(List(newBlock), List(0)))
        }

        sendBlockHeadersFromBlocks(Seq(newBlock))
        sendBlockBodiesFromBlocks(Seq(newBlock))

        sendNodeData(Seq(missingNodeValue))
      }
    }
  }

  trait TestSetup extends DefaultSyncConfig with EphemBlockchainTestSetup with SecureRandomBuilder {
    override implicit lazy val system: ActorSystem = testKitActorSystem

    storagesInstance.storages.appStateStorage.putBestBlockNumber(0)

    val etcPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val ommersPool = TestProbe()
    val txPool = TestProbe()
    val broadcaster: BlockBroadcast = mock[BlockBroadcast]
    override lazy val ledger: Ledger = mock[Ledger]

    val regularSync: TestActorRef[RegularSync] = TestActorRef[RegularSync](RegularSync.props(
      storagesInstance.storages.appStateStorage,
      etcPeerManager.ref,
      peerEventBus.ref,
      ommersPool.ref,
      txPool.ref,
      broadcaster,
      ledger,
      blockchain,
      syncConfig,
      system.scheduler
    ))

    val peer1 = Peer(new InetSocketAddress("127.0.0.1", 0), TestProbe().ref, incomingConnection = false)
    val peer1Status= Status(1, 1, 1, ByteString("peer1_bestHash"), ByteString("unused"))
    val peer1Info = PeerInfo(peer1Status, forkAccepted = true, totalDifficulty = peer1Status.totalDifficulty, maxBlockNumber = 0)

    val handshakedPeers = Map(peer1 -> peer1Info)

    regularSync ! HandshakedPeers(handshakedPeers)
    regularSync ! RegularSync.StartIdle

    val defaultHeader = BlockHeader(
      parentHash = bEmpty,
      ommersHash = bEmpty,
      beneficiary = bEmpty,
      stateRoot = bEmpty,
      transactionsRoot = bEmpty,
      receiptsRoot = bEmpty,
      logsBloom = bEmpty,
      difficulty = 1000000,
      number = 1,
      gasLimit = 1000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = bEmpty,
      mixHash = bEmpty,
      nonce = bEmpty
    )

    val defaultTx = Transaction(
      nonce = 42,
      gasPrice = 1,
      gasLimit = 90000,
      receivingAddress = Address(123),
      value = 0,
      payload = bEmpty)

    val defaultTd = 12345

    val keyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)

    def randomHash(): ByteString =
      ObjectGenerators.byteStringOfLengthNGen(32).sample.get

    def getBlock(): Block = {
      val header = defaultHeader.copy(extraData = randomHash())
      val ommer = defaultHeader.copy(extraData = randomHash())
      val tx = defaultTx.copy(payload = randomHash())
      val stx = SignedTransaction.sign(tx, keyPair, None)

      Block(header, BlockBody(List(stx), List(ommer)))
    }

    def sendNewBlockMsg(block: Block): Unit = {
      regularSync ! MessageFromPeer(NewBlock(block, 0), peer1.id)
    }

    def randomBlockHash(blockNum: BigInt = 1): BlockHash =
      BlockHash(randomHash(), blockNum)

    def sendNewBlockHashMsg(blockHashes: Seq[BlockHash]): Unit = {
      regularSync ! MessageFromPeer(NewBlockHashes(blockHashes), peer1.id)
    }

    def sendMinedBlockMsg(block: Block): Unit = {
      regularSync ! MinedBlock(block)
    }

    def sendBlockHeadersFromBlocks(blocks: Seq[Block]): Unit = {
      sendBlockHeaders(blocks.map(_.header))
    }

    def sendBlockBodiesFromBlocks(blocks: Seq[Block]): Unit = {
      sendBlockBodies(blocks.map(_.body))
    }

    def sendBlockHeaders(headers: Seq[BlockHeader]): Unit = {
      regularSync ! ResponseReceived(peer1, BlockHeaders(headers), 0)
    }

    def sendBlockBodies(bodies: Seq[BlockBody]): Unit = {
      regularSync ! ResponseReceived(peer1, BlockBodies(bodies), 0)
    }

    def sendNodeData(nodeValues: Seq[ByteString]): Unit = {
      regularSync ! ResponseReceived(peer1, NodeData(nodeValues), 0)
    }
  }

  trait DefaultSyncConfig extends SyncConfigBuilder {
    val defaultSyncConfig = SyncConfig(
      printStatusInterval = 1.hour,
      persistStateSnapshotInterval = 20.seconds,
      targetBlockOffset = 500,
      branchResolutionRequestSize = 2,
      blacklistDuration = 5.seconds,
      syncRetryInterval = 1.second,
      checkForNewBlockInterval = 1.milli,
      startRetryInterval = 500.milliseconds,
      blockChainOnlyPeersPoolSize = 100,
      maxConcurrentRequests = 10,
      blockHeadersPerRequest = 2,
      blockBodiesPerRequest = 10,
      doFastSync = false,
      nodesPerRequest = 10,
      receiptsPerRequest = 10,
      minPeersToChooseTargetBlock = 2,
      peerResponseTimeout = 1.second,
      peersScanInterval = 500.milliseconds,
      fastSyncThrottle = 100.milliseconds,
      maxQueuedBlockNumberAhead = 10,
      maxQueuedBlockNumberBehind = 10,
      maxNewBlockHashAge = 20,
      maxNewHashes = 64,
      broadcastNewBlockHashes = true,
      redownloadMissingStateNodes = true,
      fastSyncBlockValidationK = 100,
      fastSyncBlockValidationN = 2048,
      fastSyncBlockValidationX = 50,
      maxTargetDifference =  5,
      maximumTargetUpdateFailures = 1
    )

    override lazy val syncConfig: SyncConfig = defaultSyncConfig
  }

  trait ShortResponseTimeout extends DefaultSyncConfig {
    override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(peerResponseTimeout = 1.milli)
  }
}
