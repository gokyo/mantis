package io.iohk.ethereum.blockchain.sync

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.util.ByteString
import cats.data.NonEmptyList
import io.iohk.ethereum.blockchain.sync.SyncStateDownloaderActor.{
  DownloaderState,
  NoUsefulDataInResponse,
  UnrequestedResponse,
  UsefulData
}
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.network.Peer
import io.iohk.ethereum.network.p2p.messages.PV63.NodeData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SyncStateDownloaderStateSpec extends AnyFlatSpec with Matchers {
  implicit val as = ActorSystem()
  "DownloaderState" should "schedule requests for retrieval" in new TestSetup {
    val newState = initialState.scheduleNewNodesForRetrieval(potentialNodesHashes)
    assert(newState.nodesToGet.size == potentialNodesHashes.size)
    assert(newState.nonDownloadedNodes.size == potentialNodesHashes.size)
    assert(potentialNodesHashes.forall(h => newState.nodesToGet.contains(h)))
  }

  it should "assign request to peers from already scheduled nodes to a max capacity" in new TestSetup {
    val perPeerCapacity = 20
    val newState = initialState.scheduleNewNodesForRetrieval(potentialNodesHashes)
    val (requests, newState1) = newState.assignTasksToPeers(peers, None, nodesPerPeerCapacity = perPeerCapacity)
    assert(requests.size == 3)
    assert(requests.forall(req => req.nodes.size == perPeerCapacity))
    assert(newState1.activeRequests.size == 3)
    assert(newState1.nonDownloadedNodes.size == potentialNodesHashes.size - (peers.size * perPeerCapacity))
    assert(
      requests.forall(request => request.nodes.forall(hash => newState1.nodesToGet(hash).contains(request.peer.id)))
    )
  }

  it should "favour already existing requests when assigning tasks with new requests" in new TestSetup {
    val perPeerCapacity = 20
    val (alreadyExistingTasks, newTasks) = potentialNodesHashes.splitAt(2 * perPeerCapacity)
    val newState = initialState.scheduleNewNodesForRetrieval(alreadyExistingTasks)
    val (requests, newState1) =
      newState.assignTasksToPeers(peers, Some(newTasks), nodesPerPeerCapacity = perPeerCapacity)
    assert(requests.size == 3)
    assert(requests.forall(req => req.nodes.size == perPeerCapacity))
    // all already existing task should endup in delivery
    assert(alreadyExistingTasks.forall(hash => newState1.nodesToGet(hash).isDefined))
    // check that first 20 nodes from new nodes has been schedued for delivery and next 40 is waiting for available peer
    assert(newTasks.take(perPeerCapacity).forall(hash => newState1.nodesToGet(hash).isDefined))
    assert(newTasks.drop(perPeerCapacity).forall(hash => newState1.nodesToGet(hash).isEmpty))

    // standard check that active requests are in line with nodes in delivery
    assert(newState1.activeRequests.size == 3)
    assert(newState1.nonDownloadedNodes.size == potentialNodesHashes.size - (peers.size * perPeerCapacity))
    assert(
      requests.forall(request => request.nodes.forall(hash => newState1.nodesToGet(hash).contains(request.peer.id)))
    )
  }

  it should "correctly handle incoming responses" in new TestSetup {
    val perPeerCapacity = 20
    val newState = initialState.scheduleNewNodesForRetrieval(potentialNodesHashes)
    val (requests, newState1) = newState.assignTasksToPeers(peers, None, nodesPerPeerCapacity = perPeerCapacity)
    assert(requests.size == 3)
    assert(requests.forall(req => req.nodes.size == perPeerCapacity))

    val (handlingResult, newState2) =
      newState1.handleRequestSuccess(requests(0).peer, NodeData(requests(0).nodes.map(h => hashNodeMap(h))))
    assert(handlingResult.isInstanceOf[UsefulData])
    assert(handlingResult.asInstanceOf[UsefulData].responses.size == perPeerCapacity)
    assert(requests(0).nodes.forall(h => !newState2.nodesToGet.contains(h)))
    assert(newState2.activeRequests.size == 2)

    val (handlingResult1, newState3) =
      newState2.handleRequestSuccess(requests(1).peer, NodeData(requests(1).nodes.map(h => hashNodeMap(h))))
    assert(handlingResult1.isInstanceOf[UsefulData])
    assert(handlingResult1.asInstanceOf[UsefulData].responses.size == perPeerCapacity)
    assert(requests(1).nodes.forall(h => !newState3.nodesToGet.contains(h)))
    assert(newState3.activeRequests.size == 1)

    val (handlingResult2, newState4) =
      newState3.handleRequestSuccess(requests(2).peer, NodeData(requests(2).nodes.map(h => hashNodeMap(h))))
    assert(handlingResult2.isInstanceOf[UsefulData])
    assert(handlingResult2.asInstanceOf[UsefulData].responses.size == perPeerCapacity)
    assert(requests(2).nodes.forall(h => !newState4.nodesToGet.contains(h)))
    assert(newState4.activeRequests.isEmpty)
  }

  it should "ignore responses from not requested peers" in new TestSetup {
    val perPeerCapacity = 20
    val newState = initialState.scheduleNewNodesForRetrieval(potentialNodesHashes)
    val (requests, newState1) = newState.assignTasksToPeers(peers, None, nodesPerPeerCapacity = perPeerCapacity)
    assert(requests.size == 3)
    assert(requests.forall(req => req.nodes.size == perPeerCapacity))

    val (handlingResult, newState2) =
      newState1.handleRequestSuccess(notKnownPeer, NodeData(requests(0).nodes.map(h => hashNodeMap(h))))
    assert(handlingResult == UnrequestedResponse)
    // check that all requests are unchanged
    assert(newState2.activeRequests.size == 3)
    assert(requests.forall({ req =>
      req.nodes.forall(h => newState2.nodesToGet(h).contains(req.peer.id))
    }))
  }

  it should "handle empty responses from from peers" in new TestSetup {
    val perPeerCapacity = 20
    val newState = initialState.scheduleNewNodesForRetrieval(potentialNodesHashes)
    val (requests, newState1) = newState.assignTasksToPeers(peers, None, nodesPerPeerCapacity = perPeerCapacity)
    assert(requests.size == 3)
    assert(requests.forall(req => req.nodes.size == perPeerCapacity))

    val (handlingResult, newState2) = newState1.handleRequestSuccess(requests(0).peer, NodeData(Seq()))
    assert(handlingResult == NoUsefulDataInResponse)
    assert(newState2.activeRequests.size == 2)
    // hashes are still in download queue but they are free to graby other peers
    assert(requests(0).nodes.forall(h => newState2.nodesToGet(h).isEmpty))
  }

  it should "handle response where part of data is malformed (bad hashes)" in new TestSetup {
    val perPeerCapacity = 20
    val goodResponseCap = perPeerCapacity / 2
    val newState = initialState.scheduleNewNodesForRetrieval(potentialNodesHashes)
    val (requests, newState1) = newState.assignTasksToPeers(
      NonEmptyList.fromListUnsafe(List(peer1)),
      None,
      nodesPerPeerCapacity = perPeerCapacity
    )
    assert(requests.size == 1)
    assert(requests.forall(req => req.nodes.size == perPeerCapacity))
    val peerRequest = requests.head
    val goodResponse = peerRequest.nodes.take(perPeerCapacity / 2).map(h => hashNodeMap(h))
    val badResponse = (200 until 210).map(ByteString(_)).toList
    val (result, newState2) = newState1.handleRequestSuccess(requests(0).peer, NodeData(goodResponse ++ badResponse))
    assert(result.isInstanceOf[UsefulData])
    assert(result.asInstanceOf[UsefulData].responses.size == perPeerCapacity / 2)
    assert(newState2.activeRequests.isEmpty)
    // good responses where delivered and removed form request queue
    assert(peerRequest.nodes.take(goodResponseCap).forall(h => !newState2.nodesToGet.contains(h)))
    // bad responses has been put back to map but without active peer
    assert(peerRequest.nodes.drop(goodResponseCap).forall(h => newState2.nodesToGet.contains(h)))
    assert(peerRequest.nodes.drop(goodResponseCap).forall(h => newState2.nodesToGet(h).isEmpty))
  }

  trait TestSetup extends {
    val ref1 = TestProbe().ref
    val ref2 = TestProbe().ref
    val ref3 = TestProbe().ref
    val ref4 = TestProbe().ref

    val initialState = DownloaderState(Map.empty, Map.empty)
    val peer1 = Peer(new InetSocketAddress("127.0.0.1", 1), ref1, incomingConnection = false)
    val peer2 = Peer(new InetSocketAddress("127.0.0.1", 2), ref2, incomingConnection = false)
    val peer3 = Peer(new InetSocketAddress("127.0.0.1", 3), ref3, incomingConnection = false)
    val notKnownPeer = Peer(new InetSocketAddress("127.0.0.1", 4), ref4, incomingConnection = false)
    val peers = NonEmptyList.fromListUnsafe(List(peer1, peer2, peer3))
    val potentialNodes = (1 to 100).map(i => ByteString(i)).toList
    val potentialNodesHashes = potentialNodes.map(node => kec256(node))
    val hashNodeMap = potentialNodesHashes.zip(potentialNodes).toMap
  }

}
