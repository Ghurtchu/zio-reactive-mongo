import org.bson.types.ObjectId
import org.mongodb.scala.bson.collection.immutable.Document.fromSpecific
import org.mongodb.scala.*
import org.mongodb.scala.bson.ObjectId
import zio.*
import org.mongodb.scala.model.Filters.*

import scala.concurrent.Future

final case class User(id: ObjectId, name: String, email: String, password: String)
final case class DBConfig(port: String, name: String)

final case class DatabaseContext(mongoDatabase: Option[MongoDatabase])

object DatabaseContext {
  def initial: DatabaseContext = new DatabaseContext(None)
  def apply(mongoDatabase: MongoDatabase): DatabaseContext = new DatabaseContext(Some(mongoDatabase))
}

trait DataSource {
  def setCtx(databaseContext: DatabaseContext): UIO[Unit]
  def getCtx: UIO[DatabaseContext]
  def get: UIO[MongoDatabase] = for {
    db <- getCtx.map(_.mongoDatabase.get)
  } yield db
}

final case class DataSourceLive(ref: Ref[DatabaseContext]) extends DataSource {
  override def setCtx(ctx: DatabaseContext): UIO[Unit] = ref.set(ctx)
  override def getCtx: UIO[DatabaseContext] = ref.get
}

object DataSourceLive {
  def layer: ULayer[DataSource] = ZLayer.scoped {
    for {
      ref <- Ref.make[DatabaseContext](DatabaseContext.initial)
    } yield DataSourceLive(ref)
  }
}

trait DataSourceBuilder {
  def initialize(DBConfig: DBConfig): RIO[DataSource, Unit]
}

final case class MongoDatabaseBuilder(dataSource: DataSource) extends DataSourceBuilder {

  override def initialize(dbConfig: DBConfig): UIO[Unit] = (for {
    _          <- Console.printLine(s"Attempting to establish the connection with MongoDB on port: ${dbConfig.port} with db ${dbConfig.name}")
    client     <- ZIO.attempt(MongoClient(dbConfig.port))
    db         <- ZIO.attempt(client.getDatabase(dbConfig.name)) <* Console.printLine("Established connection with database successfully")
    _          <- dataSource.setCtx(DatabaseContext(db))
  } yield ()).orDie

}

object MongoDatabaseBuilder {

  def layer: URLayer[DataSource, DataSourceBuilder] = ZLayer.fromFunction(MongoDatabaseBuilder.apply _)

}

trait Dao[A] {
  def getAll: IO[Throwable, Seq[A]]
}

final case class UserDao(dataSource: DataSource) extends Dao[User] {

  override def getAll: IO[Throwable, Seq[User]] = for {
    db            <- dataSource.get
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
  def layer: URLayer[DataSource, Dao[User]] = ZLayer.fromFunction(UserDao.apply _)
}

final case class UserDaoTest() extends Dao[User] {
  override def getAll: IO[Throwable, Seq[User]] = ZIO.succeed(Seq(User(ObjectId.get(), "name", "email", "password")))
}

object UserDaoTest {
  def layer: ULayer[Dao[User]] = ZLayer.succeed(UserDaoTest())
}


object Main extends ZIOAppDefault {

  val getAllUsers: ZIO[DataSourceBuilder & Dao[User] & DataSource, Throwable, Unit] =
    for {
      builder <- ZIO.service[DataSourceBuilder]
      dbPort  <- System.envOrElse("MONGO_PORT", "mongodb://localhost:27018")
      dbName  <- System.envOrElse("MONGO_DB_NAME", "notesdb")
      _       <- builder.initialize(DBConfig(dbPort, dbName))
      users   <- ZIO.service[Dao[User]].flatMap(_.getAll)
      _       <- Console.printLine(s"fetched users from db: $users")
    } yield ()

  override def run: Task[Unit] =
    getAllUsers
      .provide(
        UserDao.layer,
        MongoDatabaseBuilder.layer,
        DataSourceLive.layer
  )

}
