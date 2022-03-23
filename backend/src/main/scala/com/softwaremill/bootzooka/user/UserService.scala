package com.softwaremill.bootzooka.user

import cats.implicits._
import com.softwaremill.bootzooka._
import com.softwaremill.bootzooka.email.{EmailData, EmailScheduler, EmailTemplates}
import com.softwaremill.bootzooka.infrastructure.Doobie._
import com.softwaremill.bootzooka.security.{ApiKey, ApiKeyService}
import com.softwaremill.bootzooka.util._
import com.softwaremill.tagging.@@
import com.typesafe.scalalogging.StrictLogging
import monix.execution.Scheduler.Implicits.global
import tsec.common.Verified

import scala.concurrent.duration.Duration

class UserService(
                   userModel: UserModel,
                   emailScheduler: EmailScheduler,
                   emailTemplates: EmailTemplates,
                   apiKeyService: ApiKeyService,
                   idGenerator: IdGenerator,
                   clock: Clock,
                   config: UserConfig
) extends StrictLogging {

  private val LoginAlreadyUsed = "Login already in use!"
  private val EmailAlreadyUsed = "E-mail already in use!"
  private val IncorrectLoginOrPassword = "Incorrect login/email or password"

  def registerNewUser(login: String, email: String, password: String): ConnectionIO[ApiKey] = {
    def failIfDefined(op: ConnectionIO[Option[User]], msg: String): ConnectionIO[Unit] = {
      op.flatMap {
        case None    => ().pure[ConnectionIO]
        case Some(_) => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit]
      }
    }

    def checkUserDoesNotExist(): ConnectionIO[Unit] = {
      failIfDefined(userModel.findByLogin(login.lowerCased), LoginAlreadyUsed) >>
        failIfDefined(userModel.findByEmail(email.lowerCased), EmailAlreadyUsed)
    }

    def doRegister(): ConnectionIO[ApiKey] = {
      for {
        id <- idGenerator.nextId[User]().to[ConnectionIO]
        now <- clock.now().to[ConnectionIO]
        user = User(id, login, login.lowerCased, email.lowerCased, User.hashPassword(password), now)
        confirmationEmail = emailTemplates.registrationConfirmation(login)
        _ = logger.debug(s"Registering new user: ${user.emailLowerCased}, with id: ${user.id}")
        _ <- userModel.insert(user)
        _ <- emailScheduler(EmailData(email, confirmationEmail))
        apiKey <- apiKeyService.create(user.id, config.defaultApiKeyValid)
      } yield apiKey
    }

    for {
      _ <- UserRegisterValidator
        .validate(login, email, password)
        .fold(msg => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit], _ => ().pure[ConnectionIO])
      _ <- checkUserDoesNotExist()
      apiKey <- doRegister()
    } yield apiKey
  }

  def findById(id: Id @@ User): ConnectionIO[User] = userOrNotFound(userModel.findById(id))

  def login(loginOrEmail: String, password: String, apiKeyValid: Option[Duration]): ConnectionIO[ApiKey] =
    for {
      user <- userOrNotFound(userModel.findByLoginOrEmail(loginOrEmail.lowerCased))
      _ <- verifyPassword(user, password, validationErrorMsg = IncorrectLoginOrPassword)
      apiKey <- apiKeyService.create(user.id, apiKeyValid.getOrElse(config.defaultApiKeyValid))
    } yield apiKey

  def changeUser(userId: Id @@ User, newLogin: String, newEmail: String): ConnectionIO[Unit] = {
    def changeLogin(newLogin: String): ConnectionIO[Unit] = {
      val newLoginLowerCased = newLogin.lowerCased
      userModel.findByLogin(newLoginLowerCased).flatMap {
        case Some(user) if user.id != userId => Fail.IncorrectInput(LoginAlreadyUsed).raiseError[ConnectionIO, Unit]
        case Some(user) if user.login == newLogin => ().pure[ConnectionIO]
        case _ =>
          logger.debug(s"Changing login for user: $userId, to: $newLogin")
          userModel.updateLogin(userId, newLogin, newLoginLowerCased)
      }
    }

    def changeEmail(newEmail: String): ConnectionIO[Unit] = {
      val newEmailLowerCased = newEmail.lowerCased
      userModel.findByEmail(newEmailLowerCased).flatMap {
        case Some(user) if user.id != userId => Fail.IncorrectInput(EmailAlreadyUsed).raiseError[ConnectionIO, Unit]
        case Some(user) if user.emailLowerCased == newEmailLowerCased => ().pure[ConnectionIO]
        case _ =>
          logger.debug(s"Changing email for user: $userId, to: $newEmail")
          userModel.updateEmail(userId, newEmailLowerCased)
      }
    }

    changeLogin(newLogin) >> changeEmail(newEmail)
  }

  def changePassword(userId: Id @@ User, currentPassword: String, newPassword: String): ConnectionIO[Unit] =
    for {
      user <- userOrNotFound(userModel.findById(userId))
      _ <- verifyPassword(user, currentPassword, validationErrorMsg = "Incorrect current password")
      _ = logger.debug(s"Changing password for user: $userId")
      _ <- userModel.updatePassword(userId, User.hashPassword(newPassword))
    } yield ()

  private def userOrNotFound(op: ConnectionIO[Option[User]]): ConnectionIO[User] = {
    op.flatMap {
      case Some(user) => user.pure[ConnectionIO]
      case None       => Fail.Unauthorized(IncorrectLoginOrPassword).raiseError[ConnectionIO, User]
    }
  }

  private def verifyPassword(user: User, password: String, validationErrorMsg: String): ConnectionIO[Unit] = {
    if (user.verifyPassword(password) == Verified) {
      ().pure[ConnectionIO]
    } else {
      Fail.Unauthorized(validationErrorMsg).raiseError[ConnectionIO, Unit]
    }
  }
}

object UserRegisterValidator {
  private val ValidationOk = Right(())
  val MinLoginLength = 3

  def validate(login: String, email: String, password: String): Either[String, Unit] =
    for {
      _ <- validLogin(login.trim)
      _ <- validEmail(email.trim)
      _ <- validPassword(password.trim)
    } yield ()

  private def validLogin(login: String): Either[String, Unit] =
    if (login.length >= MinLoginLength) ValidationOk else Left("Login is too short!")

  private val emailRegex =
    """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  private def validEmail(email: String) =
    if (emailRegex.findFirstMatchIn(email).isDefined) ValidationOk else Left("Invalid e-mail format!")

  private def validPassword(password: String) =
    if (password.nonEmpty) ValidationOk else Left("Password cannot be empty!")
}
