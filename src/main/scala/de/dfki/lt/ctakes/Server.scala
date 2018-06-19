package de.dfki.lt.ctakes

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.apache.uima.UIMAFramework
import org.apache.uima.cas._
import org.apache.uima.jcas.JCas
import org.apache.uima.jcas.cas._
import org.apache.uima.util.XMLInputSource
import spray.can.server.ServerSettings
import spray.http.HttpEntity
import spray.http.ContentTypes._
import spray.routing.SimpleRoutingApp
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.JSONValue
import org.json.simple.parser.JSONParser
import spray.json._
import java.util.Iterator


import scala.collection.mutable

object Server extends SimpleRoutingApp  {
  implicit val system = ActorSystem("CTakes-REST")

  import DefaultJsonProtocol._

  def main(args: Array[String]) {
    val url = args(0)
    val port = args(1).toInt

    val desc = if(args.length > 2) args(2)
      else "desc/ctakes-clinical-pipeline/desc/analysis_engine/AggregatePlaintextFastUMLSProcessor.xml"

    //get Resource Specifier from XML file
    val in = new XMLInputSource(desc)
    val specifier = UIMAFramework.getXMLParser.parseResourceSpecifier(in)
    //create AE here
    val ae = UIMAFramework.produceAnalysisEngine(specifier)
    val jcas = ae.newJCas()

    val conf = ConfigFactory
      .parseString("""spray.can.server { registration-timeout=infinite }""")
      .withFallback(ConfigFactory.defaultReference())
    val settings = ServerSettings.fromSubConfig(conf.getConfig("spray.can.server"))

    startServer(interface = url, port = port, settings = Some(settings)) {
      path("ctakes") {
        get {
          parameter('text ?) { text =>
            try {
              jcas.reset()
              jcas.setDocumentText(text.getOrElse("You have to provide a 'text' as input!"))
              ae.process(jcas)
	      val json_string = cas2FeatureMap(jcas).toString()
              complete(HttpEntity(`application/json`, json_string))
            } catch {
              case t: Throwable => complete(t.getMessage)
            }
          }
        } ~
        post {
          entity(as[String]) { text =>
            try {
              jcas.reset()
              jcas.setDocumentText(text)
              ae.process(jcas)
              complete(HttpEntity(`application/json`, cas2FeatureMap(jcas).toString()))
            } catch {
              case t: Throwable => complete(t.getMessage)
            }
          }
        }
      }
    }
  }

  def cas2FeatureMap(jcas:JCas) = {
    val anIndex = jcas.getAnnotationIndex()
    val anIter = anIndex.iterator()
    val bf= mutable.ListBuffer[JsValue]()
    while (anIter.isValid) {
      val annot = anIter.get()
      if(annot.getType.getName.toString() contains "Mention"){
      bf += Map("typ" -> annot.getType.getName.toJson, "annotation" -> featureStructure2Map(annot)).toJson }
      anIter.moveToNext()
    }
    bf.result().toJson
  }

  def featureStructure2Map(fs:FeatureStructure):JsValue = {
    val m = mutable.Map[String,JsValue]()
    val featureIt = fs.getType.getFeatures.iterator
    while (featureIt.hasNext) {
      val feat = featureIt.next()
      val value:JsValue = if(feat.getRange.isPrimitive) {
        feat.getRange.getShortName match {
          case "Integer" => fs.getIntValue(feat).toJson
          case "Double" => fs.getDoubleValue(feat).toJson
          case "Float" => fs.getFloatValue(feat).toJson
          case "Short" => fs.getShortValue(feat).toJson
          case _ => val value = fs.getFeatureValueAsString(feat); if(value == null) JsNull else value.toJson
        }
      } else if(feat.getRange.isArray) {
        fs.getFeatureValue(feat) match {
          case a:FSArray => a.toArray.map(fs2 =>
              Map("typ" -> fs2.getType.getName.toJson, "annotation" -> featureStructure2Map(fs2).toJson).toJson).toJson
          case a:DoubleArrayFS => a.toArray.toJson
          case a:IntArrayFS => a.toArray.toJson
          case a:FloatArrayFS => a.toArray.toJson
          case a:ShortArrayFS => a.toArray.toJson
          case a:CommonArrayFS => a.toStringArray.toJson
          case a => JsNull
        }
      } else JsNull
      m += feat.getShortName -> value
    }
    m.toMap.toJson
  }

}
