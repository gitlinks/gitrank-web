package controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import models.{Score, User}
import models.daos.drivers.GitHubAPI
import models.services.{RepositoryService, UserService}
import modules.CustomGitHubProvider
import play.api.i18n.MessagesApi

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * The basic application controller.
 *
 * @param messagesApi The Play messages API.
 * @param env The Silhouette environment.
 * @param gitHubProvider The social provider registry.
 */
class ApplicationController @Inject() (
                                        val messagesApi: MessagesApi,
                                        val env: Environment[User, SessionAuthenticator],
                                        gitHubProvider: CustomGitHubProvider,
                                        repoService: RepositoryService,
                                        userService: UserService,
                                        gitHub: GitHubAPI)
  extends Silhouette[User, SessionAuthenticator] {

  /**
   * Handles the main action.
   *
   * @return The result to display.
   */
  def index = UserAwareAction.async { implicit request =>
    Future.successful(Ok(views.html.home(gitHubProvider, request.identity)))
  }

  /**
   * Handles the repository view
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system. (GitHub)
   * @return The html page of the repository
   */
  def gitHubRepository(owner: String, repositoryName: String) = UserAwareAction.async { implicit request =>
    repoService.getFromNeoOrGitHub(request.identity, owner + "/" + repositoryName).flatMap({
      case Some(repository) => repoService.getFeedback(owner + "/" + repositoryName).map((scores: Seq[Score])=>
        Ok(views.html.repository(gitHubProvider, request.identity, repository, scores)(owner, repositoryName))
      )
      case None => Future(NotFound(views.html.error("notFound", 404 , "Not Found",
        "We cannot find the repository page, it is likely that you misspelled it, try something else !")))
    })
  }

  /**
   * Handles the feedback page
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return the hml page with the scoring form for the given repository.
   */
  def giveFeedbackPage(owner: String, repositoryName: String) = UserAwareAction.async {implicit request =>
    repoService.getFromNeoOrGitHub(request.identity, owner + "/" + repositoryName).map({
      case Some(repository) => Ok(views.html.feedbackForm(gitHubProvider, request.identity)(owner, repositoryName))
      case None => NotFound(views.html.error("notFound",404 , "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else !"))
    })
  }
}
