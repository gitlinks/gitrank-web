package actors


import javax.inject.{Named, Inject}

import actors.GitHubActor.UpdateContributions
import actors.UsersSupervisor.{AskGithubForUserContributions, SchedulePulling}
import akka.actor._
import akka.event.LoggingReceive
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import models.{Contribution, User}
import models.daos.{OAuth2InfoDAO, ContributionDAO, UserDAO}
import models.services.UserService
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._


object UsersSupervisor {
  case class AskGithubForUserContributions(loginInfo: LoginInfo)

  case class SchedulePulling(user: User)

  def props = Props[UsersSupervisor]
}

class UsersSupervisor @Inject()(userDAO: UserDAO, oAuth2InfoDAO: OAuth2InfoDAO, contributionDAO: ContributionDAO, userService: UserService, @Named("github-actor") gitHubActor: ActorRef) extends Actor with ActorLogging {

  var cancellableUpdates: Map[LoginInfo, Cancellable] = Map.empty[LoginInfo, Cancellable]

  /**
   * When starting the supervisor, we fetch all users in database and scheduleUpdates
   */
  override def preStart(): Unit = {
    for {
      users <- userDAO.findAll
    } yield users.map(schedulePullingFromGithub).foreach(addCancellableWithReplacement)
  }

  override def receive: Receive = LoggingReceive {
    case s: String => log.info(s)
    case AskGithubForUserContributions(loginInfo) =>
      for {
        user: Option[User] <- userDAO.find(loginInfo)
        authInfo: Option[OAuth2Info] <- oAuth2InfoDAO.find(loginInfo)
      } yield gitHubActor ! UpdateContributions(user.get, authInfo.get)
    case SchedulePulling(user) =>
      addCancellableWithReplacement(schedulePullingFromGithub(user))
  }

  /**
   * this is scheduling to send an UpdateUser to the UsersSupervisor every hour starting now.
   * @param user
   */
  private def schedulePullingFromGithub(user: User): (LoginInfo, Cancellable) = {
    user.loginInfo -> context.system.scheduler.schedule(0.microseconds, 1.hour, self, AskGithubForUserContributions(user.loginInfo))
  }

  /**
   * cancel previous schedule if exists and add new one to cancellableUpdates
   * @param loginInfoWithCancellable
   */
  private def addCancellableWithReplacement(loginInfoWithCancellable: (LoginInfo, Cancellable)) =
    loginInfoWithCancellable match {
      case tuple@(loginInfo, cancellable) =>
        cancellableUpdates.get(loginInfo).map(_.cancel())
        cancellableUpdates += tuple
    }
}