package models.services

import java.util.Date
import javax.inject.{Named, Inject}

import actors.RepositorySupervisor.PropagateRepositoryScore
import akka.actor.ActorRef
import models.daos.drivers.{NeoParsers, GitHubAPI}
import models.daos._
import models._

import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RepositoryService @Inject()(
                                   parser: NeoParsers,
                                   repoDAO: RepositoryDAO,
                                   contributionDAO: ContributionDAO,
                                   userDAO: UserDAO,
                                   scoreDAO: ScoreDAO,
                                   oAuth2InfoDAO: OAuth2InfoDAO,
                                   gitHub: GitHubAPI,
                                   userService: UserService,
                                   @Named("repository-supervisor") repositorySupervisor: ActorRef) {

  /**
   * Saves or create a repository to the database according to the current needs
   *
   * @param name name of the repo to save or update
   * @param addedLines number of added lines to the repo
   * @param removedLines number of deleted lines from the repo
   * @param karmaWeight sum of all the karma weight
   * @param score score of the repo
   * @return the saved Repository
   */
  def save(id: Int,
           name: String,
           addedLines: Option[Int],
           removedLines: Option[Int],
           karmaWeight: Option[Int] = None,
           score: Option[Float] = None): Future[Repository] = {

    repoDAO.find(id).flatMap({
      case Some(existingRepo) =>
        repoDAO.update(existingRepo.copy(
          name = name,
          addedLines = addedLines.getOrElse(existingRepo.addedLines),
          removedLines = removedLines.getOrElse(existingRepo.removedLines),
          karmaWeight = karmaWeight.getOrElse(existingRepo.karmaWeight),
          score = score.getOrElse(existingRepo.score)
        ))
      case None =>
        repoDAO.create(Repository(
          repoID = id,
          addedLines = addedLines.getOrElse(0),
          removedLines = removedLines.getOrElse(0),
          karmaWeight = karmaWeight.getOrElse(0),
          name = name,
          score = score.getOrElse(0)
        ))
    })
  }

  /**
   * Adds a contribution to the given repository
   *
   * @param userName name of the user who has contributed to the repository
   * @param repoName name of the repository he has contributed to
   * @param contribution Contribution itself.
   * @return
   */
  def saveContribution(userName: String, repoName: String, contribution: Contribution) = {
    contributionDAO.find(userName, repoName).flatMap({
      case Some(existingContribution) => {
        contributionDAO.update(userName, repoName, existingContribution.copy(
          timestamp = contribution.timestamp,
          addedLines = existingContribution.addedLines + contribution.addedLines -
            parser.parseWeekAddedLines(existingContribution.currentWeekBuffer),
          removedLines = existingContribution.removedLines + contribution.removedLines -
            parser.parseWeekDeletedLines(existingContribution.currentWeekBuffer),
          currentWeekBuffer = contribution.currentWeekBuffer
        ))
      }
      case None => contributionDAO.add(userName, repoName, contribution)
    })
  }

  /**
   * Retrieves a repository according to its name.
   *
   * @param name of the repository to be retrieved
   * @return
   */
  def retrieve(name: String): Future[Option[Repository]] = repoDAO.find(name)

  /**
   * Retrieves a repository according to its UUID
   *
   * @param repoId UUID of the repository to be retrieved.
   * @return
   */
  def retrieve(repoId: Int): Future[Option[Repository]] = repoDAO.find(repoId)

  /**
   * Gets all the contributors for a given repository with all their contributions
   *
   * @param repoName name of the repository to look for, "owner/repo"
   * @return A Sequence of contributors
   */
  def findContributors(repoName: String): Future[Seq[User]] = {
    repoDAO.find(repoName).flatMap({
      case Some(repository) => userDAO.findAllFromRepo(repository)
      case None => Future(Seq())
    })
  }

  /**
   * Function that check if a repository exists in the database, if it does, returns the corresponding repository
   * If not, it checks if the repository exists on GitHub. if it does, it returns the corresponding repository
   * If not, it returns None
   *
   * @param identity identity of the current user, can be None if no user is connected
   * @param repoName "owner/repo"
   *
   * @return Future of Option of repository
   */
  def getFromNeoOrGitHub(identity: Option[User], repoName: String): Future[Option[Repository]] = {
    retrieve(repoName).flatMap((repoOption: Option[Repository]) => repoOption match {
      case Some(repository) => Future(Some(repository))
      case None => identity match {
        case None => gitHub.getRepository(repoName)
        case Some(user) => userService.getOAuthInfo(user).flatMap(oAuthInfo => gitHub.getRepository(repoName, oAuthInfo))
      }
    })
  }

  /**
   * get all the feedback made for a repository for the given page and item per page.
   *
   * @param repoName name of the repository to get the scores from ("owner/repo")
   * @param page page number to get from the database. Default value to 1
   * @param itemsPerPage number of items to display in a database page
   * @return Seq of Feedback.
   */
  def getFeedback(repoName: String, page: Option[Int], itemsPerPage: Int = 10): Future[Seq[Feedback]] = page match {
    case Some(p) => scoreDAO.findRepositoryFeedback(repoName, p, itemsPerPage)
    case None => scoreDAO.findRepositoryFeedback(repoName, 1, itemsPerPage)
  }

  /**
   * Get a score from a given user
   * @param repoName repository of the feedbacl
   * @param username username
   * @return Score given from the user to the repo
   */
  def getScoreFromUser(repoName:String, username:String): Future[Option[Score]]={
    scoreDAO.find(username, repoName)
  }

  /**
   * Get a map with a user feedback
   * @param repoName repository of the feedback
   * @param user user of the request
   * @return map with feedback values
   */
  def getMapScoreFromUser(repoName:String, user:Option[User] ): Future[Map[String,String]]={
    user match {
      case Some(u) => getScoreFromUser(repoName,u.username).map{
        score => score.fold(HashMap[String,String]())(x => x.toMap())
      }
      case None => Future.successful(HashMap[String,String]())
    }

  }
  /**
   * Get all the scoring made for a repository for the given page and item per page.
   *
   * @param repoName name of the repository to get the scores from ("owner/repo")
   * @param page page number to get from the database. Default value to 1
   * @param itemsPerPage number of items to display in a database page
   * @return Seq of Scores.
   */
  def getScores(repoName: String, page: Int = 1, itemsPerPage: Int = 10): Future[Seq[Score]] =
    scoreDAO.findRepositoryScores(repoName, page, itemsPerPage)

  /**
   * Check if the user has already given feedback to a repository
   *
   * @param repoName Repo with feedback
   * @param user User that is giving the score
   * @return true if the user can update a given feedback
   */
  def canUpdateFeedback(repoName: String, user: Option[User]): Future[Boolean] = user match {
    case Some(userEntity) => scoreDAO.find(userEntity.username, repoName).map(_.isDefined)
    case None => Future.successful(false)
  }

  /**
   * Checks if a user can add a feedback
   *
   * @param repoName Repo to be scored
   * @param user User that is giving the feedback
   * @return true if the user has not contributed to the repository
   */
  def canAddFeedback(repoName: String, user: Option[User]): Future[Boolean] = user match {
    case Some(userEntity) =>
      contributionDAO.checkIfUserContributed(userEntity.username, repoName).map(hasContributed => !hasContributed)
    case None => Future(false)
  }

  /**
   * Gets the number of feedback page result for a given repository.
   *
   * @param repoName name of the repository to get the page count from
   * @param itemsPerPage number of items to put in the page
   * @return number of page as an integer.
   */
  def getFeedbackPageCount(repoName: String, itemsPerPage: Int = 10): Future[Int] = {

    if (itemsPerPage == 0) {
      throw new Exception("There can't be 0 items on a page")
    }

    scoreDAO.countRepositoryFeedback(repoName).map(feedbackCount => {
      feedbackCount % itemsPerPage match {
        case 0 => feedbackCount / itemsPerPage
        case _ => (feedbackCount / itemsPerPage) + 1
      }
    })
  }

  /**
   * Gives a specific score to a repo.
   *
   * @param owner User logged in
   * @param repositoryName name of the repo to be scored
   * @param scoreDocumentation score given for documentation
   * @param scoreMaturity score given for maturity
   * @param scoreDesign score given for design
   * @param scoreSupport score given for support
   * @param feedback feedback written by user
   * @return repo scored
   */
  def giveScoreToRepo(owner: String,
                      user: User,
                      repositoryName: String,
                      scoreDocumentation: Int,
                      scoreMaturity: Int,
                      scoreDesign: Int,
                      scoreSupport: Int,
                      feedback: String): Future[Repository] = {

    val repoName: String = owner + "/" + repositoryName

    findOrCreate(user, repoName).flatMap(repo =>
      canAddFeedback(repoName, Some(user)).flatMap({
        case true => saveScore(user, repo, scoreDocumentation, scoreMaturity, scoreDesign, scoreSupport, feedback)
          .flatMap(s => updateRepoScore(repo))
        case false => Future(repo) // TODO this is weird, we should throw an error or something
      })
    )
  }

  /**
   * Look for a repo in the database, if the repository is not found, it looks at GitHub and creates it in the
   * database
   *
   * @param user user requesting the creation
   * @param repoName name of the repository to be created
   * @return Future of created repository
   */
  def findOrCreate(user: User, repoName: String): Future[Repository] = {
    repoDAO.find(repoName).flatMap({
      case Some(repo) => Future.successful(repo)
      case None => oAuth2InfoDAO.find(user.loginInfo).flatMap(
        gitHub.getRepository(repoName, _).flatMap(repo => repoDAO.create(repo.get)))
    })
  }

  /**
   * Recalculate score for repo
   *
   * @param repository repo to recalculate
   */
  def calculateScoreForRepo(repository: Repository): Future[Float] = {
    scoreDAO.findRepositoryFeedback(repository.name).map {
      feedbackList => feedbackList.map {
        feedback: Feedback => computeTotalScore(repository, feedback) }.sum / feedbackList.length
    }
  }

  /**
   * Calculate the total score based on a repo/user karma
   *
   * @param repository repository whose score is being calculated
   * @param feedback score and user who scored
   * @return 1 to 5 score points
   */
  def computeTotalScore(repository: Repository, feedback: Feedback): Float = {
    ((repository.karmaWeight * repository.score + feedback.user.karma *
      (feedback.score.designScore + feedback.score.docScore + feedback.score.maturityScore + feedback.score.supportScore) / 4)
      / (repository.karmaWeight + feedback.user.karma))
  }

  /**
   * Update a score of a given repo
   *
   * @param repository repo to update
   */
  def updateRepoScore(repository: Repository): Future[Repository] = {
    calculateScoreForRepo(repository).flatMap(score => repoDAO.update(repository.copy(score = score)))
  }

  /**
   * Add a Score/Feedback to the database
   *
   * @param user user that gave the feedback
   * @param repository repository on which the feedback is made
   * @param scoreDocumentation Documentation score
   * @param scoreMaturity maturity score
   * @param scoreDesign design score
   * @param scoreSupport support score
   * @param feedback Feedback text
   * @return saved score
   */
  private def saveScore(user: User,
                        repository: Repository,
                        scoreDocumentation: Int,
                        scoreMaturity: Int,
                        scoreDesign: Int,
                        scoreSupport: Int,
                        feedback: String): Future[Score] = {
    val score = Score(
      new Date(),
      scoreDesign,
      scoreDocumentation,
      scoreSupport,
      scoreMaturity,
      feedback,
      user.karma
    )
    scoreDAO.find(user.username, repository.name).flatMap {
      case Some(_) => scoreDAO.update(user.username, repository.name, score).map(propagateScoreChange(_, repository))
      case None => scoreDAO.save(user.username, repository.name, score).map(propagateScoreChange(_, repository))
    }
  }

  /**
   * Sends the propagation order to the Repository actor if the score has been saved properly.
   *
   * @param scoreOpt Option containing the saved score Score saved
   * @param repository repository scored.
   * @return score that should be propagated.
   */
  private def propagateScoreChange(scoreOpt: Option[Score], repository: Repository): Score = {
    scoreOpt match {
      case Some(score) =>
        repositorySupervisor ! PropagateRepositoryScore(repository, score)
        score
      case None => throw new Exception("Cannot propagate unsaved score, check score DAO or DB")
    }
  }
}
