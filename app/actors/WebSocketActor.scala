package actors

import actors.messages.{NodeRegistered, WebSocketSpawned, PlantHumidityUpdated}
import akka.actor.{ActorRef, Props, UntypedActor}
import controllers.PlantController._
import models.Plant
import play.api.Logger
import play.api.libs.json.{JsString, JsObject, JsValue, Json}
import util.SlickDB
import db.Tables._
import scala.slick.driver.MySQLDriver.simple._


class WebSocketActor(out:ActorRef) extends UntypedActor {
  val logger: Logger = Logger(this.getClass)

  // Notify the californium actor that we exist
  global.Global.getCaliforniumActor ! new WebSocketSpawned

  @throws[Exception](classOf[Exception])
  override def onReceive(message: Any): Unit = message match {
    case x:String =>
      logger.debug(s"Received string message: $message")
      handleStringMessage(x)

    case PlantHumidityUpdated(x) => SlickDB.withSession { implicit session =>
      val plants = Plants.list.map(Plant.fromPlantsRow)

      val json: JsValue = JsObject(Seq(
        "action" -> JsString("update plant overview"),
        "html" -> JsString(views.html.index(plants, true).toString)
      ))
      out ! Json.stringify(json)
    }



    case NodeRegistered(x) => SlickDB.withSession { implicit session =>
      val json: JsValue = JsObject(Seq(
        "action" -> JsString("update node list"),
        "html" -> JsString(views.html.node.index(Nodes.list, Plants.list, true).toString)
      ))
      out ! Json.stringify(json)
    }

    case x => logger.debug(s"Received unknown message: $x");
  }


  def handleStringMessage(message:String):Unit = WebSocketActor.parseJson(message) match {
    case Some(("update plant id of node", json)) =>
      val plantId = (json \ "plantId").as[Int]
      val nodeId = (json \ "nodeId").as[Int]
      updatePlantIdOfNode(nodeId, plantId)

    // Error cases
    case Some((_, json)) => logger.debug(s"Unknown JSON message: ${json.toString}")
    case None => logger.debug(s"Could not parse message as Json: $message")
  }

  def updatePlantIdOfNode(nodeId:Int, plantId:Int):Unit = SlickDB.withSession { implicit session =>
    logger.debug(s"Setting plant ID $plantId for node with ID $nodeId")

    val plantIds = for(n <- Nodes if n.plantId === plantId) yield n.plantId
    plantIds.update(None)

    val plantIdToUpdate = for(n <- Nodes if n.id === nodeId) yield n.plantId

    if(plantId == -1) {
      plantIdToUpdate.update(None)
    } else {
      plantIdToUpdate.update(Some(plantId))
    }

  }

}

object WebSocketActor {
  type JsonMessage = (String, JsValue)

  def props(out:ActorRef) = Props(new WebSocketActor(out))

  def parseJson(message:String):Option[JsonMessage] = try {
    val json = Json.parse(message)

    (json \ "action").asOpt[String] match {
      case Some(action) => Some((action, json))
      case None => Some(("unknown", json))
    }
  } catch {
    case _ => None
  }
}
