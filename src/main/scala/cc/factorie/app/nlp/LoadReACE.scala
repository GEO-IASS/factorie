package cc.factorie.app.nlp

import coref._
import ner.NerSpan
import pos.PosLabel
import relation.RelationVariables.{RelationMention, RelationMentions}

import xml.{XML, NodeSeq}
import java.io.File

/**
 * @author brian martin
 * @date 1/23/11
 */

trait ReACEMentionIdentifiers {
  val mId: Option[String]
  val eId: Option[String]
  val headStart: Option[Int]
  val headEnd: Option[Int]
  val headLength: Option[Int]
  val mType: String
  val mSubType: String
}

trait ReACERelationIdentifiers {
  val rId: Option[String]
  val rType: Option[String]
  val rSubtype: Option[String]
}

trait ReACESentenceAnnotations {
  val paragraphId: Option[String]
  val sentenceId: Option[String]
}

trait ReACEWordAnnotations {
  val lemma: Option[String]
  val pos: Option[String]
  val chunk: Option[String]
  val nounHead: Option[String]
  val verbStem: Option[String]
  val verbHead: Option[String]
  val verbVoice: Option[String]
  val verbNeg: Option[String]
}

class ReACESentenceId(val sentId: String)

object LoadReACE {

  private def getAttr(ns: NodeSeq, key: String): Option[String] = {
    val fullKey: String = "@" + key
    val v = (ns \ fullKey).text
    if (v == "") None
    else Some(v)
  }

  private def makeTokenAnnotations(wordXml: NodeSeq): ReACEWordAnnotations = {
    val a: String => Option[String] = getAttr(wordXml, _)
    new ReACEWordAnnotations {
      val lemma: Option[String] = a("l")
      val pos: Option[String] = a("p")
      val chunk: Option[String] = a("phr")
      val nounHead: Option[String] = a("headn")
      val verbStem: Option[String] = a("vstem")
      val verbHead: Option[String] = a("headv")
      val verbVoice: Option[String] = a("voice")
      val verbNeg: Option[String] = a("neg")
    }
  }

  private def makeDoc(xml: String): Document = {
    val doc = new Document(xml)
    doc.attr += new ACEFileIdentifier(xml)
    val xmlText: NodeSeq = XML.loadFile(xml + ".ttt.xml")

    var currP = 0
    for (p <- xmlText \\ "p") {
      currP += 1
      for (s <- p \\ "s") {
        val sId = getAttr(s, "id")
        val sent = new Sentence(doc)(null)
        sent.attr += new ReACESentenceAnnotations {
          val paragraphId = Some(currP.toString);
          val sentenceId = sId
        }
        for (w <- s \\ "w") {
          val t = new Token(sent, w.text)
          doc.appendString(" ")
          val annotations = makeTokenAnnotations(w)
          t.attr += annotations
          annotations.pos.foreach(p => t.attr += new PosLabel(t, p))
        }
      }
    }
    doc
  }

  private def lookupEntityMention(doc: Document, id: String): Option[PairwiseMention] = {
    val opt = doc.spans.find {
      s => {
        val a = s.attr[ReACEMentionIdentifiers]
        (a ne null) && a.mId.get == id
      }
    }
    if (opt == None) None
    else Some(opt.get.asInstanceOf[PairwiseMention])
  }

  def addNrm(doc: Document, xml: String): Document = {
    var xmlText: NodeSeq = XML.loadFile(xml + ".nrm.xml")
    assert(doc.attr[ACEFileIdentifier].fileId == xml) // adding to the right document?

    // Add mentions
    for (mention <- xmlText \\ "ne") {
      // named-entity mentions
      // phrase span
      val start = (mention \ "@fr").text.drop(1).toInt - 1
      val end = (mention \ "@to").text.drop(1).toInt - 1
      val length = end - start + 1

      // head span
      val hstart = (mention \ "@hfr").text.drop(1).toInt - 1
      val hend = (mention \ "@hto").text.drop(1).toInt - 1
      val hlength = hend - hstart + 1

      // ner type
      val nerType = (mention \ "@t").text
      val nerSubType = (mention \ "@st").text

      val m = new NerSpan(doc, nerType, start, length)(null) with PairwiseMention

      m.attr += new ReACEMentionIdentifiers {
        val mId = getAttr(mention, "id")
        val eId = getAttr(mention, "gid")
        val headStart = Some(hstart)
        val headEnd = Some(hend)
        val headLength = Some(hlength)
        val mType = nerType
        val mSubType = nerSubType
      }

      // set the head of the mention
      m.attr[ReACEMentionIdentifiers].headEnd.foreach(he => m._head = doc(he))
    }


    // Add relations
    xmlText = XML.loadFile(xml + ".nrm.xml") // is there a way to avoid rereading?
    doc.attr += new RelationMentions
    for (rel <- (xmlText \\ "rel")) {
      val ids = new ReACERelationIdentifiers {
        val rId = getAttr(rel, "id")
        val rType = getAttr(rel, "t")
        val rSubtype = getAttr(rel, "st")
      }

      val e1 = lookupEntityMention(doc, getAttr(rel, "e1").get).get
      val e2 = lookupEntityMention(doc, getAttr(rel, "e2").get).get
      val args = Seq(e1, e2)

      val m = new RelationMention(e1, e2, ids.rType.get) // + "-" + ids.rSubtype.get)
      m.attr += ids
      doc.attr[RelationMentions].add(m)(null)
      args.foreach(_.attr.getOrElseUpdate(new RelationMentions).add(m)(null))
    }

    doc
  }

  // TODO: consider renaming this to fromFile to match the API for other loaders.
  // But if renamed, how can the user know that ttt.xml is required?
  def fromTtt(ttt: String): Document = {
    val fileStr = ttt.dropRight(8)
    val doc = makeDoc(fileStr)
    addNrm(doc, fileStr)
    doc
  }

  def fromDirectory(dir: String, takeOnly: Int = Int.MaxValue): Seq[Document] =
    new File(dir).listFiles().filter(_.getName.endsWith(".ttt.xml")).take(takeOnly).map(f => fromTtt(f.getAbsolutePath))

  def main(args: Array[String]): Unit = {
    val docs = fromDirectory(args(0))
    for (d <- docs)
      d.spansOfClass[PairwiseMention].foreach(s => println(s))
  }

}