package models

import com.mohiva.play.silhouette.api.{Identity, LoginInfo}
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait Identifiable {
  def loginInfo: LoginInfo
}

/**
 * The user object.
 *
 * @param loginInfo The linked login info. Uniquely identifies a user.
 * @param username the github username ex: callicles
 * @param fullName Maybe the full name of the authenticated user.
 * @param email Maybe the email of the authenticated provider.
 * @param avatarURL Maybe the avatar URL of the authenticated provider.
 * @param karma current karma of the user
 */
case class User(
                 loginInfo: LoginInfo,
                 username: String,
                 fullName: Option[String],
                 email: Option[String],
                 avatarURL: Option[String],
                 karma: Int,
                 publicEventsETag: Option[String], // An ETag is used to know if the data has been modified since the last poll
                 lastPublicEventPull: Option[Long] // Used to filter the already retrieved events
               ) extends Identity with Identifiable

object User {
  implicit val userWrites: Writes[User] = (
    (JsPath \ "loginInfo").write[LoginInfo] and
    (JsPath \ "username").write[String] and
    (JsPath \ "fullName").writeNullable[String] and
    (JsPath \ "email").writeNullable[String] and
    (JsPath \ "avatarURL").writeNullable[String] and
    (JsPath \ "karma").write[Int] and
    (JsPath \ "publicEventsETag").writeNullable[String] and
    (JsPath \ "lastPublicEventPull").writeNullable[Long]
    )(unlift(User.unapply))
}

case class Contributor(
                        user: User,
                        contributions: Contribution)
