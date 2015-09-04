package models.daos

import javax.inject.Inject

import models.{Repository, User, Contribution}
import models.daos.drivers.Neo4J
import play.api.libs.json.{JsObject, JsUndefined, Json}
import play.api.libs.ws.WSResponse

import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ContributionDAO @Inject() (neo: Neo4J){

  /**
   * Finds a contribution in the data store.
   *
   * @param username name of the contributing user
   * @param repoName name od the repository he contributed to
   * @return the found Contribution if any.
   */
  def find(username: String, repoName:String): Future[Option[Contribution]] = {
    neo.cypher(
      """
        MATCH (u:User)-[c:CONTRIBUTED_TO]->(r:Repository)
        WHERE u.username={username} AND r.name={repoName}
        RETURN c
      """,
      Json.obj(
        "username" -> username,
        "repoName" -> repoName
      )
    ).map(parseNeoContribution)
  }

  /**
   * Finds all contributions of a given user in the data store.
   *
   * @param username name of the contributing user
   * @return the found Contribution if any.
   */
  def findAll(username: String): Future[Seq[(Repository,Contribution)]] = {
    neo.cypher(
      """
        MATCH (u:User)-[c:CONTRIBUTED_TO]->(r:Repository)
        WHERE u.username={username}
        RETURN r, c
      """,
      Json.obj(
        "username" -> username
      )
    ).map(parseNeoContributions)
  }

  /**
   * Adds a contribution for a user to a repository. If the username or the repository doesn't exist. This doesn't do
   * anything and returns None.
   *
   * @param username name of the contributing user
   * @param repoName name of the repository he contributes to.
   * @param contribution contribution to be saved.
   * @return saved contribution
   */
  def add(username: String, repoName: String, contribution: Contribution): Future[Option[Contribution]] = {
    neo.cypher(
      """
        MATCH (u:User),(r:Repository)
        WHERE u.username={username} AND r.name={repoName}
        CREATE (u)-[c:CONTRIBUTED_TO {props}]->(r)
        RETURN c
      """,
      Json.obj(
        "username" -> username,
        "repoName" -> repoName,
        "props" -> Json.toJson(contribution)
      )
    ).map(parseNeoContribution)
  }

  /**
   * Overrides an existing contribution relationship with the one provided. If the username or the repository doesn't exist. This doesn't do
   * anything and returns None.
   *
   * @param username name of the user who made the contribution
   * @param repoName name of the repo he contributed to
   * @param contribution contribution to be saved
   * @return saved contribution
   */
  def update(username: String, repoName: String, contribution: Contribution): Future[Option[Contribution]] = {
    neo.cypher(
      """
        MATCH (u:User)-[c:CONTRIBUTED_TO]->(r:Repository)
        WHERE u.username={username} AND r.name={repoName}
        SET c={props}
        RETURN c
      """,
      Json.obj(
        "username" -> username,
        "repoName" -> repoName,
        "props" -> Json.toJson(contribution)
      )
    ).map(parseNeoContribution)
  }

  /**
   * Parses a neo4j response to get a Contribution out of it.
   *
   * @param response neo4j response
   * @return parsed contribution or None
   */
  def parseNeoContribution(response: WSResponse): Option[Contribution] = {
    (((Json.parse(response.body) \ "results")(0) \ "data")(0) \ "row")(0) match {
      case _: JsUndefined => None
      case repo => repo.asOpt[Contribution]
    }
  }

  /**
   * Parse multiple contributions and repos
   * @param response response from neo4j
   * @return map with each contribution from repo
   */
  def parseNeoContributions(response: WSResponse): Seq[(Repository,Contribution)] = {
      ((Json.parse(response.body) \\ "row")).map{
        case contribution => (contribution(0).as[Repository], contribution(1).as[Contribution])
      }.seq
  }

}
