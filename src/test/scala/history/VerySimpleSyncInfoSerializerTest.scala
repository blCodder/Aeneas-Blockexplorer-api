package history

import block.PowBlock
import scorex.core.ModifierId
import scorex.core.transaction.state.PrivateKey25519Companion
import settings.SimpleSettings

/**
  * @author luger. Created on 06.02.18.
  * @version ${VERSION}
  */
class VerySimpleSyncInfoSerializerTest extends org.scalatest.FunSuite {
  test ("From Air SyncInfo Serialize") {
    val config = "/Users/flystyle/Documents/Work/Scorex_Aeneas/Scorex/simpleblockchain/src/main/resources/settings.conf"
    val settings = SimpleSettings.read(Some(config))
    val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)

    val block1 = new PowBlock(
      settings.miningSettings.GenesisParentId,
      System.currentTimeMillis(),
      1,
      0,
      Array.fill(32) (0 : Byte),
      genesisAccount._2,
      Seq()
    )

    val block2 = new PowBlock(
      ModifierId @@ block1.id,
      System.currentTimeMillis(),
      2,
      0,
      Array.fill(32) (0 : Byte),
      genesisAccount._2,
      Seq()
    )

    val block3 = new PowBlock(
      ModifierId @@ block2.id,
      System.currentTimeMillis(),
      3,
      0,
      Array.fill(32) (0 : Byte),
      genesisAccount._2,
      Seq()
    )

    val block4 = new PowBlock(
      ModifierId @@ block3.id,
      System.currentTimeMillis(),
      4,
      0,
      Array.fill(32) (0 : Byte),
      genesisAccount._2,
      Seq()
    )

    val simpleSyncInfo : VerySimpleSyncInfo = VerySimpleSyncInfo(Seq(block1, block2, block3, block4).map(_.id))

    val decoded = VerySimpleSyncInfoSerializer.toBytes(simpleSyncInfo)
    val preEncoded = VerySimpleSyncInfoSerializer.parseBytes(decoded)
    assert(preEncoded.isSuccess)
    val encoded = preEncoded.get
    assert(simpleSyncInfo.lastBlocks.lengthCompare(preEncoded.get.lastBlocks.size) == 0)
    encoded.lastBlocks.zip(simpleSyncInfo.lastBlocks).foreach(el => assert(el._1.deep == el._2.deep))
  }

//
// test ("From History SyncInfo Serialize") {
//    val config = "/Users/flystyle/Documents/Work/Scorex_Aeneas/Scorex/simpleblockchain/src/main/resources/settings.conf"
//    val settings = SimpleSettings.read(Some(config))
//    val history = SimpleHistory.readOrGenerate(settings.scorexSettings, settings.miningSettings)
//    val _syncInfo = history.syncInfo
//
//    val decoded = VerySimpleSyncInfoSerializer.toBytes(_syncInfo)
//    val preEncoded = VerySimpleSyncInfoSerializer.parseBytes(decoded)
//    assert(preEncoded.isSuccess)
//    assert(_syncInfo.equals(preEncoded.get))
//  }
}
