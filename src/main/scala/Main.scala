import org.bson.types.ObjectId
import org.mongodb.scala.bson.collection.immutable.Document.fromSpecific
import org.mongodb.scala.*
import org.mongodb.scala.bson.ObjectId
import zio.*
import org.mongodb.scala.model.Filters.*

import scala.concurrent.Future

final case class User(id: ObjectId, name: String, email: String, password: String)
final case class DBConfig(port: String, name: String)

object MongoDatabaseBuilder {
  lazy final val build: UIO[MongoDatabase] = (for {
    port          <- System.envOrElse("MONGO_PORT", "mongodb://localhost:27018")
    name          <- System.envOrElse("MONGO_DB_NAME", "notesdb")
    client        <- ZIO.attempt(MongoClient(port))
    mongoDatabase <- ZIO.attempt(client.getDatabase(name))
  } yield mongoDatabase).orDie
}

object MongoDatabaseProvider {
  lazy final val get: UIO[MongoDatabase] = MongoDatabaseBuilder.build
}

trait Dao[A] {
  def getAll: IO[Throwable, Seq[A]]
}

final case class UserDao() extends Dao[User] {

  val mongo: UIO[MongoDatabase] = MongoDatabaseProvider.get

  override def getAll: IO[Throwable, Seq[User]] = for {
    db            <- mongo
    userDocuments <- ZIO.fromFuture(implicit ec => db.getCollection("user").find.toFuture())
    users         <- ZIO.succeed(userDocuments.map(parseDocumentToUser))
  } yield users

  private def parseDocumentToUser(doc: Document): User =
    User(
      id       = doc("_id").asObjectId().getValue,
      name     = doc("name").asString().getValue,
      email    = doc("email").asString().getValue,
      password = doc("password").asString().getValue
    )

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
