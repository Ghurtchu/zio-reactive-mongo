import org.mongodb.scala.bson.collection.immutable.Document.fromSpecific
import org.mongodb.scala.*
import org.mongodb.scala.bson.ObjectId
import zio.*
import org.mongodb.scala.model.Filters.*

import scala.concurrent.Future

final case class User(id: ObjectId, name: String, email: String, password: String)

trait Database[A] {
  def get: A
}

final case class MongoDatabaseLive() extends Database[MongoDatabase] {

  override lazy val get: MongoDatabase = {
    val client: MongoClient = MongoClient("mongodb://localhost:27018")

    client.getDatabase("userdb")
  }

}

object MongoDatabaseLive {

  def layer: ULayer[Database[MongoDatabase]] = ZLayer.fromFunction(MongoDatabaseLive.apply _)

}

trait Dao[A] {
  def getAll: IO[Throwable, Seq[A]]
}

final case class UserDao(database: Database[MongoDatabase]) extends Dao[User] {

  override def getAll: IO[Throwable, Seq[User]] = for {
    userDocuments <- ZIO.fromFuture(implicit ec => database.get.getCollection("user").find.toFuture())
    users         <- ZIO.succeed {
      userDocuments
        .map(_.toMap)
        .map { userMap =>
          User(
            id = userMap("_id").asObjectId().getValue,
            name = userMap("name").asString().getValue,
            email = userMap("email").asString().getValue,
            password = userMap("password").asString().getValue
          )
        }
    }
  } yield users

}

object UserDao {

  def layer: URLayer[Database[MongoDatabase], Dao[User]] = ZLayer.fromFunction(UserDao.apply _)

}


object Main extends ZIOAppDefault {

  val getAllUsers: ZIO[Dao[User], Throwable, Unit] =
    for {
      db    <- ZIO.service[Dao[User]]
      users <- db.getAll.tap(Console.printLine(_))
    } yield ()

  override def run: Task[Unit] = getAllUsers.provide(UserDao.layer, MongoDatabaseLive.layer)

}
