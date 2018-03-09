package settings

import java.io.File


/**
  * @author luger. Created on 09.03.18.
  * @version ${VERSION}
  */
case class SeedSettings(passPhraseSize: Int = 15, file:String){
  val fileDictSrc = new File (getClass.getResource("/"+file).getPath)
  if (!fileDictSrc.exists()) throw new Error("Malformed words dictionary file was provided! Aborting!")

}
