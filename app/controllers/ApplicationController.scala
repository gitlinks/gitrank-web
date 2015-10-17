package controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import models.daos.drivers.GitHubAPI
import models.forms.QuickstartForm
import models.services.{QuickstartService, RepositoryService, UserService}
import models.{Feedback, User}
import modules.CustomGitHubProvider
import play.api.i18n.MessagesApi
import forms.FeedbackForm
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
 * The basic application controller.
 *
 * @param messagesApi The Play messages API.
 * @param env The Silhouette environment.
 * @param gitHubProvider The social provider registry.
 */
class ApplicationController @Inject()(
                                       val messagesApi: MessagesApi,
                                       val env: Environment[User, SessionAuthenticator],
                                       gitHubProvider: CustomGitHubProvider,
                                       repoService: RepositoryService,
                                       userService: UserService,
                                       gitHub: GitHubAPI,
                                       quickstartService: QuickstartService)
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
  def gitHubRepository(owner: String, repositoryName: String, page: Option[Int] = None) = UserAwareAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(request.identity, repoName).flatMap({
      case Some(repository) => repoService.getFeedback(repoName, page).flatMap((feedback: Seq[Feedback]) =>
        repoService.getFeedbackPageCount(repoName).flatMap(totalPage => {
          repoService.canAddFeedback(repoName, request.identity).flatMap({
            case true => repoService.canUpdateFeedback(repoName, request.identity).map(
              canUpdate => Ok(views.html.repository(gitHubProvider, request.identity, repository, feedback, totalPage, true, canUpdate)
                (owner, repositoryName, page.getOrElse(1))))
            case false => Future.successful(Ok(views.html.repository(gitHubProvider, request.identity, repository, feedback, totalPage)
              (owner, repositoryName, page.getOrElse(1))))
          })
        })
      )
      case None => Future(NotFound(views.html.error("notFound", 404, "Not Found",
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
  def giveFeedbackPage(owner: String, repositoryName: String) = UserAwareAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(request.identity, repoName).flatMap({
      case Some(repository) =>
        repoService.canUpdateFeedback(repoName, request.identity).map(canUpdate =>
          Ok(views.html.feedbackForm(gitHubProvider, request.identity)(owner, repositoryName, FeedbackForm.form, canUpdate))
        )
      case None => Future(NotFound(views.html.error("notFound", 404, "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else !")))
    })
  }

  /**
   * Handles the feedback score post
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return Redirect to repo page
   */
  def postScore(owner: String, repositoryName: String, update: Option[Boolean]) = SecuredAction.async {implicit request =>
    FeedbackForm.form.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.feedbackForm(gitHubProvider, Some(request.identity))
        (owner, repositoryName, formWithErrors, update.getOrElse(false)))),
      data => repoService.giveScoreToRepo(owner,
        request.identity,
        repositoryName,
        data.scoreDocumentation,
        data.scoreMaturity,
        data.scoreDesign,
        data.scoreSupport,
        data.feedback
      ).map(repo => Redirect(routes.ApplicationController.gitHubRepository(owner, repositoryName, None).url))
    )
  }

  /**
   * Handles the quickstart guide post
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return Redirect to repo page
   */
  def postQuickstartGuide(owner: String, repositoryName: String) = SecuredAction.async { implicit request =>
    QuickstartForm.form.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.quickstartGuide(gitHubProvider, Some(request.identity))
        (owner, repositoryName, formWithErrors)))
      ,
      data => {
        repoService.getFromNeoOrGitHub(Some(request.identity), owner + "/" + repositoryName).flatMap {
          case Some(repo) => quickstartService.createQuickstart(
            request.identity,
            repo,
            data.title,
            data.description,
            QuickstartForm.validateUrl(data.url)
          ).map(q => Redirect(routes.ApplicationController.gitHubRepository(owner, repositoryName, None).url))
          case None => Future(NotFound(views.html.error("notFound", 404, "Not Found",
            "We cannot find the repository feedback page, it is likely that you misspelled it, try something else !")))
        }
      })
  }

  /**
   * Handles the feedback page
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return the hml page with the scoring form for the given repository.
   */
  def createGuidePage(owner: String, repositoryName: String) = UserAwareAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(request.identity, repoName).map({
      case Some(repository) =>
        Ok(views.html.quickstartGuide(gitHubProvider, request.identity)(owner, repositoryName, QuickstartForm.form))
      case None => NotFound(views.html.error("notFound", 404, "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else !"))
    })
  }


  /**
   * Service for upvoting a guide
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @param title title of the guide
   * @param voteType if the vote is upvote or downvote
   * @return the guide
   */
  def upVote(owner: String, repositoryName: String, title: String, voteType: String) = SecuredAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(Some(request.identity), repoName).flatMap({
      case Some(repository) =>
        voteType match {
          case "upvote" => quickstartService.updateVote(repository, true, title, request.identity).map(guide => Ok(Json.toJson(guide)))
          case _ => quickstartService.updateVote(repository, false, title,request.identity).map(guide => Ok(Json.toJson(guide)))
        }
      case None => Future(NotFound(views.html.error("notFound", 404, "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else !")))
    })
  }
}
