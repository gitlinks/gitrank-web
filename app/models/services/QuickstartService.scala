package models.services

import java.util.Date
import javax.inject.Inject

import models.daos.QuickstartDAO
import models.{Quickstart, Repository, User}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class QuickstartService @Inject()(
                                   quickstartDAO: QuickstartDAO,
                                   repositoryService: RepositoryService) {

  /**
   * Create a quickstart guide
   *
   * @param user user who made the guide
   * @param repoName repo name for whom the guide is intended
   * @param title title of the quick starter
   * @param description description of the quick starter
   * @param url url
   * @return guide created
   */
  def createQuickstart(user: User, repoName: String, title: String, description: String, url: String): Future[Quickstart] = {
    repositoryService.findOrCreate(user, repoName).flatMap(repo => {

      val quickstart = Quickstart(
        new Date(),
        title,
        description,
        if (url.startsWith("http")) url else "http://" + url
      )

      quickstartDAO.save(user.username, repo.name, quickstart).map({
        case Some(quick) => quick
        case None => throw new Exception("Quickstart not created properly")
      })
    })
  }

  /**
    * Deletes the quickstart if it is one of the user's quickstart
    *
    * @throws Exception if the user is not authorized
    * @param user That wants to delete the quickstart
    * @param quickstartId id of the quickstart to be deleted
    * @return If the quickstart has been deleted properly
    */
  def delete(user: User, quickstartId: Int): Future[Boolean] = {
    quickstartDAO.canDelete(user.username, quickstartId).flatMap({
      case true => quickstartDAO.delete(quickstartId).map(v => true)
      case false => Future.successful(false)
    })
  }

  /**
    * Checks if a user can delete a quickstart
    *
    * @param user that to check the user from
    * @param quickstartId id of the quickstart to be removed
    * @return boolean true if the quickstart should be deleted
    */
  def canDelete(user: User, quickstartId: Int): Future[Boolean] =
    quickstartDAO.canDelete(user.username, quickstartId)

  /**
   * Get all quickstart guides from repo
   *
   * @param repository repository
   * @return sequence of guides
   */
  def getQuickstartGuidesForRepo(repository: Repository, page: Option[Int] = None): Future[Seq[Quickstart]] = page match {
    case Some(p) => quickstartDAO.findRepositoryGuides(repository.name, p)
    case None => quickstartDAO.findRepositoryGuides(repository.name, 1)
  }

  /**
   * Build a Quickstart from a vote and a previous version of the guide
   *
   * @param guide guide to be updated
   * @param upVote vote true for up false for down
   * @param username name of the voter
   * @return
   */
  def buildFromVote(guide: Quickstart, upVote: Boolean, username: String): Quickstart = {
    guide.copy(
      url=if (guide.url.startsWith("http")) guide.url else "http://" + guide.url,
      upvote = guide.upvote + (if (upVote) 1 else 0),
      downvote = guide.downvote + (if (!upVote) 1 else 0),
      listVoters = guide.listVoters :+ username
    )
  }

  /**
   * Update downvote and upVote of a guide on given repo
   *
   * @param repository repo on which the guide is
   * @param upVote is it upVote?
   * @param id id of the guide
   */
  def updateVote(repository: Repository, upVote: Boolean, id: Int, user: User): Future[Option[Quickstart]] = {
    quickstartDAO.findRepositoryGuide(repository.name, id).flatMap {
      case Some(guide) => {
        if (!guide.listVoters.contains(user.username)) {
          quickstartDAO.update(id, repository.name, buildFromVote(guide, upVote, user.username))
        } else {
          Future(Some(guide))
        }
      }
      case None => Future(None)
    }
  }

  /**
    * Gets the number of quickstart pages result for a given repository.
    *
    * @param repoName name of the repository to get the page count from
    * @param itemsPerPage number of items to put in the page
    * @return number of page as an integer.
    */
  def getQuickstartPageCount(repoName: String, itemsPerPage: Int = 10): Future[Int] = {

    if (itemsPerPage == 0) {
      throw new Exception("There can't be 0 items on a page")
    }

    quickstartDAO.countRepositoryQuickstart(repoName).map(quickstartCount => {
      quickstartCount % itemsPerPage match {
        case 0 => quickstartCount / itemsPerPage
        case _ => (quickstartCount / itemsPerPage) + 1
      }
    })
  }
}
