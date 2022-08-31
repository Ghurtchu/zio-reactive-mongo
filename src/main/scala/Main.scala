import org.mongodb.scala.bson.collection.immutable.Document.fromSpecific
import org.mongodb.scala.*
import org.mongodb.scala.bson.ObjectId
import zio.*
import org.mongodb.scala.model.Filters.*


object Main extends ZIOAppDefault {

  val database: UIO[MongoDatabase] = ZIO.succeed {
    val uri: String = "mongodb://localhost:27018"
    val client: MongoClient = MongoClient(uri)

    client.getDatabase("notesdb")
  }

  final case class User(id: ObjectId, name: String, email: String, password: String)

  val program: Task[Unit] = for {
    db          <- database
    users       <- ZIO.fromFuture(implicit ex => db.getCollection("user").find().toFuture())
    listOfUsers <- ZIO.succeed {
      users
        .map(_.toMap)
        .map { userMap =>
        User(
          id       = userMap("_id").asObjectId().getValue,
          name     = userMap("name").asString().getValue,
          email    = userMap("email").asString().getValue,
          password = userMap("password").asString().getValue
        )
      }
    }
    _           <- ZIO.succeed(println(listOfUsers))
  } yield ()

  override def run = program

}
