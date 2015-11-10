package models.daos.drivers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.inject.Inject

import com.fasterxml.jackson.core.{JsonFactory, JsonParser}
import play.api.Play
import play.api.Play.current
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.libs.ws._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Class to generate requests to the neo4j database and get the results.
 *
 * @param ws injected WS play service
 */
class Neo4j @Inject() (ws: WSClient){

  val neo4jEndpoint =
    Play.configuration.getString("neo4j.server").getOrElse("http://localhost:7474") +
      Play.configuration.getString("neo4j.endpoint").getOrElse("/db/data/")

  val neo4jUser = Play.configuration.getString("neo4j.username").getOrElse("neo4j")
  val neo4jPassword = Play.configuration.getString("neo4j.password").getOrElse("neo4j")

  /**
   * Sends a Cypher query to the neo4j server
   *
   * @param query Cypher query sent to the server
   * @param parameters parameter object to be used by the query. (See Cypher reference for more details)
   * @return
   */
  def cypher(query: String, parameters: JsObject): Future[WSResponse] = {
    val request: WSRequest = ws.url(neo4jEndpoint + "transaction/commit")

    buildNeo4JRequest(request).post(Json.obj(
      "statements" -> Json.arr(
        Json.obj(
          "statement" -> query,
          "parameters" -> parameters
        )
      )
    )).map(response => {
      response.status match {
        case 200 =>
          val json = Json.parse(response.body)
          if ((json \ "errors").as[Seq[JsObject]].nonEmpty) {
            throw new Exception(response.body)
          }
          response
        case _ => throw new Exception(response.body)
      }
    })
  }

  /**
   * Builds a request to be sent to the neo4J database
   *
   * @param req request to be modified
   * @return modified request
   */
  def buildNeo4JRequest(req: WSRequest): WSRequest = req
      .withAuth(neo4jUser, neo4jPassword, WSAuthScheme.BASIC)
      .withHeaders("Accept" -> "application/json ; charset=UTF-8", "Content-Type" -> "application/json")
      .withRequestTimeout(10000)

  /**
   * Execute a query with a stream result
   * @param query query for using on the neo4j database
   *
   */
  def cypherStream(query: String): Future[JsonParser] = {
    val outputStream = new ByteArrayOutputStream()
    buildNeo4JRequest(ws.url(neo4jEndpoint + "transaction/commit"))
      .withHeaders("X-Stream" -> "true")
      .withMethod("POST")
      .withBody(Json.obj(
      "statements" -> Json.arr(
        Json.obj(
          "statement" -> query
        )
      )
    ))
      .stream().map{
      case (response,body) =>
        if(response.status == 200){
          val iteratee = Iteratee.foreach[Array[Byte]] { bytes =>
            outputStream.write(bytes)
          }
          (body |>>> iteratee).andThen {
            case result =>
              outputStream.close()
              result.get
          }
          new JsonFactory().createParser(new ByteArrayInputStream(outputStream.toByteArray));
        }else{
          throw new Exception("Failure in getting users");
        }

    }
  }
}
