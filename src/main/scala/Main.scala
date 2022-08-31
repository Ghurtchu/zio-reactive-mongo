import org.bson.types.ObjectId
import org.mongodb.scala.bson.collection.immutable.Document.fromSpecific
import org.mongodb.scala.*
import org.mongodb.scala.bson.ObjectId
import zio.*
import org.mongodb.scala.model.Filters.*

import scala.concurrent.Future

final case class User(id: ObjectId, name: String, email: String, password: String)

final case class DBConfig(port: String, name: String)

final case class MongoDatabaseBuilder(dbConfig: DBConfig) {

  lazy val build: MongoDatabase = {
    val client: MongoClient = MongoClient(dbConfig.port)
    client.getDatabase(dbConfig.name)
  }

}

object MongoDatabaseProvider {

  val get: MongoDatabase = MongoDatabaseBuilder(
    DBConfig(
      port ="mongodb://localhost:27018",
      name = "notesdb"
    )
  ).build

}

trait Dao[A] {
  def getAll: IO[Throwable, Seq[A]]
}

final case class UserDao() extends Dao[User] {

  val mongo: MongoDatabase = MongoDatabaseProvider.get

  override def getAll: IO[Throwable, Seq[User]] = for {
    userDocuments <- ZIO.fromFuture(implicit ec => mongo.getCollection("user").find.toFuture())
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

  def layer: ULayer[Dao[User]] = ZLayer.succeed(UserDao())

}

final case class UserDaoTest() extends Dao[User] {
  override def getAll: IO[Throwable, Seq[User]] = ZIO.succeed(Seq(User(ObjectId.get(), "name", "email", "password")))
}

object UserDaoTest {

  def layer: ULayer[Dao[User]] = ZLayer.succeed(UserDaoTest())

}


object Main extends ZIOAppDefault {

  val getAllUsers: ZIO[Dao[User], Throwable, Unit] =
    for {
      db    <- ZIO.service[Dao[User]]
      users <- db.getAll.tap(Console.printLine(_))
    } yield ()

  override def run: Task[Unit] = getAllUsers.provide(UserDao.layer)

}
